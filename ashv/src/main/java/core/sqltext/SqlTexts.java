package core.sqltext;

import core.snapshot.SnapshotService;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import profile.IProfile;
import remote.RemoteDBManager;
import store.StoreManager;

/**
 * SQL text of a SQL_ID: from the local history (saved by the SQL detail or the Postgres collection),
 * otherwise from v$sql (Oracle) in the background, then saved in the local history.
 * One instance for the application: one connection at a time.
 */
@Slf4j
public final class SqlTexts {
    private static final Pattern ORACLE_SQL_ID = Pattern.compile("^[0-9a-z]{13}$");
    private static final String QUERY = "SELECT sql_fulltext FROM v$sql WHERE sql_id = '%s' AND rownum < 2";

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
