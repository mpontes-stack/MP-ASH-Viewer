-- Test tables (in the test user's schema): ~1M rows, an invisible index (plan_change), control and lock tables
whenever sqlerror exit failure
set verify off serveroutput on feedback off
declare
  procedure drop_if(t varchar2) is
  begin
    execute immediate 'drop table ' || t || ' purge';
  exception when others then if sqlcode != -942 then raise; end if;
  end;
begin
  drop_if('ashv_t'); drop_if('ashv_ctl'); drop_if('ashv_lock');
end;
/
create table ashv_t (id number primary key, grp number, pad varchar2(200));
insert /*+ append */ into ashv_t
  select level, mod(level, 1000), rpad('x', 200, 'x') from dual connect by level <= 1000000;
commit;
create index ashv_t_grp on ashv_t(grp) invisible;
exec dbms_stats.gather_table_stats(user, 'ASHV_T')
create table ashv_ctl (stop number);
insert into ashv_ctl values (0);
create table ashv_lock (id number primary key, v number);
insert into ashv_lock values (1, 0);
commit;
select 'setup ok: ' || count(*) || ' linhas em ashv_t' resultado from ashv_t;
exit
