param(
    [Parameter(Mandatory = $true)][ValidateSet('Start', 'Restart', 'Stop')][string]$Action,
    [ValidateRange(0, 65535)][int]$UiPort = 0
)
$ErrorActionPreference = 'Stop'
$runtimeRoot = $PSScriptRoot
$projectRoot = Split-Path $runtimeRoot -Parent
$settings = Import-PowerShellDataFile -LiteralPath (Join-Path $runtimeRoot 'config/environment.psd1')
if (-not $UiPort) { $UiPort = [int]$settings.UiPort }
if ($UiPort -lt 1024 -or $UiPort -gt 65535) {
    throw 'UI port must be between 1024 and 65535.'
}
$stateDirectory = Join-Path $runtimeRoot 'state'
$stateFile = Join-Path $stateDirectory 'processes.json'
New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
$records = [System.Collections.Generic.List[object]]::new()

function Save-State {
    $json = @{ RuntimeRoot = $runtimeRoot; Processes = @($script:records.ToArray()) } | ConvertTo-Json -Depth 5
    $temporary = Join-Path $stateDirectory 'processes.tmp'
    [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $stateFile -Force
}
function Owned-Process($record) {
    $process = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$record.Pid)
    if (-not $process) { return $null }
    $expectedJar = Join-Path $runtimeRoot $(if ($record.Name -eq 'ui') { 'bin/v-daemon.jar' } else { throw 'Unknown process record; no process was stopped.' })
    if ($record.Jar -ne $expectedJar -or $process.Name -ne 'java.exe' -or
        $process.CreationDate.ToUniversalTime().Ticks.ToString() -ne $record.CreatedTicks -or
        -not $process.CommandLine -or $process.CommandLine.IndexOf(('"' + $expectedJar + '"'), [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw ('Process ownership mismatch; no unrelated process will be stopped. PID=' + $record.Pid)
    }
    return $process
}
function Port-Owners([int]$port) {
    @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq $port } | Select-Object -ExpandProperty OwningProcess -Unique)
}
function Assert-PortAvailable([int]$port, [int[]]$allowed = @()) {
    $foreign = @(Port-Owners $port | Where-Object { $_ -notin $allowed })
    if ($foreign.Count) { throw ("Port $port is already in use by PID " + ($foreign -join ',') + '. No existing process was stopped.') }
}
function Ready($record) {
    if (-not (Owned-Process $record)) { throw ($record.Name + ' exited. Check runtime/logs.') }
    $bindings = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq [int]$record.Pid })
    if (@($bindings | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') -or $_.LocalPort -ne [int]$record.Port }).Count) {
        throw ($record.Name + ' opened an unexpected listener. Startup aborted.')
    }
    if (-not $bindings.Count) { return $false }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:" + $record.Port + '/api/chargers') -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch { return $false }
}
function Wait-Ready($record) {
    $deadline = [DateTime]::UtcNow.AddSeconds([int]$settings.StartupTimeoutSeconds)
    do { if (Ready $record) { return }; Start-Sleep -Milliseconds 300 } while ([DateTime]::UtcNow -lt $deadline)
    throw ($record.Name + ' did not become ready. Check runtime/logs.')
}
function Stop-Managed {
    # Validate every live record before stopping any process, including PID reuse checks.
    foreach ($record in $script:records) { $null = Owned-Process $record }
    foreach ($record in @($script:records.ToArray() | Sort-Object @{Expression = { if ($_.Name -eq 'ui') { 0 } else { 1 } }})) {
        if (Owned-Process $record) {
            Stop-Process -Id ([int]$record.Pid) -ErrorAction Stop
            $deadline = [DateTime]::UtcNow.AddSeconds(10)
            while ((Owned-Process $record) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
            if (Owned-Process $record) { throw ('Process did not stop: ' + $record.Pid) }
        }
        $null = $script:records.Remove($record); Save-State
        Write-Host ('Stopped ' + $record.Name + ' PID=' + $record.Pid)
    }
}
function Launch([string]$name, [int]$port, [string]$java, [string[]]$javaArguments) {
    $jar = Join-Path $runtimeRoot 'bin/v-daemon.jar'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $process = Start-Process -FilePath $java -ArgumentList $javaArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot "logs/$name-$stamp.out.log") -RedirectStandardError (Join-Path $runtimeRoot "logs/$name-$stamp.err.log")
    $identity = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $process.Id)
    if (-not $identity) { throw ($name + ' exited immediately. Check runtime/logs.') }
    $record = [pscustomobject]@{ Name = $name; Pid = $process.Id; CreatedTicks = $identity.CreationDate.ToUniversalTime().Ticks.ToString(); Jar = $jar; Port = $port }
    $script:records.Add($record); Save-State
    Wait-Ready $record
    Write-Host ('Started ' + $name + ' PID=' + $process.Id + ' port=' + $port)
}
$lock = $null
try {
    $lock = [IO.File]::Open((Join-Path $stateDirectory 'control.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if (Test-Path -LiteralPath $stateFile) {
        $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        if ($state.RuntimeRoot -ne $runtimeRoot) { throw 'Runtime state belongs to another directory.' }
        foreach ($record in @($state.Processes)) { if ($null -ne $record) { $records.Add($record) } }
    }
    if ($Action -eq 'Stop') { Stop-Managed; Write-Host 'v_daemon stopped. LH_EV was left running.'; exit 0 }
    foreach ($record in $records) { $null = Owned-Process $record }
    $ownedIds = @($records.ToArray() | ForEach-Object { if (Owned-Process $_) { [int]$_.Pid } })
    Assert-PortAvailable $UiPort $ownedIds
    $java = (Get-Command ([string]$settings.JavaExe) -ErrorAction Stop).Source
    foreach ($file in @('bin/v-daemon.jar', '../config/local.properties')) {
        if (-not (Test-Path -LiteralPath (Join-Path $runtimeRoot $file) -PathType Leaf)) { throw ('Missing ' + $file + '. Run prepare.ps1 first.') }
    }
    if ($Action -eq 'Restart') { Stop-Managed }
    elseif ($ownedIds.Count) {
        if ($records.Count -eq 1 -and @($records.ToArray() | Where-Object { -not (Ready $_) }).Count -eq 0 -and
            @($records.ToArray() | Where-Object { ($_.Name -eq 'ui' -and $_.Port -ne $UiPort) }).Count -eq 0) {
            Write-Host 'Runtime is already running.'; exit 0
        }
        throw 'Runtime is partially running or uses different ports. Use restart.bat.'
    }
    else { $records.Clear(); Save-State }
    New-Item -ItemType Directory -Path (Join-Path $runtimeRoot 'logs') -Force | Out-Null
    $savedUiPort = $env:VDAEMON_SERVER_PORT; $savedLogs = $env:VDAEMON_LOGS_DIRECTORY
    try {
        Assert-PortAvailable $UiPort
        $env:VDAEMON_SERVER_PORT = "$UiPort"; $env:VDAEMON_LOGS_DIRECTORY = (Join-Path $runtimeRoot 'logs/events').Replace('\', '/')
        Launch 'ui' $UiPort $java @('-jar', ('"' + (Join-Path $runtimeRoot 'bin/v-daemon.jar') + '"'), ('"' + (Join-Path $runtimeRoot '../config/local.properties') + '"'))
    } catch {
        $failure = $_
        Stop-Managed
        throw $failure
    } finally { $env:VDAEMON_SERVER_PORT = $savedUiPort; $env:VDAEMON_LOGS_DIRECTORY = $savedLogs }
    Write-Host ("UI: http://127.0.0.1:$UiPort")
    Write-Host 'CSMS: D:\LH_EV (start its start-all.bat separately).'
} catch { Write-Error $_ -ErrorAction Continue; exit 1 }
finally { if ($lock) { $lock.Dispose() } }
