@echo off
chcp 65001 >nul
title PowerLaunch CLI Test

cd /d "%~dp0"

:: First check what versions are available
echo ============================================
echo   Checking Minecraft versions...
echo ============================================
echo.
echo %%APPDATA%%\.minecraft\versions:
dir /b "%APPDATA%\.minecraft\versions" 2>nul
if %ERRORLEVEL% NEQ 0 echo   [NOT FOUND]
echo.
echo C:\.minecraft\versions:
dir /b "C:\.minecraft\versions" 2>nul
if %ERRORLEVEL% NEQ 0 echo   [NOT FOUND]
echo.

:: Build
echo ============================================
echo   Building...
echo ============================================
call gradlew.bat jar 2>&1 | findstr "BUILD ERROR FAILED"
echo.

:: Run CLI mode via gradle (handles JavaFX automatically)
echo ============================================
echo   Launching Minecraft via CLI...
echo ============================================
echo.
call gradlew.bat run --args="cli --version Fabric 26.2 --gameDir \"%APPDATA%\.minecraft\" --ram 4096 --all-logs"

set EXITCODE=%ERRORLEVEL%
echo.
if %EXITCODE%==0 (
    echo ============================================
    echo   SUCCESS — Minecraft exited normally
    echo ============================================
) else (
    echo ============================================
    echo   CRASHED with exit code: %EXITCODE%
    echo ============================================
)
echo.
pause
