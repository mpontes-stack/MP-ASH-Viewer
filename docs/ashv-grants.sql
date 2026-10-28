-- MP-ASH-Viewer: least privilege grants for the monitoring user (Oracle)
--
-- The simplest is:  GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO ashv;
-- This script gives, instead, only what the app reads, view by view. Run as SYS (V_$ are the objects behind the
-- V$ synonyms). In a container database run it in the PDB you monitor (or use a common user C##ASHV for the root).
-- Every grant is a read of the dictionary: the app never writes in the monitored database.

-- CREATE USER ashv IDENTIFIED BY "<password>";
GRANT CREATE SESSION TO ashv;

-- Collection, every second (OracleSE profile)
GRANT SELECT ON sys.v_$session     TO ashv;
GRANT SELECT ON sys.v_$sesstat     TO ashv;
GRANT SELECT ON sys.v_$mystat      TO ashv;
GRANT SELECT ON sys.audit_actions  TO ashv;
GRANT SELECT ON sys.v_$sql         TO ashv;   -- plan of the running SQL; SQL texts (every 10 min)
GRANT SELECT ON sys.v_$sql_plan    TO ashv;   -- execution plans in the SQL details
GRANT SELECT ON sys.v_$sql_plan_statistics_all TO ashv;   -- DBMS_XPLAN tab, format "Actual rows (ALLSTATS LAST)"

-- Locks: object names (falls back to ALL_OBJECTS without it)
GRANT SELECT ON sys.dba_objects    TO ashv;

-- Now: Sessions / Now: Resources (each section shows "no permission" when its view is missing)
GRANT SELECT ON sys.v_$instance                     TO ashv;
GRANT SELECT ON sys.v_$database                     TO ashv;
GRANT SELECT ON sys.v_$parameter                    TO ashv;
GRANT SELECT ON sys.v_$resource_limit               TO ashv;
GRANT SELECT ON sys.v_$process                      TO ashv;
GRANT SELECT ON sys.v_$pgastat                      TO ashv;
GRANT SELECT ON sys.v_$transaction                  TO ashv;
GRANT SELECT ON sys.v_$px_process                   TO ashv;
GRANT SELECT ON sys.v_$fast_start_transactions      TO ashv;
GRANT SELECT ON sys.v_$session_longops              TO ashv;
GRANT SELECT ON sys.v_$tempseg_usage                TO ashv;
GRANT SELECT ON sys.v_$undostat                     TO ashv;
GRANT SELECT ON sys.v_$recovery_file_dest           TO ashv;
GRANT SELECT ON sys.v_$recovery_area_usage          TO ashv;
GRANT SELECT ON sys.v_$dataguard_stats              TO ashv;
GRANT SELECT ON sys.v_$archive_dest_status          TO ashv;
GRANT SELECT ON sys.dba_tablespaces                 TO ashv;
GRANT SELECT ON sys.dba_tablespace_usage_metrics    TO ashv;
GRANT SELECT ON sys.dba_temp_files                  TO ashv;
GRANT SELECT ON sys.dba_scheduler_running_jobs      TO ashv;

-- Now: OS (CPU, load, memory and I/O of the server; CPU of this database / of each container)
GRANT SELECT ON sys.v_$osstat                       TO ashv;
GRANT SELECT ON sys.v_$sys_time_model               TO ashv;
GRANT SELECT ON sys.v_$sgainfo                      TO ashv;
GRANT SELECT ON sys.v_$iostat_function              TO ashv;
-- CDB root with a common user (C##ASHV): CPU by container also needs
-- GRANT SELECT ON sys.v_$con_sys_time_model TO c##ashv;  GRANT SELECT ON sys.v_$containers TO c##ashv;
-- and, to see the rows of the PDBs in the V$ views:  ALTER USER c##ashv SET CONTAINER_DATA=ALL CONTAINER=CURRENT;

-- Optional: "Trace 10046" in the session details
-- GRANT EXECUTE ON sys.dbms_monitor TO ashv;
