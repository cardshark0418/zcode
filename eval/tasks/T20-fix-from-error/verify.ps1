$ErrorActionPreference = "Continue"
$here = $PSScriptRoot
& "$PSScriptRoot\..\..\lib\Assert-JavaMain.ps1" -WorkDir (Join-Path $here "fixture") -MainRelPath "Check.java"
exit $LASTEXITCODE