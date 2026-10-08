<#
  Stop all Career Orbit processes started for local development.
  Double-click 停止全部.bat at the project root, or run:
    powershell -ExecutionPolicy Bypass -File .\scripts\stop-all.ps1

  Kills whatever is LISTENING on these ports: backend 8080, frontend 5173,
  Elasticsearch 9200, MinIO 9000/9001. System services (MySQL 3306, Redis 6379)
  are intentionally left running.
#>
$ports = @(
  @{ Port = 8080; Name = 'Backend' },
  @{ Port = 5173; Name = 'Frontend' },
  @{ Port = 9200; Name = 'Elasticsearch' },
  @{ Port = 9000; Name = 'MinIO API' },
  @{ Port = 9001; Name = 'MinIO console' }
)
foreach ($entry in $ports) {
  $conns = Get-NetTCPConnection -LocalPort $entry.Port -State Listen -ErrorAction SilentlyContinue
  if ($conns) {
    foreach ($c in $conns) {
      try {
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction Stop
        Write-Host "Stopped $($entry.Name) (PID $($c.OwningProcess), port $($entry.Port))" -ForegroundColor Yellow
      } catch {
        Write-Host "Could not stop $($entry.Name) on port $($entry.Port)" -ForegroundColor Red
      }
    }
  } else {
    Write-Host "$($entry.Name) not running (port $($entry.Port))"
  }
}
Write-Host ''
Write-Host 'Done. MySQL and Redis are left running by design.' -ForegroundColor Green
