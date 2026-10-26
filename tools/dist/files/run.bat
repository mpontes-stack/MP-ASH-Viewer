@REM MP-ASH-Viewer start script (Windows)
@REM   Java 11 or later: uses JAVA_HOME when set, otherwise the java of the PATH
@REM   Memory: set ASHV_XMX (default 256m), e.g.  set ASHV_XMX=1g
@REM Licensed under the GNU General Public License v3.0
@echo off
cd /d "%~dp0"

set JAVA_EXE=java
if defined JAVA_HOME set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
if not defined ASHV_XMX set ASHV_XMX=256m

@REM A connection stays marked as running when the app was closed abruptly: "Connect" stays disabled for it
if exist configuration powershell -NoProfile -Command "Get-ChildItem configuration -File | ForEach-Object { (Get-Content -LiteralPath $_.FullName) -replace 'running: true','running: false' | Set-Content -LiteralPath $_.FullName }"

%JAVA_EXE% -Xmx%ASHV_XMX% %* -jar MP-ASH-Viewer.jar
