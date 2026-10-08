@echo off
chcp 65001 >nul
echo Stopping whatever is listening on port 8080 (the old backend)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue; if ($c) { $c.OwningProcess | Sort-Object -Unique | ForEach-Object { try { Stop-Process -Id $_ -Force -ErrorAction Stop; Write-Host ('  stopped PID ' + $_ + ' on port 8080') } catch { Write-Host ('  could not stop PID ' + $_) } } } else { Write-Host '  nothing is listening on 8080' }"
echo.
echo Done. You can now Run the backend again (from IntelliJ or 一键启动.bat).
echo Press any key to close.
pause >nul
