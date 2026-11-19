function Get-AvdDevice([string[]]$lines,[string]$serial) {
    if ($lines -notcontains 'List of devices attached') { throw 'Invalid adb devices response.' }
    $rows = @($lines | Where-Object { $_ -match ('^' + [regex]::Escape($serial) + '\s+') })
    if ($rows.Count -gt 1) { throw 'Ambiguous ADB serial.' }
    if ($rows.Count -eq 1) {
        $state = ($rows[0] -split '\s+')[1]
        $transport = if ($rows[0] -match '(?:^|\s)transport_id:(\d+)(?:\s|$)') { $Matches[1] }
        [pscustomobject]@{ State=$state; Transport=$transport }
    }
}
function Test-AvdTransport($device,[string]$transport) {
    $transport -and $device.State -eq 'device' -and $device.Transport -ceq $transport
}
function Get-AvdProcessIdentity($process) {
    [pscustomobject]@{ Id = $process.Id; StartTicks = $process.StartTime.ToUniversalTime().Ticks }
}
function Test-AvdProcessIdentity($expected,$process) {
    $null -ne $process -and $expected.Id -eq $process.Id -and
        $expected.StartTicks -eq $process.StartTime.ToUniversalTime().Ticks
}
function Test-AvdLaunchedEngine($launcher,$process,$parent,[string]$emulatorPath) {
    if ($process.Id -eq $launcher.Id) {
        return (Test-AvdProcessIdentity $launcher $process) -and $process.Path -ieq $emulatorPath
    }
    $qemuRoot = Join-Path (Split-Path -Parent $emulatorPath) 'qemu\windows-x86_64\'
    (Test-AvdProcessIdentity $launcher $parent) -and $process.StartTime.ToUniversalTime().Ticks -ge $launcher.StartTicks -and
        $process.Path -and $process.Path.StartsWith($qemuRoot,[StringComparison]::OrdinalIgnoreCase) -and
        $process.ProcessName -in @('qemu-system-x86_64','qemu-system-x86_64-headless')
}
