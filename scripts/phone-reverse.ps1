<#
.SYNOPSIS
  Lets USB-connected phones reach the local Firebase emulators (and the web companion) on this PC.
  Use with an APK built with -Psocial-emulator-host=127.0.0.1. Re-run after replugging a phone.
#>
param([string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10')
. (Join-Path $PSScriptRoot 'use-workspace-build.ps1') -JavaHome $JavaHome | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
# Auth, Firestore, Realtime Database, Functions, Storage, web companion
$ports = 9099, 8080, 9000, 5001, 9199, 5050
$devices = & $adb devices | Select-String '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value }
if (-not $devices) { Write-Host 'No phone found. Enable USB debugging and accept the prompt on the phone.'; exit 1 }
foreach ($device in $devices) {
    foreach ($port in $ports) { & $adb -s $device reverse "tcp:$port" "tcp:$port" | Out-Null }
    Write-Host "$device -> ports $($ports -join ', ') forwarded"
}
