package core.sqltext;

import com.sleepycat.persist.EntityCursor;
import config.profile.SqlColProfile;
import core.plsql.PlsqlSource;
import core.snapshot.SnapshotService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import profile.IProfile;
import remote.RemoteDBManager;
import store.StoreManager;
import store.entity.database.MainData;

/**
 * SQL text of a SQL_ID: from the local history (saved by the SQL detail or the Postgres collection),
 * otherwise from v$sql (Oracle) in the background, then saved in the local history.
 * One instance for the application: one connection at a time.
 */
@Slf4j
public final class SqlTexts {
    private static final Pattern ORACLE_SQL_ID = Pattern.compile("^[0-9a-z]{13}$");
    /**
     * Text and PL/SQL origin of several SQL_IDs at once (first child of each); ids are checked against ORACLE_SQL_ID
     * before. The first %s is one of the PROGRAM_* columns
     */
    private static final String QUERY_MANY = "SELECT sql_id, sql_fulltext, program_id, program_line#, first_load_time FROM ("
            + "SELECT sql_id, sql_fulltext, %s, first_load_time, ROW_NUMBER() OVER (PARTITION BY sql_id ORDER BY child_number) rn "
            + "FROM v$sql WHERE sql_id IN (%s)) WHERE rn = 1";
    /** In the CDB root v$sql also shows the SQL of the PDBs, whose program_id is an object of the PDB: -1 */
    private static final String PROGRAM_CDB = "CASE WHEN con_id IN (0, TO_NUMBER(SYS_CONTEXT('USERENV', 'CON_ID'))) "
            + "THEN program_id ELSE -1 END program_id, program_line#";
    /** 11g: no con_id */
    private static final String PROGRAM_11G = "program_id, program_line#";
    private static final int BATCH = 100;

    private static volatile SqlTexts instance;

    private final StoreManager storeManager;
    private final SnapshotService service;
    private final RemoteDBManager remoteDBManager;

    /** SQL_IDs not found in v$sql (aged out of the shared pool): not asked again */
    private final Set<String> notFound = ConcurrentHashMap.newKeySet();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    /** SQL_IDs whose PL/SQL origin was asked by a tooltip: once per connection */
    private final Set<String> sourceAsked = ConcurrentHashMap.newKeySet();
    private final PlsqlSource plsqlSource;
    private volatile boolean cdbQuery = true;

    private SqlTexts(StoreManager storeManager, RemoteDBManager remoteDBManager) {
        this.storeManager = storeManager;
        this.remoteDBManager = remoteDBManager;
        this.service = new SnapshotService(remoteDBManager);
        this.plsqlSource = new PlsqlSource(service);
    }

    public static void configure(StoreManager storeManager, RemoteDBManager remoteDBManager) {
        instance = new SqlTexts(storeManager, remoteDBManager);
    }

    /** null when not configured */
    public static SqlTexts get() {
        return instance;
    }

    /** Text from the local history, null when not there */
    public String cached(String sqlId) {
        if (sqlId == null || sqlId.isEmpty()) {
            return null;
        }
        try {
            if (storeManager.getDatabaseDAO().getParamStringStringDAO().isExistValueByParameter(sqlId)) {
                return storeManager.getDatabaseDAO().getParamStringStringDAO().getValue(sqlId);
            }
        } catch (Exception e) {
            log.info("SQL text from local history: " + e.getMessage());
        }
        return null;
    }

    /** PL/SQL origin kept in the local history ("object_id|line|name", "" = not run by PL/SQL), null when unknown */
    private String storedSource(String sqlId) {
        if (sqlId == null || sqlId.isEmpty()) {
            return null;
        }
        try {
            String key = PlsqlSource.KEY_PREFIX + sqlId;
            if (storeManager.getDatabaseDAO().getParamStringStringDAO().isExistValueByParameter(key)) {
                return storeManager.getDatabaseDAO().getParamStringStringDAO().getValue(key);
            }
        } catch (Exception e) {
            log.info("PL/SQL origin from local history: " + e.getMessage());
        }
        return null;
    }

    /** No origin kept, or one of an older version of the package (the cursor may have been parsed again since) */
    private boolean needsSource(String sqlId) {
        String stored = storedSource(sqlId);
        return stored == null || PlsqlSource.isOlder(stored);
    }

    /** Where the SQL is written in PL/SQL ("OWNER.PACKAGE.PROCEDURE (line 15)"), null when unknown or not PL/SQL */
    public String source(String sqlId) {
        return PlsqlSource.display(storedSource(sqlId));
    }

    /** Value of the PLSQL_SOURCE column: OWNER.PACKAGE.PROCEDURE, "" when unknown or not PL/SQL */
    public String sourceGroup(String sqlId) {
        return PlsqlSource.group(storedSource(sqlId));
    }

    /**
     * Look up the PL/SQL origin of a SQL whose text is kept but the origin is not (texts saved before 1.5.0) in
     * the background, once per connection
     *
     * @param onFound on the EDT, when an origin was found
     */
    public void fetchSource(String sqlId, Runnable onFound) {
        if (!canFetch(sqlId) || !needsSource(sqlId) || !sourceAsked.add(sqlId)) {
            return;
        }
        fetch(sqlId, text -> {
            if (source(sqlId) != null) {
                onFound.run();
            }
        }, error -> { });
    }

    public boolean isNotFound(String sqlId) {
        return notFound.contains(sqlId);
    }

    public boolean isLoading(String sqlId) {
        return loading.contains(sqlId);
    }

    /** The text can be looked up in the database */
    public boolean canFetch(String sqlId) {
        IProfile iProfile = storeManager.getConfigurationManager().getIProfile();
        return sqlId != null && ORACLE_SQL_ID.matcher(sqlId).matches() && !notFound.contains(sqlId)
                && iProfile != null && iProfile.getProfileName().startsWith("Oracle")
                && remoteDBManager.getBasicDataSource() != null;
    }

    /**
     * Save the texts of the SQL_IDs seen in [from, to) that are not in the local history yet, reading v$sql in
     * batches (runs in the caller's thread). Texts aged out of the shared pool are marked "not found".
     *
     * @return texts saved, -1 when the database could not be read
     */
    public int captureRecent(long from, long to) {
        IProfile iProfile = storeManager.getConfigurationManager().getIProfile();
        if (iProfile == null || !iProfile.getProfileName().startsWith("Oracle") || remoteDBManager.getBasicDataSource() == null) {
            return 0;
        }
        List<String> missing = new ArrayList<>();
        for (String sqlId : sqlIdsIn(from, to)) {
            if (ORACLE_SQL_ID.matcher(sqlId).matches() && !notFound.contains(sqlId) && !loading.contains(sqlId)
                    && (cached(sqlId) == null || needsSource(sqlId))) {
                missing.add(sqlId);
            }
        }
        int saved = 0;
        for (int i = 0; i < missing.size(); i += BATCH) {
            List<String> batch = missing.subList(i, Math.min(missing.size(), i + BATCH));
            SnapshotService.Result result = query(batch);
            if (!result.isOk()) {
                log.info("SQL text capture: " + result.error);
                return saved == 0 ? -1 : saved;
            }
            Set<String> found = new HashSet<>();
            for (Object[] row : result.rows) {
                if (row[0] == null || row[1] == null) {
                    continue;
                }
                String sqlId = String.valueOf(row[0]);
                found.add(sqlId);
                if (cached(sqlId) != null) {
                    continue;
                }
                try {
                    storeManager.getDatabaseDAO().getParamStringStringDAO().putNoOverwrite(sqlId, String.valueOf(row[1]));
                    saved++;
                } catch (Exception e) {
                    log.info("SQL text to local history: " + e.getMessage());
                }
            }
            saveSources(result.rows);
            batch.stream().filter(id -> !found.contains(id)).forEach(notFound::add);
        }
        return saved;
    }

    /** Distinct SQL_IDs of the stored samples in [from, to) */
    private Set<String> sqlIdsIn(long from, long to) {
        Set<String> out = new LinkedHashSet<>();
        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        SqlColProfile sqlIdCol = cols == null ? null
                : cols.stream().filter(c -> c.getColName().equalsIgnoreCase("SQL_ID")).findFirst().orElse(null);
        // set by the collection: there is no capture without it
        if (sqlIdCol == null || storeManager.getDatabaseDAO().getConvertManager() == null) {
            return out;
        }
        int index = sqlIdCol.getColId() - 1;
        // the samples keep dictionary ids: convert each distinct id once
        Set<Integer> seen = new HashSet<>();
        try (EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(from, true, to, false)) {
            for (MainData sl : cursor) {
                int[][] matrix = sl.getMainMatrix();
                for (int row = 0; row < matrix.length; row++) {
                    if (index >= matrix[row].length || !seen.add(matrix[row][index])) {
                        continue;
                    }
                    Object value = storeManager.getDatabaseDAO().getConvertManager()
                            .getMatrixDataForJTable(sqlIdCol.getColDbTypeName(), sqlIdCol.getColId(), sl, row);
                    String sqlId = value == null ? "" : String.valueOf(value);
                    if (!sqlId.isEmpty()) {
                        out.add(sqlId);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Look up the text in v$sql in the background
     *
     * @param onDone on the EDT: the text, "" when not found in v$sql
     * @param onError on the EDT: the error message (not cached: a new attempt next time)
     */
    public void fetch(String sqlId, Consumer<String> onDone, Consumer<String> onError) {
        if (!canFetch(sqlId) || !loading.add(sqlId)) {
            return;
        }
        Thread t = new Thread(() -> {
            String text = null;
            String error = null;
            try {
                SnapshotService.Result result = query(Collections.singletonList(sqlId));
                if (!result.isOk()) {
                    error = result.error;
                } else if (result.rows.isEmpty() || result.rows.get(0)[1] == null) {
                    notFound.add(sqlId);
                    text = "";
                } else {
                    text = String.valueOf(result.rows.get(0)[1]);
                    try {
                        storeManager.getDatabaseDAO().getParamStringStringDAO().putNoOverwrite(sqlId, text);
                    } catch (Exception e) {
                        log.info("SQL text to local history: " + e.getMessage());
                    }
                    saveSources(result.rows);
                }
            } catch (RuntimeException e) { // the callbacks run anyway: the tooltip does not stay on "loading"
                log.info("SQL text: " + e);
                error = text == null ? String.valueOf(e.getMessage()) : null;
            } finally {
                loading.remove(sqlId);
            }
            String done = text;
            String failed = error;
            SwingUtilities.invokeLater(() -> {
                if (done != null) {
                    onDone.accept(done);
                } else {
                    onError.accept(failed);
                }
            });
        }, "sql-text");
        t.setDaemon(true);
        t.start();
    }

    /** v$sql rows {sql_id, text, program_id, program_line#, first_load_time} of valid SQL_IDs; 11g has no con_id (retried without) */
    private SnapshotService.Result query(List<String> sqlIds) {
        String in = sqlIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        boolean cdb = cdbQuery;
        SnapshotService.Result result = service.run(
                Collections.singletonMap("sql", String.format(QUERY_MANY, cdb ? PROGRAM_CDB : PROGRAM_11G, in)),
                Collections.singletonMap("sql", "v$sql")).get("sql");
        if (!result.isOk() && cdb && result.error != null
                && (result.error.contains("ORA-00904") || result.error.contains("ORA-02003"))) {
            cdbQuery = false;
            result = service.run(Collections.singletonMap("sql", String.format(QUERY_MANY, PROGRAM_11G, in)),
                    Collections.singletonMap("sql", "v$sql")).get("sql");
        }
        return result;
    }

    /**
     * Keep the PL/SQL origin of each row ("" when not run by PL/SQL). When the names can not be read now, the
     * origins wait for the next capture
     */
    private void saveSources(List<Object[]> rows) {
        Map<String, PlsqlSource.Origin> origins = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null || !needsSource(String.valueOf(row[0]))) {
                continue;
            }
            long objectId = row[2] instanceof Number ? ((Number) row[2]).longValue() : 0;
            int line = row[3] instanceof Number ? ((Number) row[3]).intValue() : 0;
            String loaded = row[4] == null ? null : String.valueOf(row[4]);
            origins.put(String.valueOf(row[0]), objectId > 0 ? new PlsqlSource.Origin(objectId, line, loaded) : null);
        }
        Map<PlsqlSource.Origin, String> values = plsqlSource.stored(
                origins.values().stream().filter(Objects::nonNull).collect(Collectors.toSet()));
        origins.forEach((sqlId, origin) -> {
            if (origin != null && (values == null || values.get(origin) == null)) {
                return; // read again in a next capture
            }
            try {
                storeManager.getDatabaseDAO().getParamStringStringDAO().put( // replaces an origin of an older version
                        PlsqlSource.KEY_PREFIX + sqlId, origin == null ? "" : values.get(origin));
            } catch (Exception e) {
                log.info("PL/SQL origin to local history: " + e.getMessage());
            }
        });
    }
}
