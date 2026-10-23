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

    private final RemoteDBManager remoteDBManager;

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

        try (Connection connection = remoteDBManager.getConnection()) {
            queries.forEach((name, sql) -> out.put(name, runQuery(connection, sql, views.get(name))));
        } catch (SQLException e) {
            log.error("Snapshot connection: " + e.getMessage());
            queries.keySet().forEach(name -> out.put(name, Result.error("sem conexão: " + e.getMessage())));
        }

        return out;
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
                    }
                    rows.add(row);
                }
                return new Result(columns, rows, null, System.currentTimeMillis() - start);
            }
        } catch (SQLTimeoutException e) {
            return Result.error("tempo limite de " + QUERY_TIMEOUT_SEC + " s excedido");
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
