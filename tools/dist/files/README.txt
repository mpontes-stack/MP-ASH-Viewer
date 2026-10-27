MP-ASH-Viewer @VERSION@ - installation
======================================

Active Session History for Oracle (Standard and Enterprise Edition): samples the active sessions every second,
keeps a local history and compares periods. No Diagnostics Pack needed with the OracleSE profile.

1. Java 11 or later, with graphics
   Ubuntu:   sudo apt install openjdk-21-jre      (not "openjdk-21-jre-headless": it cannot open windows)
   Windows:  any JDK / JRE 11+ (e.g. Eclipse Temurin)
   Check:    java -version

2. Unzip this package in a folder of your own, e.g. ~/ashv or C:\ashv
   Keep the files together: mp_ash_viewer_v@VERSION@.jar needs bc-fips-1.0.2.4.jar next to it.

3. JDBC driver (not included: Oracle license)
   Download ojdbc8.jar or later from
   https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html  and put it in lib/

4. Start
   Linux / macOS:  ./exec_ashv.sh        (chmod +x exec_ashv.sh if needed)
   Windows:        exec_ashv.bat
   Memory and other options: see the comments at the top of the script.

5. In the connection dialog: New, then
   URL       jdbc:oracle:thin:@host:port/service
   User / password of the monitoring user (below)
   JAR       the driver in lib/ ("Open JAR")
   Profile   OracleSE
   Save, Connect. Language and theme are chosen in the same dialog.

The app keeps in this folder:  configuration/ (connections, passwords encrypted) and database/ (history).
To upgrade, unzip the new version and copy these two folders into it.

Monitoring user (read only)
   GRANT CREATE SESSION, SELECT_CATALOG_ROLE TO <user>;
   or only the views the app reads: ashv-grants.sql
   The OracleEE profile and the ASH Report tab need the Oracle Diagnostics Pack license.

Common problems
   HeadlessException / "No X11 DISPLAY"       Java without graphics: install openjdk-21-jre (step 1)
   NoClassDefFoundError ...BouncyCastleFips   bc-fips-1.0.2.4.jar is not next to the app jar (step 2)
   "Connect" disabled for a connection        the app was closed abruptly: start it with exec_ashv.sh / .bat

Documentation: https://github.com/mpontes-stack/MP-ASH-Viewer
Contact: Marco Pontes - marco.pontes.app@gmail.com
