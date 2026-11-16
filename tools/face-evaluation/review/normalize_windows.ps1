# Windows PowerShell 5.1 / WPF; same-PC evaluation only.
[CmdletBinding()]
param([string[]]$SelectedFiles, [string]$OutputDirectory, [switch]$SamePcApproved, [switch]$FunctionsOnly)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName PresentationCore
if (-not ('FaceNormalizePixels' -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot 'NormalizePixels.cs') -ReferencedAssemblies PresentationCore,WindowsBase
}
function Assert-LocalOutsideGit([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    if($full.StartsWith('\\') -or $full -notmatch '^[A-Za-z]:\\') { throw 'Local disk required' }
    $drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($full))
    if($drive.DriveType -ne [IO.DriveType]::Fixed){throw 'Fixed local disk required'}
    $part=$full
    while($part) {
        if(Test-Path -LiteralPath $part) {
            $item=Get-Item -LiteralPath $part -Force
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse point rejected'}
        }
        if(Test-Path -LiteralPath (Join-Path $part '.git')){throw 'Git data rejected'}
        $parent=[IO.Directory]::GetParent($part);if($parent){$part=$parent.FullName}else{$part=$null}
    }
    return $full
}
function Get-NormalizedPixels([string]$Path) {
    $source=Assert-LocalOutsideGit $Path
    $file=Get-Item -LiteralPath $source
    if($file.Length -gt 134217728 -or $file.Extension.ToLowerInvariant() -notin @('.jpg','.jpeg','.png')){throw 'Input limit/format'}
    $stream=[IO.File]::Open($source,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $decoder=[Windows.Media.Imaging.BitmapDecoder]::Create($stream,[Windows.Media.Imaging.BitmapCreateOptions]::PreservePixelFormat,[Windows.Media.Imaging.BitmapCacheOption]::OnDemand)
        if($decoder.Frames.Count -ne 1){throw 'Single frame required'}
        $frame=$decoder.Frames[0]
        if($frame.ColorContexts.Count -ne 1){throw 'Exactly one embedded ICC profile required'}
        $orientation=1
        if($frame.Metadata) {
            foreach($query in @('/app1/ifd/{ushort=274}','/eXIf/ifd/{ushort=274}')) {
                if($frame.Metadata.ContainsQuery($query)){$orientation=[int]$frame.Metadata.GetQuery($query);break}
            }
        }
        $w=0;$h=0
        $pixels=[FaceNormalizePixels]::Convert($frame,$frame.ColorContexts[0],$orientation,4000000,[ref]$w,[ref]$h)
        return @{width=$w;height=$h;pixels=$pixels}
    } finally {$stream.Dispose()}
}
if($FunctionsOnly){return}
try {
    if(-not $SamePcApproved -or $SelectedFiles.Count -lt 1 -or $SelectedFiles.Count -gt 64){throw 'Explicit selection/approval required'}
    $output=Assert-LocalOutsideGit $OutputDirectory
    if(Test-Path -LiteralPath $output){throw 'New output directory required'}
    [IO.Directory]::CreateDirectory($output) | Out-Null
    $sha=[Security.Cryptography.SHA256]::Create()
    try {
        $recipe='windows-wpf-icc-srgb-black-exif-v1;max4000000;'+[Environment]::Version.ToString()+';'+(Get-FileHash (Join-Path $PSScriptRoot 'NormalizePixels.cs') -Algorithm SHA256).Hash+';'+(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash
        $revision=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($recipe)))).Replace('-','').ToLowerInvariant()
        $samples=@();$seen=@{}
        foreach($inputPath in $SelectedFiles) {
            $result=Get-NormalizedPixels $inputPath
            $hash=([BitConverter]::ToString($sha.ComputeHash($result.pixels))).Replace('-','').ToLowerInvariant()
            if($seen.ContainsKey($hash)){throw 'Duplicate pixels'};$seen[$hash]=$true
            $id='evaluation-'+[Guid]::NewGuid().ToString('N').Substring(0,16)
            [IO.File]::WriteAllBytes((Join-Path $output ($id+'.rgba')),$result.pixels)
            $samples+=@{id=$id;width=$result.width;height=$result.height;sha256=$hash}
        }
        $manifest=@{schema=1;geometry='upright-srgb-argb8888-v1';revision=$revision;samples=@($samples)}|ConvertTo-Json -Depth 6 -Compress
        [IO.File]::WriteAllText((Join-Path $output 'manifest.json'),$manifest,[Text.UTF8Encoding]::new($false))
    } finally {$sha.Dispose()}
    Write-Output 'Normalization completed locally. Keep this bundle private.'
} catch {
    # Preserve partial private output for local inspection; never report source paths.
    Write-Error 'Normalization rejected. Incomplete private output may remain; do not use without a manifest.'
    exit 1
}
