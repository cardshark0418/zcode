@echo off
setlocal EnableExtensions
rem zcode launcher — run from repo: bin\zcode.cmd  [serve]

set "PROJECT_ROOT=%~dp0.."
for %%I in ("%PROJECT_ROOT%") do set "PROJECT_ROOT=%%~fI"
set "ZCODE_HOME=%PROJECT_ROOT%\.zcode"
set "JAR=%ZCODE_HOME%\zcode.jar"

if not exist "%JAR%" (
  echo [zcode] jar not found: %JAR%
  echo [zcode] run: powershell -File "%~dp0install.ps1"
  exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
  echo [zcode] java not found on PATH
  exit /b 1
)

rem Always treat project root as cwd/workspace so data stays under .zcode\
cd /d "%PROJECT_ROOT%"

rem Force UTF-8 console so Chinese input/output is not corrupted (Java 18+ defaults UTF-8).
chcp 65001 >nul
set JAVA_TOOL_OPTIONS=
if defined ZCODE_CLI_CHAR_DELAY_MS (
  set "ZCODE_CHAR_DELAY_OPT=-Dzcode.cli.char-delay-ms=%ZCODE_CLI_CHAR_DELAY_MS%"
) else (
  set "ZCODE_CHAR_DELAY_OPT="
)
java -Dfile.encoding=UTF-8 -Dstdin.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dconsole.encoding=UTF-8 -Dzcode.home="%ZCODE_HOME%" -Dzcode.workspace="%PROJECT_ROOT%" %ZCODE_CHAR_DELAY_OPT% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
