param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot '.env'
if (Test-Path -LiteralPath $EnvFile) {
  Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process') }
  }
}
if (-not $env:DB_USER) { $env:DB_USER = 'career' }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = 'career_dev_password' }
if (-not $env:DB_URL) { $env:DB_URL = 'jdbc:mysql://localhost:3306/career_orbit?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' }
$dbMatch = [regex]::Match($env:DB_URL, 'jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<db>[^?;]+)')
if (-not $dbMatch.Success) { throw 'DB_URL must use jdbc:mysql://host:port/database format.' }
$dbHost = $dbMatch.Groups['host'].Value
$dbPort = if ($dbMatch.Groups['port'].Success) { [int]$dbMatch.Groups['port'].Value } else { 3306 }
$dbName = $dbMatch.Groups['db'].Value
if (-not $SkipChecks) {
  $port = Test-NetConnection -ComputerName $dbHost -Port $dbPort -WarningAction SilentlyContinue
  if (-not $port.TcpTestSucceeded) { throw "MySQL $dbHost`:$dbPort is unreachable. Start MySQL or run docker compose up -d." }
  $mysql = Get-Command mysql -ErrorAction SilentlyContinue
  if ($mysql) {
    $oldPassword = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:DB_PASSWORD
    try { & $mysql.Source -h $dbHost -P $dbPort -u $env:DB_USER -e 'SELECT 1' $dbName | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'MySQL credentials or database are invalid.' } }
    finally { $env:MYSQL_PWD = $oldPassword }
  }
}
$wrapper = Join-Path $ProjectRoot 'backend\mvnw.cmd'
$maven = Get-Command mvn -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $wrapper) { $mvn = $wrapper }
elseif ($maven) { $mvn = $maven.Source }
else { throw 'Maven was not found and the project Maven Wrapper is missing.' }
Set-Location (Join-Path $ProjectRoot 'backend')
& $mvn 'spring-boot:run' '-Dspring-boot.run.profiles=dev'
