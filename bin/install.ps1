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

$InstallDir = Join-Path $env:USERPROFILE ".zcode"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

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
Copy-Item (Join-Path $PSScriptRoot "zcode.cmd") (Join-Path $InstallDir "zcode.cmd") -Force

# Ensure user PATH contains %USERPROFILE%\.zcode
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (-not $userPath) { $userPath = "" }
$parts = $userPath.Split(";", [System.StringSplitOptions]::RemoveEmptyEntries)
if ($parts -notcontains $InstallDir) {
    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $InstallDir } else { "$userPath;$InstallDir" }
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    $env:Path = "$InstallDir;$env:Path"
    Write-Host "[zcode] added to User PATH: $InstallDir"
} else {
    if ($env:Path -notlike "*$InstallDir*") {
        $env:Path = "$InstallDir;$env:Path"
    }
    Write-Host "[zcode] already on User PATH"
}

Write-Host ""
Write-Host "[zcode] installed -> $InstallDir"
Write-Host "[zcode] open a NEW terminal, then run:  zcode"
Write-Host "[zcode] optional web UI:               zcode serve"
