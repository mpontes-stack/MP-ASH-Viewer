#!/usr/bin/env bash
# Builds the installation package of a version: MP-ASH-Viewer-v<version>.zip with
#   mp_ash_viewer_v<version>.jar, bc-fips-1.0.2.4.jar, exec_ashv.sh / exec_ashv.bat, README.txt (installation),
#   ashv-grants.sql, LICENSE and lib/ for the JDBC driver (not included: Oracle license).
# The version comes from AppLogo.VERSION.
#
#   tools/dist/make-dist.sh             build the zip (ashv/target/dist/)
#   tools/dist/make-dist.sh --release   also publish it on the GitHub Releases page of the fork (remote "pessoal"),
#                                       under the tag v<version>, which must already be pushed; release notes from
#                                       tools/dist/notes/v<version>.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
VERSION=$(grep -oP 'VERSION = "\K[^"]+' ashv/src/main/java/gui/custom/AppLogo.java)
NAME="MP-ASH-Viewer-v$VERSION"
JAR="mp_ash_viewer_v$VERSION.jar"
OUT="ashv/target/dist"
BCFIPS="$HOME/.m2/repository/org/bouncycastle/bc-fips/1.0.2.4/bc-fips-1.0.2.4.jar"
FILES="tools/dist/files"

mvn -q -o package -DskipTests
[ -f "$BCFIPS" ] || { echo "bc-fips not found in the Maven repository: $BCFIPS"; exit 1; }

rm -rf "${OUT:?}/$NAME" "$OUT/$NAME.zip"
mkdir -p "$OUT/$NAME/lib"
cp ashv/target/ashv-4.0-SNAPSHOT-jar-with-dependencies.jar "$OUT/$NAME/$JAR"
cp "$BCFIPS" "$OUT/$NAME/"
cp docs/ashv-grants.sql LICENSE "$OUT/$NAME/"
cp "$FILES/lib/README.txt" "$OUT/$NAME/lib/"
# the scripts and the README name the jar of this version
for f in exec_ashv.sh exec_ashv.bat README.txt; do
    sed "s/@VERSION@/$VERSION/g" "$FILES/$f" > "$OUT/$NAME/$f"
done
sed -i 's/$/\r/' "$OUT/$NAME/exec_ashv.bat"      # Windows line endings
chmod +x "$OUT/$NAME/exec_ashv.sh"
(cd "$OUT" && zip -qr "$NAME.zip" "$NAME")
ZIP="$ROOT/$OUT/$NAME.zip"
echo "$ZIP ($(du -h "$ZIP" | cut -f1))"

if [ "${1:-}" = "--release" ]; then
    TAG="v$VERSION"
    NOTES="tools/dist/notes/$TAG.md"
    git ls-remote --exit-code --tags pessoal "$TAG" >/dev/null || { echo "tag $TAG is not on the remote pessoal"; exit 1; }
    [ -f "$NOTES" ] || { echo "release notes missing: $NOTES"; exit 1; }
    REPO=$(git remote get-url pessoal | sed -E 's#.*github.com[:/]##; s#\.git$##')
    gh release create "$TAG" "$ZIP" -R "$REPO" --title "MP-ASH-Viewer $VERSION" --notes-file "$NOTES"
fi
