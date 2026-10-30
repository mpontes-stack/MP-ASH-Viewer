-- PL/SQL caller test: package ashv_pkg with 3 procedures, each runs a different SQL (created before the load)
set verify off feedback off
create or replace package ashv_pkg as
  procedure calc_total;
  procedure scan_groups;
  procedure count_pads;
end ashv_pkg;
/
create or replace package body ashv_pkg as
  procedure calc_total is
    n number;
  begin
    select sum(sqrt(id)) into n from ashv_t;
  end;
  procedure scan_groups is
    n number;
  begin
    select count(distinct grp) into n from ashv_t where mod(id, 3) = 0;
  end;
  procedure count_pads is
    n number;
  begin
    select count(*) into n from ashv_t where pad like '%x%';
  end;
end ashv_pkg;
/
show errors package body ashv_pkg
exit
