-- CPU: PL/SQL arithmetic for &1 minutes
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'cpu')
declare
  n number := 0; s number; deadline date := sysdate + &1 / 1440;
begin
  while sysdate < deadline loop
    for i in 1 .. 200000 loop n := mod(n + sqrt(i), 1000); end loop;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
exit
