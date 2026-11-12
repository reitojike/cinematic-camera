param(
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AvdConfig,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
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
$avdName = @(Run-Checked $adb @('-s',$Serial,'emu','avd','name') | Where-Object { $_ -and $_ -ne 'OK' })
if ($avdName.Count -ne 1) { throw 'Running AVD identity is missing or ambiguous' }
$avdName = $avdName[0].Trim()
$AvdConfig = (Resolve-Path -LiteralPath $AvdConfig).Path
$avdDirectory = Split-Path -Parent $AvdConfig
if ((Split-Path -Leaf $avdDirectory) -ne "$avdName.avd") { throw 'Selected config does not belong to the running AVD' }
$port = $Serial.Substring('emulator-'.Length)
$processes = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'qemu-system-*' -and $_.CommandLine -match "(?:^|\s)-port\s+$port(?:\s|$)"
})
if ($processes.Count -ne 1) { throw 'Cannot map emulator serial to exactly one running QEMU process' }
$process = $processes[0]
$command = $process.CommandLine
if ($command -notmatch "(?:^|\s)-avd\s+$([Regex]::Escape($avdName))(?:\s|$)" -or
    $command -notmatch '-gpu\s+host\b' -or $command -notmatch '-feature\s+GuestAngle,Vulkan\b') {
    throw 'Running process does not match the required AVD and host/GuestAngle/Vulkan flags'
}
# This is emulator-generated in its actual AVD directory, not a caller-supplied launch note.
$launchPath = Join-Path $avdDirectory 'emu-launch-params.txt'
$launch = Get-Content -LiteralPath $launchPath
function Launch-Value([string]$Name) {
    $indexes = @(for ($i=0; $i -lt $launch.Count; $i++) { if ($launch[$i] -eq $Name) { $i } })
    if ($indexes.Count -ne 1 -or $indexes[0]+1 -ge $launch.Count) { throw "Missing or ambiguous launch option: $Name" }
    return $launch[$indexes[0]+1]
}
if ((Launch-Value '-avd') -ne $avdName -or (Launch-Value '-port') -ne $port -or
    (Launch-Value '-gpu') -ne 'host' -or (Launch-Value '-feature') -ne 'GuestAngle,Vulkan') {
    throw 'Emulator-generated launch record does not match the live process'
}
$started = $process.CreationDate.ToUniversalTime()
$launchModified = (Get-Item -LiteralPath $launchPath).LastWriteTimeUtc
if ($launchModified -lt $started.AddSeconds(-5) -or $launchModified -gt $started.AddSeconds(30)) {
    throw 'Emulator-generated launch record is stale; actual config association is unverified'
}
# PID and local paths stay in run evidence only. Published metadata keeps hashes and versions.
[ordered]@{ processId=$process.ProcessId; processStartedUtc=$started.ToString('o'); avdName=$avdName;
    configPath=$AvdConfig; launchPath=$launchPath; launchSha256=(Hash $launchPath) } |
    ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $output 'runtime-link.local.json')
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
$hostData = [ordered]@{
    schema=1; sourceCommit=$commit; sourceTree=$tree; sourceDirty=$false
    appApkSha256=$null; testApkSha256=$null; environment=$environment
    instrumentationPassed=$false; phase='build_pending'; capturedUtc=[DateTime]::UtcNow.ToString('o')
}
$hostPath = Join-Path $output 'host.json'
function Save-Host { $hostData | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 $hostPath }
# Preserve identity even if build/install/instrumentation/pull fails. Never upgrade an uncertain run to success.
Save-Host
try {
    $env:CINEMATIC_SIGNING_MODE = 'ephemeral_debug'
    & ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2 *> (Join-Path $output 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed; no installation performed' }
    if (@(Run-Checked git @('status','--porcelain')).Count) { throw 'Sources changed during build' }
    if ((Run-Checked git @('rev-parse','HEAD')).Trim() -ne $commit) { throw 'Source commit changed during build' }
    $app = Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
    $test = Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    $hostData.appApkSha256 = Hash $app
    $hostData.testApkSha256 = Hash $test
    $hostData.phase = 'install_pending'; Save-Host
    # The same process must still own the serial after the build.
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.ProcessId)"
    if (!$current -or $current.CreationDate -ne $process.CreationDate -or $current.CommandLine -ne $command) {
        throw 'AVD process changed during build'
    }
    if ((Hash $AvdConfig) -ne $environment.avdConfigSha256) { throw 'AVD config changed during build' }
    Run-Checked $adb @('-s',$Serial,'install','-r','-t',$app) | Out-File (Join-Path $output 'install-app.txt')
    Run-Checked $adb @('-s',$Serial,'install','-r','-t',$test) | Out-File (Join-Path $output 'install-test.txt')
    $hostData.phase = 'instrumentation_pending'; Save-Host
    $selector = 'dev.cinematic.p0.gpu.golden.GpuGoldenAndroidTest#captureNinetyFullFramesAndCompareBothBandSizes'
    & $adb -s $Serial shell am instrument -w -r -e isolated true -e gpuGolden true -e gpuGoldenRunId $runId -e class $selector dev.cinematic.p0.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Tee-Object -FilePath (Join-Path $output 'instrumentation.txt')
    $exitCode = $LASTEXITCODE
    $raw = Get-Content -LiteralPath (Join-Path $output 'instrumentation.txt') -Raw
    $passed = $exitCode -eq 0 -and $raw -match 'OK \(1 test\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_STATUS_CODE: -3|INSTRUMENTATION_STATUS_CODE: -4|shortMsg='
    $hostData.instrumentationPassed = $passed
    $hostData.phase = 'pull_pending'; Save-Host
    & $adb -s $Serial pull "/sdcard/Android/data/dev.cinematic.p0/files/gpu-golden/$runId" $output 2>&1 | Out-File (Join-Path $output 'pull.txt')
    $pullExit = $LASTEXITCODE
    $directory = Join-Path $output $runId
    if ($pullExit -ne 0 -or !(Test-Path -LiteralPath (Join-Path $directory 'capture.json'))) { throw 'Candidate retrieval failed; retain host/instrumentation evidence' }
    if (!$passed) { throw "GPU candidate failed; stop and inspect $output. No automatic rerun." }
    $hostData.phase = 'complete'; Save-Host
    Copy-Item -LiteralPath $hostPath -Destination (Join-Path $directory 'host.json')
    Run-Checked $Python @((Join-Path $PSScriptRoot 'gpu_golden.py'),'validate',$directory)
    Run-Checked $Python @((Join-Path $PSScriptRoot 'gpu_golden.py'),'review',$directory,(Join-Path $output 'review'))
    Write-Output "CANDIDATE=$directory"
    Write-Output "REVIEW=$(Join-Path $output 'review/index.html')"
    Write-Output 'Candidate only. Request owner visual approval before running approve or committing baseline images.'
} catch {
    $hostData.phase = 'failed_' + $hostData.phase
    throw
} finally {
    Save-Host
    if ($directory -and (Test-Path -LiteralPath $directory)) {
        Copy-Item -LiteralPath $hostPath -Destination (Join-Path $directory 'host.json') -Force
    }
}
