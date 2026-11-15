# No AVD, process, network, or SDK operation. PowerShell 7, no Pester dependency.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'instance_evidence.ps1')
$script:count=0
function Check([string]$Name,[scriptblock]$Test){& $Test;$script:count++;Write-Output "PASS $Name"}
function Reject([scriptblock]$Test){$failed=$false;try{& $Test|Out-Null}catch{$failed=$true};if(!$failed){throw 'Expected rejection'}}
function Clone($value){$value|ConvertTo-Json -Depth 8|ConvertFrom-Json}
$root=Join-Path ([IO.Path]::GetTempPath()) ('gpu-instance-unit-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $root|Out-Null
try {
 $config=Join-Path $root 'config.ini';'test-config'|Set-Content $config
 $config=(Resolve-Path $config).Path
 $live=[pscustomobject]@{processId=41;startedUtc='2026-11-15T00:00:00.0000000Z';commandLine='qemu.exe -avd fixture -port 5600 -gpu host -feature GuestAngle,Vulkan -read-only -no-snapshot -no-snapshot-save -cores 4 -memory 4096'}
 $launch=@('-avd','fixture','-port','5600','-gpu','host','-feature','GuestAngle,Vulkan','-read-only','-no-snapshot','-no-snapshot-save','-cores','4','-memory','4096')
 $boot='11111111-2222-3333-4444-555555555555'
 $directory=Join-Path $root 'first';New-Item -ItemType Directory $directory|Out-Null
 $raw=Join-Path $directory 'emulator-launch.local.txt';$launch|Set-Content $raw
 $record=[pscustomobject]@{schema=1;serial='emulator-5600';avdName='fixture';processId=41;startedUtc=$live.startedUtc;commandLine=$live.commandLine;configPath=$config;configSha256=(Get-EvidenceHash $config);bootId=$boot;launchModifiedUtc='2026-11-15T00:00:01.0000000Z';launchSha256=(Get-EvidenceHash $raw)}
 function Validate($candidate,$runtime=$live,$serial='emulator-5600',$cfgHash=(Get-EvidenceHash $config),$bootId=$boot,$lines=$launch,$rawHash=$record.launchSha256){Assert-InstanceEvidence $candidate $runtime $serial $config $cfgHash $bootId 'fixture' $lines $rawHash}
 Check valid {Validate $record}
 Check wrongSerial {Reject {Validate $record $live 'emulator-5602'}}
 Check reusedPid {$bad=Clone $live;$bad.startedUtc='2026-11-15T00:10:00.0000000Z';Reject {Validate $record $bad}}
 Check changedPid {$bad=Clone $live;$bad.processId=42;Reject {Validate $record $bad}}
 Check changedCommand {$bad=Clone $live;$bad.commandLine+=' -gpu software';Reject {Validate $record $bad}}
 Check changedBoot {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'}}
 Check invalidBoot {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) ''}}
 Check changedConfig {Reject {Validate $record $live 'emulator-5600' ('0'*64)}}
 Check changedRaw {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot $launch ('0'*64)}}
 Check staleLaunch {$bad=Clone $record;$bad.launchModifiedUtc='2026-11-14T23:59:54.0000000Z';Reject {Validate $bad}}
 Check tooLateLaunch {$bad=Clone $record;$bad.launchModifiedUtc='2026-11-15T00:00:31.0000000Z';Reject {Validate $bad}}
 Check duplicateRawOption {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot ($launch+@('-port','5600'))}}
 Check duplicateProcessOption {$bad=Clone $live;$bad.commandLine+=' -port 5600';Reject {Assert-LaunchAssociation $bad $launch ([DateTime]::Parse($record.launchModifiedUtc)) 'emulator-5600' 'fixture'}}
 Check missingReadonly {$bad=Clone $live;$bad.commandLine=$bad.commandLine.Replace(' -read-only','');Reject {Validate $record $bad}}
 Check missingRawReadonly {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot @($launch|Where-Object {$_ -ne '-read-only'})}}
 Check missingSnapshotFlag {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot @($launch|Where-Object {$_ -ne '-no-snapshot'})}}
 Check duplicateReadonly {Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot ($launch+@('-read-only'))}}
 Check coresMismatch {$bad=@($launch|ForEach-Object {if($_ -eq '4'){'2'}else{$_}});Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot $bad}}
 Check memoryMismatch {$bad=@($launch|ForEach-Object {if($_ -eq '4096'){'2048'}else{$_}});Reject {Validate $record $live 'emulator-5600' (Get-EvidenceHash $config) $boot $bad}}
 Check physicalSerial {Reject {Assert-LaunchAssociation $live $launch ([DateTime]::Parse($record.launchModifiedUtc)) 'physical' 'fixture'}}
 $path=Join-Path $directory 'instance.local.json';$record|ConvertTo-Json|Set-Content $path
 Check incompleteRegistration {Reject {Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'}}
 Get-EvidenceHash $path|Set-Content (Join-Path $directory 'instance.sha256')
 Check completedRegistration {$null=Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'}
 Check tamperedReceipt {$original=Get-Content $path -Raw;try{Add-Content $path ' ';Reject {Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'}}finally{[IO.File]::WriteAllText($path,$original)}}
 Check recomputedHashCannotChangeLiveIdentity {$bad=Clone $record;$bad.processId=99;$bad|ConvertTo-Json|Set-Content $path;Get-EvidenceHash $path|Set-Content (Join-Path $directory 'instance.sha256');Reject {Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'};$record|ConvertTo-Json|Set-Content $path;Get-EvidenceHash $path|Set-Content (Join-Path $directory 'instance.sha256')}
 Check sharedOverwriteDoesNotChangeSavedReceipt {$shared=Join-Path $root 'emu-launch-params.txt';$launch|Set-Content $shared;@('-port','5602')|Set-Content $shared;$null=Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'}
 Check secondInstanceAndReverseValidation {
  $second=Clone $record;$secondLive=Clone $live;$secondLive.processId=52;$secondLive.commandLine=$live.commandLine.Replace('5600','5602');$second.serial='emulator-5602';$second.processId=52;$second.commandLine=$secondLive.commandLine
  $secondDirectory=Join-Path $root 'second';New-Item -ItemType Directory $secondDirectory|Out-Null
  $secondRaw=Join-Path $secondDirectory 'emulator-launch.local.txt';@($launch|ForEach-Object {if($_ -eq '5600'){'5602'}else{$_}})|Set-Content $secondRaw
  $second.launchSha256=Get-EvidenceHash $secondRaw;$secondPath=Join-Path $secondDirectory 'instance.local.json';$second|ConvertTo-Json|Set-Content $secondPath;Get-EvidenceHash $secondPath|Set-Content (Join-Path $secondDirectory 'instance.sha256')
  $null=Read-InstanceEvidence $secondPath $secondLive 'emulator-5602' $config $boot 'fixture'
  $null=Read-InstanceEvidence $path $live 'emulator-5600' $config $boot 'fixture'
  Reject {Read-InstanceEvidence $secondPath $live 'emulator-5600' $config $boot 'fixture'}
 }
 Check launcherPrecision {$time=[DateTime]'2026-11-15T00:00:00Z';$net=[pscustomobject]@{Id=10;HasExited=$false;StartTime=$time.AddTicks(3)};$cim=[pscustomobject]@{ProcessId=10;CreationDate=$time};$id=New-LauncherIdentity $net $cim;if($id.startedUtc -cne $time.ToUniversalTime().ToString('o')){throw 'CIM identity not retained'}}
 Check launcherPrecisionDoesNotHideReuse {$time=[DateTime]'2026-11-15T00:00:00Z';$net=[pscustomobject]@{Id=10;HasExited=$false;StartTime=$time.AddTicks(3)};$cim=[pscustomobject]@{ProcessId=10;CreationDate=$time.AddTicks(10)};Reject {New-LauncherIdentity $net $cim}}
 Check launcherExited {$net=[pscustomobject]@{Id=10;HasExited=$true};Reject {New-LauncherIdentity $net ([pscustomobject]@{ProcessId=10})}}
 Check cleanupUnattributed {if(Test-CleanupOwnership $null $live){throw 'Unattributed process must not be killed'}}
 $launcherIdentity=[pscustomobject]@{processId=10;startedUtc='2026-11-15T00:00:00.0000000Z'}
 $currentLauncher=[pscustomobject]@{ProcessId=10;CreationDate=[DateTime]$launcherIdentity.startedUtc}
 Check cleanupOldProcess {$owner=$null;$candidate=Clone $live;$candidate.startedUtc='2026-11-14T23:59:59Z';try{Assert-LauncherOwnership $candidate $launcherIdentity $currentLauncher;$owner=$candidate}catch{};if(Test-CleanupOwnership $owner $candidate){throw 'Old process must not be killed'}}
 Check cleanupOtherLauncher {$owner=$null;$candidate=Clone $live;$candidate|Add-Member parentProcessId 99;try{Assert-LauncherOwnership $candidate $launcherIdentity $currentLauncher;$owner=$candidate}catch{};if(Test-CleanupOwnership $owner $candidate){throw 'Other launcher must not be killed'}}
 Check cleanupOtherAvd {$owner=$null;$candidate=Clone $live;$candidate|Add-Member parentProcessId 10;$candidate.commandLine=$candidate.commandLine.Replace('fixture','other');try{Assert-LauncherOwnership $candidate $launcherIdentity $currentLauncher;Assert-LaunchAssociation $candidate $launch ([DateTime]$record.launchModifiedUtc) 'emulator-5600' 'fixture';$owner=$candidate}catch{};if(Test-CleanupOwnership $owner $candidate){throw 'Other AVD must not be killed'}}
 Check cleanupVerifiedChild {$candidate=Clone $live;$candidate|Add-Member parentProcessId 10;Assert-LauncherOwnership $candidate $launcherIdentity $currentLauncher;if(!(Test-CleanupOwnership $candidate $candidate)){throw 'Verified child should be eligible'}}
 Check cleanupLauncherGone {Reject {Assert-LauncherOwnership $live $launcherIdentity $null}}
 Check cleanupLauncherReused {$bad=Clone $currentLauncher;$bad.CreationDate=[DateTime]'2026-11-15T00:01:00Z';Reject {Assert-LauncherOwnership $live $launcherIdentity $bad}}
 # Exercise live-process ambiguity/PID mapping without querying Windows processes.
 function Get-CimInstance {param($ClassName) $script:fakeProcesses}
 $script:fakeProcesses=@()
 Check noProcess {Reject {Get-LiveGpuProcess 'emulator-5600'}}
 $script:fakeProcesses=@([pscustomobject]@{Name='qemu-system-x86_64-headless.exe';ProcessId=41;CreationDate=[DateTime]::Parse($live.startedUtc);CommandLine=$live.commandLine})
 Check uniqueProcess {if((Get-LiveGpuProcess 'emulator-5600').processId -ne 41){throw 'Wrong PID'}}
 $script:fakeProcesses+=Clone $script:fakeProcesses[0]
 Check ambiguousProcess {Reject {Get-LiveGpuProcess 'emulator-5600'}}
 Check scriptsParse {foreach($file in 'instance_evidence.ps1','start_gpu_instance.ps1','capture_android.ps1'){$tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors);if($errors.Count){throw ($errors|Out-String)}}}
 Write-Output "OK $script:count tests; no emulator launched"
} finally {
 if((Split-Path -Leaf $root) -notmatch '^gpu-instance-unit-[a-f0-9]{32}$'){throw 'Unexpected test cleanup path'}
 Remove-Item -LiteralPath $root -Recurse -Force
}
