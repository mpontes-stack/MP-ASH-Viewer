# MP-ASH-Viewer

Fork of [akardapolov/ASH-Viewer](https://github.com/akardapolov/ASH-Viewer) (discontinued upstream): a Swing desktop
app that samples active sessions of Oracle / PostgreSQL every second, stores them locally (Berkeley DB) and shows
them live (Top Activity, "Agora" tabs) and as history (History, Comparar). Work happens on branch `melhorias-ui`.

The owner is a DBA, not a Java developer: explain in Portuguese and plain terms, present a plan (and the SQL that
will run against the monitored databases) before big features, and let them test each stage before committing.

## Build and run

```bash
mvn -q -o package -DskipTests        # from the repo root; offline `install` fails (plugin resolution)
# app: ashv/target/ashv-4.0-SNAPSHOT-jar-with-dependencies.jar  (needs bc-fips-1.0.2.4.jar next to it)
```

- Modules: `jfreechart-fse` (patched JFreeChart: mouse-release selection listeners, `IDetailPanel`), `egantt`
  (gantt + `org.rtv.Options` wait class colors), `ashv` (the app).
- Java source level is **11** (no records, no switch expressions, no `var` in lambdas...), even if the JDK is newer.
- There are almost no unit tests (`ashv/src/test`). Changes are checked with headless programs (below) and by the
  owner in the real app.

## Architecture (ashv)

- `Main` → `Themes.apply` → Dagger (`config.dagger.MainComponent`) → `gui.MainWindow`.
- `gui/connect/ConnectToDbArea`: connection dialog (connections tree, theme combo). Connection configs are YAML
  files in `configuration/` (password encrypted); history per connection in `database/<connection name>/`.
- `profile/*`: one `IProfile` per database type. `OracleSE` builds its own ASH from `v$session` every second (most
  connections use it); `OracleEE` reads `v$active_session_history` (on CPU, wait class / event come empty: treat
  as CPU); `Postgres*` reads `pg_stat_activity`.
- `core/processing/GetFromRemoteAndStore`: the collection loop (+ manual `reconnect()`).
- `store/*`: Berkeley DB (`MainData` = one sample: a matrix of int ids; strings and big numbers live in
  dictionaries `ParameterString` / `ParameterDouble`; `ConvertManager` turns ids back into values). History
  aggregates in `store/entity/olap` (15 s and 1 min). SQL texts cached in `ParamStringString` (`core/sqltext`:
  `SqlTexts` is read by every tooltip; `SqlTextCapture` saves the texts of the collected SQL_IDs every 10 min).
- `core/filter/AshFilter` (global filter, AND between columns, OR inside one, `!COL` exclusions) and
  `AshFilter.Matcher` (not thread safe: one per task). `FilteredAshLoader` rebuilds charts from raw samples.
- `gui/MonitorDbPanel`: builds the tabs. `gui/compare` + `core/compare/PeriodComparison`: "Comparar" tab.
  `gui/snapshot/SnapshotPanel`: "Agora" tabs. `gui/admin` + `core/admin`: "Admin" tab (disk usage, purge, keep days). `gui/lock`: lock tree. `gui/util/Themes`: light / dark / classic.
- UI texts through `config.Labels`: `messages.properties` in **English** (the default, also used for any missing
  key) and `messages_pt.properties` in Portuguese (UTF-8). `gui/util/Languages` applies the language chosen in the
  connection dialog (or the system one) at startup: texts, `Locale.setDefault` (number and date formats, date
  pickers). Technical terms stay in English in both (Top Activity, History, ASH Report, Raw data, wait class...).

## Rules that are easy to break

- **Line endings:** some sources are CRLF (e.g. `Options.java`, `RawDataTable.java`, `ConfigurationManager.java`,
  `ConfigProfile.java`), others LF. Keep each file's style: use the Edit tool or Python with `newline=''`, and check
  `git diff --ignore-cr-at-eol --stat` against `git diff --stat` before committing.
- **ASH query columns are append-only** (`OracleSE.sqlTextAsh`): stored samples are read by column position.
  New columns go at the end; `ConfigurationManager.isAppendedOnly` upgrades existing configs and rows stored before
  show the new columns as empty. Never reorder or remove columns.
- **Cost on production:** the queries run every second on production databases. Keep additions cheap (columns of
  `v$session`, scalar lookups by key) and explain the cost before adding anything.
- **Preferences:** the node `ASH-Viewer` holds connection secrets; app settings of this fork go in `MP-ASH-Viewer`.
- **Colors in SwingX tables:** `JXTable` resets renderer colors; use `ColorHighlighter` (see `ComparePanel`).
  Pick light and dark variants (`Themes.isDark()`); charts keep the colors they were created with.
- **Every new text goes to both message files** (English and Portuguese, same `%s` order); no user text in the
  code. Check with `python3 tools/i18n/check_messages.py`. Never decide by comparing a translated message (use a flag).
- **Numbers and dates** use the default locale (the app language): no hardcoded `new Locale("pt", "BR")`. CSV files
  follow the system locale (`Languages.systemLocale()`), as Excel does.
- **Red means "negative" to the owner:** increases are blue, decreases green (Comparar).
- Do not use the owner's real data (`configuration/`, `database/`, `filters.yaml` of their install) in commits,
  and work on copies of it in tests.

## Headless checks

Get app objects without opening the window:

```java
Object comp = config.dagger.DaggerMainComponent.create();
// private javax.inject.Provider fields: configurationManagerProvider, storeManagerProvider, ashFilterProvider,
// filteredAshLoaderProvider, colorManagerProvider  (getDeclaredField + setAccessible + get())
cm.loadCurrentConfiguration(conn); sm.setUpBDBAndDAO(conn); org.rtv.Options.getInstance();
```

Run from a scratch directory holding copies of `configuration/<conn>` and `database/<conn>`:
`java -Djava.awt.headless=true -Djava.util.prefs.userRoot=<scratch>/prefs -cp app.jar:bc-fips-1.0.2.4.jar:<test classes> Test`.
Panels can be rendered to PNG (set size, lay out recursively, `printAll`) to review layout in both themes.
Components that load only when `isShowing()` stay empty headless. A SwingX RolloverProducer exception is noise.

## Test database and test load

A local Oracle 19c EE runs in Docker (container `oracle19c`, PDB `orclpdb1` on localhost:1521, test user
`user_teste`). The app has a connection for it, **"LOCAL - Oracle 19c TESTE"** (profile OracleSE), so the owner
can collect from it while a test load runs, and the history lands in `database/LOCAL - Oracle 19c TESTE`.

`tools/testdb/testdb.sh` creates known activity there (password from `ASHV_TESTDB_PASSWORD` or
`~/.config/mp-ashv/testdb.env`, never in the repo):

```bash
tools/testdb/testdb.sh start                     # start the container, fix a lost network, wait for the PDB
tools/testdb/testdb.sh setup                     # test tables (once): ashv_t 1M rows, invisible index, lock row
tools/testdb/testdb.sh run <scenario> [min] [sessions]   # background load; prints the start time (DB clock)
tools/testdb/testdb.sh status | stop | cleanup
```

Scenarios: `cpu`, `io` (direct path reads), `plan_change` (same SQL_ID, new plan at half time: index made visible),
`long_exec` (executions of several seconds), `many_execs` (short PK lookups), `lock` (row lock holder + waiters),
`mixed`. Every session sets MODULE `ashv_test` and ACTION = scenario, so the app can filter them.
The script refuses to connect unless port 1521 is published by the container: production databases are reached
through SSM tunnels on localhost ports.

## Commits

Commit only when asked. Messages: `feature: ...` / `fix: ...` with a short body in English. Remotes: `origin` is
upstream (no push access), `pessoal` is the fork on GitHub.
