#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'session_identity.ps1')
function Check([bool]$ok,[string]$name) { if (!$ok) { throw $name } }
$launcher = [pscustomobject]@{Id=10; StartTime=[DateTime]::UtcNow; Path='C:\sdk\emulator\emulator.exe'}
$identity = Get-AvdProcessIdentity $launcher
$changed = [pscustomobject]@{Id=10; StartTime=$launcher.StartTime.AddSeconds(1); Path=$launcher.Path}
$child = [pscustomobject]@{Id=22; StartTime=$launcher.StartTime.AddSeconds(1); Path='C:\sdk\emulator\qemu\windows-x86_64\qemu-system-x86_64.exe'; ProcessName='qemu-system-x86_64'}
Check (Test-AvdLaunchedEngine $identity $launcher $null $launcher.Path) 'Own launcher'
Check (Test-AvdLaunchedEngine $identity $child $launcher $launcher.Path) 'Own QEMU child'
Check (!(Test-AvdLaunchedEngine $identity $child $changed $launcher.Path)) 'Reject reused launcher PID'
Check (!(Test-AvdLaunchedEngine $identity $child $null $launcher.Path)) 'Reject unrelated listener'
$wrongSdk = $child.PSObject.Copy()
$wrongSdk.Path = 'C:\other\qemu-system-x86_64.exe'
Check (!(Test-AvdLaunchedEngine $identity $wrongSdk $launcher $launcher.Path)) 'Reject foreign QEMU path'
$devices = @('List of devices attached','synthetic-physical device transport_id:9','emulator-5620 offline transport_id:4','emulator-5622 device transport_id:6')
Check ((Get-AvdDevice $devices 'emulator-5620').State -eq 'offline') 'Offline target is still occupied'
Check (!(Get-AvdDevice $devices 'emulator-5624')) 'Only exact serial'
Check (Test-AvdTransport (Get-AvdDevice $devices 'emulator-5622') '6') 'Accept exact live transport'
Check (!(Test-AvdTransport (Get-AvdDevice $devices 'emulator-5622') '5')) 'Reject recycled serial with different transport'
Check (!(Test-AvdTransport (Get-AvdDevice $devices 'emulator-5620') '4')) 'Reject offline transport'
Check (!(Test-AvdTransport (Get-AvdDevice @('List of devices attached','emulator-5622 device') 'emulator-5622') '6')) 'Reject unreported transport ID'
$rejected = $false
try { $null = Get-AvdDevice @('invalid output') 'emulator-5620' } catch { $rejected = $true }
Check $rejected 'Fail closed on invalid ADB response'
$rejected = $false
try { $null = Get-AvdDevice ($devices + 'emulator-5620 device') 'emulator-5620' } catch { $rejected = $true }
Check $rejected 'Fail closed on repeated serial'
'PASS: 13 process and ADB transport checks (no emulator)'
