#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "[zcode] starting Qdrant via docker compose..."
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    throw "docker compose up failed — is Docker Desktop running?"
}

Write-Host "[zcode] waiting for http://127.0.0.1:6333 ..."
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:6333/" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) {
            $ok = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $ok) {
    Write-Warning "Qdrant container started but health check timed out. Check: docker compose logs qdrant"
    exit 1
}

Write-Host "[zcode] Qdrant ready — http://127.0.0.1:6333  (dashboard: /dashboard)"
Write-Host "[zcode] stop with:  docker compose down"
