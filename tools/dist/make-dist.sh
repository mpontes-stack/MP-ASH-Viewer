#!/usr/bin/env bash
# Builds the package to share: MP-ASH-Viewer-<version>.zip with the jar, bc-fips, start scripts and docs.
#   tools/dist/make-dist.sh            (output: ashv/target/dist/MP-ASH-Viewer-<version>.zip)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
VERSION=$(grep -oP 'VERSION = "\K[^"]+' ashv/src/main/java/gui/custom/AppLogo.java)
NAME="MP-ASH-Viewer-$VERSION"
OUT="ashv/target/dist"
BCFIPS="$HOME/.m2/repository/org/bouncycastle/bc-fips/1.0.2.4/bc-fips-1.0.2.4.jar"

mvn -q -o package -DskipTests
[ -f "$BCFIPS" ] || { echo "bc-fips not found in the Maven repository: $BCFIPS"; exit 1; }

rm -rf "$OUT/$NAME" "$OUT/$NAME.zip"
mkdir -p "$OUT/$NAME/lib"
cp ashv/target/ashv-4.0-SNAPSHOT-jar-with-dependencies.jar "$OUT/$NAME/MP-ASH-Viewer.jar"
cp "$BCFIPS" "$OUT/$NAME/"
cp tools/dist/files/run.sh tools/dist/files/run.bat tools/dist/files/README.txt "$OUT/$NAME/"
cp tools/dist/files/lib/README.txt "$OUT/$NAME/lib/"
cp README.md docs/FEATURES.md docs/ashv-grants.sql LICENSE "$OUT/$NAME/"
chmod +x "$OUT/$NAME/run.sh"
# the docs and their images, with the paths of the package
cp -r docs/images "$OUT/$NAME/images"
sed -i 's#docs/images/#images/#g; s#(docs/FEATURES.md#(FEATURES.md#g; s#(docs/ashv-grants.sql)#(ashv-grants.sql)#g' "$OUT/$NAME/README.md"
sed -i 's#(../README.md#(README.md#g' "$OUT/$NAME/FEATURES.md"
(cd "$OUT" && zip -qr "$NAME.zip" "$NAME")
echo "$ROOT/$OUT/$NAME.zip ($(du -h "$OUT/$NAME.zip" | cut -f1))"
