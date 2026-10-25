-- Plan change: the same SQL text runs &1 / 2 minutes with a full scan, then the index becomes visible
-- (new plan, same SQL_ID) for the other half. Prints the time of the change.
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'plan_change')
alter index ashv_t_grp invisible;
declare
  n number; s number; half date := sysdate + (&1 / 2) / 1440; deadline date := sysdate + &1 / 1440;
  sql_text constant varchar2(100) := 'select /* ashv_plan */ count(pad) from ashv_t where grp = 7';
begin
  while sysdate < half loop
    execute immediate sql_text into n;
    select stop into s from ashv_ctl;
    if s = 1 then return; end if;
  end loop;
  execute immediate 'alter index ashv_t_grp visible';
  dbms_output.put_line('indice visivel (novo plano) as ' || to_char(sysdate, 'YYYY-MM-DD HH24:MI:SS'));
  while sysdate < deadline loop
    execute immediate sql_text into n;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
alter index ashv_t_grp invisible;
exit
