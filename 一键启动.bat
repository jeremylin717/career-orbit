@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================
echo   Career Orbit - one-click start
echo ============================================
echo Starting infrastructure, backend and frontend...
echo A browser window will open automatically when ready.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-all.ps1" %*
echo.
echo ------------------------------------------------------------
echo If you saw errors above, open the .run folder for logs.
echo Press any key to close this window (services keep running).
pause >nul
