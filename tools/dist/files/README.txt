MP-ASH-Viewer
=============

Active Session History for Oracle (Standard and Enterprise Edition, no Diagnostics Pack needed with the OracleSE
profile): samples the active sessions every second, keeps a local history, compares periods.

Start
  1. Java 11 or later.
  2. Put the JDBC driver in lib/ (Oracle: ojdbc8.jar or later, from oracle.com; PostgreSQL: postgresql-*.jar).
  3. Linux / macOS: ./run.sh          Windows: run.bat
  4. Connection dialog: name, URL (jdbc:oracle:thin:@host:port/service), user, password, the driver jar from lib/,
     profile OracleSE. Connect.
  The local history is kept in database/ and the connections in configuration/, in this folder.

Database user
  GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO <user>;
  or, view by view: ashv-grants.sql
  The OracleEE profile and the ASH Report tab need the Oracle Diagnostics Pack license.

More: README.md and FEATURES.md in this folder.
Contact: Marco Pontes - marco.pontes.app@gmail.com
