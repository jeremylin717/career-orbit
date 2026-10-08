param([switch]$StartInfrastructure)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot
if ($StartInfrastructure) {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker was not found.' }
  docker compose up -d
  if ($LASTEXITCODE -ne 0) { throw 'docker compose failed.' }
}
$RunDir = Join-Path $ProjectRoot '.run'
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
$backend = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-backend.ps1')) -RedirectStandardOutput (Join-Path $RunDir 'backend.log') -RedirectStandardError (Join-Path $RunDir 'backend-error.log')
$frontend = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-frontend.ps1')) -RedirectStandardOutput (Join-Path $RunDir 'frontend.log') -RedirectStandardError (Join-Path $RunDir 'frontend-error.log')
"Backend PID: $($backend.Id)  Frontend PID: $($frontend.Id)"
"Logs: $RunDir"
"Web: http://localhost:5173  API: http://localhost:8080"
