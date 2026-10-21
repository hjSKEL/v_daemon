param(
    [string]$JavaHome = '',
    [string]$MavenRepository = 'C:\AntigravityProject\repository'
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $JavaHome) { $JavaHome = Split-Path (Split-Path (Get-Command java -ErrorAction Stop).Source -Parent) -Parent }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw 'Specify a JDK 17+ installation using -JavaHome.' }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
& mvn -B package "-Dmaven.repo.local=$MavenRepository"
exit $LASTEXITCODE
