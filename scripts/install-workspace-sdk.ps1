[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [switch]$AcceptAndroidSdkLicense,
    [string]$JavaHome,
    [string]$WorkRoot
)

$ErrorActionPreference = 'Stop'
if (-not $AcceptAndroidSdkLicense) {
    throw 'Read https://developer.android.com/studio/terms and pass -AcceptAndroidSdkLicense only if you accept the Android SDK License Agreement.'
}
$taskRepoRoot = Split-Path -Parent $PSScriptRoot
if (-not $WorkRoot) { $WorkRoot = $env:MIHON_WORK_ROOT }
if (-not $WorkRoot) {
    # Machine-specific location, kept out of git in local.properties (mihon.workRoot=...).
    $localProperties = Join-Path $taskRepoRoot 'local.properties'
    if (Test-Path $localProperties) {
        $line = Select-String -Path $localProperties -Pattern '^\s*mihon\.workRoot\s*=\s*(.+)$' | Select-Object -First 1
        if ($line) { $WorkRoot = $line.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $WorkRoot) {
    $WorkRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $taskRepoRoot)) 'work'
}
$taskWorkRoot = [IO.Path]::GetFullPath($WorkRoot)
$taskSdkRoot = Join-Path $taskWorkRoot 'android-sdk'
$taskDownloads = Join-Path $taskWorkRoot 'android-build'
New-Item -ItemType Directory -Path $taskDownloads, $taskSdkRoot -Force | Out-Null
if ($JavaHome) { $env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome) }
$env:ANDROID_USER_HOME = Join-Path $taskWorkRoot 'android-user-home'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null

# Google's repository2-3.xml identified this Windows archive and SHA-1 on 2026-10-01.
$taskArchive = Join-Path $taskDownloads 'commandlinetools-win-16111833_latest.zip'
$taskChecksum = '57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d'
if (-not (Test-Path -LiteralPath $taskArchive)) {
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' -OutFile $taskArchive
}
$taskActualChecksum = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA1).Hash.ToLowerInvariant()
if ($taskActualChecksum -ne $taskChecksum) { throw "Android command-line tools checksum mismatch: $taskActualChecksum" }

$taskLatest = Join-Path $taskSdkRoot 'cmdline-tools/latest'
$taskAndroidCli = Join-Path $taskLatest 'bin/android.exe'
if (-not (Test-Path -LiteralPath $taskAndroidCli)) {
    $taskStage = Join-Path $taskDownloads 'commandlinetools-16111833'
    if (-not (Test-Path -LiteralPath (Join-Path $taskStage 'cmdline-tools/bin/android.exe'))) {
        Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskStage -Force
    }
    New-Item -ItemType Directory -Path $taskLatest -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $taskStage 'cmdline-tools') | Copy-Item -Destination $taskLatest -Recurse -Force
}

# Command-line tools 23 use Android CLI package IDs with slashes. The deprecated
# sdkmanager wrapper splits semicolon IDs and can return zero without installing them.
& $taskAndroidCli '--no-metrics' "--sdk=$taskSdkRoot" 'sdk' 'install' 'platforms/android-37.2' 'build-tools/36.0.0' 'build-tools/37.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw "Android CLI installation failed: $LASTEXITCODE" }
foreach ($taskRequiredFile in @('platforms/android-37.2/android.jar', 'build-tools/36.0.0/aapt2.exe', 'build-tools/37.0.0/aapt2.exe', 'platform-tools/adb.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $taskSdkRoot $taskRequiredFile))) { throw "Required SDK file is missing: $taskRequiredFile" }
}

Write-Output "Workspace Android SDK installed: $taskSdkRoot"
Write-Output 'NDK and Android emulator images are not installed by this script; install them when a build or device test requires them.'
