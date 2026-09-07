# P0 Release Gate Automation Script
# Runs all mandatory pre-release checks and generates a structured gate report.
# Usage: .\scripts\release-gate.ps1
# Requirements: PowerShell 7+ or Windows PowerShell 5.1+, Maven 3.9+, Java 17+

param(
    [switch]$SkipPgAcceptance,
    [switch]$SkipFlyway,
    [string]$ReportPath = "RELEASE_GATE_RESULT.md"
)

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\.."

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  NocoBase Java - P0 Release Gate" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')"
Write-Host ""

# ── Helper Functions ──────────────────────────────────────────────────────────

<#
.SYNOPSIS
    Parses all surefire TEST-*.xml files in the target/surefire-reports directory
    and aggregates totals across all testsuite elements.
.DESCRIPTION
    Reads XML files matching target/surefire-reports/TEST-*.xml, extracts
    testsuite attributes (tests, failures, errors, skipped, time), and returns
    an aggregated summary object.
#>
function Parse-SurefireReports {
    param(
        [string]$ReportsDir = "$ProjectRoot\target\surefire-reports"
    )

    $result = [PSCustomObject]@{
        TestFiles  = @()
        TotalTests = 0
        Failures   = 0
        Errors     = 0
        Skipped    = 0
        TotalTime  = 0.0
        FileCount  = 0
    }

    if (-not (Test-Path $ReportsDir)) {
        Write-Host "  WARNING: Surefire reports directory not found: $ReportsDir" -ForegroundColor Yellow
        $result.TotalTests = -1
        return $result
    }

    $xmlFiles = Get-ChildItem -Path $ReportsDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
    if (-not $xmlFiles -or $xmlFiles.Count -eq 0) {
        Write-Host "  WARNING: No TEST-*.xml files found in $ReportsDir" -ForegroundColor Yellow
        $result.TotalTests = -1
        return $result
    }

    foreach ($file in $xmlFiles) {
        try {
            [xml]$xml = Get-Content $file.FullName
            $suite = $xml.testsuite

            $tests    = [int]$suite.tests
            $failures = [int]$suite.failures
            $errors   = [int]$suite.errors
            $skipped  = [int]$suite.skipped
            $time     = [double]$suite.time

            $result.TotalTests += $tests
            $result.Failures   += $failures
            $result.Errors     += $errors
            $result.Skipped    += $skipped
            $result.TotalTime  += $time
            $result.FileCount++

            $result.TestFiles += [PSCustomObject]@{
                Name     = $file.Name
                Tests    = $tests
                Failures = $failures
                Errors   = $errors
                Skipped  = $skipped
                Time     = $time
            }

            # If any test case has failures/errors, capture details
            if ($failures -gt 0 -or $errors -gt 0) {
                foreach ($tc in $suite.testcase) {
                    if ($tc.failure) {
                        Write-Host "    FAILURE: $($tc.classname).$($tc.name)" -ForegroundColor Red
                    }
                    if ($tc.error) {
                        Write-Host "    ERROR: $($tc.classname).$($tc.name)" -ForegroundColor Red
                    }
                }
            }
        }
        catch {
            Write-Host "  WARNING: Failed to parse $($file.Name): $_" -ForegroundColor Yellow
        }
    }

    return $result
}

<#
.SYNOPSIS
    Runs a Maven command, captures exit code and output, and measures duration.
#>
function Invoke-MavenCommand {
    param(
        [string]$Command,
        [string]$Description
    )

    Write-Host "── [$Description] ──" -ForegroundColor Yellow
    Write-Host "  Running: mvn $Command" -ForegroundColor Gray

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $output = & mvn $Command 2>&1
    $exitCode = $LASTEXITCODE
    $sw.Stop()

    $duration = [math]::Round($sw.Elapsed.TotalSeconds, 2)

    if ($exitCode -eq 0) {
        Write-Host "  RESULT: PASS (exit $exitCode, ${duration}s)" -ForegroundColor Green
    }
    else {
        Write-Host "  RESULT: FAIL (exit $exitCode, ${duration}s)" -ForegroundColor Red
    }

    return [PSCustomObject]@{
        Command     = "mvn $Command"
        Description = $Description
        ExitCode    = $exitCode
        Duration    = $duration
        Output      = $output -join "`n"
    }
}

<#
.SYNOPSIS
    Runs a sensitive-code scan across the source tree.
    Checks for:
      - System.out.println (debug output left in production code)
      - printStackTrace (leaked stack traces)
      - JWT/token/secret/password leaks in log statements or config
#>
function Invoke-SensitiveScan {
    param(
        [string]$SrcDir = "$ProjectRoot\src"
    )

    Write-Host "── [Sensitive Code Scan] ──" -ForegroundColor Yellow

    $issues = @()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    # Scan 1: System.out.println in main source (not test)
    $sysOutMain = Get-ChildItem -Path "$SrcDir\main" -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Select-String -Pattern 'System\.out\.println' -SimpleMatch
    foreach ($hit in $sysOutMain) {
        $issues += [PSCustomObject]@{
            Severity = "WARN"
            Category = "System.out.println (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 2: System.out.println in test source (informational)
    $sysOutTest = Get-ChildItem -Path "$SrcDir\test" -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Select-String -Pattern 'System\.out\.println' -SimpleMatch
    foreach ($hit in $sysOutTest) {
        $issues += [PSCustomObject]@{
            Severity = "INFO"
            Category = "System.out.println (test)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 3: printStackTrace in main source
    $pstMain = Get-ChildItem -Path "$SrcDir\main" -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Select-String -Pattern '\.printStackTrace\(\)' -SimpleMatch
    foreach ($hit in $pstMain) {
        $issues += [PSCustomObject]@{
            Severity = "ERROR"
            Category = "printStackTrace (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 4: Hardcoded JWT secrets, tokens, passwords in main source
    $hardcodedMain = Get-ChildItem -Path "$SrcDir\main" -Recurse -Include "*.java","*.yml","*.yaml","*.properties" -ErrorAction SilentlyContinue |
        Select-String -Pattern '(password|secret|token|key)\s*[:=]\s*"[^"]{8,}"|(password|secret|token|key)\s*[:=]\s*[a-zA-Z0-9+/=]{20,}' -CaseSensitive:$false
    foreach ($hit in $hardcodedMain) {
        # Exclude safe patterns like ${ENV_VAR} placeholders
        if ($hit.Line -match '\$\{') { continue }
        # Exclude empty password
        if ($hit.Line -match 'password\s*:\s*""') { continue }
        # Exclude comments
        if ($hit.Line.Trim() -match '^\s*#|^\s*//|^\s*\*') { continue }

        $issues += [PSCustomObject]@{
            Severity = "ERROR"
            Category = "Hardcoded secret (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 5: JWT or token patterns in log statements
    $jwtLogs = Get-ChildItem -Path "$SrcDir\main" -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Select-String -Pattern 'log\.\w*\(.*(token|secret|password|jwt|credential)' -CaseSensitive:$false
    foreach ($hit in $jwtLogs) {
        $issues += [PSCustomObject]@{
            Severity = "WARN"
            Category = "Sensitive data in log statement"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    $sw.Stop()

    # Determine pass/fail: ERROR = fail, WARN = pass (informational), INFO = pass
    $errorCount = ($issues | Where-Object { $_.Severity -eq "ERROR" }).Count
    $passed = $errorCount -eq 0

    if ($passed) {
        Write-Host "  RESULT: PASS (${errorCount} errors, $($issues.Count) total issues)" -ForegroundColor Green
    }
    else {
        Write-Host "  RESULT: FAIL (${errorCount} errors, $($issues.Count) total issues)" -ForegroundColor Red
        foreach ($iss in $issues | Where-Object { $_.Severity -eq "ERROR" }) {
            Write-Host "    ERROR: $($iss.File):$($iss.Line) - $($iss.Content)" -ForegroundColor Red
        }
    }

    return [PSCustomObject]@{
        ExitCode  = if ($passed) { 0 } else { 1 }
        Duration  = [math]::Round($sw.Elapsed.TotalSeconds, 2)
        Issues    = $issues
        Passed    = $passed
        ErrorCount = $errorCount
        TotalIssues = $issues.Count
    }
}

# ── Gate 1: mvn test (H2 unit/integration tests) ──────────────────────────────

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 1: mvn test (H2 Default)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$gate1Cmd = Invoke-MavenCommand -Command "test" -Description "Unit and Integration Tests (H2)"

# Parse surefire reports
$gate1Reports = Parse-SurefireReports -ReportsDir "$ProjectRoot\target\surefire-reports"

$gate1Tests    = $gate1Reports.TotalTests
$gate1Failures = $gate1Reports.Failures
$gate1Errors   = $gate1Reports.Errors
$gate1Skipped  = $gate1Reports.Skipped

# Gate 1 rules: exit 0, tests > 0, failures = 0, errors = 0
$gate1Passed = ($gate1Cmd.ExitCode -eq 0) -and ($gate1Tests -gt 0) -and ($gate1Failures -eq 0) -and ($gate1Errors -eq 0)

if ($gate1Passed) {
    Write-Host "  GATE 1: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 1: FAIL" -ForegroundColor Red
    if ($gate1Tests -le 0)    { Write-Host "    REASON: No tests found (tests=$gate1Tests)" -ForegroundColor Red }
    if ($gate1Failures -gt 0) { Write-Host "    REASON: $gate1Failures test failure(s)" -ForegroundColor Red }
    if ($gate1Errors -gt 0)   { Write-Host "    REASON: $gate1Errors test error(s)" -ForegroundColor Red }
    if ($gate1Cmd.ExitCode -ne 0) { Write-Host "    REASON: Maven exit code $($gate1Cmd.ExitCode)" -ForegroundColor Red }
}

# ── Gate 2: mvn flyway:validate ────────────────────────────────────────────────

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 2: mvn flyway:validate" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

if ($SkipFlyway) {
    Write-Host "  SKIPPED by user request." -ForegroundColor Yellow
    $gate2Cmd = [PSCustomObject]@{
        Command     = "mvn flyway:validate"
        Description = "Flyway Migration Validation"
        ExitCode    = -1
        Duration    = 0
        Output      = "SKIPPED"
    }
    $gate2Passed = $false
}
else {
    $gate2Cmd = Invoke-MavenCommand -Command "flyway:validate" -Description "Flyway Migration Validation"
    $gate2Passed = $gate2Cmd.ExitCode -eq 0
}

if ($gate2Passed) {
    Write-Host "  GATE 2: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 2: FAIL" -ForegroundColor Red
}

# ── Gate 3: mvn test -Ppostgresql-acceptance ───────────────────────────────────

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 3: mvn test -Ppostgresql-acceptance" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Check PG environment variables FIRST
$pgUrl      = $env:PG_URL
$pgUsername = $env:PG_USERNAME
$pgPassword = $env:PG_PASSWORD

$pgEnvMissing = @()
if (-not $pgUrl)      { $pgEnvMissing += "PG_URL" }
if (-not $pgUsername) { $pgEnvMissing += "PG_USERNAME" }
if (-not $pgPassword) { $pgEnvMissing += "PG_PASSWORD" }

if ($pgEnvMissing.Count -gt 0) {
    Write-Host "  ERROR: Missing required PostgreSQL environment variables: $($pgEnvMissing -join ', ')" -ForegroundColor Red
    Write-Host "  Set PG_URL, PG_USERNAME, PG_PASSWORD before running this gate." -ForegroundColor Red
    Write-Host "  This gate CANNOT be skipped -- it MUST be run against a real PostgreSQL instance." -ForegroundColor Red

    $gate3Cmd = [PSCustomObject]@{
        Command     = "mvn test -Ppostgresql-acceptance"
        Description = "PostgreSQL Acceptance Tests"
        ExitCode    = 1
        Duration    = 0
        Output      = "FAILED: Missing env vars: $($pgEnvMissing -join ', ')"
    }
    $gate3Tests    = 0
    $gate3Failures = 0
    $gate3Errors   = 0
    $gate3Skipped  = 0
    $gate3Passed   = $false
}
elseif ($SkipPgAcceptance) {
    Write-Host "  SKIPPED by user request." -ForegroundColor Yellow
    Write-Host "  WARNING: PG acceptance is mandatory for release. Skipping only for dev iteration." -ForegroundColor Yellow

    $gate3Cmd = [PSCustomObject]@{
        Command     = "mvn test -Ppostgresql-acceptance"
        Description = "PostgreSQL Acceptance Tests"
        ExitCode    = -1
        Duration    = 0
        Output      = "SKIPPED"
    }
    $gate3Tests    = 0
    $gate3Failures = 0
    $gate3Errors   = 0
    $gate3Skipped  = 0
    $gate3Passed   = $false
}
else {
    Write-Host "  PG_URL: $pgUrl" -ForegroundColor Gray
    Write-Host "  PG_USERNAME: $pgUsername" -ForegroundColor Gray
    Write-Host "  PG_PASSWORD: ****" -ForegroundColor Gray

    $gate3Cmd = Invoke-MavenCommand -Command "test -Ppostgresql-acceptance" -Description "PostgreSQL Acceptance Tests"

    # Parse PG-specific surefire reports (should have PostgreSqlIntegrationTest)
    $gate3Reports = Parse-SurefireReports -ReportsDir "$ProjectRoot\target\surefire-reports"
    $gate3Tests    = $gate3Reports.TotalTests
    $gate3Failures = $gate3Reports.Failures
    $gate3Errors   = $gate3Reports.Errors
    $gate3Skipped  = $gate3Reports.Skipped

    # Gate 3 rules: exit 0, tests > 0, failures = 0, errors = 0
    $gate3Passed = ($gate3Cmd.ExitCode -eq 0) -and ($gate3Tests -gt 0) -and ($gate3Failures -eq 0) -and ($gate3Errors -eq 0)

    if ($gate3Tests -le 0 -and $gate3Cmd.ExitCode -eq 0) {
        Write-Host "  REASON: No PG tests found (tests=$gate3Tests). Ensure PG is reachable." -ForegroundColor Red
        $gate3Passed = $false
    }
}

if ($gate3Passed) {
    Write-Host "  GATE 3: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 3: FAIL" -ForegroundColor Red
}

# ── Gate 4: Sensitive Scan ─────────────────────────────────────────────────────

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 4: Sensitive Code Scan" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$gate4 = Invoke-SensitiveScan -SrcDir "$ProjectRoot\src"

if ($gate4.Passed) {
    Write-Host "  GATE 4: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 4: FAIL" -ForegroundColor Red
}

# ── Overall Assessment ────────────────────────────────────────────────────────

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  RELEASE GATE SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$overallPassed = $gate1Passed -and $gate2Passed -and $gate3Passed -and $gate4.Passed

if ($overallPassed) {
    Write-Host "  OVERALL: ALL GATES PASSED" -ForegroundColor Green
    Write-Host "  Release can proceed." -ForegroundColor Green
}
else {
    Write-Host "  OVERALL: ONE OR MORE GATES FAILED" -ForegroundColor Red
    $failedGates = @()
    if (-not $gate1Passed) { $failedGates += "Gate 1 (mvn test)" }
    if (-not $gate2Passed) { $failedGates += "Gate 2 (flyway:validate)" }
    if (-not $gate3Passed) { $failedGates += "Gate 3 (postgresql-acceptance)" }
    if (-not $gate4.Passed) { $failedGates += "Gate 4 (sensitive scan)" }
    Write-Host "  Failed: $($failedGates -join '; ')" -ForegroundColor Red
    Write-Host "  Release is BLOCKED." -ForegroundColor Red
}

# ── Generate RELEASE_GATE_RESULT.md ────────────────────────────────────────────

Write-Host ""
Write-Host "Generating $ReportPath..." -ForegroundColor Gray

$reportContent = @"
# Release Gate Result

**Generated:** $(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
**Project:** nocobase-java (NocoBase Java Backend)
**Branch/Commit:** $(try { git -C $ProjectRoot rev-parse --short HEAD 2>$null } catch { "N/A" })

## Gate Summary

| Gate | Command | Exit Code | Duration | Tests | Failures | Errors | Skipped | Result |
|------|---------|-----------|----------|-------|----------|--------|---------|--------|
| 1 | `mvn test` | $($gate1Cmd.ExitCode) | $($gate1Cmd.Duration)s | $gate1Tests | $gate1Failures | $gate1Errors | $gate1Skipped | $(if ($gate1Passed) { "PASS" } else { "FAIL" }) |
| 2 | `mvn flyway:validate` | $($gate2Cmd.ExitCode) | $($gate2Cmd.Duration)s | -- | -- | -- | -- | $(if ($gate2Passed) { "PASS" } else { "FAIL" }) |
| 3 | `mvn test -Ppostgresql-acceptance` | $($gate3Cmd.ExitCode) | $($gate3Cmd.Duration)s | $gate3Tests | $gate3Failures | $gate3Errors | $gate3Skipped | $(if ($gate3Passed) { "PASS" } else { "FAIL" }) |
| 4 | Sensitive code scan | $(if ($gate4.Passed) { 0 } else { 1 }) | $($gate4.Duration)s | -- | -- | -- | -- | $(if ($gate4.Passed) { "PASS" } else { "FAIL" }) |

**Overall Result:** $(if ($overallPassed) { "**ALL GATES PASSED** - Release can proceed." } else { "**GATES FAILED** - Release is BLOCKED." })

## Gate 1: Unit and Integration Tests (H2)

- **Command:** `mvn test`
- **Exit Code:** $($gate1Cmd.ExitCode)
- **Duration:** $($gate1Cmd.Duration)s
- **Tests Run:** $gate1Tests
- **Failures:** $gate1Failures
- **Errors:** $gate1Errors
- **Skipped:** $gate1Skipped
- **Result:** $(if ($gate1Passed) { "PASS" } else { "FAIL" })

### Test Suite Details

$(
    if ($gate1Reports.TestFiles -and $gate1Reports.TestFiles.Count -gt 0) {
        $lines = @()
        $lines += "| Test Suite | Tests | Failures | Errors | Skipped | Time (s) |"
        $lines += "|------------|-------|----------|--------|---------|----------|"
        foreach ($tf in $gate1Reports.TestFiles) {
            $lines += "| $($tf.Name) | $($tf.Tests) | $($tf.Failures) | $($tf.Errors) | $($tf.Skipped) | $($tf.Time) |"
        }
        $lines -join "`n"
    }
    else {
        "No test suite data available."
    }
)

## Gate 2: Flyway Migration Validation

- **Command:** `mvn flyway:validate`
- **Exit Code:** $($gate2Cmd.ExitCode)
- **Duration:** $($gate2Cmd.Duration)s
- **Result:** $(if ($gate2Passed) { "PASS" } else { "FAIL" })
$(
    if (-not $gate2Passed) {
        "`n### Flyway Validation Output`n`n```"
        $gate2Cmd.Output
        "```"
    }
)

## Gate 3: PostgreSQL Acceptance Tests

- **Command:** `mvn test -Ppostgresql-acceptance`
- **Exit Code:** $($gate3Cmd.ExitCode)
- **Duration:** $($gate3Cmd.Duration)s
- **Tests Run:** $gate3Tests
- **Failures:** $gate3Failures
- **Errors:** $gate3Errors
- **Skipped:** $gate3Skipped
- **Result:** $(if ($gate3Passed) { "PASS" } else { "FAIL" })

$(
    if ($pgEnvMissing.Count -gt 0) {
        "**NOTE:** PG environment variables were missing: $($pgEnvMissing -join ', ')`n"
        "This gate FAILED because PG_URL, PG_USERNAME, and PG_PASSWORD are required.`n"
        "Set these environment variables and re-run the gate."
    }
)

## Gate 4: Sensitive Code Scan

- **Result:** $(if ($gate4.Passed) { "PASS" } else { "FAIL" })
- **Errors:** $($gate4.ErrorCount)
- **Total Issues:** $($gate4.TotalIssues)

$(
    if ($gate4.TotalIssues -gt 0) {
        $lines = @()
        $lines += "### Issues Found"
        $lines += ""
        $lines += "| Severity | Category | File | Line | Content |"
        $lines += "|----------|----------|------|------|---------|"
        foreach ($iss in $gate4.Issues) {
            $safeContent = $iss.Content -replace '["\\]', '`$0'
            $lines += "| $($iss.Severity) | $($iss.Category) | $($iss.File) | $($iss.Line) | ``$safeContent`` |"
        }
        $lines -join "`n"
    }
    else {
        "No sensitive code issues found."
    }
)

## Gate Rules Reference

| Gate | Rule |
|------|------|
| 1 | Exit 0, Tests > 0, Failures = 0, Errors = 0 |
| 2 | Exit 0, no error output |
| 3 | Exit 0, Tests > 0, Failures = 0, Errors = 0 |
| 4 | Zero ERROR-level issues (WARN/INFO are informational) |

Any FAIL is a **BLOCKER** for the release.
"@

# Write the report
$reportContent | Out-File -FilePath "$ProjectRoot\$ReportPath" -Encoding utf8 -Force
Write-Host "Report written to $ProjectRoot\$ReportPath" -ForegroundColor Green

# ── Exit Code ─────────────────────────────────────────────────────────────────

if ($overallPassed) {
    exit 0
}
else {
    exit 1
}