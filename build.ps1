# Clean, verified build of the module APK.
#   .\build.ps1
# Fixes the stale-APK problem: valid JAVA_HOME, clean build, and verification that the
# APK really contains the build stamp of this run. Output: dist\WhatsAppCompleteMute-<stamp>.apk
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 1. JDK: the JAVA_HOME env var on this machine can point to a folder that does not exist.
function Test-Jdk($p) { return ($p -and (Test-Path (Join-Path $p 'bin\java.exe'))) }
if (-not (Test-Jdk $env:JAVA_HOME)) {
    $candidates = @()
    $candidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName
    $candidates += Get-ChildItem 'C:\Program Files\Android\Android Studio*' -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'jbr' }
    $candidates += Get-ChildItem 'C:\Program Files\Java' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | ForEach-Object FullName
    $found = $candidates | Where-Object { Test-Jdk $_ } | Select-Object -First 1
    if (-not $found) { throw 'No valid JDK 17 found. Install JDK 17 or fix JAVA_HOME.' }
    Write-Host "JAVA_HOME was invalid ('$env:JAVA_HOME'); using $found"
    $env:JAVA_HOME = $found
}

# 2. Clean build (no stale outputs)
Push-Location $root
try {
    & "$root\gradlew.bat" clean assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

# 3. Verify the APK contains this build's stamp
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
$cfg = Get-ChildItem (Join-Path $root 'app\build\generated\source\buildConfig') -Recurse -Filter BuildConfig.java | Select-Object -First 1
$stamp = [regex]::Match((Get-Content $cfg.FullName -Raw), 'BUILD_STAMP = "([^"]+)"').Groups[1].Value
if (-not $stamp) { throw 'BUILD_STAMP not found in generated BuildConfig' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
$hit = $false
try {
    foreach ($e in $zip.Entries | Where-Object { $_.Name -like 'classes*.dex' }) {
        $ms = New-Object System.IO.MemoryStream
        $s = $e.Open(); $s.CopyTo($ms); $s.Close()
        if ([System.Text.Encoding]::ASCII.GetString($ms.ToArray()).Contains($stamp)) { $hit = $true; break }
    }
} finally { $zip.Dispose() }
if (-not $hit) { throw "APK does not contain build stamp $stamp - not built from current source" }

# 4. Publish with a unique name
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$ver = [regex]::Match((Get-Content (Join-Path $root 'app\build.gradle.kts') -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$out = Join-Path $dist "Eliav-WA-Modul-v$ver.apk"
# remove older versions to avoid confusion
Get-ChildItem $dist -Filter "Eliav-WA-Modul-v*.apk" | Where-Object { $_.Name -ne (Split-Path $out -Leaf) } | Remove-Item -Force
Copy-Item $apk $out -Force
Write-Host ''
Write-Host "BUILD OK  stamp=$stamp"
Write-Host "APK: $out"
Write-Host 'Next: install this APK on the phone, then RE-PATCH WhatsApp with LSPatch using this module.'
Write-Host 'The module embedded in an already-patched WhatsApp is a copy and is NOT updated by a new build.'
Write-Host "Verify on the phone: the module app screen must show 'build $stamp'."
