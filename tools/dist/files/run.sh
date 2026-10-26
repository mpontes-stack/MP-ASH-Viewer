#!/usr/bin/env bash
# MP-ASH-Viewer start script (Linux / macOS)
#   Java 11 or later: uses $JAVA_HOME when set, otherwise the java of the PATH
#   Memory: ASHV_XMX (default 256m), e.g.  ASHV_XMX=1g ./run.sh
#   Theme at the first start: ./run.sh -Dashv.theme=dark   (later the choice of the connection dialog wins)
# Licensed under the GNU General Public License v3.0

cd "$(dirname "$0")" || exit 1     # configuration/ and database/ are kept next to the jar

JAVA_EXE=java
[ -n "$JAVA_HOME" ] && JAVA_EXE="$JAVA_HOME/bin/java"
if ! "$JAVA_EXE" -version >/dev/null 2>&1; then
    echo "Java not found: install Java 11 or later, or set JAVA_HOME"
    exit 1
fi

# A connection stays marked as running when the app was closed abruptly, and "Connect" stays disabled for it
if [ -d configuration ]; then
    for f in configuration/*; do
        [ -f "$f" ] && grep -q "running: true" "$f" && sed -i.bak "s/running: true/running: false/" "$f" && rm -f "$f.bak"
    done
fi

exec "$JAVA_EXE" -Xmx"${ASHV_XMX:-256m}" "$@" -jar MP-ASH-Viewer.jar
