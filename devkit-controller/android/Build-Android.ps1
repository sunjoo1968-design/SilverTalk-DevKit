param(
    [string]$AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (!$AndroidSdk) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$platform = Join-Path $AndroidSdk 'platforms/android-36/android.jar'
$tools = Join-Path $AndroidSdk 'build-tools/35.0.0'
$aapt = Join-Path $tools 'aapt.exe'
$zipalign = Join-Path $tools 'zipalign.exe'
$d8 = Join-Path $tools 'lib/d8.jar'
foreach ($path in @($platform, $aapt, $zipalign, $d8)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing SDK file $path" }
}

$build = Join-Path $PSScriptRoot 'build'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('silvertalk-build-' + [guid]::NewGuid())
$classes = Join-Path $temp 'classes'
$dex = Join-Path $temp 'dex'
$jar = Join-Path $temp 'classes.jar'
$output = Join-Path $build 'SilverTalk-DevKit-unsigned.apk'
$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$resources = Join-Path $PSScriptRoot 'res'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File | ForEach-Object FullName)
New-Item -ItemType Directory -Force -Path $classes, $dex, $build | Out-Null

try {
    javac -encoding UTF-8 --release 8 -cp $platform -d $classes @sources
    if ($LASTEXITCODE) { throw 'Java compilation failed.' }
    jar cf $jar -C $classes .
    if ($LASTEXITCODE) { throw 'Class archive creation failed.' }
    java -cp $d8 com.android.tools.r8.D8 --lib $platform --min-api 26 --output $dex $jar
    if ($LASTEXITCODE) { throw 'DEX compilation failed.' }
    # Native build tools can misread Windows paths containing non-ASCII characters.
    Copy-Item -LiteralPath $manifest, $platform, $resources -Destination $temp -Recurse
    Copy-Item -LiteralPath (Join-Path $dex 'classes.dex') -Destination $temp
    Push-Location -LiteralPath $temp
    try {
        & $aapt package -f -M AndroidManifest.xml -I android.jar -S res -F unsigned.apk
        if ($LASTEXITCODE) { throw 'APK resource packaging failed.' }
        & $aapt add unsigned.apk classes.dex
        if ($LASTEXITCODE) { throw 'Adding classes.dex failed.' }
        & $zipalign -f 4 unsigned.apk aligned.apk
        if ($LASTEXITCODE) { throw 'APK alignment failed.' }
    } finally {
        Pop-Location
    }
    Move-Item -LiteralPath (Join-Path $temp 'aligned.apk') -Destination $output -Force
    Write-Output $output
} finally {
    $resolved = Get-Item -LiteralPath $temp -Force
    if ($resolved.FullName -eq $temp -and !($resolved.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $resolved.FullName -Recurse -Force
    }
}
