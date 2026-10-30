package profile;

import java.util.LinkedList;
import java.util.List;

public class OracleSE extends OracleEE implements IProfile {
    String profileName = "OracleSE";

    private String sqlTextAsh = "SELECT * FROM (SELECT sysdate SAMPLE_TIME, vs.sid SESSION_ID, vs.state SESSION_STATE, "
            + "vs.serial# SESSION_SERIAL#, vs.user# USER_ID, vs.sql_id SQL_ID, vs.type SESSION_TYPE, "
            + "vs.event# EVENT#, (CASE WHEN vs.wait_time != 0 THEN 'CPU used' ELSE vs.event END) EVENT, "
            + "vs.seq# SEQ#, vs.p1 P1, vs.p2 P2, vs.p3 P3, "
            + "vs.wait_time WAIT_TIME, vs.wait_class_id WAIT_CLASS_ID, vs.wait_class# WAIT_CLASS#, "
            + "(CASE WHEN vs.wait_time != 0 THEN 'CPU used' ELSE vs.wait_class END) WAIT_CLASS, vss.value TIME_WAITED, "
            + "vs.row_wait_obj# CURRENT_OBJ#, vs.row_wait_file# CURRENT_FILE#, vs.row_wait_block# CURRENT_BLOCK#, "
            + "vs.program PROGRAM, vs.module MODULE, vs.action ACTION, vs.fixed_table_sequence FIXED_TABLE_SEQUENCE, "
            + "nvl(au.name, 'UNKNOWN') COMMAND, "
            // new columns must be appended at the end to keep stored history readable
            + "vs.username USERNAME, vs.osuser OSUSER, vs.machine MACHINE, "
            + "vs.blocking_session BLOCKING_SESSION, vs.blocking_session_status BLOCKING_SESSION_STATUS, "
            + "vs.final_blocking_session FINAL_BLOCKING_SESSION, vs.sql_exec_start SQL_EXEC_START, "
            + "vs.prev_sql_id PREV_SQL_ID, vs.plsql_entry_object_id PLSQL_ENTRY_OBJECT_ID, "
            + "vs.client_identifier CLIENT_IDENTIFIER, vs.client_info CLIENT_INFO, "
            + "vs.service_name SERVICE_NAME, vs.sql_hash_value SQL_HASH_VALUE, "
            // v$session has no serial# of the blocker: looked up only for blocked rows of this instance
            + "(CASE WHEN vs.blocking_session IS NOT NULL AND vs.blocking_instance = USERENV('INSTANCE') "
            + "THEN (SELECT b.serial# FROM v$session b WHERE b.sid = vs.blocking_session) END) BLOCKING_SESSION_SERIAL#, "
            // plan of the running child cursor (v$session has no plan hash): compare periods shows plan changes
            + "vs.sql_exec_id SQL_EXEC_ID, "
            + "(CASE WHEN vs.sql_id IS NOT NULL THEN (SELECT s.plan_hash_value FROM v$sql s "
            + "WHERE s.sql_id = vs.sql_id AND s.child_number = vs.sql_child_number AND rownum = 1) END) SQL_PLAN_HASH_VALUE, "
            // PL/SQL called first by the session (package + its subprogram): groups the SQLs run by one procedure
            + "(CASE WHEN vs.plsql_entry_object_id IS NOT NULL "
            + "THEN vs.plsql_entry_object_id || '.' || vs.plsql_entry_subprogram_id END) PLSQL_ENTRY "
            + "FROM "
            + "v$session vs, v$sesstat vss, audit_actions au "
            + "WHERE vs.sid != ( select distinct sid from v$mystat  where rownum < 2 ) "
            + "and vs.sid = vss.sid and vs.command = au.action(+) "
            + "and vss.statistic# = 12 and (vs.wait_class != 'Idle' or vs.wait_time != 0) )";

    long interval = 1000; // 1 sec

    List<String> sqlIdAdditionalColName = new LinkedList<>();

    public OracleSE() {
        sqlIdAdditionalColName.add("COMMAND");
    }

    @Override
    public String getProfileName() { return profileName; }

    @Override
    public String getSqlTextAsh() { return sqlTextAsh; }

    @Override
    public String getSqlTextAshOneRow() { return sqlTextAsh; }

    @Override
    public List<String> getSqlIdAdditionalColName() { return sqlIdAdditionalColName; }

    @Override
    public long getInterval() {
        return interval;
    }
}
