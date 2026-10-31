# Minimal Android launcher build without Gradle:
#   aapt2 compile -> aapt2 link -> javac -> d8 -> zip dex -> zipalign -> apksigner
# Keep this file ASCII-only: PowerShell 5.1 reads .ps1 as ANSI and breaks on Cyrillic.
param(
    [string]$Sdk = "C:\Android\Sdk",
    [string]$BuildTools = "35.0.0",
    [string]$Platform = "android-34",
    [string]$Jdk = ""
)

$ErrorActionPreference = "Stop"

$root  = $PSScriptRoot
$build = Join-Path $root "build"
$dist  = Join-Path $root "dist"

if (-not $Jdk) {
    $Jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $Jdk) { throw "JDK 17 not found. Pass -Jdk <path>." }

$bt         = Join-Path $Sdk "build-tools\$BuildTools"
$javac      = Join-Path $Jdk "bin\javac.exe"
$keytool    = Join-Path $Jdk "bin\keytool.exe"
$aapt2      = Join-Path $bt  "aapt2.exe"
$d8         = Join-Path $bt  "d8.bat"
$zipalign   = Join-Path $bt  "zipalign.exe"
$apksigner  = Join-Path $bt  "apksigner.bat"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"

foreach ($tool in @($javac, $keytool, $aapt2, $d8, $zipalign, $apksigner, $androidJar)) {
    if (-not (Test-Path $tool)) { throw "Not found: $tool" }
}

$env:JAVA_HOME = $Jdk
$env:PATH = "$($Jdk)\bin;$bt;" + $env:PATH

Write-Host "JDK: $Jdk"
Write-Host "SDK: $Sdk ($BuildTools / $Platform)"

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $build, (Join-Path $build "gen"), (Join-Path $build "classes"), (Join-Path $build "dex") | Out-Null
New-Item -ItemType Directory -Path $dist -Force | Out-Null

Write-Host "1/6 aapt2 compile"
& $aapt2 compile --dir (Join-Path $root "res") -o (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $LASTEXITCODE" }

Write-Host "2/6 aapt2 link"
& $aapt2 link -o (Join-Path $build "base.apk") -I $androidJar --manifest (Join-Path $root "AndroidManifest.xml") `
    -R (Join-Path $build "res.zip") --java (Join-Path $build "gen") `
    --min-sdk-version 23 --target-sdk-version 34 `
    --version-code 1 --version-name "1.0" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

Write-Host "3/6 javac"
$sources  = @(Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem (Join-Path $build "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 --release 8 -classpath $androidJar -d (Join-Path $build "classes") $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Write-Host "4/6 d8"
# d8 takes jars/zips or .class files, not a bare directory.
$classesZip = Join-Path $build "classes.zip"
Compress-Archive -Path (Join-Path $build "classes\*") -DestinationPath $classesZip -Force
& $d8 --lib $androidJar --min-api 23 --output (Join-Path $build "dex") $classesZip
if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

Write-Host "5/6 pack dex + zipalign"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$baseApk = Join-Path $build "base.apk"
$dexFile = Join-Path $build "dex\classes.dex"
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, "Update")
try {
    $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
} finally {
    $zip.Dispose()
}

& $zipalign -f 4 $baseApk (Join-Path $build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

Write-Host "6/6 sign"
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
}

$apk = Join-Path $dist "minilauncher-debug.apk"
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $apk (Join-Path $build "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

& $apksigner verify --print-certs $apk | Select-Object -First 3

$size = (Get-Item $apk).Length
Write-Host ("DONE: {0} ({1:N0} bytes, {2:N1} KB)" -f $apk, $size, ($size / 1KB))
