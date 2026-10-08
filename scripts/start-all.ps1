<#
  One-click launcher for Career Orbit (no Docker).

  Double-click 一键启动.bat at the project root, or run:
    powershell -ExecutionPolicy Bypass -File .\scripts\start-all.ps1

  What it does:
    1. Loads .env
    2. Starts Elasticsearch + MinIO if they are not already listening
    3. Starts backend (Spring Boot) and frontend (Vite) in hidden windows
    4. Waits until http://localhost:5173 is ready, then opens your browser

  Options:
    -InfraRoot C:\career-orbit-infra   where install-infra.ps1 put ES/MinIO
    -NoBrowser                         do not open the browser
#>
param(
  [string]$InfraRoot = 'F:\career-orbit-infra',
  [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

$RunDir = Join-Path $ProjectRoot '.run'
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

function Test-Port($port) {
  try {
    $c = Test-NetConnection -ComputerName localhost -Port $port -WarningAction SilentlyContinue
    return $c.TcpTestSucceeded
  } catch { return $false }
}

function Wait-Port($port, $seconds, $name) {
  for ($i = 0; $i -lt $seconds; $i++) {
    if (Test-Port $port) { return $true }
    Start-Sleep -Seconds 1
  }
  return (Test-Port $port)
}

# ---------- 1. Load .env ----------
$EnvFile = Join-Path $ProjectRoot '.env'
if (Test-Path -LiteralPath $EnvFile) {
  Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
      [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
  }
  Write-Step ".env loaded"
} else {
  Write-Host 'WARNING: .env not found; AI features will report "not configured".' -ForegroundColor Yellow
}

# ---------- 2. Infrastructure ----------
if (Test-Port 6379) { Write-Step 'Redis already up (6379)' }
else { Write-Host 'WARNING: Redis is not listening on 6379. Start it, or the interview feature will fail.' -ForegroundColor Yellow }

# Elasticsearch
if (Test-Port 9200) {
  Write-Step 'Elasticsearch already up (9200)'
} else {
  $esDir = Get-ChildItem -Path $InfraRoot -Directory -Filter 'elasticsearch-*' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($esDir) {
    Write-Step "Starting Elasticsearch from $($esDir.FullName) ..."
    Start-Process -FilePath (Join-Path $esDir.FullName 'bin\elasticsearch.bat') -WorkingDirectory $esDir.FullName
    if (Wait-Port 9200 120 'Elasticsearch') { Write-Step 'Elasticsearch ready (9200)' }
    else { Write-Host 'WARNING: Elasticsearch did not come up within 120s.' -ForegroundColor Yellow }
  } else {
    Write-Host "WARNING: Elasticsearch not found under $InfraRoot. Run scripts\install-infra.ps1 first." -ForegroundColor Yellow
  }
}

# MinIO
if (Test-Port 9000) {
  Write-Step 'MinIO already up (9000)'
} else {
  $minioExe = Join-Path $InfraRoot 'minio.exe'
  if (Test-Path -LiteralPath $minioExe) {
    $minioData = Join-Path $InfraRoot 'minio-data'
    New-Item -ItemType Directory -Path $minioData -Force | Out-Null
    if (-not $env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER = 'minioadmin' }
    if (-not $env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD = 'minioadmin' }
    Write-Step 'Starting MinIO (9000 / console 9001) ...'
    Start-Process -FilePath $minioExe -ArgumentList @('server', $minioData, '--console-address', ':9001') -WorkingDirectory $InfraRoot
    if (Wait-Port 9000 30 'MinIO') { Write-Step 'MinIO ready (9000)' }
    else { Write-Host 'WARNING: MinIO did not come up within 30s.' -ForegroundColor Yellow }
  } else {
    Write-Host "WARNING: minio.exe not found under $InfraRoot. Run scripts\install-infra.ps1 first." -ForegroundColor Yellow
  }
}

# ---------- 3. Backend ----------
if (Test-Port 8080) {
  Write-Step 'Backend already up (8080)'
} else {
  Write-Step 'Starting backend (Spring Boot) ...'
  $backend = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @(
    '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-backend.ps1'),'-SkipChecks'
  ) -RedirectStandardOutput (Join-Path $RunDir 'backend.log') -RedirectStandardError (Join-Path $RunDir 'backend-error.log')
  if (Wait-Port 8080 180 'Backend') { Write-Step 'Backend ready (8080)' }
  else {
    Write-Host 'ERROR: Backend did not start within 180s.' -ForegroundColor Red
    Write-Host "Check log: $RunDir\backend.log" -ForegroundColor Red
    exit 1
  }
}

# ---------- 4. Frontend ----------
if (Test-Port 5173) {
  Write-Step 'Frontend already up (5173)'
} else {
  Write-Step 'Starting frontend (Vite) ...'
  $frontend = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @(
    '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-frontend.ps1')
  ) -RedirectStandardOutput (Join-Path $RunDir 'frontend.log') -RedirectStandardError (Join-Path $RunDir 'frontend-error.log')
  if (Wait-Port 5173 120 'Frontend') { Write-Step 'Frontend ready (5173)' }
  else {
    Write-Host 'ERROR: Frontend did not start within 120s.' -ForegroundColor Red
    Write-Host "Check log: $RunDir\frontend.log" -ForegroundColor Red
    exit 1
  }
}

# ---------- 5. Done ----------
Write-Host ''
Write-Host 'Career Orbit is running:' -ForegroundColor Green
Write-Host '  Web (open this) : http://localhost:5173'
Write-Host '  API / Swagger   : http://localhost:8080/swagger-ui.html'
Write-Host '  MinIO console   : http://localhost:9001  (minioadmin / minioadmin)'
Write-Host '  Elasticsearch   : http://localhost:9200'
Write-Host '  Admin login     : admin@career.local  (password in .env)'
Write-Host ''
Write-Host "Logs: $RunDir   Stop everything with 停止全部.bat" -ForegroundColor DarkGray

if (-not $NoBrowser) { Start-Process 'http://localhost:5173' }
