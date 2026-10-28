# Changelog

Changes in each version of MP-ASH-Viewer. Downloads: [Releases](https://github.com/mpontes-stack/MP-ASH-Viewer/releases).

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
