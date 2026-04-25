@echo off
REM ============================================================
REM  Health-ID Vault — One-Click Run
REM  Uses: tools\apache-maven-3.9.6\bin\mvn.cmd (already downloaded)
REM ============================================================
setlocal

set "ROOT=%~dp0"
set "MVN=%ROOT%tools\apache-maven-3.9.6\bin\mvn.cmd"

if not exist "%MVN%" (
    echo Maven not found at %MVN%
    echo Please run the project folder setup or re-download Maven.
    pause
    exit /b 1
)

echo.
echo  Starting Health-ID Vault...
echo.

call "%MVN%" -f "%ROOT%pom.xml" javafx:run

pause
