<#
.SYNOPSIS
  Local CI: backend unit + emulator suites and the Android checks, with a summary. Exit code 0 only if all pass.
.EXAMPLE
  .\scripts\verify-all.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.10'
#>
param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [switch]$SkipEmulators,
    [switch]$SkipAndroid,
    [switch]$SkipWeb,
    [switch]$Install
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
. (Join-Path $PSScriptRoot 'use-workspace-build.ps1') -JavaHome $JavaHome | Out-Null
$results = [ordered]@{}
$logDir = Join-Path $repo 'build\verify'
New-Item -ItemType Directory -Force $logDir | Out-Null

function Stop-StaleEmulators {
    # `firebase emulators:exec` can leave emulator JVMs bound to these ports on Windows.
    Get-NetTCPConnection -State Listen -LocalPort 9099, 8080, 9000, 5001, 9199, 4400, 4500, 9150 -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$_"
            if ($process.CommandLine -match 'firebase|emulator') { Stop-Process -Id $_ -Force -Confirm:$false }
        }
}

function Step([string]$name, [scriptblock]$body) {
    $log = Join-Path $logDir "$($name -replace '\W', '-').log"
    $started = Get-Date
    & $body *> $log
    $ok = $LASTEXITCODE -eq 0
    $results[$name] = '{0} ({1:N0}s) {2}' -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), ((Get-Date) - $started).TotalSeconds, $log
    if (-not $ok) { Get-Content $log -Tail 25 | Write-Host }
}

if ($Install) { Step 'backend npm ci' { npm --prefix backend ci } }
Step 'backend unit' { npm --prefix backend test }
if (-not $SkipEmulators) {
    Stop-StaleEmulators
    Step 'backend emulators' { npm --prefix backend run test:emulators }
    Stop-StaleEmulators
}
if (-not $SkipWeb) {
    if (-not (Test-Path web/node_modules)) { Step 'web npm ci' { npm --prefix web ci } }
    Step 'web typecheck + tests + build' { npm --prefix web run typecheck; if ($LASTEXITCODE -eq 0) { npm --prefix web test }; if ($LASTEXITCODE -eq 0) { npm --prefix web run build } }
}
if (-not $SkipAndroid) {
    Step 'android checks + build' {
        .\gradlew.bat spotlessCheck testDebugUnitTest verifySqlDelightMigration assembleDebug '-Pdist=local' `
            '-Pinclude-telemetry=false' '-Penable-updater=false' --console=plain
    }
}

Write-Host "`nVerification summary"
$results.GetEnumerator() | ForEach-Object { Write-Host ('  {0,-24} {1}' -f $_.Key, $_.Value) }
if ($results.Values -match '^FAIL') { exit 1 } else { exit 0 }
