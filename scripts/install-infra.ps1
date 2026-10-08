<#
  Install & start the two remaining Career Orbit dependencies on Windows (no Docker):
  Elasticsearch + MinIO.

  Usage (run in PowerShell at the project root, i.e. the folder containing .env):
    powershell -ExecutionPolicy Bypass -File .\scripts\install-infra.ps1

  Options:
    -Root C:\career-orbit-infra   install directory (default)
    -EsVersion 8.19.5             Elasticsearch version
    -SkipStart                    download & configure only, do not start

  What it does:
    1. Downloads minio.exe
    2. Downloads + extracts the Elasticsearch Windows zip
    3. Patches ES config: disables security, single-node, 512m heap
    4. Starts MinIO (9000 API / 9001 console) and Elasticsearch (9200)
    5. Waits until both ports are listening
#>
param(
  [string]$Root = 'F:\career-orbit-infra',
  [string]$EsVersion = '8.9.2',
  [switch]$SkipStart
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-File($urls, $out) {
  foreach ($url in $urls) {
    Write-Host "Downloading $url"
    try {
      if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        & curl.exe -L --fail --retry 2 -o $out $url
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $out) -and (Get-Item $out).Length -gt 0) { return }
        Write-Host "  failed (exit $LASTEXITCODE), trying next mirror..."
      } else {
        Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
        return
      }
    } catch {
      Write-Host "  failed: $($_.Exception.Message), trying next mirror..."
    }
  }
  throw "All download sources failed. Last target: $out"
}

New-Item -ItemType Directory -Path $Root -Force | Out-Null
Write-Host "Install root: $Root"

# ---------- MinIO ----------
# NOTE: dl.min.io/server/... returns 410 since 2026. Try the China mirror first,
# then the legacy/AIStor paths as fallback.
$minioUrls = @(
  'https://dl.minio.org.cn/server/minio/release/windows-amd64/minio.exe',
  'https://dl.min.io/server/minio/release/windows-amd64/minio.exe',
  'https://dl.min.io/aistor/minio/release/windows-amd64/minio.exe'
)
$minioExe = Join-Path $Root 'minio.exe'
if (-not (Test-Path -LiteralPath $minioExe)) {
  Get-File $minioUrls $minioExe
} else {
  Write-Host 'minio.exe already present, skipping download.'
}

# ---------- Elasticsearch ----------
$esZip = Join-Path $Root "elasticsearch-$EsVersion-windows-x86_64.zip"
$esDir = Join-Path $Root "elasticsearch-$EsVersion"
if (-not (Test-Path -LiteralPath $esDir)) {
  if (-not (Test-Path -LiteralPath $esZip)) {
    Get-File @(
      "https://mirrors.huaweicloud.com/elasticsearch/$EsVersion/elasticsearch-$EsVersion-windows-x86_64.zip",
      "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$EsVersion-windows-x86_64.zip"
    ) $esZip
  }
  Write-Host 'Extracting Elasticsearch ...'
  Expand-Archive -LiteralPath $esZip -DestinationPath $Root -Force
}
if (-not (Test-Path -LiteralPath $esDir)) {
  $found = Get-ChildItem -Path $Root -Directory -Filter 'elasticsearch-*' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($found) { $esDir = $found.FullName } else { throw 'Elasticsearch directory not found after extraction.' }
}
Write-Host "Elasticsearch dir: $esDir"

# ---------- Patch ES config ----------
$yml = Join-Path $esDir 'config\elasticsearch.yml'
$content = [System.IO.File]::ReadAllText($yml)
if ($content -match '(?m)^\s*xpack\.security\.enabled\s*:') {
  $content = $content -replace '(?m)^\s*xpack\.security\.enabled\s*:.*$', 'xpack.security.enabled: false'
} else {
  $content += "`r`nxpack.security.enabled: false"
}
if ($content -match '(?m)^\s*discovery\.type\s*:') {
  $content = $content -replace '(?m)^\s*discovery\.type\s*:.*$', 'discovery.type: single-node'
} else {
  $content += "`r`ndiscovery.type: single-node"
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($yml, $content, $utf8NoBom)

$jvm = Join-Path $esDir 'config\jvm.options'
if (Test-Path -LiteralPath $jvm) {
  $j = [System.IO.File]::ReadAllText($jvm)
  $j = $j -replace '(?m)^-Xms\d+[mMgG]', '-Xms512m'
  $j = $j -replace '(?m)^-Xmx\d+[mMgG]', '-Xmx512m'
  [System.IO.File]::WriteAllText($jvm, $j, $utf8NoBom)
}
Write-Host 'Elasticsearch config patched (security off, single-node, 512m heap).'

if ($SkipStart) { Write-Host 'Done (not started).'; exit 0 }

# ---------- Start MinIO ----------
$minioData = Join-Path $Root 'minio-data'
New-Item -ItemType Directory -Path $minioData -Force | Out-Null
$env:MINIO_ROOT_USER = 'minioadmin'
$env:MINIO_ROOT_PASSWORD = 'minioadmin'
Write-Host 'Starting MinIO (9000 API / 9001 console) ...'
Start-Process -FilePath $minioExe -ArgumentList @('server', $minioData, '--console-address', ':9001') -WorkingDirectory $Root

# ---------- Start Elasticsearch ----------
Write-Host 'Starting Elasticsearch (first start may take 30-90s) ...'
Start-Process -FilePath (Join-Path $esDir 'bin\elasticsearch.bat') -WorkingDirectory $esDir

function Wait-Port($port, $seconds, $name) {
  for ($i = 0; $i -lt $seconds; $i++) {
    try {
      $c = Test-NetConnection -ComputerName localhost -Port $port -WarningAction SilentlyContinue
      if ($c.TcpTestSucceeded) { Write-Host "$name is ready (:$port)"; return $true }
    } catch { }
    Start-Sleep -Seconds 1
  }
  Write-Host "$name NOT ready after $seconds s (port $port not listening)."
  return $false
}
Wait-Port 9000 30 'MinIO' | Out-Null
$esOk = Wait-Port 9200 90 'Elasticsearch'

Write-Host ''
Write-Host 'Finished. Endpoints:'
Write-Host '  MinIO console : http://localhost:9001  (minioadmin / minioadmin)'
Write-Host '  Elasticsearch : http://localhost:9200'
if (-not $esOk) { Write-Host 'NOTE: Elasticsearch did not come up in time. Open its window/log to check the error.' }
