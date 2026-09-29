@echo off
setlocal

for %%I in ("%~dp0..") do set "CONDUIT_ROOT=%%~fI"
set "CONDUIT_JAVA_TEMP=%CONDUIT_ROOT%\target\java-temp"
if not exist "%CONDUIT_JAVA_TEMP%" mkdir "%CONDUIT_JAVA_TEMP%"

rem Some Windows launch environments provide TEMP/TMP through an 8.3 path.
rem Java 21's Windows selector pipe can fail to connect through that path.
set "TEMP=%CONDUIT_JAVA_TEMP%"
set "TMP=%CONDUIT_JAVA_TEMP%"

pushd "%CONDUIT_ROOT%"
where mvn.cmd >nul 2>nul
if errorlevel 1 (
    call "%CONDUIT_ROOT%\mvnw.cmd" %*
) else (
    call mvn %*
)
set "CONDUIT_MAVEN_EXIT=%ERRORLEVEL%"
popd
exit /b %CONDUIT_MAVEN_EXIT%
