# Compile all .java in WorkDir and run MainRelPath's class. Exit 0 on success.
param(
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$MainRelPath
)

$ErrorActionPreference = "Continue"
$javaFile = Join-Path $WorkDir $MainRelPath
if (-not (Test-Path $javaFile)) {
    Write-Error "missing $MainRelPath"
    exit 1
}

$tmp = Join-Path $WorkDir ".eval-out"
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
New-Item -ItemType Directory -Path $tmp | Out-Null

# Strip UTF-8 BOM if present (Set-Content -Encoding utf8 on Windows PowerShell writes BOM).
Get-ChildItem $WorkDir -Filter "*.java" | ForEach-Object {
    $bytes = [IO.File]::ReadAllBytes($_.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $text = [Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
        [IO.File]::WriteAllText($_.FullName, $text, [Text.UTF8Encoding]::new($false))
    }
}

Push-Location $WorkDir
try {
    $sources = @(Get-ChildItem -Filter "*.java" | ForEach-Object { $_.Name })
    if ($sources.Count -eq 0) {
        Write-Error "no .java files"
        exit 1
    }
    $javacOut = & javac -encoding UTF-8 -d $tmp @sources 2>&1
    $javacCode = $LASTEXITCODE
    if ($javacCode -ne 0) {
        $javacOut | ForEach-Object { Write-Host $_ }
        exit 1
    }
    $cn = [IO.Path]::GetFileNameWithoutExtension($MainRelPath)
    $javaOut = & java -cp $tmp $cn 2>&1
    $javaCode = $LASTEXITCODE
    $javaOut | ForEach-Object { Write-Host $_ }
    exit $javaCode
} finally {
    Pop-Location
}
