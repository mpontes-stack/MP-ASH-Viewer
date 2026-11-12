package core.plsql;

import config.Labels;
import core.snapshot.SnapshotService;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Where a SQL is written in PL/SQL: v$sql.program_id / program_line# turned into OWNER.PACKAGE.PROCEDURE and the
 * line. The procedure of a package body is the last PROCEDURE / FUNCTION declared before the line (dba_source), read
 * once per object. Not the PL/SQL the session called first (that is PLSQL_ENTRY): the one that holds the SQL.
 * Thread safe; one instance per connection. Only numbers (ids) are sent to the database.
 */
@Slf4j
public final class PlsqlSource {
    /** Key prefix of the origins kept with the SQL texts in the local history ({@code ParamStringString}) */
    public static final String KEY_PREFIX = "PLSQL_SRC:";
    /**
     * Column of Group by, Compare by and the filters that is not collected: OWNER.PACKAGE.PROCEDURE of the SQL_ID of
     * the sample (see {@code AshFilter.Matcher}); "" when the SQL was not run by PL/SQL or its source is not known
     */
    public static final String COLUMN = "PLSQL_SOURCE";

    private static final int IDS_PER_QUERY = 500;
    /** last_ddl_time as v$sql.first_load_time is written: the two compare as text */
    private static final String OBJECTS = "SELECT object_id, owner, object_name, object_type, "
            + "TO_CHAR(last_ddl_time, 'YYYY-MM-DD/HH24:MI:SS') FROM %s WHERE object_id IN (%s)";
    /**
     * Owner, name and type as literals (read from dba_objects, quotes doubled): a join or subquery with dba_objects
     * is not pushed into dba_source in a PDB (full scan of the container data, minutes)
     */
    private static final String DECLARATIONS = "SELECT line, text FROM %s WHERE owner = '%s' AND name = '%s'"
            + " AND type = '%s' AND REGEXP_LIKE(text, '^\\s*(procedure|function)\\s', 'i')";
    private static final Pattern DECLARATION =
            Pattern.compile("^\\s*(?:procedure|function)\\s+(\"[^\"]+\"|[A-Za-z][\\w$#]*)", Pattern.CASE_INSENSITIVE);

    private final SnapshotService service;
    private boolean dbaViews = true;
    /** object_id + last_ddl_time of a package / type body -> line -> subprogram declared there */
    private final Map<String, TreeMap<Integer, String>> declarations = new HashMap<>();
    /** Declarations that failed (timeout): object_id + last_ddl_time -> failures, and when to try again */
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> retryAfter = new HashMap<>();
    private static final int MAX_FAILURES = 3;
    private static final long RETRY_AFTER_MS = 3600_000L;

    public PlsqlSource(SnapshotService service) {
        this.service = service;
    }

    /**
     * Values to keep for the origins (see {@link #encode}), call off the EDT. The objects are read again on every
     * call (cheap, by id): a package changed while the app runs gets its new declarations
     *
     * @return value by origin (origins whose declarations can not be read now are left out, kept for a next
     *         call), null when the objects could not be read
     */
    public synchronized Map<Origin, String> stored(Collection<Origin> origins) {
        Map<Long, String[]> objects = new HashMap<>();
        List<Long> ids = origins.stream().map(o -> o.objectId).filter(id -> id > 0).distinct().collect(Collectors.toList());
        for (int from = 0; from < ids.size(); from += IDS_PER_QUERY) {
            if (!readObjects(ids.subList(from, Math.min(ids.size(), from + IDS_PER_QUERY)), objects)) {
                return null;
            }
        }
        Map<Origin, String> out = new HashMap<>();
        for (Origin origin : origins) {
            String[] object = objects.get(origin.objectId);
            if (object == null) { // not visible: the id and the line
                out.put(origin, encode(origin, "", false));
                continue;
            }
            String name = object[0] + "." + object[1];
            // the line is the one of the first parse of the cursor: a package changed after it may have moved the SQL
            boolean older = origin.loaded != null && object[3] != null && object[3].compareTo(origin.loaded) > 0;
            if (object[2].endsWith(" BODY") && !older) {
                TreeMap<Integer, String> lines = declarations(origin.objectId, object);
                if (lines == null) {
                    continue;
                }
                Map.Entry<Integer, String> declared = lines.floorEntry(origin.line);
                if (declared != null) {
                    name = name + "." + declared.getValue();
                }
            }
            out.put(origin, encode(origin, name, older));
        }
        return out;
    }

    private boolean readObjects(List<Long> ids, Map<Long, String[]> objects) {
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        SnapshotService.Result result = query(String.format(OBJECTS, view("objects"), in), view("objects"));
        if (!result.isOk() && result.isNoPermission() && dbaViews) {
            dbaViews = false; // objects visible to the user
            result = query(String.format(OBJECTS, view("objects"), in), view("objects"));
        }
        if (!result.isOk()) {
            log.info("PL/SQL source objects: " + result.error);
            return false;
        }
        for (Object[] row : result.rows) {
            objects.put(((Number) row[0]).longValue(), new String[]{String.valueOf(row[1]), String.valueOf(row[2]),
                    String.valueOf(row[3]), row[4] == null ? null : String.valueOf(row[4])});
        }
        return true;
    }

    /**
     * null when the database could not be read (asked again after an hour); empty when the source is not visible or
     * failed {@link #MAX_FAILURES} times (the object name only: no more queries on a database where it is slow)
     */
    private TreeMap<Integer, String> declarations(long objectId, String[] object) {
        String key = objectId + "@" + object[3];
        TreeMap<Integer, String> lines = declarations.get(key);
        if (lines != null) {
            return lines;
        }
        if (retryAfter.getOrDefault(key, 0L) > System.currentTimeMillis()) {
            return null;
        }
        SnapshotService.Result result = query(declarationsQuery(object), view("source"));
        if (!result.isOk() && result.isNoPermission() && dbaViews) {
            dbaViews = false;
            result = query(declarationsQuery(object), view("source"));
        }
        if (!result.isOk()) {
            log.info("PL/SQL source declarations: " + result.error);
            int failed = failures.merge(key, 1, Integer::sum);
            if (result.isNoPermission() || failed >= MAX_FAILURES) { // the object name only
                declarations.put(key, new TreeMap<>());
                return declarations.get(key);
            }
            retryAfter.put(key, System.currentTimeMillis() + RETRY_AFTER_MS);
            return null;
        }
        lines = new TreeMap<>();
        for (Object[] row : result.rows) {
            String subprogram = subprogram(row[1] == null ? "" : String.valueOf(row[1]));
            if (subprogram != null && row[0] instanceof Number) {
                lines.put(((Number) row[0]).intValue(), subprogram);
            }
        }
        declarations.put(key, lines);
        return lines;
    }

    private String declarationsQuery(String[] object) {
        return String.format(DECLARATIONS, view("source"), literal(object[0]), literal(object[1]), literal(object[2]));
    }

    private static String literal(String value) {
        return value.replace("'", "''");
    }

    /** "  procedure count_pads is" -> COUNT_PADS; quoted names keep their case */
    static String subprogram(String text) {
        Matcher m = DECLARATION.matcher(text);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1);
        return name.startsWith("\"") ? name.substring(1, name.length() - 1) : name.toUpperCase();
    }

    private String view(String kind) {
        return (dbaViews ? "dba_" : "all_") + kind;
    }

    /**
     * In a thread of its own: when a slow query is cancelled by its timeout, ojdbc8 interrupts the thread that ran
     * it some seconds later, which would fail the next query of the SQL text capture
     */
    private SnapshotService.Result query(String sql, String view) {
        java.util.concurrent.atomic.AtomicReference<SnapshotService.Result> out = new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> out.set(
                service.run(Collections.singletonMap("q", sql), Collections.singletonMap("q", view)).get("q")), "plsql-source");
        t.setDaemon(true);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.get() != null ? out.get() : SnapshotService.Result.failed("interrupted");
    }

    /** program_id, program_line# and first_load_time of a SQL */
    public static final class Origin {
        public final long objectId;
        public final int line;
        /** v$sql.first_load_time ("YYYY-MM-DD/HH24:MI:SS"), null when unknown */
        public final String loaded;

        public Origin(long objectId, int line, String loaded) {
            this.objectId = objectId;
            this.line = line;
            this.loaded = loaded;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Origin && ((Origin) o).objectId == objectId && ((Origin) o).line == line
                    && Objects.equals(((Origin) o).loaded, loaded);
        }

        @Override
        public int hashCode() {
            return Objects.hash(objectId, line, loaded);
        }
    }

    /**
     * Origin kept in the local history: "object_id|line|name", plus "|old" when the package changed after the SQL
     * was parsed (the line is of an older version: name without the procedure). "" = the SQL was not run by PL/SQL
     */
    static String encode(Origin origin, String name, boolean older) {
        return origin.objectId + "|" + origin.line + "|" + name + (older ? "|old" : "");
    }

    /** Value of {@link #COLUMN} for a kept origin: the name without the line ("" when none) */
    public static String group(String stored) {
        if (stored == null || stored.isEmpty()) {
            return "";
        }
        String[] parts = stored.split("\\|", 4);
        if (parts.length < 3) {
            return "";
        }
        return parts[2].isEmpty() ? String.format(Labels.getLabel("gui.plsqlsource.object"), parts[0]) : parts[2];
    }

    /** Kept origin whose line is of an older version of the package: read again while the SQL is in v$sql */
    public static boolean isOlder(String stored) {
        return stored != null && stored.endsWith("|old");
    }

    /**
     * Text to show: "OWNER.PACKAGE.PROCEDURE (line 15)"
     *
     * @return null when the stored value has no origin
     */
    public static String display(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        String[] parts = stored.split("\\|", 4);
        if (parts.length < 3) {
            return null;
        }
        String name = parts[2].isEmpty() ? String.format(Labels.getLabel("gui.plsqlsource.object"), parts[0]) : parts[2];
        boolean older = parts.length > 3 && parts[3].equals("old");
        return String.format(Labels.getLabel(older ? "gui.plsqlsource.older" : "gui.plsqlsource.at"), name, parts[1]);
    }
}
