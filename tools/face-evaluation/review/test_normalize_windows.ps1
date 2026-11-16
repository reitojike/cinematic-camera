. (Join-Path $PSScriptRoot 'normalize_windows.ps1') -FunctionsOnly
$ErrorActionPreference='Stop'
function Assert($condition,$message){if(-not $condition){throw $message}}
$profile=[Windows.Media.ColorContext]::new([Windows.Media.PixelFormats]::Bgra32)
[byte[]]$bgra=0,0,255,255,0,255,0,255,255,0,0,255,255,255,255,255,0,0,0,255,128,128,128,255
$source=[Windows.Media.Imaging.BitmapSource]::Create(3,2,96,96,[Windows.Media.PixelFormats]::Bgra32,$null,$bgra,12)
$w=0;$h=0
[byte[]]$rgba=[FaceNormalizePixels]::Convert($source,$profile,1,4000000,[ref]$w,[ref]$h)
Assert ($w-eq3 -and $h-eq2 -and $rgba[0]-ge250 -and $rgba[1]-le5) 'sRGB identity failed'
for($o=1;$o-le8;$o++) {
    [byte[]]$p=[FaceNormalizePixels]::Convert($source,$profile,$o,4000000,[ref]$w,[ref]$h)
    Assert ($p.Length-eq24 -and ($w*$h)-eq6) 'orientation dimensions'
    $redIndex=@(0,2,5,3,0,1,5,4)[$o-1]
    Assert ($p[$redIndex*4]-ge250 -and $p[$redIndex*4+1]-le5) 'orientation pixel mapping'
}
[byte[]]$small=[FaceNormalizePixels]::Convert($source,$profile,1,2,[ref]$w,[ref]$h)
Assert (($w*$h)-le2) 'pixel cap'
$alphaSource=[Windows.Media.Imaging.BitmapSource]::Create(1,1,96,96,[Windows.Media.PixelFormats]::Bgra32,$null,[byte[]]@(0,0,255,128),4)
[byte[]]$opaque=[FaceNormalizePixels]::Convert($alphaSource,$profile,1,4000000,[ref]$w,[ref]$h)
Assert ($opaque[0]-ge120 -and $opaque[0]-le135 -and $opaque[1]-le5 -and $opaque[3]-eq255) 'black alpha composition'
$bad=$false;try{[FaceNormalizePixels]::Convert($source,$null,1,4000000,[ref]$w,[ref]$h)}catch{$bad=$true};Assert $bad 'missing ICC not rejected'
# Synthetic CMYK with the OS-supplied SWOP ICC exercises a non-sRGB color transform.
$cmykProfile=[Windows.Media.ColorContext]::new([Uri]::new((Join-Path $env:WINDIR 'System32\spool\drivers\color\RSWOP.icm')))
[byte[]]$ink=0,0,0,0,255,255,255,255
$cmyk=[Windows.Media.Imaging.BitmapSource]::Create(2,1,96,96,[Windows.Media.PixelFormats]::Cmyk32,$null,$ink,8)
[byte[]]$rgb=[FaceNormalizePixels]::Convert($cmyk,$cmykProfile,1,4000000,[ref]$w,[ref]$h)
Assert ($rgb[0]-gt200 -and $rgb[4]-lt80 -and $rgb[3]-eq255 -and $rgb[7]-eq255) 'CMYK ICC conversion'
# Tagged file decode, original protection and untagged rejection. Only self-generated temporary files.
$root=Join-Path ([IO.Path]::GetTempPath()) ('face-normalize-synthetic-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root)|Out-Null
try {
    $contexts=[Collections.ObjectModel.ReadOnlyCollection[Windows.Media.ColorContext]]::new([Windows.Media.ColorContext[]]@($profile))
    $frame=[Windows.Media.Imaging.BitmapFrame]::Create($source,$null,$null,$contexts)
    $encoder=[Windows.Media.Imaging.PngBitmapEncoder]::new();$encoder.Frames.Add($frame)
    $path=Join-Path $root 'synthetic.png';$stream=[IO.File]::Create($path);try{$encoder.Save($stream)}finally{$stream.Dispose()}
    $metadata=[Windows.Media.Imaging.BitmapMetadata]::new('jpg')
    $metadata.SetQuery('/app1/ifd/{ushort=274}',[uint16]6)
    $jpeg=[Windows.Media.Imaging.JpegBitmapEncoder]::new();$jpeg.QualityLevel=100
    $jpeg.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($source,$null,$metadata,$contexts))
    $jpegPath=Join-Path $root 'orientation6.jpg';$stream=[IO.File]::Create($jpegPath);try{$jpeg.Save($stream)}finally{$stream.Dispose()}
    $rotated=Get-NormalizedPixels $jpegPath
    Assert ($rotated.width-eq2 -and $rotated.height-eq3) 'JPEG EXIF read connection'
    $bundle=Join-Path $root 'bundle'
    & (Join-Path $PSScriptRoot 'normalize_windows.ps1') -SelectedFiles @($path) -OutputDirectory $bundle -SamePcApproved
    & python (Join-Path $PSScriptRoot '..\private_manifest.py') $bundle
    Assert ($LASTEXITCODE-eq0) 'manifest integration'
    $before=(Get-FileHash $path).Hash;$out=Get-NormalizedPixels $path
    Assert ($out.width-eq3 -and $out.height-eq2) 'tagged decode'
    Assert ((Get-FileHash $path).Hash-eq$before) 'source changed'
    $plain=[Windows.Media.Imaging.PngBitmapEncoder]::new();$plain.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($source))
    $plainPath=Join-Path $root 'untagged.png';$stream=[IO.File]::Create($plainPath);try{$plain.Save($stream)}finally{$stream.Dispose()}
    $bad=$false;try{Get-NormalizedPixels $plainPath}catch{$bad=$true};Assert $bad 'untagged accepted'
} finally {
    if($bundle -and (Test-Path -LiteralPath $bundle)) {
        # This is our just-created synthetic bundle; verify the only expected file names before deletion.
        $ownedFiles=@(Get-ChildItem -LiteralPath $bundle -File)
        foreach($f in $ownedFiles){Assert ($f.Name -eq 'manifest.json' -or $f.Name -match '^evaluation-[0-9a-f]{16}\.rgba$') 'unexpected synthetic file';Remove-Item -LiteralPath $f.FullName}
        [IO.Directory]::Delete($bundle)
    }
    foreach($name in @('synthetic.png','untagged.png','orientation6.jpg')){$owned=Join-Path $root $name;if(Test-Path -LiteralPath $owned){Remove-Item -LiteralPath $owned}}
    [IO.Directory]::Delete($root)
}
'PASS: synthetic ICC identity/CMYK, eight orientations, cap, tagged decode, source protection, missing ICC rejection'
