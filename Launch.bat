@echo off
title PowerLaunch Launcher
cd /d "%~dp0"
echo Starting PowerLaunch...
call gradlew.bat run
pause
