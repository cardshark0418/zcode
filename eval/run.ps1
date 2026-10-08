<#
.SYNOPSIS
  Run zcode eval suite (prepare workdir -> agent run -> verify -> score).

.EXAMPLE
  # smoke: verify fixtures fail before agent (sanity)
  powershell -File eval/run.ps1 -VerifyOnly

.EXAMPLE
  # one task end-to-end (needs API key + installed jar)
  powershell -File eval/run.ps1 -Task T01

.EXAMPLE
  # full suite
  powershell -File eval/run.ps1 -MaxIterations 40
#>
param(
    [string]$Task = "",
    [int]$MaxIterations = 40,
    [switch]$VerifyOnly,
    [switch]$SkipAgent,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$EvalRoot = $PSScriptRoot
$RepoRoot = Split-Path $EvalRoot -Parent
$Suite = Get-Content (Join-Path $EvalRoot "suite.json") -Raw | ConvertFrom-Json

if (-not $OutDir) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $EvalRoot "runs\$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$zcode = Join-Path $RepoRoot "bin\zcode.cmd"
if (-not $VerifyOnly -and -not $SkipAgent -and -not (Test-Path $zcode)) {
    Write-Error "missing $zcode — run bin/install.ps1 first"
}

$env:ZCODE_AGENT_MAX_ITERATIONS = "$MaxIterations"
$env:ZCODE_PERMISSION_MODE = "auto-confirm"

$tasks = $Suite.tasks
if ($Task) {
    $tasks = @($Suite.tasks | Where-Object { $_.id -eq $Task -or "$($_.id)-$($_.slug)" -eq $Task })
    if (-not $tasks -or $tasks.Count -eq 0) {
        Write-Error "task not found: $Task"
    }
}

$results = @()

foreach ($t in $tasks) {
    $name = "$($t.id)-$($t.slug)"
    $taskDir = Join-Path $EvalRoot "tasks\$name"
    if (-not (Test-Path $taskDir)) {
        Write-Warning "missing task dir $taskDir — run eval/bootstrap-tasks.ps1"
        $results += [pscustomobject]@{ id = $t.id; slug = $t.slug; pass = $false; category = "missing_task"; seconds = 0 }
        continue
    }

    $work = Join-Path $OutDir $name
    if (Test-Path $work) { Remove-Item $work -Recurse -Force }
    Copy-Item (Join-Path $taskDir "fixture") $work -Recurse

    $promptFile = Join-Path $taskDir "prompt.md"
    $verify = Join-Path $taskDir "verify.ps1"
    $log = Join-Path $OutDir "$name.log"

    # Precheck: fixture should FAIL verify (otherwise task is invalid)
    $pre = Join-Path $OutDir "$name.pre"
    if (Test-Path $pre) { Remove-Item $pre -Recurse -Force }
    Copy-Item (Join-Path $taskDir "fixture") $pre -Recurse
    $preOk = $false
    Push-Location $pre
    try {
        # verify.ps1 expects sibling fixture/; adapt by calling Assert helper directly
        $main = "Check.java"
        & (Join-Path $EvalRoot "lib\Assert-JavaMain.ps1") -WorkDir $pre -MainRelPath $main 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $preOk = $true }
    } catch {
        $preOk = $false
    } finally {
        Pop-Location
    }
    if ($preOk) {
        Write-Warning "$name precheck already passes — fixture may be fixed already"
        $results += [pscustomobject]@{ id = $t.id; slug = $t.slug; pass = $false; category = "precheck_already_pass"; seconds = 0 }
        continue
    }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $pass = $false
    $category = "verify_failed"

    if ($VerifyOnly) {
        # Fixture correctly broken (precheck failed) => "pass" for suite health.
        Write-Host "==> fixture-health $name (expect broken)"
        $pass = $true
        $category = "fixture_broken_ok"
    } elseif (-not $SkipAgent) {
        Write-Host "==> agent $name"
        # zcode prints tool progress on stderr; with ErrorAction=Stop that becomes a terminating error.
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $cmdline = "`"$zcode`" run --workspace `"$work`" --prompt-file `"$promptFile`" > `"$log`" 2>&1"
            cmd /c $cmdline
            $agentExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prevEap
        }
        if ($agentExit -ne 0) {
            $category = "agent_error"
        } else {
            & (Join-Path $EvalRoot "lib\Assert-JavaMain.ps1") -WorkDir $work -MainRelPath "Check.java" *> (Join-Path $OutDir "$name.verify.log")
            if ($LASTEXITCODE -eq 0) {
                $pass = $true
                $category = "pass"
            } else {
                $category = "verify_failed"
            }
        }
    } else {
        Write-Host "==> verify-skip-agent $name"
        & (Join-Path $EvalRoot "lib\Assert-JavaMain.ps1") -WorkDir $work -MainRelPath "Check.java" *> (Join-Path $OutDir "$name.verify.log")
        if ($LASTEXITCODE -eq 0) {
            $pass = $true
            $category = "pass"
        }
    }

    $sw.Stop()
    $row = [pscustomobject]@{
        id       = $t.id
        slug     = $t.slug
        pass     = $pass
        category = $category
        seconds  = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
    $results += $row
    Write-Host ("    {0} {1} ({2}s)" -f $(if ($pass) { "PASS" } else { "FAIL" }), $name, $row.seconds)
}

$summaryPath = Join-Path $OutDir "summary.json"
$passed = @($results | Where-Object { $_.pass }).Count
$total = $results.Count
$rate = if ($total -eq 0) { 0 } else { [math]::Round(100.0 * $passed / $total, 1) }

$summary = [ordered]@{
    suite     = $Suite.name
    outDir    = $OutDir
    passed    = $passed
    total     = $total
    passRate  = $rate
    maxIterations = $MaxIterations
    verifyOnly = [bool]$VerifyOnly
    results   = $results
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 $summaryPath

Write-Host ""
Write-Host ("Score: {0}/{1} = {2}%" -f $passed, $total, $rate)
Write-Host "Wrote $summaryPath"
