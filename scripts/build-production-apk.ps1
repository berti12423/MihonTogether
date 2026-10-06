<#
.SYNOPSIS
  Builds the friends test APK against the real Firebase project in backend/production.json: the "preview" build
  type (R8-minified like a release, about 40% of the debug size) with the debug app ID dev.mihontogether.reader.dev
  and signing key, so it installs next to Mihon and updates earlier test builds. Copies it to the Desktop.
#>
param([string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$config = Get-Content (Join-Path $repo 'backend\production.json') -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'use-workspace-build.ps1') -JavaHome $JavaHome | Out-Null
.\gradlew.bat assemblePreview '-Pdist=local' '-Pinclude-telemetry=false' '-Penable-updater=false' `
    '-Psocial-environment=production' "-Psocial-project-id=$($config.projectId)" `
    "-Psocial-api-key=$($config.android.apiKey)" "-Psocial-app-id=$($config.android.appId)" `
    "-Psocial-database-url=$($config.databaseUrl)" "-Psocial-storage-bucket=$($config.storageBucket)" `
    "-Psocial-web-url=https://$($config.projectId).web.app" --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
$out = Join-Path ([Environment]::GetFolderPath('Desktop')) 'MihonTogether-online'
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item app\build\outputs\apk\preview\app-arm64-v8a-preview.apk (Join-Path $out 'MihonTogether-online-arm64.apk') -Force
Copy-Item app\build\outputs\apk\preview\app-universal-preview.apk (Join-Path $out 'MihonTogether-online-universal.apk') -Force
# Version for the website's update check (apk.json): versionName ends in the build number (commit count).
Copy-Item app\build\outputs\apk\preview\output-metadata.json (Join-Path $out 'output-metadata.json') -Force
Get-ChildItem $out -Filter *.apk | ForEach-Object { '{0}  {1:N1} MB  {2}' -f $_.Name, ($_.Length / 1MB), (Get-FileHash $_.FullName).Hash }
