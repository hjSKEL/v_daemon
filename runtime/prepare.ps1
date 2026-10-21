$ErrorActionPreference = 'Stop'
$runtimeRoot = $PSScriptRoot
$destinations = @(
    @{ Source = '../target/v-daemon-1.0.0.jar'; Name = 'v-daemon.jar' }
)
foreach ($item in $destinations) {
    $item.Source = (Resolve-Path -LiteralPath (Join-Path $runtimeRoot $item.Source)).Path
}
$binPath = Join-Path $runtimeRoot 'bin'
$running = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.IndexOf($binPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
})
if ($running.Count) { throw 'This runtime is running. Run stop.bat before updating its JARs.' }
New-Item -ItemType Directory -Path $binPath -Force | Out-Null
foreach ($item in $destinations) {
    $destination = Join-Path $binPath $item.Name
    Copy-Item -LiteralPath $item.Source -Destination $destination -Force
    if ((Get-FileHash -LiteralPath $item.Source).Hash -ne (Get-FileHash -LiteralPath $destination).Hash) {
        throw ('JAR copy verification failed: ' + $item.Name)
    }
    Write-Host ('Prepared ' + $item.Name)
}
Write-Host 'v_daemon prepared. LH_EV and local configuration were not changed.'
