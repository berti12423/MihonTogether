<#
.SYNOPSIS
  Tests, then deploys rules, indexes, functions, storage rules and the web companion to the real Firebase project
  described by backend/production.json. Requires `firebase login` once (npx --prefix backend firebase login).
.PARAMETER Only
  Optional firebase --only list, e.g. "firestore:rules,storage". Default: everything.
#>
param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [string]$Only = 'firestore:rules,firestore:indexes,database,storage,functions,hosting',
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$configPath = Join-Path $repo 'backend\production.json'
if (-not (Test-Path $configPath)) { throw 'Create backend/production.json from backend/production.example.json first.' }
$config = Get-Content $configPath -Raw | ConvertFrom-Json
if ($config.projectId -like 'demo-*' -or $config.projectId -eq 'your-project-id') { throw 'production.json still has a placeholder or demo project ID.' }

if (-not $SkipTests) {
    & (Join-Path $PSScriptRoot 'verify-all.ps1') -JavaHome $JavaHome -SkipAndroid
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed; not deploying.' }
}

# Per-project function environment (git-ignored). See docs/FIREBASE_SETUP.md about App Check.
$envFile = Join-Path $repo "backend\.env.$($config.projectId)"
"ENFORCE_APP_CHECK=$(if ($config.enforceAppCheck) { 'true' } else { 'false' })" | Set-Content -NoNewline $envFile

# Optional: publish the APK as a GitHub release asset of `releasesRepo` ("owner/name", needs `gh auth login`), so
# downloads don't count against Hosting traffic. The site keeps its own copy while older apps still use it
# (set "hostApkOnFirebase": false in production.json once they've updated).
$apkDir = Join-Path ([Environment]::GetFolderPath('Desktop')) 'MihonTogether-online'
$apkFile = Join-Path $apkDir 'MihonTogether-online-arm64.apk'
if ($config.releasesRepo -and (Test-Path $apkFile) -and $Only -match 'hosting') {
    $metadata = Get-Content (Join-Path $apkDir 'output-metadata.json') -Raw | ConvertFrom-Json
    $versionName = ($metadata.elements | Where-Object { $_.filters.value -contains 'arm64-v8a' } | Select-Object -First 1).versionName
    $build = ($versionName -split '-')[-1]
    $tag = "build-$build"
    $asset = Join-Path $env:TEMP 'MihonTogether.apk'
    Copy-Item $apkFile $asset -Force
    gh release view $tag --repo $config.releasesRepo *> $null
    if ($LASTEXITCODE -ne 0) {
        $notes = Join-Path $env:TEMP 'mihon-release-notes.md'
        # The newest WHATS_NEW section is the release text.
        ((Get-Content (Join-Path $repo 'docs\WHATS_NEW.md') -Raw) -split '(?m)^## ')[1] -replace '^[^\n]*\n', '' | Set-Content $notes
        gh release create $tag $asset --repo $config.releasesRepo --title "Mihon Together $versionName" --notes-file $notes
        if ($LASTEXITCODE -ne 0) { throw 'GitHub release failed; not deploying.' }
    }
    $env:MIHON_WEB_APK_URL = "https://github.com/$($config.releasesRepo)/releases/download/$tag/MihonTogether.apk"
    if ($config.hostApkOnFirebase -eq $false) { $env:MIHON_WEB_HOST_APK = '0' }
}

# Web companion against the real project.
$env:MIHON_WEB_FIREBASE_CONFIG = ($config.web | ConvertTo-Json -Compress)
npm --prefix web run build
if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
Remove-Item Env:MIHON_WEB_FIREBASE_CONFIG
Remove-Item Env:MIHON_WEB_APK_URL, Env:MIHON_WEB_HOST_APK -ErrorAction SilentlyContinue

npm --prefix backend run build
Push-Location backend
try {
    npx firebase deploy --project $config.projectId --only $Only --non-interactive
    if ($LASTEXITCODE -ne 0) { throw 'firebase deploy failed.' }
} finally { Pop-Location }
Write-Host "Deployed to $($config.projectId). Web: https://$($config.projectId).web.app"
