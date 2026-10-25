-- Long executions (several seconds each, distinct SQL_EXEC_ID) for &1 minutes
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'long_exec')
declare
  n number; s number; deadline date := sysdate + &1 / 1440;
begin
  while sysdate < deadline loop
    execute immediate 'select /* ashv_long */ count(*) from ashv_t a, (select level l from dual connect by level <= 20) b '
                   || 'where mod(a.id + b.l, 97) = 0' into n;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
exit
