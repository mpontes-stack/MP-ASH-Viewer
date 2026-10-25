-- Drops the test tables
set verify off serveroutput on feedback off
declare
  procedure drop_if(t varchar2) is
  begin
    execute immediate 'drop table ' || t || ' purge';
  exception when others then if sqlcode != -942 then raise; end if;
  end;
begin
  drop_if('ashv_t'); drop_if('ashv_ctl'); drop_if('ashv_lock');
  dbms_output.put_line('tabelas de teste removidas');
end;
/
exit
