package core.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Light point in time queries for Oracle (11g+), used by the "Agora" tab
 */
public final class OracleSnapshotQueries {
    public static final String HEADER = "header";
    public static final String SESSIONS_BY_TYPE = "sessionsByType";
    public static final String SESSIONS_USER = "sessionsUser";
    public static final String LIMITS = "limits";
    public static final String PGA_STAT = "pgaStat";
    public static final String PGA_LIMIT = "pgaLimit";
    public static final String PGA_TOP = "pgaTop";
    public static final String TABLESPACES = "tablespaces";
    public static final String LOCKS = "locks";
    public static final String ROLLBACK_SESSIONS = "rollbackSessions";
    public static final String ROLLBACK_SMON = "rollbackSmon";

    private OracleSnapshotQueries() {}

    public static Map<String, String> queries() {
        Map<String, String> q = new LinkedHashMap<>();

        q.put(HEADER,
                "SELECT i.instance_name, i.host_name, i.version, i.startup_time, i.status, "
                + "d.name, d.database_role, d.open_mode "
                + "FROM v$instance i, v$database d");

        q.put(SESSIONS_BY_TYPE,
                "SELECT type, status, COUNT(*) cnt FROM v$session GROUP BY type, status");

        q.put(SESSIONS_USER,
                "SELECT username, machine, program, module, status, COUNT(*) cnt "
                + "FROM v$session WHERE type = 'USER' "
                + "GROUP BY username, machine, program, module, status");

        q.put(LIMITS,
                "SELECT resource_name, current_utilization, max_utilization, limit_value "
                + "FROM v$resource_limit "
                + "WHERE resource_name IN ('processes', 'sessions', 'transactions', 'parallel_max_servers')");

        q.put(LOCKS,
                "SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program, s.sql_id, "
                + "s.blocking_session, s.final_blocking_session, s.event, s.seconds_in_wait, "
                + "o.owner || '.' || o.object_name obj "
                + "FROM v$session s, dba_objects o "
                + "WHERE (s.blocking_session IS NOT NULL "
                + "OR s.sid IN (SELECT blocking_session FROM v$session WHERE blocking_session IS NOT NULL)) "
                + "AND s.row_wait_obj# = o.object_id(+)");

        q.put(ROLLBACK_SESSIONS, // flag 128 = rollback in progress
                "SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program, s.sql_id, "
                + "t.start_time, t.used_ublk, t.used_urec, "
                + "ROUND(t.used_ublk * (SELECT value FROM v$parameter WHERE name = 'db_block_size') / 1048576, 1) undo_mb "
                + "FROM v$transaction t, v$session s "
                + "WHERE t.ses_addr = s.saddr AND BITAND(t.flag, 128) = 128");

        q.put(ROLLBACK_SMON, // dead transactions recovered by SMON (e.g. after kill session)
                "SELECT usn, state, undoblocksdone, undoblockstotal, "
                + "ROUND(undoblocksdone * 100 / NULLIF(undoblockstotal, 0), 1) pct "
                + "FROM v$fast_start_transactions WHERE state <> 'RECOVERED'");

        q.put(PGA_STAT,
                "SELECT name, value FROM v$pgastat "
                + "WHERE name IN ('aggregate PGA target parameter', 'total PGA allocated', 'total PGA inuse', "
                + "'maximum PGA allocated', 'total freeable PGA memory', 'over allocation count')");

        q.put(PGA_LIMIT,
                "SELECT value FROM v$parameter WHERE name = 'pga_aggregate_limit'");

        q.put(PGA_TOP,
                "SELECT * FROM ("
                + "SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program, s.module, s.sql_id, "
                + "ROUND(p.pga_used_mem / 1048576, 1) used_mb, "
                + "ROUND(p.pga_alloc_mem / 1048576, 1) alloc_mb, "
                + "ROUND(p.pga_max_mem / 1048576, 1) max_mb "
                + "FROM v$session s, v$process p "
                + "WHERE s.paddr = p.addr "
                + "ORDER BY p.pga_alloc_mem DESC) "
                + "WHERE ROWNUM <= 20");

        q.put(TABLESPACES,
                "SELECT m.tablespace_name, t.contents, "
                + "ROUND(m.used_space * t.block_size / 1073741824, 2) used_gb, "
                + "ROUND(m.tablespace_size * t.block_size / 1073741824, 2) max_gb, "
                + "ROUND(m.used_percent, 1) used_pct "
                + "FROM dba_tablespace_usage_metrics m, dba_tablespaces t "
                + "WHERE m.tablespace_name = t.tablespace_name "
                + "ORDER BY m.used_percent DESC");

        return q;
    }

    /** Views used by each block (shown when the user has no permission) */
    public static Map<String, String> views() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put(HEADER, "V$INSTANCE, V$DATABASE");
        v.put(SESSIONS_BY_TYPE, "V$SESSION");
        v.put(SESSIONS_USER, "V$SESSION");
        v.put(LIMITS, "V$RESOURCE_LIMIT");
        v.put(PGA_STAT, "V$PGASTAT");
        v.put(PGA_LIMIT, "V$PARAMETER");
        v.put(PGA_TOP, "V$SESSION, V$PROCESS");
        v.put(TABLESPACES, "DBA_TABLESPACE_USAGE_METRICS, DBA_TABLESPACES");
        v.put(LOCKS, "V$SESSION, DBA_OBJECTS");
        v.put(ROLLBACK_SESSIONS, "V$TRANSACTION, V$SESSION, V$PARAMETER");
        v.put(ROLLBACK_SMON, "V$FAST_START_TRANSACTIONS");
        return v;
    }
}
