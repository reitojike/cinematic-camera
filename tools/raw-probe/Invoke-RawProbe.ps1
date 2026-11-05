[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$ApkDirectory = $PSScriptRoot,
    [string]$OutputDirectory = '.android/rawprobe-results',
    [switch]$AllowPhysical,
    [switch]$MemoryOnly,
    [switch]$NativeOnly,
    [switch]$RenderOnly,
    [ValidateSet(1024, 1536, 2048)][int]$RenderEdge = 1024,
    [switch]$LargeHeap
)
$ErrorActionPreference = 'Stop'
if (([int]$MemoryOnly.IsPresent + [int]$NativeOnly.IsPresent + [int]$RenderOnly.IsPresent) -gt 1) {
    throw 'Select at most one of MemoryOnly, NativeOnly and RenderOnly.'
}
$package = if ($LargeHeap) { 'dev.cinematic.rawprobe.largeheap' } else { 'dev.cinematic.rawprobe' }
$apkNames = if ($LargeHeap) { @('rawprobe-largeheap.apk', 'rawprobe-largeheap-androidTest.apk') } else {
    @('rawprobe-debug.apk', 'rawprobe-debug-androidTest.apk')
}
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
foreach ($name in $apkNames) {
    $apk = Join-Path $ApkDirectory $name
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing diagnostic APK: $name" }
    Invoke-Adb @('install', '-r', $apk) | Write-Output
}
$classes = 'dev.cinematic.rawprobe.MemoryPolicyTest'
if (-not ($MemoryOnly -or $RenderOnly)) { $classes += ',dev.cinematic.rawprobe.FullResolutionProbeTest' }
if (-not ($MemoryOnly -or $NativeOnly)) { $classes += ',dev.cinematic.rawprobe.RenderMemoryTest' }
$runId = [Guid]::NewGuid().ToString('N')
$runDirectory = Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$arguments = @('shell', 'am', 'instrument', '-w', '-e', 'rawProbe', 'true', '-e', 'runId', $runId, '-e', 'class', $classes)
$arguments += @('-e', 'renderEdge', "$RenderEdge")
if ($AllowPhysical) { $arguments += @('-e', 'allowPhysical', 'true') }
$arguments += "${package}.test/androidx.test.runner.AndroidJUnitRunner"
$result = & $adb -s $Serial @arguments 2>&1 | Out-String
$instrumentExit = $LASTEXITCODE
$result = $result.Replace($Serial, '<device>')
[IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $runDirectory).Path 'instrumentation.txt'), $result)
Write-Output $result
# am instrument may exit 0 on a test failure. Require the JUnit success marker as well.
$expected = if ($MemoryOnly) { 1 } elseif ($RenderOnly) { 2 } elseif ($NativeOnly) { 3 } else { 4 }
$passed = $instrumentExit -eq 0 -and $result -match "OK \($expected tests?\)" -and $result -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED'
$names = @('memory-policy')
if (-not ($MemoryOnly -or $RenderOnly)) { $names += @('full-resolution', 'boundaries') }
if (-not ($MemoryOnly -or $NativeOnly)) { $names += 'render-memory' }
foreach ($name in $names) {
    try {
        $report = Invoke-Adb @('shell', 'run-as', $package, 'cat', "files/$name.json") | Out-String
        $json = $report | ConvertFrom-Json
        if ($json.runId -ne $runId) { throw 'Report belongs to an earlier run' }
        [IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $runDirectory).Path "$name.json"), $report)
    } catch {
        $passed = $false
        Write-Warning "Current-run report unavailable: $name. No earlier report was copied."
    }
}
if (-not $passed) { throw 'Diagnostic did not pass. Current-run evidence was collected where available; do not infer device support.' }
