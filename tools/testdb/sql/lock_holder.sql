-- Holds a row lock of ashv_lock for &1 minutes (waiters show "enq: TX - row lock contention"), then commits
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'lock')
declare
  s number; deadline date := sysdate + &1 / 1440;
begin
  update ashv_lock set v = v + 1 where id = 1;
  while sysdate < deadline loop
    dbms_session.sleep(2);
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
  commit;
end;
/
exit
