param(
    [string]$DaemonJar = '../lh/lh-localEV/localcsms/daemon/ocpp16-daemon/target/ocpp16-daemon.jar',
    [string]$Config = 'config/local-daemon.properties',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Test-Path -LiteralPath $DaemonJar -PathType Leaf)) { throw 'Build the LocalCSMS ocpp16-daemon JAR first. See README.md.' }
$daemonJarPath = (Resolve-Path -LiteralPath $DaemonJar).Path
$daemonConfigPath = (Resolve-Path -LiteralPath $Config).Path
$daemonConfigUri = ([System.Uri]$daemonConfigPath).AbsoluteUri
$javaArguments = @(
    "-Dspring.config.additional-location=$daemonConfigUri",
    '-Dserver.address=127.0.0.1',
    '-Ddaemon.health-check-enabled=false',
    '-jar', $daemonJarPath,
    '1', '127.0.0.1', '8900'
)
if ($DryRun) {
    [pscustomobject]@{ WorkingDirectory = $PSScriptRoot; Arguments = $javaArguments }
    return
}
& java @javaArguments
exit $LASTEXITCODE
