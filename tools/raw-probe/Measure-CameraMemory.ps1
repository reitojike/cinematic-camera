[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateRange(5, 300)][int]$Seconds = 60,
    [string]$OutputDirectory = '.android/rawprobe-results'
)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'adb' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$records = [Collections.Generic.List[object]]::new()
$clock = [Diagnostics.Stopwatch]::StartNew()
Write-Output 'Read-only observation started. Operate the existing camera app yourself; no app setting is changed.'
while ($clock.Elapsed.TotalSeconds -lt $Seconds) {
    $text = & $adb -s $Serial shell dumpsys -t 2 meminfo dev.cinematic.p0 2>&1 | Out-String
    $ok = $LASTEXITCODE -eq 0
    # Keep aggregate rows only, never full dumps, serial, PID, photo URI or UI data.
    $rows = @($text -split '\r?\n' | Where-Object {
        $_ -match '^\s*(Native Heap:?|Dalvik Heap|TOTAL\s|TOTAL PSS:|TOTAL RSS:|Java Heap:|Code:|Stack:|Graphics:|Private Other:|System:)\s*\d'
    })
    $status = if ($ok -and $rows.Count -gt 0) { 'observed' } else { 'unavailable' }
    $records.Add([pscustomobject]@{ elapsedMs = $clock.ElapsedMilliseconds; status = $status; memoryRows = $rows })
    Start-Sleep -Milliseconds 1000
}
$report = [pscustomobject]@{
    scope = 'read-only-production-process-sampled-memory-not-exact-peak-or-render-duration'
    applicationId = 'dev.cinematic.p0'
    requestedSeconds = $Seconds
    records = $records.ToArray()
}
$name = 'camera-memory-' + [Guid]::NewGuid().ToString('N') + '.json'
[IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $OutputDirectory).Path $name), ($report | ConvertTo-Json -Depth 6))
if (-not ($records | Where-Object { $_.status -eq 'observed' })) { throw 'No production process memory was observed; the report is not a successful measurement.' }
