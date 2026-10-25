-- Waits for the row locked by lock_holder.sql
set verify off feedback off
exec dbms_application_info.set_module('ashv_test', 'lock')
update ashv_lock set v = v + 1 where id = 1;
commit;
exit
