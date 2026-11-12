param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,
    [Parameter(Mandatory = $true)]
    [string]$JavaArgumentsFile,
    [string]$BridgeDir = "$PSScriptRoot\build\Release",
    [string]$RuntimeDir = "C:\dev\DLSS\lib\Windows_x86_64\rel",
    [string]$JavaExecutable = "java"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectId)) {
    throw "An authorized NVIDIA NGX project ID is required."
}
if (-not (Test-Path -LiteralPath $BridgeDir -PathType Container)) {
    throw "Bridge directory does not exist: $BridgeDir"
}
if (-not (Test-Path -LiteralPath $RuntimeDir -PathType Container)) {
    throw "NGX runtime directory does not exist: $RuntimeDir"
}
if (-not (Test-Path -LiteralPath $JavaArgumentsFile -PathType Leaf)) {
    throw "Java argument file does not exist: $JavaArgumentsFile"
}

$env:SUPERRESOLUTION_NGX_PROJECT_ID = $ProjectId
$env:PATH = "$BridgeDir;$RuntimeDir;$env:PATH"
$arguments = Get-Content -LiteralPath $JavaArgumentsFile

& $JavaExecutable @arguments
exit $LASTEXITCODE
