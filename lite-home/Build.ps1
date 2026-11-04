<#
.SYNOPSIS
Build an APK for local testing with a developer-owned trial signature.
.DESCRIPTION
Each build uses fresh staging directories and verifies its staged APK before
publishing it to OutputApk. KeyStorePath can reuse an existing trial keystore
with the local-test alias and the local-test-only passwords. The default
keystore is generated once in BuildDirectory and reused for later test builds.
Prepare and protect a separate signing key for a formal release.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$BuildTools='35.0.0',
    [string]$Platform='android-36',
    [string]$BuildDirectory='',
    [string]$KeyStorePath='',
    [string]$OutputApk=''
)
$ErrorActionPreference='Stop'

function Run-Checked([string]$exe,[string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

function Get-ApkSha256([string]$path) {
    $stream=[IO.File]::OpenRead($path)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','') }
    finally { $sha.Dispose(); $stream.Dispose() }
}

$jdk=(Resolve-Path -LiteralPath $JavaHome).ProviderPath
$sdk=(Resolve-Path -LiteralPath $AndroidSdk).ProviderPath
$tools=Join-Path $sdk "build-tools\$BuildTools"
$android=Join-Path $sdk "platforms\$Platform\android.jar"
$java=Join-Path $jdk 'bin\java.exe'
$javac=Join-Path $jdk 'bin\javac.exe'
$keytool=Join-Path $jdk 'bin\keytool.exe'
$build=if ($BuildDirectory) {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BuildDirectory)
} else {
    Join-Path $PSScriptRoot 'build'
}
New-Item -ItemType Directory -Force -Path $build | Out-Null
$build=(Resolve-Path -LiteralPath $build).ProviderPath
$apk=if ($OutputApk) {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
} else {
    Join-Path (Split-Path -Parent $PSScriptRoot) 'coocaa-lite-home-test.apk'
}
if ([IO.Path]::GetExtension($apk) -ine '.apk') { throw 'OutputApk must end in .apk.' }
$outputParent=Split-Path -Parent $apk
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "The output directory does not exist: $outputParent"
}
if (Test-Path -LiteralPath $apk) {
    $destination=Get-Item -LiteralPath $apk -Force
    if ($destination.PSIsContainer -or ($destination.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "OutputApk must name a regular file: $apk"
    }
}

$buildId=[Guid]::NewGuid().ToString('N')
$stage=Join-Path $build "run-$buildId"
$classes=Join-Path $stage 'classes'
$dex=Join-Path $stage 'dex'
$unsigned=Join-Path $stage 'unsigned.apk'
# Stage the signed APK beside its destination so publication stays on one volume.
$pending=Join-Path $outputParent ".litehome-$buildId.pending.apk"
$previous=Join-Path $outputParent ".litehome-$buildId.previous.apk"
$lock=[IO.FileStream]::new("$apk.build.lock",[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,
    [IO.FileShare]::None,4096,[IO.FileOptions]::DeleteOnClose)
$published=$false
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    New-Item -ItemType Directory -Path $classes,$dex | Out-Null
    $sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -File -Recurse |
        Sort-Object FullName | ForEach-Object FullName)
    Run-Checked $javac (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$android,'-d',$classes)+$sources)
    $classFiles=@(Get-ChildItem -LiteralPath $classes -Filter '*.class' -File -Recurse |
        Sort-Object FullName | ForEach-Object FullName)
    Run-Checked $java (@('-cp',(Join-Path $tools 'lib\d8.jar'),'com.android.tools.r8.D8','--min-api','21','--lib',$android,'--output',$dex)+$classFiles)
    Run-Checked (Join-Path $tools 'aapt.exe') @('package','-f','-M',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'-I',$android,'-F',$unsigned)
    Push-Location -LiteralPath $dex
    try { Run-Checked (Join-Path $tools 'aapt.exe') @('add',$unsigned,'classes.dex') } finally { Pop-Location }

    if ($KeyStorePath) {
        $key=(Resolve-Path -LiteralPath $KeyStorePath).ProviderPath
        if (-not (Test-Path -LiteralPath $key -PathType Leaf)) { throw 'KeyStorePath must be an existing trial keystore file.' }
    } else {
        $key=Join-Path $build 'local-test.jks'
        if (-not [IO.File]::Exists($key)) {
            $stagedKey=Join-Path $stage 'local-test.jks'
            Run-Checked $keytool @('-genkeypair','-noprompt','-keystore',$stagedKey,'-storepass','local-test-only','-keypass','local-test-only','-alias','local-test','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Coocaa Local Test, O=Local Test, C=CN')
            try { [IO.File]::Move($stagedKey,$key) }
            catch [IO.IOException] {
                # A concurrent first build can publish the shared trial key first.
                if (-not [IO.File]::Exists($key)) { throw }
            }
        }
    }

    $signer=Join-Path $tools 'lib\apksigner.jar'
    Run-Checked $java @('-jar',$signer,'sign','--ks',$key,'--ks-key-alias','local-test','--ks-pass','pass:local-test-only','--key-pass','pass:local-test-only','--v1-signing-enabled','true','--v2-signing-enabled','true','--v4-signing-enabled','false','--out',$pending,$unsigned)
    Run-Checked $java @('-jar',$signer,'verify','--min-sdk-version','21','--verbose','--print-certs',$pending)
    $hash=Get-ApkSha256 $pending

    if ([IO.File]::Exists($apk)) {
        [IO.File]::Replace($pending,$apk,$previous)
    } else {
        [IO.File]::Move($pending,$apk)
    }
    $published=$true
    [pscustomobject]@{Algorithm='SHA256';Hash=$hash;Path=$apk}
} finally {
    try {
        if ([IO.File]::Exists($previous)) {
            if ($published) {
                Remove-Item -LiteralPath $previous -Force
            } elseif (-not [IO.File]::Exists($apk)) {
                [IO.File]::Move($previous,$apk)
            } else {
                Write-Warning "The previous APK is preserved at $previous."
            }
        }
        if ([IO.File]::Exists($pending)) { Remove-Item -LiteralPath $pending -Force }
        if (Test-Path -LiteralPath $stage -PathType Container) {
            $item=Get-Item -LiteralPath $stage
            if ((Split-Path -Parent $item.FullName) -ine $build -or $item.Name -cne "run-$buildId" -or
                ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw "Unexpected staging directory. Inspect $stage before removing it."
            }
            Remove-Item -LiteralPath $item.FullName -Recurse -Force
        }
    } catch {
        Write-Warning "Build cleanup requires attention: $($_.Exception.Message)"
    } finally {
        $lock.Dispose()
    }
}
