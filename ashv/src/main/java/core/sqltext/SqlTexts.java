package core.sqltext;

import com.sleepycat.persist.EntityCursor;
import config.profile.SqlColProfile;
import core.snapshot.SnapshotService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String QUERY = "SELECT sql_fulltext FROM v$sql WHERE sql_id = '%s' AND rownum < 2";
    /** Several SQL_IDs at once (first child of each); ids are checked against ORACLE_SQL_ID before */
    private static final String QUERY_MANY = "SELECT sql_id, sql_fulltext FROM (SELECT sql_id, sql_fulltext, "
            + "ROW_NUMBER() OVER (PARTITION BY sql_id ORDER BY child_number) rn FROM v$sql WHERE sql_id IN (%s)) WHERE rn = 1";
    private static final int BATCH = 100;

    private static volatile SqlTexts instance;

    private final StoreManager storeManager;
    private final SnapshotService service;
    private final RemoteDBManager remoteDBManager;

    /** SQL_IDs not found in v$sql (aged out of the shared pool): not asked again */
    private final Set<String> notFound = ConcurrentHashMap.newKeySet();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    private SqlTexts(StoreManager storeManager, RemoteDBManager remoteDBManager) {
        this.storeManager = storeManager;
        this.remoteDBManager = remoteDBManager;
        this.service = new SnapshotService(remoteDBManager);
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
                    && cached(sqlId) == null) {
                missing.add(sqlId);
            }
        }
        int saved = 0;
        for (int i = 0; i < missing.size(); i += BATCH) {
            List<String> batch = missing.subList(i, Math.min(missing.size(), i + BATCH));
            String in = batch.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
            SnapshotService.Result result = service.run(
                    Collections.singletonMap("sql", String.format(QUERY_MANY, in)),
                    Collections.singletonMap("sql", "v$sql")).get("sql");
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
                try {
                    storeManager.getDatabaseDAO().getParamStringStringDAO().putNoOverwrite(sqlId, String.valueOf(row[1]));
                    saved++;
                } catch (Exception e) {
                    log.info("SQL text to local history: " + e.getMessage());
                }
            }
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
            SnapshotService.Result result = service.run(
                    Collections.singletonMap("sql", String.format(QUERY, sqlId)),
                    Collections.singletonMap("sql", "v$sql")).get("sql");
            String text = null;
            if (result.isOk()) {
                if (result.rows.isEmpty() || result.rows.get(0)[0] == null) {
                    notFound.add(sqlId);
                    text = "";
                } else {
                    text = String.valueOf(result.rows.get(0)[0]);
                    try {
                        storeManager.getDatabaseDAO().getParamStringStringDAO().putNoOverwrite(sqlId, text);
                    } catch (Exception e) {
                        log.info("SQL text to local history: " + e.getMessage());
                    }
                }
            }
            loading.remove(sqlId);
            String done = text;
            SwingUtilities.invokeLater(() -> {
                if (done != null) {
                    onDone.accept(done);
                } else {
                    onError.accept(result.error);
                }
            });
        }, "sql-text");
        t.setDaemon(true);
        t.start();
    }
}
