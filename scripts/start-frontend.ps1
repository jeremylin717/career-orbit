$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { throw 'npm was not found. Install Node.js 20+.' }
Set-Location (Join-Path $ProjectRoot 'frontend')
if (-not (Test-Path -LiteralPath 'node_modules')) { npm install; if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' } }
npm run dev
