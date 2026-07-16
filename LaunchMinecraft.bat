@echo off
chcp 65001 >nul
title PowerLaunch CLI - Minecraft Debug

echo ============================================
echo   PowerLaunch CLI — Minecraft Launcher Debug
echo ============================================
echo.

:: Default values
set VERSION=1.7.10
set GAMEDIR=C:\.minecraft
set RAM=4096
set USERNAME=
set ALLLOGS=--all-logs

:: Parse arguments
:parse
if "%1"=="" goto run
if /I "%1"=="--version" set VERSION=%2& shift & shift & goto parse
if /I "%1"=="-v" set VERSION=%2& shift & shift & goto parse
if /I "%1"=="--gameDir" set GAMEDIR=%2& shift & shift & goto parse
if /I "%1"=="-g" set GAMEDIR=%2& shift & shift & goto parse
if /I "%1"=="--ram" set RAM=%2& shift & shift & goto parse
if /I "%1"=="-r" set RAM=%2& shift & shift & goto parse
if /I "%1"=="--username" set USERNAME=--username %2& shift & shift & goto parse
if /I "%1"=="-u" set USERNAME=--username %2& shift & shift & goto parse
if /I "%1"=="--errors" set ALLLOGS=& shift & goto parse
if /I "%1"=="-e" set ALLLOGS=& shift & goto parse
shift & goto parse

:run
echo Configuration:
echo   Version:    %VERSION%
echo   Game dir:   %GAMEDIR%
echo   RAM:        %RAM% MB
echo   Username:   %USERNAME:~11%
echo   Full logs:  %ALLLOGS%
echo.

cd /d "%~dp0"

echo [1/2] Building project...
call gradlew.bat compileJava 2>&1 | findstr /V "^$" | findstr /V "UP-TO-DATE"
if %ERRORLEVEL% NEQ 0 (
    echo Build failed! Check errors above.
    pause
    exit /b 1
)

echo [2/2] Launching Minecraft %VERSION%...
echo.
echo ============================================
echo   MINECRAFT OUTPUT (errors/warnings only, use --all-logs for full output)
echo ============================================
echo.

call gradlew.bat run --args="cli --version %VERSION% --gameDir %GAMEDIR% --ram %RAM% %USERNAME% %ALLLOGS%"

set EXITCODE=%ERRORLEVEL%
echo.
if %EXITCODE%==0 (
    echo ============================================
    echo   MINECRAFT EXITED SUCCESSFULLY
    echo ============================================
) else if %EXITCODE%==1 (
    echo ============================================
    echo   MINECRAFT CRASHED! Exit code: %EXITCODE%
    echo ============================================
) else if %EXITCODE%==2 (
    echo ============================================
    echo   TIMEOUT — Minecraft didn't close in 5 min
    echo ============================================
) else (
    echo ============================================
    echo   EXIT CODE: %EXITCODE%
    echo ============================================
)

echo.
pause
