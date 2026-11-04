<#
.SYNOPSIS
Applies or reverses a verified byte-range patch on a local image file.
.DESCRIPTION
Build creates a separate patched file. Undo creates a separate file matching
the original SHA256 from the supplied patch specification. Undo operates only
on local files. It cannot restore a television, roll back a flash, or recover
a device that cannot boot. See docs/offline-image.md for the file contract.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Build','Undo')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$InputImage,
    [Parameter(Mandatory=$true)][string]$OutputImage,
    [Parameter(Mandatory=$true)][string]$PatchFile
)
$ErrorActionPreference = 'Stop'

function Get-CoocaaFileHash {
    param([Parameter(Mandatory=$true)][string]$LiteralPath,[ValidateSet('SHA256')][string]$Algorithm='SHA256')
    $resolvedHashPath = (Resolve-Path -LiteralPath $LiteralPath).ProviderPath
    $hashStream = [IO.File]::OpenRead($resolvedHashPath)
    $hashEngine = [Security.Cryptography.SHA256]::Create()
    try { $hashValue = [BitConverter]::ToString($hashEngine.ComputeHash($hashStream)).Replace('-','') }
    finally { $hashEngine.Dispose(); $hashStream.Dispose() }
    [pscustomobject]@{Algorithm='SHA256';Hash=$hashValue;Path=$resolvedHashPath}
}

# Offline file transformation only. This script contains no ADB or flash commands.
$source = (Resolve-Path -LiteralPath $InputImage).ProviderPath
$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputImage)
if ([string]::Equals($source,$destination,[StringComparison]::OrdinalIgnoreCase)) { throw 'Input and output must be different files.' }
if (Test-Path -LiteralPath $destination) { throw 'Output already exists; refusing to overwrite it.' }
$spec = Get-Content -LiteralPath $PatchFile -Raw | ConvertFrom-Json
if ($spec.format -ne 1 -or $spec.device -ne 'sky638_5s21') { throw 'Unsupported patch specification.' }
$sourceHash = if ($Mode -eq 'Build') { $spec.originalSha256 } else { $spec.patchedSha256 }
$resultHash = if ($Mode -eq 'Build') { $spec.patchedSha256 } else { $spec.originalSha256 }
if ((Get-Item -LiteralPath $source).Length -ne [long]$spec.bytes) { throw 'Image size does not match this exact backup.' }
if ((Get-CoocaaFileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Image SHA256 does not match; no output was created.' }
$lastEnd = 0L
$ranges = @()
foreach ($entry in ($spec.patches | Sort-Object offset)) {
    $before = [Convert]::FromBase64String($entry.before)
    $after = [Convert]::FromBase64String($entry.after)
    $offset = [long]$entry.offset
    if ($before.Length -ne $after.Length -or $offset -lt $lastEnd -or $offset -lt 0 -or ($offset + $before.Length) -gt [long]$spec.bytes) { throw 'Invalid or overlapping patch range.' }
    $ranges += [pscustomobject]@{Offset=$offset; Before=$before; After=$after}
    $lastEnd = $offset + $before.Length
}
if ($ranges.Count -eq 0) { throw 'Empty patch.' }
$parent = Split-Path -Parent $destination
if (-not (Test-Path -LiteralPath $parent -PathType Container)) { throw 'Output directory does not exist.' }
$temporary = Join-Path $parent ('.coocaa-' + [Guid]::NewGuid().ToString('N') + '.incomplete')
$stream = $null
try {
    [IO.File]::Copy($source,$temporary,$false)
    $stream = [IO.File]::Open($temporary,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    foreach ($range in $ranges) {
        $expected = if ($Mode -eq 'Build') { $range.Before } else { $range.After }
        $replacement = if ($Mode -eq 'Build') { $range.After } else { $range.Before }
        [void]$stream.Seek($range.Offset,[IO.SeekOrigin]::Begin)
        $buffer = New-Object byte[] $expected.Length
        $total = 0
        while ($total -lt $buffer.Length) {
            $read = $stream.Read($buffer,$total,$buffer.Length-$total)
            if ($read -eq 0) { throw 'Unexpected end of image.' }
            $total += $read
        }
        for ($i=0; $i -lt $buffer.Length; $i++) {
            if ($buffer[$i] -ne $expected[$i]) { throw 'Patch bytes differ from expected input.' }
        }
        [void]$stream.Seek($range.Offset,[IO.SeekOrigin]::Begin)
        $stream.Write($replacement,0,$replacement.Length)
    }
    $stream.Flush($true)
    $stream.Dispose(); $stream=$null
    if ((Get-CoocaaFileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $resultHash) { throw 'Result SHA256 verification failed.' }
    [IO.File]::Move($temporary,$destination)
    [pscustomobject]@{File=$destination; SHA256=$resultHash; Status='Local image file verified'; Mode=$Mode}
} finally {
    if ($null -ne $stream) { $stream.Dispose() }
    if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
}
