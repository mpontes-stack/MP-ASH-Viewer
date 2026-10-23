package core.snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import remote.RemoteDBManager;

/**
 * Point in time queries for the "Agora" tab: one pooled connection per refresh, a timeout per query
 * and an error message per block (no permission, timeout...) instead of failing the whole refresh
 */
@Slf4j
public class SnapshotService {
    public static final int QUERY_TIMEOUT_SEC = 10;

    // Network timeout of the borrowed connection: a query can not be cancelled when the network is down
    private static final int NETWORK_TIMEOUT_MS = RemoteDBManager.TIMEOUT_SECONDS * 1000;
    private static final java.util.concurrent.Executor NETWORK_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "snapshot-network-timeout");
                t.setDaemon(true);
                return t;
            });

    private final RemoteDBManager remoteDBManager;

    // Getting a connection runs apart and is abandoned after the timeout: the Oracle driver can wait forever
    // for the database answer while connecting (frozen VPN / tunnel)
    private static final java.util.concurrent.ExecutorService CONNECT_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread t = new Thread(runnable, "snapshot-connect");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.Future<Connection> pendingConnect;
    private long pendingSince;
    private static final long RETRY_PENDING_AFTER_MS = 60000; // a stuck attempt may never end

    public SnapshotService(RemoteDBManager remoteDBManager) {
        this.remoteDBManager = remoteDBManager;
    }

    /**
     * Rows of one query, or the error that prevented it
     */
    public static class Result {
        public final List<String> columns;
        public final List<Object[]> rows;
        public final String error;
        public final long elapsedMs;
        boolean isLostConnection;

        Result(List<String> columns, List<Object[]> rows, String error, long elapsedMs) {
            this.columns = columns;
            this.rows = rows;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        public boolean isOk() {
            return error == null;
        }

        static Result error(String error) {
            return new Result(Collections.emptyList(), Collections.emptyList(), error, 0);
        }
    }

    /**
     * Run the queries in order on one connection taken from the pool
     *
     * @param queries block name -> query
     * @param views   block name -> views used (for the "no permission" message)
     */
    public Map<String, Result> run(Map<String, String> queries, Map<String, String> views) {
        Map<String, Result> out = new LinkedHashMap<>();

        try (Connection connection = getConnectionWithTimeout()) {
            setNetworkTimeout(connection, NETWORK_TIMEOUT_MS);
            try {
                String lostConnection = null;
                for (Map.Entry<String, String> query : queries.entrySet()) {
                    if (lostConnection != null) { // do not wait again for each remaining query
                        out.put(query.getKey(), Result.error(lostConnection));
                        continue;
                    }
                    Result result = runQuery(connection, query.getValue(), views.get(query.getKey()));
                    out.put(query.getKey(), result);
                    if (result.isLostConnection) {
                        lostConnection = result.error;
                    }
                }
            } finally {
                setNetworkTimeout(connection, 0); // back to the pool without timeout
            }
        } catch (SQLException e) {
            log.error("Snapshot connection: " + e.getMessage());
            String message = e.getMessage() != null && (e.getMessage().startsWith("sem resposta") || e.getMessage().startsWith("aguardando"))
                    ? e.getMessage() : "sem conexão: " + e.getMessage();
            queries.keySet().forEach(name -> out.put(name, Result.error(message)));
        }

        return out;
    }

    /**
     * Connection from the pool in at most {@link RemoteDBManager#TIMEOUT_SECONDS}; an abandoned attempt
     * returns its connection to the pool when it ends, and no new attempt starts while it is pending
     */
    private synchronized Connection getConnectionWithTimeout() throws SQLException {
        if (pendingConnect != null && !pendingConnect.isDone()
                && System.currentTimeMillis() - pendingSince < RETRY_PENDING_AFTER_MS) {
            throw new SQLException("aguardando a tentativa anterior de conexão (rede ou conexão caiu?)");
        }
        java.util.concurrent.atomic.AtomicBoolean abandoned = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.Future<Connection> future = CONNECT_EXECUTOR.submit(() -> {
            Connection c = remoteDBManager.getConnection();
            if (abandoned.get()) {
                closeQuietly(c);
                return null;
            }
            return c;
        });
        try {
            return future.get(RemoteDBManager.TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            abandoned.set(true);
            pendingConnect = future;
            pendingSince = System.currentTimeMillis();
            if (future.isDone()) { // finished just after the timeout
                try {
                    closeQuietly(future.get());
                } catch (Exception ignore) {
                    // failed anyway
                }
            }
            throw new SQLException("sem resposta do banco em " + RemoteDBManager.TIMEOUT_SECONDS + " s (rede ou conexão caiu?)");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof SQLException ? (SQLException) e.getCause() : new SQLException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignore) {
            // broken connection
        }
    }

    private static void setNetworkTimeout(Connection connection, int millis) {
        try {
            connection.setNetworkTimeout(NETWORK_EXECUTOR, millis);
        } catch (Throwable e) { // driver without support or connection already broken
            log.debug("setNetworkTimeout: " + e.getMessage());
        }
    }

    private Result runQuery(Connection connection, String sql, String views) {
        long start = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SEC);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(md.getColumnLabel(i).toUpperCase());
                }
                List<Object[]> rows = new ArrayList<>();
                while (rs.next()) {
                    Object[] row = new Object[columns.size()];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = rs.getObject(i + 1);
                        if (row[i] instanceof java.sql.Clob) { // read now: not valid after the connection goes back
                            java.sql.Clob clob = (java.sql.Clob) row[i];
                            row[i] = clob.getSubString(1, (int) clob.length());
                        }
                    }
                    rows.add(row);
                }
                return new Result(columns, rows, null, System.currentTimeMillis() - start);
            }
        } catch (SQLTimeoutException e) {
            return Result.error("tempo limite de " + QUERY_TIMEOUT_SEC + " s excedido");
        } catch (java.sql.SQLRecoverableException e) { // socket read timeout / connection lost
            log.info("Snapshot connection lost: " + e.getMessage());
            Result result = Result.error("sem resposta do banco em " + RemoteDBManager.TIMEOUT_SECONDS
                    + " s (rede ou conexão caiu?)");
            result.isLostConnection = true;
            return result;
        } catch (SQLException e) {
            log.info("Snapshot query failed: " + e.getMessage());
            switch (e.getErrorCode()) {
                case 942:   // table or view does not exist
                case 1031:  // insufficient privileges
                    return Result.error("sem permissão: precisa de SELECT em " + (views == null ? "views do catálogo" : views));
                case 1013:  // user requested cancel (timeout)
                    return Result.error("tempo limite de " + QUERY_TIMEOUT_SEC + " s excedido");
                default:
                    return Result.error(e.getMessage() == null ? e.toString() : e.getMessage().trim());
            }
        }
    }
}
