<#
  Move the Career Orbit local infrastructure (Elasticsearch + MinIO + their data)
  from C:\career-orbit-infra to F:\career-orbit-infra, so nothing keeps filling C:.

  Run once, in a PowerShell window at the project root:
    powershell -ExecutionPolicy Bypass -File .\scripts\move-infra-to-f.ps1

  It will:
    1. Stop whatever is listening on 9200/9000/9001 (ES and MinIO) so files unlock
    2. Move the whole folder C:\career-orbit-infra  ->  F:\career-orbit-infra
    3. Leave MySQL and Redis alone (they are system services on another disk anyway)

  After it finishes, start the app with 一键启动.bat as usual (now pointing at F:).
#>
param(
  [string]$Source = 'C:\career-orbit-infra',
  [string]$Dest = 'F:\career-orbit-infra'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Source)) {
  Write-Host "Source not found: $Source  (nothing to move)" -ForegroundColor Yellow
  exit 0
}
if (Test-Path -LiteralPath $Dest) {
  Write-Host "Destination already exists: $Dest" -ForegroundColor Red
  Write-Host "Remove or rename it first, then re-run. Aborting to avoid overwriting data." -ForegroundColor Red
  exit 1
}

# 1. Stop ES + MinIO so their data files are not locked
Write-Host 'Stopping Elasticsearch and MinIO...' -ForegroundColor Cyan
foreach ($port in 9200, 9000, 9001) {
  $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($c in $conns) {
    try {
      Stop-Process -Id $c.OwningProcess -Force -ErrorAction Stop
      Write-Host "  stopped PID $($c.OwningProcess) on port $port"
    } catch {
      Write-Host "  could not stop process on port $port (may need admin)" -ForegroundColor Yellow
    }
  }
}
Start-Sleep -Seconds 3

# 2. Move the folder (copy across volumes, then remove source)
Write-Host "Moving $Source  ->  $Dest ..." -ForegroundColor Cyan
Move-Item -LiteralPath $Source -Destination $Dest

Write-Host 'Verifying...' -ForegroundColor Cyan
$esOk = Test-Path -LiteralPath (Join-Path $Dest 'elasticsearch-8.9.2\bin\elasticsearch.bat')
$minioOk = Test-Path -LiteralPath (Join-Path $Dest 'minio.exe')
Write-Host "  Elasticsearch present: $esOk"
Write-Host "  minio.exe present    : $minioOk"

if ($esOk -and $minioOk) {
  Write-Host ''
  Write-Host 'Done. Infrastructure now lives on F:.' -ForegroundColor Green
  Write-Host 'Start everything again with:  一键启动.bat' -ForegroundColor Green
} else {
  Write-Host ''
  Write-Host 'Move finished but expected files are missing; check the folder manually.' -ForegroundColor Yellow
}
