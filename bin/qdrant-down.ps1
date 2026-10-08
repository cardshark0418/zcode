#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "[zcode] stopping Qdrant..."
docker compose down
if ($LASTEXITCODE -ne 0) {
    throw "docker compose down failed"
}
Write-Host "[zcode] Qdrant stopped (data kept under data/qdrant/)"
