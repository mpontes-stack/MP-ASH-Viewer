#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# MP-ASH-Viewer @VERSION@ - start script (Linux / macOS)
# Licensed under the GNU General Public License v3.0
# ----------------------------------------------------------------------------
# Run it from anywhere: it works in its own folder, where the app keeps
#   configuration/  the connections (passwords encrypted)
#   database/       the local history of each connection
#
# Java 11 or later, with graphics: on Ubuntu "openjdk-21-jre", not "...-jre-headless".
#   JAVA_HOME   uses $JAVA_HOME/bin/java when set, otherwise the java of the PATH
#   ASHV_XMX    maximum memory of the app (default 256m), e.g.  ASHV_XMX=1g ./exec_ashv.sh
#   Theme at the first start:  ./exec_ashv.sh -Dashv.theme=dark
#   (later the theme and language chosen in the connection dialog win)
#
# Run in the background, without a terminal:
#   nohup ./exec_ashv.sh >/dev/null 2>&1 &
# ----------------------------------------------------------------------------

APP_JAR=mp_ash_viewer_v@VERSION@.jar

cd "$(dirname "$0")" || exit 1

JAVA_EXE=java
[ -n "$JAVA_HOME" ] && JAVA_EXE="$JAVA_HOME/bin/java"
if ! "$JAVA_EXE" -version >/dev/null 2>&1; then
    echo "Java not found: install Java 11 or later (Ubuntu: sudo apt install openjdk-21-jre) or set JAVA_HOME"
    exit 1
fi

# the app jar finds bc-fips (encryption of the passwords) only in its own folder
if [ ! -f bc-fips-1.0.2.4.jar ]; then
    echo "bc-fips-1.0.2.4.jar is missing in $(pwd): unzip the whole package"
    exit 1
fi

# A connection stays marked as running when the app was closed abruptly, and "Connect" stays disabled for it
if [ -d configuration ]; then
    for f in configuration/*; do
        [ -f "$f" ] && grep -q "running: true" "$f" && sed -i.bak "s/running: true/running: false/" "$f" && rm -f "$f.bak"
    done
fi

exec "$JAVA_EXE" -Xmx"${ASHV_XMX:-256m}" "$@" -jar "$APP_JAR"
