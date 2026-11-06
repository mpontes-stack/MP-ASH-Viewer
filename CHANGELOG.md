# Changelog

Changes in each version of MP-ASH-Viewer. Downloads: [Releases](https://github.com/mpontes-stack/MP-ASH-Viewer/releases).

## 1.4.0 (2026-11-06)

New:

- **Hide background** (Oracle), next to the Top Activity, History and Compare charts: hides the sessions of the
  background processes (`SESSION_TYPE = BACKGROUND`: DBWn, LGWR, MMON...) in every chart and table. It is not one of
  the filters (Clear and favorites keep it) and the choice is kept for the next connections.
- **Hide (empty)**, next to "Group by" and "Compare by": leaves out the "(empty)" value of the grouped column
  (sessions without MODULE, ACTION...) in the Group by charts and in the Compare by table.
- **PL/SQL caller** (OracleSE): new column `PLSQL_ENTRY`. Group, compare or filter by it to see the SQLs run inside
  one procedure as one block, like the "Top Level SQL" of Enterprise Manager, shown as `OWNER.PACKAGE.PROCEDURE`.
  `PLSQL_ENTRY_OBJECT_ID` (by package) also shows names now. The names come from `dba_procedures` (new grant in
  `docs/ashv-grants.sql`; falls back to `all_procedures`), looked up once per package while connected.
- `TOP_LEVEL_SQL_ID` in "Group by" and "Compare by" for the OracleEE profile.

Fixed:

- OracleSE collection: `TIME_WAITED` was `v$sesstat` statistic# 12 (`user logons cumulative` in 19c), not a time,
  and the join with `v$sesstat` was the most expensive part of the query run every second (about half of its time).
  Now it is the time of the current wait in microseconds (`v$session.wait_time_micro`) and 0 on CPU, as in ASH. In
  the history collected before 1.4.0 this column (Raw data, CSV) is a count of logons: do not use it as a time. The
  `v_$sesstat` grant is no longer needed.

## 1.3.0 (2026-10-29)

New:

- **Now: OS**: the chart keeps its history while the tab is hidden. With the automatic refresh on, only `v$osstat`
  is read in the background (one small query per interval), so the chart already has the points since the connection
  when the tab is opened. The chart shows the last hour, whatever the refresh interval.

## 1.2.0 (2026-10-29)

PostgreSQL reviewed (tested on PostgreSQL on AWS RDS):

- The features of this fork work on PostgreSQL connections: **Compare** two periods (by wait class, SQL_ID,
  session...), **filters** and favorites, **Group by** any column, SQL text, CSV export, Admin.
- The screens that exist only for Oracle are hidden on PostgreSQL: Now: Sessions / Resources / OS, ASH Report,
  Locks, execution plan and SQL / session statistics. The SQL window shows "ASH" and "Sql text".

Fixed:

- Compare: the "Compare by" table is no longer squeezed when the wait class table is made taller; the tables below
  are pushed down (scroll bar), as the charts already did.

## 1.1.0 (2026-10-28)

New:

- **Now: OS** tab: server CPU (user, sys, I/O wait), load per core, how much of the CPU is this database (or each PDB,
  when connected to the root), memory (RAM, free, OS cache, swap, SGA, PGA, paging), a chart of the last 60 refreshes
  and I/O by function with latency. Needs new grants (see `docs/ashv-grants.sql`): `v_$osstat`, `v_$sys_time_model`,
  `v_$sgainfo`, `v_$iostat_function`.
- **DBMS_XPLAN** sub tab in the SQL details: the plan as `DBMS_XPLAN.DISPLAY_CURSOR` text, with actual rows when
  available.
- Execution plan tree easier to read; full table scans highlighted.

Fixed:

- Top Activity keeps the last hour after a connection gap (Reconnect) instead of shrinking the time scale.
- OracleSE is the default profile of a new connection (OracleEE needs the Diagnostics Pack).
- Connection names cut with "..." in the connection tree even when there was room.

## 1.0.1 (2026-10-26)

- First published version: README, features documentation, grants script, "About" window.

## 1.0 (2026-10-26)

First version of the fork of [ASH Viewer](https://github.com/akardapolov/ASH-Viewer):

- Top Activity with filters (also exclusions), saved filters, "Group by" any column, right click "filter by value".
- More `v$session` columns collected by OracleSE; SQL texts captured every 10 minutes and kept in the local history.
- **Compare** tab: two periods of the history, by wait class and by SQL_ID, module, session...; execution plan
  changes; more executions or slower ones.
- **Locks** tab: blocking tree live and in the history.
- **Now: Sessions** and **Now: Resources** tabs: sessions, locks, long operations, jobs, PGA, TEMP, UNDO,
  tablespaces, FRA, Data Guard.
- **Admin** tab: disk usage of the local histories, manual and automatic purge, export / import of a period (.zip).
- CSV export (Raw data, Top sql & sessions, Locks); SQL text tooltip and "Copy SQL text".
- Reconnect button for the collection.
- English and Portuguese; light, dark and classic themes; connection tree with search.
