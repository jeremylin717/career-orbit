<#
  Find and start Redis for Career Orbit, covering more install shapes.
  Double-click 启动Redis.bat, or run:
    powershell -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1
#>
$ErrorActionPreference = 'SilentlyContinue'

function Test-6379 {
  try { return (Test-NetConnection -ComputerName localhost -Port 6379 -WarningAction SilentlyContinue).TcpTestSucceeded }
  catch { return $false }
}
function Start-Exe($exe) {
  Write-Host "Starting: $exe"
  $dir = Split-Path $exe
  $conf = Join-Path $dir 'redis.windows.conf'
  if ($exe -match 'redis-server' -and (Test-Path -LiteralPath $conf)) {
    Start-Process -FilePath $exe -ArgumentList @($conf) -WorkingDirectory $dir
  } else {
    Start-Process -FilePath $exe -WorkingDirectory $dir
  }
  Start-Sleep -Seconds 3
  return (Test-6379)
}

if (Test-6379) { Write-Host 'Redis is already running on 6379.' -ForegroundColor Green; exit 0 }
Write-Host 'Redis is not running. Searching...' -ForegroundColor Cyan

# 1) Windows service
$svc = Get-Service | Where-Object { $_.Name -match 'redis|memurai' -or $_.DisplayName -match 'redis|memurai' } | Select-Object -First 1
if ($svc) {
  Write-Host "Service found: $($svc.Name) [$($svc.Status)]"
  if ($svc.Status -ne 'Running') { Start-Service -Name $svc.Name; Start-Sleep -Seconds 3 }
  if (Test-6379) { Write-Host 'Redis started via service.' -ForegroundColor Green; exit 0 }
}

# 2) PATH
foreach ($name in 'redis-server.exe','memurai.exe') {
  $w = Get-Command $name -ErrorAction SilentlyContinue
  if ($w -and (Test-Path $w.Source)) { if (Start-Exe $w.Source) { Write-Host 'Redis started.' -ForegroundColor Green; exit 0 } }
}

# 3) Registry uninstall entries -> install locations
$regRoots = @(
  'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
  'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$locations = @()
foreach ($r in $regRoots) {
  Get-ItemProperty $r -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match 'redis|memurai' } | ForEach-Object {
    Write-Host "Registry says: $($_.DisplayName) -> $($_.InstallLocation)"
    if ($_.InstallLocation) { $locations += $_.InstallLocation }
  }
}

# 4) Filesystem search (bounded depth)
$roots = @(
  'C:\','D:\','E:\','F:\',
  "$env:ProgramFiles","${env:ProgramFiles(x86)}",
  "$env:USERPROFILE","$env:USERPROFILE\Downloads","$env:LOCALAPPDATA","C:\ProgramData"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -Unique
$found = @()
foreach ($root in $roots) {
  Write-Host "  scanning $root ..."
  $found += Get-ChildItem -LiteralPath $root -Recurse -Depth 4 -Include 'redis-server.exe','memurai.exe' -File -ErrorAction SilentlyContinue | Select-Object -First 5 -ExpandProperty FullName
}
if ($locations.Count) {
  foreach ($loc in $locations) {
    $found += Get-ChildItem -LiteralPath $loc -Recurse -Depth 3 -Include 'redis-server.exe','memurai.exe' -File -ErrorAction SilentlyContinue | Select-Object -First 5 -ExpandProperty FullName
  }
}

$exe = $found | Where-Object { $_ } | Select-Object -First 1
if ($exe) { if (Start-Exe $exe) { Write-Host 'Redis started.' -ForegroundColor Green; exit 0 } }

# 5) WSL?
$wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
if ($wsl) {
  Write-Host 'Checking WSL for Redis...'
  $distros = (& wsl.exe -l -q) 2>$null
  if ($distros) {
    Write-Host "WSL distros: $($distros -join ', ')"
    & wsl.exe -e bash -lc "redis-server --daemonize yes" 2>$null
    Start-Sleep -Seconds 3
    if (Test-6379) { Write-Host 'Redis started inside WSL.' -ForegroundColor Green; exit 0 }
  }
}

Write-Host ''
Write-Host 'Still could not find Redis.' -ForegroundColor Red
Write-Host 'Please tell me: how did you start Redis before? (a window you opened, a service, or WSL?)' -ForegroundColor Red
exit 1
