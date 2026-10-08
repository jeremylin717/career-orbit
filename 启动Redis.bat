@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Starting Redis for Career Orbit...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-redis.ps1"
echo.
echo Press any key to close.
pause >nul
