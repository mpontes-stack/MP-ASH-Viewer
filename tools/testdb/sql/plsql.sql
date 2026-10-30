-- PL/SQL caller: an anonymous block calls the 3 procedures of ashv_pkg for &1 minutes
-- (PLSQL_ENTRY of the samples = ashv_pkg.CALC_TOTAL / SCAN_GROUPS / COUNT_PADS)
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'plsql')
declare
  s number; deadline date := sysdate + &1 / 1440;
begin
  while sysdate < deadline loop
    ashv_pkg.calc_total;
    ashv_pkg.scan_groups;
    ashv_pkg.calc_total;
    ashv_pkg.count_pads;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
exit
