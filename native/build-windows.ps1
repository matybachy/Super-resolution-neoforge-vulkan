param(
    [string]$DlssSdkDir = "C:\dev\DLSS",
    [string]$BuildDir = "$PSScriptRoot\build"
)

$ErrorActionPreference = "Stop"

$includeDir = Join-Path $DlssSdkDir "include"
$importDir = Join-Path $DlssSdkDir "lib\Windows_x86_64\x64"
$runtimeDir = Join-Path $DlssSdkDir "lib\Windows_x86_64\rel"

foreach ($path in @($includeDir, $importDir, $runtimeDir)) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        throw "DLSS SDK directory is incomplete: $path"
    }
}

cmake -S $PSScriptRoot -B $BuildDir -DDLSS_SDK_DIR=$DlssSdkDir
cmake --build $BuildDir --config Release

Write-Host ""
Write-Host "Bridge built under: $BuildDir"
Write-Host "Add this directory to PATH for NGX runtime DLLs: $runtimeDir"
Write-Host "Put the built superresolution_dlss.dll on java.library.path."
