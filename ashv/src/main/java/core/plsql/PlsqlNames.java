package core.plsql;

import remote.RemoteDBManager;
import core.snapshot.SnapshotService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import profile.IProfile;

/**
 * Names of the PL/SQL a session called first, shown instead of the ids in "Group by", Compare and the filters:
 * PLSQL_ENTRY ("object_id.subprogram_id" -> OWNER.PACKAGE.PROCEDURE) and PLSQL_ENTRY_OBJECT_ID (-> OWNER.PACKAGE).
 * Looked up in dba_procedures only for the ids shown and cached for the connection. Oracle only; without a
 * connection (offline, imported history) the ids stay as they are.
 */
@Slf4j
public final class PlsqlNames {
    public static final String ENTRY = "PLSQL_ENTRY";
    public static final String ENTRY_OBJECT = "PLSQL_ENTRY_OBJECT_ID";

    private static final int IDS_PER_QUERY = 500;
    private static final String QUERY =
            "SELECT object_id, subprogram_id, owner, object_name, procedure_name FROM %s WHERE object_id IN (%s)";

    private static volatile RemoteDBManager remoteDBManager;
    private static volatile boolean oracle;
    private static volatile String view = "dba_procedures";
    /** "object_id" and "object_id.subprogram_id" -> name */
    private static final Map<String, String> names = new ConcurrentHashMap<>();
    /** Object ids already looked up (found or not): asked only once per connection */
    private static final Set<String> looked = ConcurrentHashMap.newKeySet();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plsql-names");
        t.setDaemon(true);
        return t;
    });

    private PlsqlNames() {
    }

    /** A connection was opened (names of the previous one are forgotten) */
    public static void configure(RemoteDBManager manager, String profileName) {
        names.clear();
        looked.clear();
        view = "dba_procedures";
        remoteDBManager = manager;
        oracle = IProfile.isOracle(profileName);
    }

    public static boolean isPlsqlColumn(String col) {
        return ENTRY.equalsIgnoreCase(col) || ENTRY_OBJECT.equalsIgnoreCase(col);
    }

    /** Name to show for a value (the value itself when not a PL/SQL column or not known) */
    public static String display(String col, String value) {
        if (value == null || value.isEmpty() || !isPlsqlColumn(col)) {
            return value;
        }
        String name = names.get(value);
        return name == null ? value : name;
    }

    /** Some values have ids not looked up yet */
    public static boolean needsLookup(String col, Collection<String> values) {
        return !missing(col, values).isEmpty();
    }

    /** Look up in background, then run whenFound (on that thread) if new names were found */
    public static void lookupLater(String col, Collection<String> values, Runnable whenFound) {
        if (!needsLookup(col, values)) {
            return;
        }
        List<String> copy = new ArrayList<>(values);
        EXECUTOR.execute(() -> {
            if (lookup(col, copy)) {
                whenFound.run();
            }
        });
    }

    /**
     * Look up the names of the values not known yet (call off the EDT)
     *
     * @return true when the database was asked
     */
    public static boolean lookup(String col, Collection<String> values) {
        List<String> ids = missing(col, values);
        if (ids.isEmpty()) {
            return false;
        }
        looked.addAll(ids); // asked once, also when it fails: no query on every refresh
        for (int from = 0; from < ids.size(); from += IDS_PER_QUERY) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IDS_PER_QUERY));
            SnapshotService.Result result = query(chunk);
            if (!result.isOk() && result.isNoPermission() && view.equals("dba_procedures")) {
                view = "all_procedures"; // PL/SQL visible to the user
                result = query(chunk);
            }
            if (!result.isOk()) {
                log.info("PL/SQL names: " + result.error);
                return true;
            }
            for (Object[] row : result.rows) {
                String objectId = id(row[0]);
                String name = row[2] + "." + row[3];
                names.putIfAbsent(objectId, name); // package or standalone procedure
                if (row[1] != null) {
                    names.put(objectId + "." + id(row[1]), row[4] == null ? name : name + "." + row[4]);
                }
            }
        }
        return true;
    }

    private static List<String> missing(String col, Collection<String> values) {
        List<String> out = new ArrayList<>();
        RemoteDBManager manager = remoteDBManager;
        if (!isPlsqlColumn(col) || !oracle || manager == null || manager.getBasicDataSource() == null) {
            return out;
        }
        for (String value : values) {
            String id = objectId(value);
            if (id != null && !looked.contains(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static SnapshotService.Result query(List<String> ids) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("plsql", String.format(QUERY, view, String.join(",", ids)));
        return new SnapshotService(remoteDBManager).run(queries, Collections.singletonMap("plsql", view)).get("plsql");
    }

    /** "82944.1" or "82944" -> "82944"; only positive numbers are sent to the database */
    static String objectId(String value) {
        if (value == null) {
            return null;
        }
        int dot = value.indexOf('.');
        String id = dot < 0 ? value : value.substring(0, dot);
        if (id.isEmpty() || id.length() > 12 || id.equals("0")) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return null;
            }
        }
        return id;
    }

    private static String id(Object number) {
        return number instanceof Number ? String.valueOf(((Number) number).longValue()) : String.valueOf(number);
    }
}
