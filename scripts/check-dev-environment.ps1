[CmdletBinding()]
param(
    [string]$SdkPath,
    [switch]$AsJson
)

$ErrorActionPreference = 'Stop'
$taskRepoRoot = Split-Path -Parent $PSScriptRoot
$taskChecks = [System.Collections.Generic.List[object]]::new()

function Add-TaskCheck {
    param([string]$Name, [bool]$Passed, [string]$Detail, [bool]$NativeBuildRequired = $true)
    $taskChecks.Add([pscustomobject]@{
        name = $Name
        passed = $Passed
        detail = $Detail
        nativeBuildRequired = $NativeBuildRequired
    })
}

function Get-TaskToolVersion {
    param([string]$Name, [string]$VersionArgument)
    $ErrorActionPreference = 'Continue'
    $taskCommand = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $taskCommand) { return $null }
    $taskVersionOutput = & $taskCommand.Source $VersionArgument 2>&1
    return (($taskVersionOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
}

function Read-TaskCatalogVersion {
    param([string]$Key)
    $taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepoRoot 'gradle/mihon.versions.toml') -Raw
    $taskMatch = [regex]::Match($taskCatalog, '(?m)^' + [regex]::Escape($Key) + '\s*=\s*"([^"]+)"')
    if (-not $taskMatch.Success) { throw "Version not found in catalog: $Key" }
    return $taskMatch.Groups[1].Value
}

$taskExpectedJava = (Get-Content -LiteralPath (Join-Path $taskRepoRoot '.github/.java-version') -Raw).Trim()
if ($env:JAVA_HOME) {
    $taskJavaHome = $env:JAVA_HOME.Replace('"', '')
    $taskJava = Get-TaskToolVersion (Join-Path $taskJavaHome 'bin\java.exe') '-version'
    $taskJavac = Get-TaskToolVersion (Join-Path $taskJavaHome 'bin\javac.exe') '-version'
    $taskJavaSelection = "JAVA_HOME=$taskJavaHome"
} else {
    $taskJava = Get-TaskToolVersion 'java' '-version'
    $taskJavac = Get-TaskToolVersion 'javac' '-version'
    $taskJavaSelection = 'PATH (JAVA_HOME is unset)'
}
$taskJavaPassed = $taskJava -match ('version "' + [regex]::Escape($taskExpectedJava) + '(?:\.|\")')
$taskJavacPassed = $taskJavac -match ('javac ' + [regex]::Escape($taskExpectedJava) + '(?:\.|$)')
Add-TaskCheck 'JDK runtime' $taskJavaPassed $(if ($taskJava) { "$taskJavaSelection; $taskJava" } else { "$taskJavaSelection; java executable not found" })
Add-TaskCheck 'JDK compiler' $taskJavacPassed $(if ($taskJavac) { "$taskJavaSelection; $taskJavac" } else { "$taskJavaSelection; javac executable not found" })
$taskGit = Get-TaskToolVersion 'git' '--version'
Add-TaskCheck 'Git' ([bool]$taskGit) $(if ($taskGit) { $taskGit } else { 'git not found on PATH' })
$taskNode = Get-TaskToolVersion 'node' '--version'
$taskNodePassed = $taskNode -match '^v(\d+)' -and [int]$Matches[1] -ge 20
Add-TaskCheck 'Node for Firebase tooling' $taskNodePassed $(if ($taskNode) { $taskNode } else { 'node not found on PATH' }) $false

$taskCompileSdk = Read-TaskCatalogVersion 'android-sdk-compile'
$taskNdk = Read-TaskCatalogVersion 'android-ndk'
$taskConfiguredPath = $null
$taskLocalProperties = Join-Path $taskRepoRoot 'local.properties'
if (Test-Path -LiteralPath $taskLocalProperties) {
    $taskSdkLine = Get-Content -LiteralPath $taskLocalProperties | Where-Object { $_ -match '^sdk\.dir\s*=' } | Select-Object -First 1
    if ($taskSdkLine) {
        $taskConfiguredPath = ($taskSdkLine -replace '^sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\')
    }
}
$taskCandidates = [System.Collections.Generic.List[string]]::new()
$taskSdkSelection = 'No explicit SDK configuration'
if ($SdkPath) {
    $taskCandidates.Add($SdkPath)
    $taskSdkSelection = 'Explicit -SdkPath override; configure sdk.dir or ANDROID_HOME before Gradle'
} elseif ($taskConfiguredPath) {
    $taskCandidates.Add($taskConfiguredPath)
    $taskSdkSelection = 'local.properties sdk.dir'
} elseif ($env:ANDROID_HOME) {
    $taskCandidates.Add($env:ANDROID_HOME)
    $taskSdkSelection = 'ANDROID_HOME'
} elseif ($env:ANDROID_SDK_ROOT) {
    $taskCandidates.Add($env:ANDROID_SDK_ROOT)
    $taskSdkSelection = 'ANDROID_SDK_ROOT'
} elseif ($env:LOCALAPPDATA) {
    $taskCandidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    $taskSdkSelection = 'Usual SDK location; configure sdk.dir or ANDROID_HOME before Gradle'
}
$taskResolvedSdk = $null
foreach ($taskCandidate in ($taskCandidates | Select-Object -Unique)) {
    if (Test-Path -LiteralPath $taskCandidate -PathType Container) {
        $taskResolvedSdk = (Resolve-Path -LiteralPath $taskCandidate).Path
        break
    }
}

Add-TaskCheck 'Android SDK' ([bool]$taskResolvedSdk) $(if ($taskResolvedSdk) { "$taskSdkSelection; $taskResolvedSdk" } else { "$taskSdkSelection; no SDK found, install Android Studio/SDK or pass -SdkPath" })
if ($taskResolvedSdk) {
    $taskPlatformPath = Join-Path $taskResolvedSdk "platforms\android-$taskCompileSdk\android.jar"
    $taskNdkPath = Join-Path $taskResolvedSdk "ndk\$taskNdk\source.properties"
    $taskAdbPath = Join-Path $taskResolvedSdk 'platform-tools\adb.exe'
    Add-TaskCheck "Compile SDK $taskCompileSdk" (Test-Path -LiteralPath $taskPlatformPath) $taskPlatformPath
    Add-TaskCheck "NDK $taskNdk" (Test-Path -LiteralPath $taskNdkPath) $taskNdkPath
    Add-TaskCheck 'ADB for device tests' (Test-Path -LiteralPath $taskAdbPath) $taskAdbPath $false
} else {
    Add-TaskCheck "Compile SDK $taskCompileSdk" $false "Required by gradle/mihon.versions.toml; SDK package platforms;android-$taskCompileSdk"
    Add-TaskCheck "NDK $taskNdk" $false "Required by gradle/mihon.versions.toml; SDK package ndk;$taskNdk"
    Add-TaskCheck 'ADB for device tests' $false 'Install Android SDK platform-tools' $false
}

$taskNativeFailures = @($taskChecks | Where-Object { $_.nativeBuildRequired -and -not $_.passed })
$taskReport = [pscustomobject]@{
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    repository = $taskRepoRoot
    expectedJava = $taskExpectedJava
    javaSelection = $taskJavaSelection
    sdkSelection = $taskSdkSelection
    requiredCompileSdk = $taskCompileSdk
    requiredNdk = $taskNdk
    nativeBuildPrerequisitesReady = ($taskNativeFailures.Count -eq 0)
    buildExecuted = $false
    note = 'Read-only prerequisite check. Does not prove dependency resolution, SDK licenses, compilation, or emulator readiness.'
    checks = $taskChecks.ToArray()
}

if ($AsJson) {
    $taskReport | ConvertTo-Json -Depth 5
} else {
    $taskChecks | Format-Table name, passed, detail -Wrap -AutoSize
    Write-Output "Native prerequisite check ready: $($taskReport.nativeBuildPrerequisitesReady). An Android build was not run."
}
if ($taskNativeFailures.Count -gt 0) { exit 2 }
exit 0
