# Builds MihonTogether-Translator.exe (release) and copies it to dist\.
# Prerequisites: Rust (MSVC toolchain), LLVM/libclang, Visual Studio 2022 Build Tools.
param(
    [string]$LlvmBin = 'C:\Program Files\LLVM\bin'
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
if (Test-Path $cargoBin) { $env:PATH = "$cargoBin;$env:PATH" }
if (Test-Path $LlvmBin) {
    $env:PATH = "$LlvmBin;$env:PATH"
    if (-not $env:LIBCLANG_PATH) { $env:LIBCLANG_PATH = $LlvmBin }
}

if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw 'cargo not found. Install Rust from https://rustup.rs (MSVC toolchain).'
}

Push-Location $here
try {
    cargo build --release --locked
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed ($LASTEXITCODE)" }

    $dist = Join-Path $here 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    $exe = Join-Path $here 'target\release\MihonTogether-Translator.exe'
    Copy-Item $exe $dist -Force
    Copy-Item (Join-Path $here 'README.md') $dist -Force
    $size = [math]::Round((Get-Item (Join-Path $dist 'MihonTogether-Translator.exe')).Length / 1MB, 1)
    Write-Host "Built dist\MihonTogether-Translator.exe ($size MB)"
}
finally {
    Pop-Location
}
