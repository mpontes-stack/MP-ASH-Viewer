@REM ----------------------------------------------------------------------------
@REM MP-ASH-Viewer @VERSION@ - start script (Windows)
@REM Licensed under the GNU General Public License v3.0
@REM ----------------------------------------------------------------------------
@REM Double click it or run it from anywhere: it works in its own folder, where the app keeps
@REM   configuration\  the connections (passwords encrypted)
@REM   database\       the local history of each connection
@REM
@REM Java 11 or later.
@REM   JAVA_HOME   uses %JAVA_HOME%\bin\java.exe when set, otherwise the java of the PATH
@REM   ASHV_XMX    maximum memory of the app (default 256m), e.g.  set ASHV_XMX=1g
@REM   Theme at the first start:  exec_ashv.bat -Dashv.theme=dark
@REM   (later the theme and language chosen in the connection dialog win)
@REM ----------------------------------------------------------------------------
@echo off
setlocal
cd /d "%~dp0"
set APP_JAR=mp_ash_viewer_v@VERSION@.jar

set JAVA_EXE=java
if defined JAVA_HOME set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
%JAVA_EXE% -version >nul 2>&1
if errorlevel 1 (
    echo Java not found: install Java 11 or later or set JAVA_HOME
    pause
    exit /b 1
)
if not defined ASHV_XMX set ASHV_XMX=256m

@REM the app jar finds bc-fips (encryption of the passwords) only in its own folder
if not exist bc-fips-1.0.2.4.jar (
    echo bc-fips-1.0.2.4.jar is missing in %CD%: unzip the whole package
    pause
    exit /b 1
)

@REM A connection stays marked as running when the app was closed abruptly: "Connect" stays disabled for it
if exist configuration powershell -NoProfile -Command "Get-ChildItem configuration -File | ForEach-Object { (Get-Content -LiteralPath $_.FullName) -replace 'running: true','running: false' | Set-Content -LiteralPath $_.FullName }"

%JAVA_EXE% -Xmx%ASHV_XMX% %* -jar %APP_JAR%
