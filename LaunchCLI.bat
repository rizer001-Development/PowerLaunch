@echo off
title PowerLaunch CLI
cd /d "%~dp0"

echo ============================================
echo   PowerLaunch CLI Launcher
echo ============================================
echo.

REM ---- Find Java ----
set "JAVA_EXE="

REM 1) Check JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        goto :build
    )
)

REM 2) Check PATH
where java >nul 2>&1
if %ERRORLEVEL%==0 (
    for /f "delims=" %%i in ('where java') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%i"
    )
    goto :build
)

REM 3) Check common locations
for %%j in (
    "C:\Program Files\Eclipse Adoptium\jdk-26*\bin\java.exe"
    "C:\Program Files\Eclipse Adoptium\jdk-25*\bin\java.exe"
    "C:\Program Files\Eclipse Adoptium\jdk-21*\bin\java.exe"
) do (
    for %%f in (%%j) do (
        if not defined JAVA_EXE set "JAVA_EXE=%%f"
    )
)

if not defined JAVA_EXE (
    echo ERROR: Java not found! Install Java 21+ and set JAVA_HOME.
    pause
    exit /b 1
)

:build
echo Using Java: %JAVA_EXE%
echo.

echo [1/2] Building CLI JAR...
call gradlew.bat cliJar --no-daemon -q 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Build failed! Check gradlew output above.
    pause
    exit /b 1
)

REM Find the built CLI jar
set "CLI_JAR="
for %%f in (build\libs\PowerLaunch-*-cli.jar) do set "CLI_JAR=%%f"

if not defined CLI_JAR (
    echo ERROR: CLI JAR not found in build\libs\
    pause
    exit /b 1
)

echo Built: %CLI_JAR%
echo.
echo [2/2] Launching PowerLaunch CLI...
echo.
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -jar "%CLI_JAR%" %*
