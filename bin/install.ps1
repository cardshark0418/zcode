#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $ProjectRoot "pom.xml"))) {
    $ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
    $ProjectRoot = Split-Path -Parent $ProjectRoot
}

$Mvn = "D:\aZhangChen\apache-maven-3.9.9\bin\mvn.cmd"
if (-not (Test-Path $Mvn)) {
    $Mvn = "mvn"
}

# Runtime data + jar stay under the project
$InstallDir = Join-Path $ProjectRoot ".zcode"
$BinDir = Join-Path $ProjectRoot "bin"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir "skills") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir "sessions") | Out-Null

Write-Host "[zcode] building fat jar..."
Push-Location $ProjectRoot
try {
    & $Mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "maven package failed" }
} finally {
    Pop-Location
}

$JarSrc = Get-ChildItem (Join-Path $ProjectRoot "target\zcode-*.jar") |
    Where-Object { $_.Name -notmatch "original" } |
    Select-Object -First 1
if (-not $JarSrc) { throw "built jar not found under target/" }

Copy-Item $JarSrc.FullName (Join-Path $InstallDir "zcode.jar") -Force

# Migrate llm.json from old user-home install if project has none yet
$legacyHome = Join-Path $env:USERPROFILE ".zcode"
$legacyLlm = Join-Path $legacyHome "llm.json"
$projectLlm = Join-Path $InstallDir "llm.json"
if ((Test-Path $legacyLlm) -and -not (Test-Path $projectLlm)) {
    Copy-Item $legacyLlm $projectLlm -Force
    Write-Host "[zcode] copied llm.json from legacy %USERPROFILE%\.zcode"
}

# Ensure project bin is on User PATH so `zcode` / `zcode serve` work from any cwd
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not $userPath) { $userPath = "" }
$parts = [System.Collections.Generic.List[string]]::new()
foreach ($p in $userPath.Split(";", [System.StringSplitOptions]::RemoveEmptyEntries)) {
    $norm = $p.TrimEnd('\')
    # drop legacy data dir from PATH (jar no longer lives there)
    if ($norm -eq $legacyHome -or $norm -eq "$env:USERPROFILE\.zcode") {
        continue
    }
    if (-not $parts.Contains($norm)) {
        $parts.Add($norm)
    }
}
if (-not $parts.Contains($BinDir)) {
    $parts.Insert(0, $BinDir)
    Write-Host "[zcode] added to User PATH: $BinDir"
} else {
    # keep bin early so it wins over any leftover shims
    $parts.Remove($BinDir) | Out-Null
    $parts.Insert(0, $BinDir)
    Write-Host "[zcode] project bin already on User PATH (moved to front)"
}
[Environment]::SetEnvironmentVariable("Path", ($parts -join ";"), "User")
$env:Path = "$BinDir;" + ($env:Path -replace [regex]::Escape($BinDir) + ";?", "")

# Replace legacy launcher with a forwarder (no jar/data in user profile)
if (Test-Path $legacyHome) {
    $forward = @"
@echo off
rem Thin shim — data/jar live under the zcode project, not %USERPROFILE%\.zcode
call "$BinDir\zcode.cmd" %*
"@
    Set-Content -Path (Join-Path $legacyHome "zcode.cmd") -Value $forward -Encoding ASCII
    Write-Host "[zcode] legacy %USERPROFILE%\.zcode\zcode.cmd -> forwards to project bin"
}

Write-Host ""
Write-Host "[zcode] installed -> $InstallDir"
Write-Host "[zcode] open a NEW terminal, then run:  zcode"
Write-Host "[zcode] optional web UI:               zcode serve"
Write-Host "[zcode] data stays under project .zcode\ (not user profile)"
