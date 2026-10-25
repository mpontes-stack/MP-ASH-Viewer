-- User I/O: full scans with direct path reads (no buffer cache) for &1 minutes
set verify off serveroutput on feedback off
exec dbms_application_info.set_module('ashv_test', 'io')
whenever sqlerror continue
alter session set "_serial_direct_read" = always;
declare
  n number; s number; deadline date := sysdate + &1 / 1440;
begin
  while sysdate < deadline loop
    execute immediate 'select /* ashv_io */ count(pad) from ashv_t' into n;
    select stop into s from ashv_ctl;
    exit when s = 1;
  end loop;
end;
/
exit
