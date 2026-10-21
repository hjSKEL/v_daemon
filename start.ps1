param([string]$Config = 'config/local.properties')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath 'target/v-daemon-1.0.0.jar')) { throw 'Run mvn package first. See README.md.' }
& java -jar target/v-daemon-1.0.0.jar $Config
exit $LASTEXITCODE
