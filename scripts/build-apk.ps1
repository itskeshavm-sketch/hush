<#
.SYNOPSIS
  Build the HUSH Android app and place the APK back in the repo.

.DESCRIPTION
  Gradle's Android tooling on Windows is unreliable when the project path
  contains a space ("C:\Users\...\Default Project\android" fails with
  "The filename, directory name, or volume label syntax is incorrect" while
  resolving the SDK). The repo therefore lives where the user wants it, and the
  build runs from a mirror at C:\hushbuild\android.

  This script is the fix, not a workaround: it copies sources, builds, and
  copies the APK back to the repo's dist/ directory.

.EXAMPLE
  .\build-apk.ps1 -Task assembleDebug
#>
[CmdletBinding()]
param(
    [ValidateSet('assembleDebug', 'assembleRelease', 'testDebugUnitTest', 'lint')]
    [string]$Task = 'assembleDebug'
)

$ErrorActionPreference = 'Stop'

$Toolchain = 'C:\Users\Dell\AppData\Local\Temp\opencode\toolchain'
$Repo      = Join-Path $PSScriptRoot '..'
$Mirror    = 'C:\hushbuild'

$env:JAVA_HOME         = Join-Path $Toolchain 'jdk'
$env:ANDROID_HOME      = Join-Path $Toolchain 'android-sdk'
$env:ANDROID_SDK_ROOT  = $env:ANDROID_HOME
$env:GRADLE_USER_HOME  = Join-Path $Toolchain 'gradle-home'
$env:PATH              = "$($env:JAVA_HOME)\bin;$env:PATH"

$Gradle = Join-Path $Toolchain 'gradle-8.7\bin\gradle.bat'

Write-Host "toolchain : $Toolchain" -ForegroundColor DarkGray
Write-Host "repo      : $Repo"       -ForegroundColor DarkGray
Write-Host "mirror    : $Mirror"     -ForegroundColor DarkGray
Write-Host "task      : $Task"       -ForegroundColor Cyan

# --- sync sources to the space-free mirror ---------------------------------
#
# Sources are mirrored with robocopy /MIR, but the mirror's build/ directory is
# excluded so Gradle keeps its incremental state. Deleting the whole mirror on
# every run (the obvious approach) forces a full recompile each time, which on
# a cold machine is several minutes of pure waste.
New-Item -ItemType Directory -Force -Path $Mirror | Out-Null

foreach ($item in @('android', 'LICENSE', 'README.md')) {
    $src = Join-Path $Repo $item
    if (-not (Test-Path $src)) { continue }
    $dst = Join-Path $Mirror $item

    if ((Get-Item $src).PSIsContainer) {
        # /MIR mirrors deletions too, so a removed source file disappears from
        # the mirror instead of lingering as a stale compile input.
        & robocopy $src $dst /MIR /NFL /NDL /NJH /NJS /NP /XD build .gradle | Out-Null
        if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $item (exit $LASTEXITCODE)" }
    } else {
        Copy-Item -Force $src $dst
    }
}

# local.properties must point at the SDK from the mirror's perspective.
$sdkDir = $env:ANDROID_HOME -replace '\\', '\\'
Set-Content -Path (Join-Path $Mirror 'android\local.properties') -Value "sdk.dir=$sdkDir" -Encoding ASCII

# --- build -----------------------------------------------------------------
Push-Location (Join-Path $Mirror 'android')
try {
    & $Gradle --no-daemon $Task --console=plain
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host "`nBUILD FAILED ($code)" -ForegroundColor Red
    exit $code
}

# --- collect artefacts ------------------------------------------------------
$dist = Join-Path $Repo 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

$apkRoot = Join-Path $Mirror 'android\app\build\outputs\apk'
if (Test-Path $apkRoot) {
    $apks = Get-ChildItem -Recurse -Filter '*.apk' $apkRoot
    foreach ($apk in $apks) {
        $target = Join-Path $dist $apk.Name
        Copy-Item -Force $apk.FullName $target
        $sizeMb = [math]::Round($apk.Length / 1MB, 2)
        Write-Host "apk       : $target  ($sizeMb MB)" -ForegroundColor Green
    }
    if ($apks.Count -eq 0) {
        Write-Host "no APK produced for task '$Task'" -ForegroundColor Yellow
    }
}

$testXml = Join-Path $Mirror 'android\app\build\test-results\testDebugUnitTest'
if (Test-Path $testXml) {
    $results = Join-Path $Repo 'dist\test-results'
    New-Item -ItemType Directory -Force -Path $results | Out-Null
    Copy-Item -Force (Join-Path $testXml '*.xml') $results -ErrorAction SilentlyContinue

    [xml]$summary = (Get-Content (Join-Path $testXml 'TEST-dev.hush.chat.crypto.DoubleRatchetTest.xml') -Raw)
    $s = $summary.testsuite
    Write-Host ("tests     : {0} run, {1} failures, {2} errors" -f $s.tests, $s.failures, $s.errors) -ForegroundColor Green
}

Write-Host "`nOK" -ForegroundColor Green