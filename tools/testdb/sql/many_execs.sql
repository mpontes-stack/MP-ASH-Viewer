-- Many short executions (lookups by primary key) for &1 minutes: most are too short to be sampled
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'many_execs')
declare
  v varchar2(200); s number; deadline date := sysdate + &1 / 1440; k number := 0;
begin
  while sysdate < deadline loop
    for i in 1 .. 2000 loop
      execute immediate 'select /* ashv_short */ pad from ashv_t where id = :1' into v using mod(i * 7919, 1000000) + 1;
    end loop;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
exit
