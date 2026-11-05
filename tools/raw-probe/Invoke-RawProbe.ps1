[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$ApkDirectory = $PSScriptRoot,
    [string]$OutputDirectory = '.android/rawprobe-results',
    [switch]$AllowPhysical,
    [switch]$MemoryOnly
)
$ErrorActionPreference = 'Stop'
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'adb' }
function Invoke-Adb([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed; preserve the installed camera app. No automatic uninstall. $result" }
    return $result
}
$hardware = (Invoke-Adb @('shell', 'getprop', 'ro.hardware') | Out-String).Trim()
if ($hardware -notin @('ranchu', 'goldfish') -and -not $AllowPhysical) {
    throw 'Physical-device diagnostics require -AllowPhysical. No APK was installed.'
}
foreach ($name in @('rawprobe-debug.apk', 'rawprobe-debug-androidTest.apk')) {
    $apk = Join-Path $ApkDirectory $name
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing diagnostic APK: $name" }
    Invoke-Adb @('install', '-r', $apk) | Write-Output
}
$classes = if ($MemoryOnly) { 'dev.cinematic.rawprobe.MemoryPolicyTest' } else {
    'dev.cinematic.rawprobe.MemoryPolicyTest,dev.cinematic.rawprobe.FullResolutionProbeTest'
}
$runId = [Guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$arguments = @('shell', 'am', 'instrument', '-w', '-e', 'rawProbe', 'true', '-e', 'runId', $runId, '-e', 'class', $classes)
if ($AllowPhysical) { $arguments += @('-e', 'allowPhysical', 'true') }
$arguments += 'dev.cinematic.rawprobe.test/androidx.test.runner.AndroidJUnitRunner'
$result = & $adb -s $Serial @arguments 2>&1 | Out-String
$instrumentExit = $LASTEXITCODE
$result = $result.Replace($Serial, '<device>')
[IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $runDirectory).Path 'instrumentation.txt'), $result)
Write-Output $result
# am instrument may exit 0 on a test failure. Require the JUnit success marker as well.
$expected = if ($MemoryOnly) { 1 } else { 3 }
$passed = $instrumentExit -eq 0 -and $result -match "OK \($expected tests?\)" -and $result -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED'
$names = if ($MemoryOnly) { @('memory-policy') } else { @('memory-policy', 'full-resolution', 'boundaries') }
foreach ($name in $names) {
    try {
        $report = Invoke-Adb @('shell', 'run-as', 'dev.cinematic.rawprobe', 'cat', "files/$name.json") | Out-String
        $json = $report | ConvertFrom-Json
        if ($json.runId -ne $runId) { throw 'Report belongs to an earlier run' }
        [IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $runDirectory).Path "$name.json"), $report)
    } catch {
        $passed = $false
        Write-Warning "Current-run report unavailable: $name. No earlier report was copied."
    }
}
if (-not $passed) { throw 'Diagnostic did not pass. Current-run evidence was collected where available; do not infer device support.' }
