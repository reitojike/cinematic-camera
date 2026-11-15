# Local runtime evidence only. Not a signature or an authorization boundary.
# Keep caller strict-mode policy unchanged. Missing evidence fields fail comparisons below.
function Get-EvidenceHash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-LaunchValue([string[]]$Lines,[string]$Name) {
    $indexes=@(for($i=0;$i -lt $Lines.Count;$i++){if($Lines[$i] -eq $Name){$i}})
    if($indexes.Count -ne 1 -or $indexes[0]+1 -ge $Lines.Count){throw "Missing or ambiguous launch option: $Name"}
    $Lines[$indexes[0]+1]
}
function Get-CommandValue([string]$Command,[string]$Name) {
    $matches=[regex]::Matches($Command,'(?:^|\s)'+[regex]::Escape($Name)+'\s+(?:"([^"]+)"|(\S+))')
    if($matches.Count -ne 1){throw "Missing or ambiguous process option: $Name"}
    if($matches[0].Groups[1].Success){$matches[0].Groups[1].Value}else{$matches[0].Groups[2].Value}
}
function Assert-LaunchAssociation($Live,[string[]]$Lines,[DateTime]$Modified,[string]$Serial,[string]$AvdName) {
    if($Serial -notmatch '^emulator-([0-9]+)$'){throw 'Emulator serial required'}
    $port=$Matches[1]
    $expected=@{'-port'=$port;'-avd'=$AvdName;'-gpu'='host';'-feature'='GuestAngle,Vulkan'}
    foreach($key in $expected.Keys){
        if((Get-LaunchValue $Lines $key) -ne $expected[$key] -or (Get-CommandValue $Live.commandLine $key) -ne $expected[$key]){throw "Launch/process mismatch: $key"}
    }
    $started=([DateTime]$Live.startedUtc).ToUniversalTime()
    if($Modified.ToUniversalTime() -lt $started.AddSeconds(-5) -or $Modified.ToUniversalTime() -gt $started.AddSeconds(30)){throw 'Emulator launch record is stale'}
}
function Get-LiveGpuProcess([string]$Serial) {
    if($Serial -notmatch '^emulator-([0-9]+)$'){throw 'Emulator serial required'}
    $port=$Matches[1]
    $items=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -like 'qemu-system-*' -and $_.CommandLine -match "(?:^|\s)-port\s+$port(?:\s|$)"})
    if($items.Count -ne 1){throw 'Cannot map serial to exactly one running QEMU process'}
    [pscustomobject]@{processId=$items[0].ProcessId;startedUtc=$items[0].CreationDate.ToUniversalTime().ToString('o');commandLine=$items[0].CommandLine}
}
function Assert-InstanceEvidence($Record,$Live,[string]$Serial,[string]$ConfigPath,[string]$ConfigHash,[string]$BootId,[string]$AvdName,[string[]]$Launch,[string]$LaunchHash) {
    if($Record.schema -ne 1 -or $Record.serial -ne $Serial -or $Record.avdName -ne $AvdName){throw 'Wrong instance evidence identity'}
    if($Record.processId -ne $Live.processId -or ([DateTime]$Record.startedUtc).ToUniversalTime() -ne ([DateTime]$Live.startedUtc).ToUniversalTime() -or $Record.commandLine -cne $Live.commandLine){throw 'Process changed or PID reused'}
    if($Record.configPath -ine $ConfigPath -or $Record.configSha256 -cne $ConfigHash){throw 'AVD config changed'}
    if($BootId -notmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or $Record.bootId -cne $BootId){throw 'Guest boot changed or missing'}
    if($Record.launchSha256 -cne $LaunchHash){throw 'Preserved launch record changed'}
    Assert-LaunchAssociation $Live $Launch ([DateTime]$Record.launchModifiedUtc) $Serial $AvdName
}
function Read-InstanceEvidence([string]$Path,$Live,[string]$Serial,[string]$ConfigPath,[string]$BootId,[string]$AvdName) {
    $Path=(Resolve-Path -LiteralPath $Path).Path
    $directory=Split-Path -Parent $Path
    if((Split-Path -Leaf $Path) -ne 'instance.local.json'){throw 'Expected instance.local.json'}
    $digest=(Get-Content -LiteralPath (Join-Path $directory 'instance.sha256') -Raw).Trim()
    if($digest -cne (Get-EvidenceHash $Path)){throw 'Instance record changed'}
    $record=Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $launchPath=Join-Path $directory 'emulator-launch.local.txt'
    Assert-InstanceEvidence $record $Live $Serial $ConfigPath (Get-EvidenceHash $ConfigPath) $BootId $AvdName (Get-Content -LiteralPath $launchPath) (Get-EvidenceHash $launchPath)
    $record
}
