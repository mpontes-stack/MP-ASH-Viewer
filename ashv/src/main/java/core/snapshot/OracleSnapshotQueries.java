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

    private static String limitInPdb(String resource, String view) {
        return "SELECT '" + resource + "', (SELECT COUNT(*) FROM " + view + "), NULL, "
                + "(SELECT value FROM v$parameter WHERE name = '" + resource + "') FROM dual "
                + "WHERE NOT EXISTS (SELECT 1 FROM v$resource_limit WHERE resource_name = '" + resource + "')";
    }
    public static final String PGA_STAT = "pgaStat";
    public static final String PGA_LIMIT = "pgaLimit";
    public static final String PGA_TOP = "pgaTop";
    public static final String TABLESPACES = "tablespaces";
    public static final String LOCKS = "locks";
    public static final String ROLLBACK_SESSIONS = "rollbackSessions";
    public static final String ROLLBACK_SMON = "rollbackSmon";
    public static final String LONGOPS = "longops";
    public static final String JOBS = "jobs";
    public static final String TEMP_FREE = "tempFree";
    public static final String TEMP_TOP = "tempTop";
    public static final String UNDO_STAT = "undoStat";
    public static final String FRA = "fra";
    public static final String FRA_USAGE = "fraUsage";
    public static final String DG_STANDBY = "dgStandby";
    public static final String DG_DESTINATIONS = "dgDestinations";

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

        // inside a PDB v$resource_limit has no rows (they belong to the CDB): count and parameter instead, no peak
        q.put(LIMITS,
                "SELECT resource_name, current_utilization, max_utilization, limit_value "
                + "FROM v$resource_limit "
                + "WHERE resource_name IN ('processes', 'sessions', 'transactions', 'parallel_max_servers') "
                + "UNION ALL "
                + limitInPdb("processes", "v$process") + " UNION ALL "
                + limitInPdb("sessions", "v$session") + " UNION ALL "
                + limitInPdb("transactions", "v$transaction") + " UNION ALL "
                + limitInPdb("parallel_max_servers", "v$px_process"));

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

        q.put(LONGOPS,
                "SELECT s.sid, s.serial#, s.username, l.opname, l.target, l.sofar, l.totalwork, "
                + "ROUND(l.sofar * 100 / NULLIF(l.totalwork, 0), 1) pct, l.elapsed_seconds, l.time_remaining, s.sql_id "
                + "FROM v$session_longops l, v$session s "
                + "WHERE l.sid = s.sid AND l.serial# = s.serial# AND l.totalwork > 0 AND l.sofar < l.totalwork");

        q.put(JOBS,
                "SELECT r.owner, r.job_name, r.session_id, "
                + "EXTRACT(DAY FROM r.elapsed_time) * 86400 + EXTRACT(HOUR FROM r.elapsed_time) * 3600 "
                + "+ EXTRACT(MINUTE FROM r.elapsed_time) * 60 + ROUND(EXTRACT(SECOND FROM r.elapsed_time)) elapsed_s, "
                + "s.event, s.sql_id "
                + "FROM dba_scheduler_running_jobs r, v$session s "
                + "WHERE r.session_id = s.sid(+) "
                + "ORDER BY elapsed_s DESC");

        // size of the temp files and what the sessions use (same source as the list below); not dba_temp_free_space:
        // with a CDB its size is multiplied by the number of containers (681 MB for a 227 MB file)
        q.put(TEMP_FREE,
                "SELECT f.tablespace_name, f.bytes tablespace_size, f.bytes - NVL(u.used, 0) free_space "
                + "FROM (SELECT tablespace_name, SUM(bytes) bytes FROM dba_temp_files GROUP BY tablespace_name) f "
                + "LEFT JOIN (SELECT su.tablespace tablespace_name, SUM(su.blocks * t.block_size) used "
                + "FROM v$tempseg_usage su, dba_tablespaces t WHERE su.tablespace = t.tablespace_name "
                + "GROUP BY su.tablespace) u ON u.tablespace_name = f.tablespace_name");

        q.put(TEMP_TOP,
                "SELECT * FROM ("
                + "SELECT s.sid, s.serial#, s.username, s.status, s.machine, s.program, s.sql_id, "
                + "u.tablespace, u.segtype, ROUND(u.blocks * t.block_size / 1048576, 1) mb "
                + "FROM v$tempseg_usage u, v$session s, dba_tablespaces t "
                + "WHERE u.session_addr = s.saddr AND u.tablespace = t.tablespace_name "
                + "ORDER BY u.blocks DESC) "
                + "WHERE ROWNUM <= 10");

        q.put(UNDO_STAT, // last 10 minutes interval
                "SELECT * FROM ("
                + "SELECT begin_time, end_time, activeblks, unexpiredblks, expiredblks, tuned_undoretention, "
                + "maxquerylen, maxqueryid, ssolderrcnt, nospaceerrcnt, "
                + "(SELECT value FROM v$parameter WHERE name = 'db_block_size') block_size "
                + "FROM v$undostat ORDER BY begin_time DESC) "
                + "WHERE ROWNUM = 1");

        q.put(FRA,
                "SELECT name, space_limit, space_used, space_reclaimable, number_of_files FROM v$recovery_file_dest");

        q.put(FRA_USAGE,
                "SELECT file_type, percent_space_used, percent_space_reclaimable, number_of_files "
                + "FROM v$recovery_area_usage WHERE number_of_files > 0 OR percent_space_used > 0 "
                + "ORDER BY percent_space_used DESC");

        q.put(DG_STANDBY, // rows only on a standby
                "SELECT name, value, time_computed FROM v$dataguard_stats "
                + "WHERE name IN ('transport lag', 'apply lag', 'apply finish time') AND value IS NOT NULL");

        q.put(DG_DESTINATIONS, // standby destinations of a primary
                "SELECT dest_id, dest_name, status, type, database_mode, recovery_mode, gap_status, error "
                + "FROM v$archive_dest_status WHERE status <> 'INACTIVE' AND type <> 'LOCAL'");

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
        v.put(LIMITS, "V$RESOURCE_LIMIT, V$PARAMETER");
        v.put(PGA_STAT, "V$PGASTAT");
        v.put(PGA_LIMIT, "V$PARAMETER");
        v.put(PGA_TOP, "V$SESSION, V$PROCESS");
        v.put(TABLESPACES, "DBA_TABLESPACE_USAGE_METRICS, DBA_TABLESPACES");
        v.put(LOCKS, "V$SESSION, DBA_OBJECTS");
        v.put(ROLLBACK_SESSIONS, "V$TRANSACTION, V$SESSION, V$PARAMETER");
        v.put(ROLLBACK_SMON, "V$FAST_START_TRANSACTIONS");
        v.put(LONGOPS, "V$SESSION_LONGOPS, V$SESSION");
        v.put(JOBS, "DBA_SCHEDULER_RUNNING_JOBS, V$SESSION");
        v.put(TEMP_FREE, "DBA_TEMP_FILES, V$TEMPSEG_USAGE, DBA_TABLESPACES");
        v.put(TEMP_TOP, "V$TEMPSEG_USAGE, V$SESSION, DBA_TABLESPACES");
        v.put(UNDO_STAT, "V$UNDOSTAT, V$PARAMETER");
        v.put(FRA, "V$RECOVERY_FILE_DEST");
        v.put(FRA_USAGE, "V$RECOVERY_AREA_USAGE");
        v.put(DG_STANDBY, "V$DATAGUARD_STATS");
        v.put(DG_DESTINATIONS, "V$ARCHIVE_DEST_STATUS");
        return v;
    }
}
