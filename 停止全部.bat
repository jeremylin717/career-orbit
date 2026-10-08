@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Stopping Career Orbit (backend, frontend, ES, MinIO)...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-all.ps1"
echo.
echo Press any key to close.
pause >nul
