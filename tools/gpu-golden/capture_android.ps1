param(
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AvdConfig,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$LaunchRecord,
    [string]$Python = 'python'
)
# Run only after acquiring the shared AVD lane. Never starts/stops an AVD or touches a physical device.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo
function Run-Checked([string]$File, [string[]]$Arguments) {
    $value = & $File @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$File failed: $($value -join [Environment]::NewLine)" }
    return $value
}
function Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
$dirty = @(Run-Checked git @('status','--porcelain'))
if ($dirty.Count) { throw 'Commit the reviewed sources first; candidate provenance requires a clean worktree' }
$commit = (Run-Checked git @('rev-parse','HEAD')).Trim()
$tree = (Run-Checked git @('rev-parse','HEAD^{tree}')).Trim()
$runId = 'golden-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '-' + $commit.Substring(0,8)
$output = Join-Path $repo "build/gpu-golden/$runId"
if (Test-Path -LiteralPath $output) { throw 'Never overwrite an existing run' }
New-Item -ItemType Directory -Path $output | Out-Null
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$emulator = Join-Path $SdkRoot 'emulator/emulator.exe'
$hardware = (Run-Checked $adb @('-s',$Serial,'shell','getprop','ro.hardware')).Trim()
if ($hardware -notin @('ranchu','goldfish')) { throw 'Physical or unknown device excluded' }
$launch = Get-Content -LiteralPath $LaunchRecord -Raw
if ($launch -notmatch '-gpu\s+host\b' -or $launch -notmatch '-feature\s+GuestAngle,Vulkan\b') {
    throw 'Launch evidence must identify host GPU and GuestAngle,Vulkan'
}
$config = Get-Content -LiteralPath $AvdConfig
$imageLine = $config | Where-Object { $_ -match '^image.sysdir.1=' }
if (@($imageLine).Count -ne 1) { throw 'AVD system image path missing or ambiguous' }
$image = ($imageLine -split '=',2)[1].Trim()
if ($image -notmatch 'system-images[\\/]([^\r\n]+)') { throw 'Unknown Android system image layout' }
$imageRelative = 'system-images/' + $Matches[1].Replace('\','/').TrimEnd('/')
$imagePath = if ([IO.Path]::IsPathRooted($image)) { $image } else { Join-Path $SdkRoot $image }
$revisionLine = Get-Content -LiteralPath (Join-Path $imagePath 'source.properties') | Where-Object { $_ -match '^Pkg.Revision=' }
$revision = ($revisionLine -split '=',2)[1].Trim()
if (!$revision) { throw 'Missing Android system image revision' }
$emulatorVersion = (Run-Checked $emulator @('-version') | Where-Object { $_ -match '^Android emulator version ' } | Select-Object -First 1).Trim()
if (!$emulatorVersion) { throw 'Missing emulator version' }
$drivers = @(Get-CimInstance Win32_VideoController | Sort-Object Name | Select-Object Name,DriverVersion)
if (!$drivers.Count -or ($drivers | Where-Object { !$_.DriverVersion })) { throw 'Missing host GPU driver version' }
$environment = [ordered]@{
    emulatorVersion=$emulatorVersion; systemImageRevision=$revision; avdImage=$imageRelative
    avdConfigSha256=(Hash $AvdConfig); gpuMode='host'; gpuFeatures='GuestAngle,Vulkan'; hostGpuDrivers=$drivers
}
# Build from the exact clean tree immediately before installing. Existing ephemeral debug signing only.
$env:CINEMATIC_SIGNING_MODE = 'ephemeral_debug'
& ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2 *> (Join-Path $output 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'APK build failed; no installation performed' }
if (@(Run-Checked git @('status','--porcelain')).Count) { throw 'Sources changed during build' }
if ((Run-Checked git @('rev-parse','HEAD')).Trim() -ne $commit) { throw 'Source commit changed during build' }
$app = Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
$test = Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$hostData = [ordered]@{
    schema=1; sourceCommit=$commit; sourceTree=$tree; sourceDirty=$false
    appApkSha256=(Hash $app); testApkSha256=(Hash $test); environment=$environment
    instrumentationPassed=$false; capturedUtc=[DateTime]::UtcNow.ToString('o')
}
Run-Checked $adb @('-s',$Serial,'install','-r','-t',$app) | Out-File (Join-Path $output 'install-app.txt')
Run-Checked $adb @('-s',$Serial,'install','-r','-t',$test) | Out-File (Join-Path $output 'install-test.txt')
$selector = 'dev.cinematic.p0.gpu.golden.GpuGoldenAndroidTest#captureNinetyFullFramesAndCompareBothBandSizes'
& $adb -s $Serial shell am instrument -w -r -e isolated true -e gpuGolden true -e gpuGoldenRunId $runId -e class $selector dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath (Join-Path $output 'instrumentation.txt')
$exitCode = $LASTEXITCODE
$raw = Get-Content -LiteralPath (Join-Path $output 'instrumentation.txt') -Raw
$passed = $exitCode -eq 0 -and $raw -match 'OK \(1 test\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_STATUS_CODE: -3|INSTRUMENTATION_STATUS_CODE: -4|shortMsg='
# Pull even an incomplete run. This never updates a baseline and never retries a failed run.
& $adb -s $Serial pull "/sdcard/Android/data/dev.cinematic.p0/files/gpu-golden/$runId" $output 2>&1 | Out-File (Join-Path $output 'pull.txt')
$pullExit = $LASTEXITCODE
$directory = Join-Path $output $runId
if ($pullExit -ne 0 -or !(Test-Path -LiteralPath (Join-Path $directory 'capture.json'))) { throw 'Candidate retrieval failed; retain instrumentation evidence' }
$hostData.instrumentationPassed = $passed
$hostData | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $directory 'host.json')
if (!$passed) { throw "GPU candidate failed; stop and inspect $output. No automatic rerun." }
Run-Checked $Python @((Join-Path $PSScriptRoot 'gpu_golden.py'),'validate',$directory)
Run-Checked $Python @((Join-Path $PSScriptRoot 'gpu_golden.py'),'review',$directory,(Join-Path $output 'review'))
Write-Output "CANDIDATE=$directory"
Write-Output "REVIEW=$(Join-Path $output 'review/index.html')"
Write-Output 'Candidate only. Request owner visual approval before running approve or committing baseline images.'
