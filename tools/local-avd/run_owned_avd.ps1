#Requires -Version 7.3
param(
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$AvdHome,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_.-]+$')][string]$AvdName,
    [Parameter(Mandatory)][ValidateRange(5554,5680)][int]$Port,
    [Parameter(Mandatory)][ValidateSet('AtdSnapshot','AospGpu')][string]$Mode,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][scriptblock]$Run,
    [ValidateRange(1,8)][int]$Cores = 2,
    [ValidateRange(2048,8192)][int]$MemoryMiB = 4096
)
$ErrorActionPreference = 'Stop'
if ($Port % 2) { throw 'Use an even reserved console port.' }
. (Join-Path $PSScriptRoot 'session_identity.ps1')
$adb = (Resolve-Path -LiteralPath (Join-Path $SdkRoot 'platform-tools/adb.exe')).Path
$emulator = (Resolve-Path -LiteralPath (Join-Path $SdkRoot 'emulator/emulator.exe')).Path
$avdHomePath = (Resolve-Path -LiteralPath $AvdHome).Path
$null = Get-Item -LiteralPath (Join-Path $avdHomePath "$AvdName.ini")
$serial = "emulator-$Port"
function AdbDevice {
    $lines = @(& $adb devices -l)
    if ($LASTEXITCODE) { throw 'adb devices failed.' }
    Get-AvdDevice $lines $serial
}
function New-ExclusiveListener($address, [int]$requestedPort) {
    $listener = [Net.Sockets.TcpListener]::new($address, $requestedPort)
    try {
        $listener.Server.ExclusiveAddressUse = $true
        if ($address -eq [Net.IPAddress]::IPv6Any) { $listener.Server.DualMode = $true }
        $listener.Start()
        $listener
    } catch { $listener.Stop(); throw }
}
function Assert-PortPairFree {
    $checks = @()
    try {
        foreach ($n in $Port, ($Port+1)) { $checks += New-ExclusiveListener ([Net.IPAddress]::IPv6Any) $n }
    } finally { foreach ($s in $checks) { $s.Stop() } }
}
function Confirm-ReportedPort($listener) {
    $pending = $listener.AcceptTcpClientAsync()
    if (!$pending.Wait(45000)) { throw 'Emulator did not report a console port.' }
    $conn = $pending.GetAwaiter().GetResult()
    try {
        $stream = $conn.GetStream()
        $stream.ReadTimeout = 5000
        $buf = [byte[]]::new(12)
        $count = 0
        while ($count -lt $buf.Length) {
            $read = $stream.Read($buf, $count, $buf.Length-$count)
            if ($read -eq 0) { break }
            $count += $read
        }
        if ($count -eq $buf.Length -or ![Net.IPAddress]::IsLoopback($conn.Client.RemoteEndPoint.Address) -or
            [Text.Encoding]::ASCII.GetString($buf,0,$count).Trim() -cne "$Port") {
            throw 'Emulator reported a different or invalid port.'
        }
    } finally { $conn.Dispose() }
}
function Live($identity) {
    $p = Get-Process -Id $identity.Id -ErrorAction SilentlyContinue
    if ($p -and (Test-AvdProcessIdentity $identity $p)) { return $p }
}
$transport = $null
$marker = $null
function Confirm-Guest {
    if (!(Live $engineId) -or !$transport -or !$marker) { return $false }
    $device = AdbDevice
    if (!(Test-AvdTransport $device $transport)) { return $false }
    $observed = & $adb -t $transport shell cat $markerPath 2>$null
    if ($LASTEXITCODE -or "$observed".Trim() -cne $marker) { return $false }
    (Test-AvdTransport (AdbDevice) $transport) -and [bool](Live $engineId)
}
function WaitExit($identity, [int]$seconds) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ((Live $identity) -and $timer.Elapsed.TotalSeconds -lt $seconds) { Start-Sleep -Milliseconds 200 }
    !(Live $identity)
}
function StopRecorded($identity) {
    if (!(WaitExit $identity 10)) {
        $p = Live $identity
        if ($p) { Stop-Process -InputObject $p -ErrorAction Stop }
        if (!(WaitExit $identity 10)) { throw 'Recorded process has not stopped.' }
    }
}
$lock = [Threading.Mutex]::new($false,"Global\CinematicAvdPort-$Port")
$held = $false
$launcherId = $null
$engineId = $null
$report = $null
$oldHome = $env:ANDROID_AVD_HOME
$oldSerial = $env:ANDROID_SERIAL
try {
    try { $held = $lock.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $held = $true; throw 'Abandoned reservation: investigate without stopping other processes.' }
    if (!$held) { throw 'AVD port already reserved.' }
    if (AdbDevice) { throw 'Reserved ADB serial is occupied.' }
    Assert-PortPairFree
    $report = New-ExclusiveListener ([Net.IPAddress]::Loopback) 0
    $reportPort = $report.LocalEndpoint.Port
    $output = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
    $env:ANDROID_AVD_HOME = $avdHomePath
    $env:ANDROID_SERIAL = $serial
    $arguments = @('-avd',$AvdName,'-port',$Port,'-cores',$Cores,'-memory',$MemoryMiB,
        '-report-console',"tcp:$reportPort,max=15",'-read-only','-no-snapshot-save','-no-window','-no-audio','-no-boot-anim')
    if ($Mode -eq 'AtdSnapshot') { $arguments += @('-snapshot','prepared_v1') }
    else { $arguments += @('-gpu','host','-feature','GuestAngle,Vulkan','-no-snapshot') }
    $launchOptions = @{
        ArgumentList = $arguments; WindowStyle = 'Hidden'; PassThru = $true
        RedirectStandardOutput = Join-Path $output 'emulator.stdout.local.txt'
        RedirectStandardError = Join-Path $output 'emulator.stderr.local.txt'
    }
    $launcher = Start-Process $emulator @launchOptions
    $launcherId = Get-AvdProcessIdentity $launcher
    $launcherId | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'launcher.local.json')
    Confirm-ReportedPort $report
    $report.Stop()
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (!(Live $launcherId)) { throw 'Launcher exited before QEMU ownership could be proven.' }
        $children = @(Get-Process -Name 'qemu-system-x86_64','qemu-system-x86_64-headless' -ErrorAction SilentlyContinue | Where-Object {
            Test-AvdLaunchedEngine $launcherId $_ $_.Parent $emulator
        })
        if ($children.Count -gt 1) { throw 'Multiple QEMU children: ownership ambiguous.' }
        if ($children.Count -eq 1 -and (Live $launcherId)) { $engineId = Get-AvdProcessIdentity $children[0] }
        else { Start-Sleep -Milliseconds 250 }
    } while (!$engineId -and $timer.Elapsed.TotalSeconds -lt 10)
    if (!$engineId) { throw 'No owned QEMU child detected for this SDK. Do not use this runner.' }
    $engineId | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'engine.local.json')
    $timer.Restart()
    do {
        if (!(Live $engineId)) { throw 'Owned QEMU exited during boot.' }
        $ready = $false
        $device = AdbDevice
        if ($device.State -eq 'device' -and $device.Transport) {
            $boot = & $adb -t $device.Transport shell getprop sys.boot_completed
            $ready = $LASTEXITCODE -eq 0 -and "$boot".Trim() -eq '1'
        }
        if (!$ready) { Start-Sleep -Seconds 2 }
    } while (!$ready -and $timer.Elapsed.TotalMinutes -lt 6)
    if (!$ready) { throw 'AVD boot timed out.' }
    $transport = $device.Transport
    $hardware = & $adb -t $transport shell getprop ro.hardware
    if ($LASTEXITCODE -or "$hardware".Trim() -notin @('ranchu','goldfish')) { throw 'Unexpected hardware.' }
    $name = @(& $adb -s $serial emu avd name | Where-Object { $_.Trim() -and $_.Trim() -ne 'OK' })
    if ($LASTEXITCODE -or $name.Count -ne 1 -or $name[0].Trim() -cne $AvdName) { throw 'Unexpected AVD name.' }
    if ($Mode -eq 'AtdSnapshot') {
        $current = & $adb -t $transport shell cat /proc/sys/kernel/random/boot_id
        if ($LASTEXITCODE -or "$current".Trim() -notmatch '^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$') { throw 'Invalid boot ID.' }
        $saved = & $adb -s $serial shell cat /data/local/tmp/dot-snapshot-boot-id
        if ($LASTEXITCODE -or "$saved".Trim() -cne "$current".Trim()) { throw 'Snapshot boot ID mismatch.' }
    }
    # Session marker is only in this synthetic read-only overlay, never the baseline.
    if (!(Test-AvdTransport (AdbDevice) $transport) -or !(Live $engineId)) { throw 'ADB transport changed before registration.' }
    $marker = [Guid]::NewGuid().ToString('N')
    $markerPath = "/data/local/tmp/cinematic-avd-$marker"
    & $adb -t $transport shell "printf '%s' '$marker' > '$markerPath'"
    if ($LASTEXITCODE -or !(Confirm-Guest)) { throw 'Could not verify the AVD session marker.' }
    # The callback must throw on failed native commands (PowerShell 7.3+).
    & { $PSNativeCommandUseErrorActionPreference = $true; & $Run $adb $serial }
} finally {
    try {
        if ($report) { $report.Stop() }
        if ($engineId -and (Live $engineId)) {
            try {
                if (Confirm-Guest) {
                    & $adb -s $serial emu kill | Out-Null
                }
            } catch { Write-Warning 'ADB stop not confirmed; only the recorded process may be stopped.' }
            StopRecorded $engineId
        }
        if ($launcherId) { StopRecorded $launcherId }
        if ($launcherId) {
            if (AdbDevice) { throw 'Residual ADB serial: inspect without stopping an unregistered process.' }
            Assert-PortPairFree
        }
    } finally {
        $env:ANDROID_AVD_HOME = $oldHome
        $env:ANDROID_SERIAL = $oldSerial
        if ($held) { $lock.ReleaseMutex() }
        $lock.Dispose()
    }
}
