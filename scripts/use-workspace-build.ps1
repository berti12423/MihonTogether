[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$WorkRoot
)

$ErrorActionPreference = 'Stop'
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
if (-not (Test-Path -LiteralPath (Join-Path $taskSdkRoot 'platforms/android-37.2/android.jar'))) {
    throw "Install Android SDK platform 37.2 in $taskSdkRoot first, or pass -WorkRoot for your existing workspace SDK."
}

if (-not $JavaHome) {
    if ($env:JAVA_HOME) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $taskJava = Get-Command 'java' -ErrorAction Stop
        $taskJavaSettings = & $taskJava.Source '-XshowSettings:properties' '-version' 2>&1
        $taskJavaHomeLine = $taskJavaSettings | Where-Object { $_.ToString() -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if ($taskJavaHomeLine) {
            $JavaHome = ($taskJavaHomeLine.ToString() -replace '^\s*java\.home\s*=\s*', '').Trim()
        }
    }
}
if (-not $JavaHome) { throw 'JDK 21 could not be selected. Pass -JavaHome with its installation directory.' }
$taskJavaExecutable = Join-Path $JavaHome 'bin/java.exe'
$taskJavaVersion = (& $taskJavaExecutable '-version' 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
if ($taskJavaVersion -notmatch 'version "21(?:\.|\")') { throw "JDK 21 is required: $taskJavaVersion" }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) { throw 'The selected Java installation is missing javac.' }

$env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
$env:ANDROID_HOME = $taskSdkRoot
$env:ANDROID_SDK_ROOT = $taskSdkRoot
$env:ANDROID_USER_HOME = Join-Path $taskWorkRoot 'android-user-home'
$env:GRADLE_USER_HOME = Join-Path $taskWorkRoot 'gradle-home'
foreach ($taskDirectory in @($env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME)) {
    New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
}

$taskLocalProperties = Join-Path $taskRepoRoot 'local.properties'
$taskSdkProperty = 'sdk.dir=' + $taskSdkRoot.Replace('\', '/')
$taskExistingProperties = @()
if (Test-Path -LiteralPath $taskLocalProperties) {
    $taskExistingProperties = @(Get-Content -LiteralPath $taskLocalProperties | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' })
}
Set-Content -LiteralPath $taskLocalProperties -Value @($taskExistingProperties + $taskSdkProperty) -Encoding ascii

Write-Output "JDK: $env:JAVA_HOME"
Write-Output "SDK: $env:ANDROID_HOME"
Write-Output "Gradle cache: $env:GRADLE_USER_HOME"
Write-Output 'Environment changes apply only to this PowerShell process. Use the Gradle wrapper from the repository root.'
