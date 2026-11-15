param(
    [Parameter(Mandatory=$true)][string]$AvdConfig,
    [Parameter(Mandatory=$true)][string]$AvdHome,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][ValidateRange(5554,5680)][int]$Port,
    [ValidateRange(1,8)][int]$Cores=4,
    [ValidateRange(2048,8192)][int]$MemoryMiB=4096
)
# Starts only an authorized, separate read-only emulator. No snapshot/data mutation.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'instance_evidence.ps1')
if($Port % 2){throw 'Even console port required'}
$Serial="emulator-$Port"
$AvdConfig=(Resolve-Path -LiteralPath $AvdConfig).Path
$AvdHome=(Resolve-Path -LiteralPath $AvdHome).Path
$SdkRoot=(Resolve-Path -LiteralPath $SdkRoot).Path
$avdName=[IO.Path]::GetFileNameWithoutExtension((Split-Path -Parent $AvdConfig))
if($avdName -notmatch '^[a-zA-Z0-9_.-]+$'){throw 'Simple AVD name required'}
$ini=Get-Content -LiteralPath (Join-Path $AvdHome "$avdName.ini")
$paths=@($ini|Where-Object {$_ -match '^path='})
if($paths.Count -ne 1 -or (Resolve-Path -LiteralPath (($paths[0] -split '=',2)[1])).Path -ine (Split-Path -Parent $AvdConfig)){throw 'AVD home/config association mismatch'}
$configHash=Get-EvidenceHash $AvdConfig
$key=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($AvdConfig.ToLowerInvariant())))
$mutex=[Threading.Mutex]::new($false,"Global\CinematicGpuLaunch-$key")
$owned=$false;$live=$null
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=Join-Path $repo ("build/gpu-launch/$Serial-"+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$adb=Join-Path $SdkRoot 'platform-tools/adb.exe'
function Adb([string[]]$Arguments){$result=& $adb -s $Serial @Arguments 2>&1;if($LASTEXITCODE){throw "adb failed: $($result -join ' ')"};$result}
try {
    try {$owned=$mutex.WaitOne(120000)} catch [Threading.AbandonedMutexException] {$owned=$true;throw 'Abandoned launch lock: inspect prior registration before retry'}
    if(!$owned){throw 'Launch registration is occupied'}
    if(@(Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue).Count){throw 'Console port occupied'}
    if(@(Get-NetTCPConnection -LocalPort ($Port+1) -ErrorAction SilentlyContinue).Count){throw 'ADB port occupied'}
    $env:ANDROID_AVD_HOME=$AvdHome
    $env:ANDROID_SERIAL=$Serial
    $emulator=Join-Path $SdkRoot 'emulator/emulator.exe'
    $arguments="-avd $avdName -port $Port -cores $Cores -memory $MemoryMiB -gpu host -feature GuestAngle,Vulkan -read-only -no-snapshot -no-snapshot-save -no-window -no-audio -no-boot-anim"
    $launcher=Start-Process $emulator -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'emulator.stdout.local.txt') -RedirectStandardError (Join-Path $output 'emulator.stderr.local.txt')
    $until=[DateTime]::UtcNow.AddSeconds(60)
    do {Start-Sleep -Milliseconds 250;try {$live=Get-LiveGpuProcess $Serial}catch {$live=$null}}while(!$live -and [DateTime]::UtcNow -lt $until)
    if(!$live){throw 'New QEMU process did not appear'}
    if([DateTime]::Parse($live.startedUtc).ToUniversalTime() -lt $launcher.StartTime.ToUniversalTime().AddSeconds(-2)){throw 'Selected process predates this launch'}
    $launchPath=Join-Path (Split-Path -Parent $AvdConfig) 'emu-launch-params.txt'
    $modified=(Get-Item -LiteralPath $launchPath).LastWriteTimeUtc
    $originalHash=Get-EvidenceHash $launchPath
    $copy=Join-Path $output 'emulator-launch.local.txt'
    Copy-Item -LiteralPath $launchPath -Destination $copy
    if((Get-EvidenceHash $copy) -cne $originalHash -or (Get-EvidenceHash $launchPath) -cne $originalHash -or (Get-Item $launchPath).LastWriteTimeUtc -ne $modified){throw 'Launch record changed while registering'}
    Assert-LaunchAssociation $live (Get-Content -LiteralPath $copy) $modified $Serial $avdName
    if((Get-EvidenceHash $AvdConfig) -cne $configHash){throw 'Config changed during launch'}
    # Original bytes and runtime association are now preserved. A second cooperating launch may proceed.
    $mutex.ReleaseMutex();$owned=$false
    $until=[DateTime]::UtcNow.AddMinutes(6);$boot=''
    do {Start-Sleep -Seconds 2;try {$boot=(Adb @('shell','getprop','sys.boot_completed')).Trim()}catch {$boot=''}}while($boot -ne '1' -and [DateTime]::UtcNow -lt $until)
    if($boot -ne '1'){throw 'Boot timeout'}
    $hardware=(Adb @('shell','getprop','ro.hardware')).Trim()
    if($hardware -notin @('ranchu','goldfish')){throw 'Physical or unknown target excluded'}
    $name=@(Adb @('emu','avd','name')|Where-Object {$_ -and $_ -ne 'OK'})
    if($name.Count -ne 1 -or $name[0].Trim() -ne $avdName){throw 'Running AVD identity changed'}
    $bootId=(Adb @('shell','cat','/proc/sys/kernel/random/boot_id')).Trim()
    $record=[ordered]@{schema=1;serial=$Serial;avdName=$avdName;processId=$live.processId;startedUtc=$live.startedUtc;commandLine=$live.commandLine;configPath=$AvdConfig;configSha256=$configHash;launchModifiedUtc=$modified.ToString('o');launchSha256=$originalHash;bootId=$bootId}
    Assert-InstanceEvidence ([pscustomobject]$record) (Get-LiveGpuProcess $Serial) $Serial $AvdConfig (Get-EvidenceHash $AvdConfig) $bootId $avdName (Get-Content $copy) (Get-EvidenceHash $copy)
    $path=Join-Path $output 'instance.local.json'
    $record|ConvertTo-Json|Set-Content -LiteralPath $path -Encoding utf8
    # Completion marker is published atomically only after the record is complete.
    Get-EvidenceHash $path|Set-Content -LiteralPath (Join-Path $output 'instance.sha256.pending') -Encoding ascii
    Move-Item -LiteralPath (Join-Path $output 'instance.sha256.pending') -Destination (Join-Path $output 'instance.sha256')
    Write-Output "SERIAL=$Serial"
    Write-Output "INSTANCE_RECORD=$path"
    Write-Output 'Registered only: perform boot stability/ANR gates before tests. Local evidence must not be published.'
} catch {
    $_|Out-String|Set-Content -LiteralPath (Join-Path $output 'failure.local.txt')
    # Do not stop a replacement process if this port was reassigned.
    if($live){try {$current=Get-LiveGpuProcess $Serial;if($current.processId -eq $live.processId -and $current.startedUtc -ceq $live.startedUtc -and $current.commandLine -ceq $live.commandLine){& $adb -s $Serial emu kill | Out-Null}}catch {}}
    throw
} finally {if($owned){$mutex.ReleaseMutex()};$mutex.Dispose()}
