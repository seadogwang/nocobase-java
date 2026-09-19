# P0 Release Gate Automation Script
# Runs all mandatory pre-release checks and generates a structured gate report.
# Usage: .\scripts\release-gate.ps1
# Requirements: PowerShell 7+ or Windows PowerShell 5.1+, Maven 3.9+, Java 17+

param(
    [switch]$SkipPgAcceptance,
    [switch]$SkipFlyway,
    [switch]$RequireExternalPg,
    [string]$ReportPath = "RELEASE_GATE_RESULT.md"
)

$ErrorActionPreference = "Continue"
# $PSScriptRoot is reliably populated on PowerShell 5.1 and 7+ when the
# script is invoked via -File; fall back to $MyInvocation for completeness.
$ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")

# Cross-platform paths. PowerShell 5.1's Join-Path accepts only two path
# parts, so multi-segment paths are nested (works on 5.1 and 7+).
$ReportsDir = Join-Path (Join-Path $ProjectRoot "target") "surefire-reports"
$SrcDir = Join-Path $ProjectRoot "src"
$ClassesDir = Join-Path (Join-Path $ProjectRoot "target") "classes"
$PgReportFile = Join-Path $ReportsDir "TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml"
$ReportFilePath = Join-Path $ProjectRoot $ReportPath

# PostgreSQL mode: updated to "External PostgreSQL" when -RequireExternalPg runs.
$pgMode = "Testcontainers"

# Force ASCII-safe output encoding for cross-platform compatibility
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  NocoBase Java - P0 Release Gate" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Project: $ProjectRoot"
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')"
Write-Host ""

# -- Helper Functions ----------------------------------------------------------

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
        [string]$ReportsDir = $ReportsDir
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
    Cleans the surefire reports directory before a test run.
    Ensures old reports from a previous gate do not pollute the current gate.
#>
function Clear-SurefireReports {
    param(
        [string]$ReportsDir = $ReportsDir
    )

    if (Test-Path $ReportsDir) {
        Remove-Item -Path "$ReportsDir\*" -Force -ErrorAction SilentlyContinue
        Write-Host "  Cleaned surefire reports directory: $ReportsDir" -ForegroundColor Gray
    }
}

<#
.SYNOPSIS
    Runs a Maven command, captures exit code and output, and measures duration.
    Uses array splatting for proper argument passing.
#>
function Invoke-MavenCommand {
    param(
        [string[]]$MavenArgs,
        [string]$Description
    )

    $commandStr = "mvn $($MavenArgs -join ' ')"
    Write-Host "-- [$Description] --" -ForegroundColor Yellow
    Write-Host "  Running: $commandStr" -ForegroundColor Gray

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $output = & mvn @MavenArgs 2>&1
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
        Command     = $commandStr
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
        [string]$SrcDir = $SrcDir
    )

    Write-Host "-- [Sensitive Code Scan] --" -ForegroundColor Yellow

    $issues = @()
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    # Scan 1: System.out.println in main source (not test, not release tooling)
    $sysOutMain = Get-ChildItem -Path (Join-Path $SrcDir "main") -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/]release[\\/]' } |
        Select-String -Pattern 'System\.out\.print'
    foreach ($hit in $sysOutMain) {
        # Exclude comments and Javadoc
        $trimmed = $hit.Line.Trim()
        if ($trimmed -match '^\s*(//|/\*|\*|/\*\*)') { continue }
        $issues += [PSCustomObject]@{
            Severity = "ERROR"
            Category = "System.out.println (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 2: System.out.println in test source (informational)
    $sysOutTest = Get-ChildItem -Path (Join-Path $SrcDir "test") -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Select-String -Pattern 'System\.out\.print'
    foreach ($hit in $sysOutTest) {
        # Exclude comments and Javadoc
        $trimmed = $hit.Line.Trim()
        if ($trimmed -match '^\s*(//|/\*|\*|/\*\*)') { continue }
        $issues += [PSCustomObject]@{
            Severity = "INFO"
            Category = "System.out.println (test)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 3: printStackTrace in main source (not release tooling)
    $pstMain = Get-ChildItem -Path (Join-Path $SrcDir "main") -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/]release[\\/]' } |
        Select-String -Pattern '\.printStackTrace\(\)'
    foreach ($hit in $pstMain) {
        # Exclude comments and Javadoc
        $trimmed = $hit.Line.Trim()
        if ($trimmed -match '^\s*(//|/\*|\*|/\*\*)') { continue }
        $issues += [PSCustomObject]@{
            Severity = "ERROR"
            Category = "printStackTrace (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 4: Hardcoded JWT secrets, tokens, passwords in main source
    # Excludes dev/test config files (application-dev.yml, application-test.yml)
    $hardcodedMain = Get-ChildItem -Path (Join-Path $SrcDir "main") -Recurse -Include "*.java","*.yml","*.yaml","*.properties" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/]release[\\/]' } |
        Where-Object { $_.Name -notmatch '^application-(dev|test)\.' } |
        Select-String -Pattern '\b(password|secret|token|key)\b\s*[:=]\s*"[^"]{8,}"|\b(password|secret|token|key)\b\s*[:=]\s*[a-zA-Z0-9+/=]{20,}' -CaseSensitive:$false
    foreach ($hit in $hardcodedMain) {
        # Exclude safe patterns like ${ENV_VAR} placeholders
        if ($hit.Line -match '\$\{') { continue }
        # Exclude empty password
        if ($hit.Line -match 'password\s*:\s*""') { continue }
        # Exclude comments and Javadoc
        $trimmed = $hit.Line.Trim()
        if ($trimmed -match '^\s*(//|/\*|\*|/\*\*|#)') { continue }

        $issues += [PSCustomObject]@{
            Severity = "ERROR"
            Category = "Hardcoded secret (main)"
            File     = $hit.Path.Replace($ProjectRoot, ".")
            Line     = $hit.LineNumber
            Content  = $hit.Line.Trim()
        }
    }

    # Scan 5: JWT/token/secret/password/credential passed as log parameter (main source, not release tooling)
    # Matches only when the sensitive term appears as a parameter (after the format string),
    # not when it merely appears in the log message text.
    $jwtLogs = Get-ChildItem -Path (Join-Path $SrcDir "main") -Recurse -Filter "*.java" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/]release[\\/]' } |
        Select-String -Pattern 'log\.\w+\([^"]*"[^"]*"[^)]*[,\+]\s*\b(token|secret|password|jwt|credential)\b\s*[,\)]' -CaseSensitive:$false
    foreach ($hit in $jwtLogs) {
        # Exclude comments and Javadoc
        $trimmed = $hit.Line.Trim()
        if ($trimmed -match '^\s*(//|/\*|\*|/\*\*)') { continue }
        $issues += [PSCustomObject]@{
            Severity = "ERROR"
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

# -- Gate 1: mvn test (H2 unit/integration tests) ------------------------------

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 1: mvn test (H2 Default)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Clean surefire reports before running to avoid pollution from previous runs
Clear-SurefireReports -ReportsDir $ReportsDir

$gate1Cmd = Invoke-MavenCommand -MavenArgs @("test") -Description "Unit and Integration Tests (H2)"

# Parse surefire reports
$gate1Reports = Parse-SurefireReports -ReportsDir $ReportsDir

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

# -- Gate 2: mvn flyway:validate ------------------------------------------------

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
    $gate2Cmd = Invoke-MavenCommand -MavenArgs @("flyway:validate") -Description "Flyway Migration Validation"
    $gate2Passed = $gate2Cmd.ExitCode -eq 0
}

if ($gate2Passed) {
    Write-Host "  GATE 2: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 2: FAIL" -ForegroundColor Red
}

# -- Gate 3: mvn test -Ppostgresql-acceptance -----------------------------------
#
# Postgresql acceptance tests default to Testcontainers (auto-starts a PostgreSQL
# container via org.testcontainers). No external PG instance is required.
#
# Override: use -RequireExternalPg to require PG_URL/PG_USERNAME/PG_PASSWORD
# environment variables and run against an external PostgreSQL instance.

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 3: mvn test -Ppostgresql-acceptance" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Initialize PG-specific report tracking
$gate3PgReportExists = $false
$gate3PgReportTests  = 0
$gate3PgReportFail   = 0
$gate3PgReportErr    = 0
$gate3PgReportSkip   = 0
$gate3SkipReason     = ""
$gate3VerifierExitCode = -1

# Check for external PG requirements only when -RequireExternalPg is set
$pgEnvMissing = @()
if ($RequireExternalPg) {
    $pgUrl      = $env:PG_URL
    $pgUsername = $env:PG_USERNAME
    $pgPassword = $env:PG_PASSWORD

    if (-not $pgUrl)      { $pgEnvMissing += "PG_URL" }
    if (-not $pgUsername) { $pgEnvMissing += "PG_USERNAME" }
    if (-not $pgPassword) { $pgEnvMissing += "PG_PASSWORD" }
}

if ($RequireExternalPg -and $pgEnvMissing.Count -gt 0) {
    Write-Host "  ERROR: -RequireExternalPg is set but PostgreSQL environment variables are missing: $($pgEnvMissing -join ', ')" -ForegroundColor Red
    Write-Host "  Set PG_URL, PG_USERNAME, PG_PASSWORD before running this gate." -ForegroundColor Red

    $gate3Cmd = [PSCustomObject]@{
        Command     = "mvn test -Ppostgresql-acceptance"
        Description = "PostgreSQL Acceptance Tests (external PG)"
        ExitCode    = 1
        Duration    = 0
        Output      = "FAILED: Missing env vars: $($pgEnvMissing -join ', ')"
    }
    $gate3Tests    = 0
    $gate3Failures = 0
    $gate3Errors   = 0
    $gate3Skipped  = 0
    $gate3Passed   = $false
    $gate3SkipReason = "External PG required but environment variables not configured: $($pgEnvMissing -join ', ')"
}
elseif ($SkipPgAcceptance) {
    Write-Host "  SKIPPED by user request." -ForegroundColor Yellow
    Write-Host "  WARNING: PG acceptance is mandatory for release. Skipping only for dev iteration." -ForegroundColor Yellow
    Write-Host "  OVERALL RESULT WILL BE FAIL -- PG acceptance cannot be skipped for release." -ForegroundColor Yellow

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
    $gate3SkipReason = "PG acceptance tests skipped by user request (-SkipPgAcceptance flag)"
}
else {
    if ($RequireExternalPg) {
        $pgMode = "External PostgreSQL"
        Write-Host "  Running with external PostgreSQL (Testcontainers disabled)" -ForegroundColor Yellow
        # Never print the raw PG_URL/username/password values — only whether
        # each is configured. Connection data must not leak to logs or reports.
        Write-Host "  PG_URL: (redacted; $(if ($pgUrl) { 'configured' } else { 'missing' }))" -ForegroundColor Gray
        Write-Host "  PG_USERNAME: (redacted; $(if ($pgUsername) { 'configured' } else { 'missing' }))" -ForegroundColor Gray
        Write-Host "  PG_PASSWORD: (redacted; $(if ($pgPassword) { 'configured' } else { 'missing' }))" -ForegroundColor Gray
    }
    else {
        $pgMode = "Testcontainers"
        Write-Host "  Running with Testcontainers (auto-start PostgreSQL container)" -ForegroundColor Green
    }

    # Clean surefire reports before running PG tests to avoid pollution from Gate 1
    Clear-SurefireReports -ReportsDir $ReportsDir

    if ($RequireExternalPg) {
        $gate3Cmd = Invoke-MavenCommand -MavenArgs @("test", "-Ppostgresql-acceptance", "-Dpostgresql.external.pg=true") -Description "PostgreSQL Acceptance Tests (external PG)"
    } else {
        $gate3Cmd = Invoke-MavenCommand -MavenArgs @("test", "-Ppostgresql-acceptance") -Description "PostgreSQL Acceptance Tests (Testcontainers)"
    }

    # Parse PG-specific surefire reports (should have PostgreSqlIntegrationTest)
    $gate3Reports = Parse-SurefireReports -ReportsDir $ReportsDir
    $gate3Tests    = $gate3Reports.TotalTests
    $gate3Failures = $gate3Reports.Failures
    $gate3Errors   = $gate3Reports.Errors
    $gate3Skipped  = $gate3Reports.Skipped

    # Verify the specific PG report file exists
    $pgReportFilePath = $PgReportFile
    if (Test-Path $pgReportFilePath) {
        $gate3PgReportExists = $true
        try {
            [xml]$pgXml = Get-Content $pgReportFilePath
            $pgSuite = $pgXml.testsuite
            $gate3PgReportTests = [int]$pgSuite.tests
            $gate3PgReportFail  = [int]$pgSuite.failures
            $gate3PgReportErr   = [int]$pgSuite.errors
            $gate3PgReportSkip  = [int]$pgSuite.skipped
            Write-Host "  PG Report: $gate3PgReportTests tests, $gate3PgReportFail failures, $gate3PgReportErr errors, $gate3PgReportSkip skipped" -ForegroundColor Gray
        }
        catch {
            Write-Host "  WARNING: Failed to parse PG report file: $_" -ForegroundColor Yellow
        }
    }
    else {
        Write-Host "  ERROR: Expected PG report file not found: $pgReportFilePath" -ForegroundColor Red
        # List what files are actually in the reports directory
        $foundFiles = Get-ChildItem -Path $ReportsDir -Filter "TEST-*.xml" -ErrorAction SilentlyContinue
        if ($foundFiles) {
            Write-Host "  Found files: $($foundFiles.Name -join ', ')" -ForegroundColor Gray
        }
        else {
            Write-Host "  No TEST-*.xml files found in reports directory." -ForegroundColor Gray
        }
    }

    # Gate 3 rules: exit 0, tests > 0, failures = 0, errors = 0, skipped = 0
    # AND the specific PG report file must exist with valid data
    $gate3Passed = ($gate3Cmd.ExitCode -eq 0) `
        -and ($gate3Tests -gt 0) `
        -and ($gate3Failures -eq 0) `
        -and ($gate3Errors -eq 0) `
        -and ($gate3Skipped -eq 0) `
        -and $gate3PgReportExists `
        -and ($gate3PgReportTests -gt 0) `
        -and ($gate3PgReportFail -eq 0) `
        -and ($gate3PgReportErr -eq 0) `
        -and ($gate3PgReportSkip -eq 0)

    if ($gate3Tests -le 0 -and $gate3Cmd.ExitCode -eq 0) {
        Write-Host "  REASON: No PG tests found (tests=$gate3Tests). Ensure PG is reachable." -ForegroundColor Red
        $gate3Passed = $false
    }
    if ($gate3Failures -gt 0 -and $gate3Passed) {
        Write-Host "  REASON: $gate3Failures test failure(s) in PG tests." -ForegroundColor Red
        $gate3Passed = $false
    }
    if ($gate3Errors -gt 0 -and $gate3Passed) {
        Write-Host "  REASON: $gate3Errors test error(s) in PG tests." -ForegroundColor Red
        $gate3Passed = $false
    }
    if ($gate3Skipped -gt 0 -and $gate3Passed) {
        Write-Host "  REASON: $gate3Skipped test(s) skipped in PG tests." -ForegroundColor Red
        $gate3Passed = $false
    }
    if (-not $gate3PgReportExists -and $gate3Passed) {
        Write-Host "  REASON: Expected PG report file (TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml) not found." -ForegroundColor Red
        $gate3Passed = $false
    }

    # -- ReleaseGateVerifier: validate PG report ------------------------------
    if ($gate3PgReportExists) {
        Write-Host "-- [ReleaseGateVerifier: validate PG report] --" -ForegroundColor Yellow
        $verifierArgs = @(
            "-cp", $ClassesDir,
            "com.nocobase.release.ReleaseGateVerifier",
            "verify",
            $PgReportFile
        )
        $verifierOutput = & java @verifierArgs 2>&1
        $gate3VerifierExitCode = $LASTEXITCODE
        Write-Host "  Verifier output:" -ForegroundColor Gray
        if ($verifierOutput) {
            foreach ($line in $verifierOutput) {
                Write-Host "    $line" -ForegroundColor Gray
            }
        }
        if ($gate3VerifierExitCode -eq 0) {
            Write-Host "  ReleaseGateVerifier: PASS" -ForegroundColor Green
        }
        else {
            Write-Host "  ReleaseGateVerifier: FAIL (exit $gate3VerifierExitCode)" -ForegroundColor Red
            $gate3Passed = $false
        }
    }
    else {
        Write-Host "  ReleaseGateVerifier: SKIPPED (no PG report file to verify)" -ForegroundColor Yellow
    }
}

if ($gate3Passed) {
    Write-Host "  GATE 3: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 3: FAIL" -ForegroundColor Red
    if ($gate3SkipReason) {
        Write-Host "  REASON: $gate3SkipReason" -ForegroundColor Red
    }
}

# -- Gate 4: Sensitive Scan -----------------------------------------------------

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  GATE 4: Sensitive Code Scan" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$gate4 = Invoke-SensitiveScan -SrcDir $SrcDir

if ($gate4.Passed) {
    Write-Host "  GATE 4: PASS" -ForegroundColor Green
}
else {
    Write-Host "  GATE 4: FAIL" -ForegroundColor Red
}

# -- Overall Assessment --------------------------------------------------------

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  RELEASE GATE SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# Count how many gates are treated as "required" vs "skipped"
$totalRequiredGates = 4
$skippedGates = @()
if (-not $gate1Passed) { $skippedGates += "Gate 1" }
if (-not $gate2Passed) { $skippedGates += "Gate 2" }
if (-not $gate3Passed) { $skippedGates += "Gate 3" }
if (-not $gate4.Passed) { $skippedGates += "Gate 4" }

if ($SkipPgAcceptance) {
    Write-Host "  WARNING: PG Acceptance (Gate 3) was skipped by user request." -ForegroundColor Yellow
    Write-Host "  PG acceptance is MANDATORY for release. Overall result is FAIL." -ForegroundColor Yellow
}

if ($RequireExternalPg -and $pgEnvMissing.Count -gt 0) {
    Write-Host "  ERROR: -RequireExternalPg is set but PG environment variables are missing. Overall result is FAIL." -ForegroundColor Red
    Write-Host "  Missing: $($pgEnvMissing -join ', ')" -ForegroundColor Red
}

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
    if ($SkipPgAcceptance) {
        Write-Host "  NOTE: Gate 3 was skipped by -SkipPgAcceptance flag. PG acceptance is MANDATORY for release." -ForegroundColor Yellow
    }
    if ($RequireExternalPg -and $pgEnvMissing.Count -gt 0) {
        Write-Host "  NOTE: Gate 3 failed because -RequireExternalPg is set but PG environment variables are missing." -ForegroundColor Yellow
    }
    Write-Host "  Release is BLOCKED." -ForegroundColor Red
}

# -- Generate RELEASE_GATE_RESULT.md --------------------------------------------

Write-Host ""
Write-Host "Generating $ReportPath..." -ForegroundColor Gray

# Build report file names section for Gate 1
$gate1FileNames = ""
if ($gate1Reports.TestFiles -and $gate1Reports.TestFiles.Count -gt 0) {
    $gate1FileNames = ($gate1Reports.TestFiles | ForEach-Object { $_.Name }) -join ", "
}
else {
    $gate1FileNames = "(no report files found)"
}

# Build report file names section for Gate 3
$gate3FileNames = ""
if ($gate3Reports -and $gate3Reports.TestFiles -and $gate3Reports.TestFiles.Count -gt 0) {
    $gate3FileNames = ($gate3Reports.TestFiles | ForEach-Object { $_.Name }) -join ", "
}
else {
    $gate3FileNames = "(no report files found)"
}

# Build PG-specific report verification line
$gate3PgReportLine = ""
if ($gate3PgReportExists) {
    $gate3PgReportLine = "`n- **PG Report File:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml (found: YES, tests=$gate3PgReportTests, failures=$gate3PgReportFail, errors=$gate3PgReportErr, skipped=$gate3PgReportSkip)"
}
elseif ($SkipPgAcceptance -or ($RequireExternalPg -and $pgEnvMissing.Count -gt 0)) {
    $gate3PgReportLine = "`n- **PG Report File:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml (not checked -- gate was skipped or env vars missing)"
}
else {
    $gate3PgReportLine = "`n- **PG Report File:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml (NOT FOUND -- this is a failure condition)"
}

$reportContent = @"
# Release Gate Result

**Generated:** $(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
**Project:** nocobase-java (NocoBase Java Backend)
**Branch/Commit:** $(try { git -C $ProjectRoot rev-parse --short HEAD 2>$null } catch { "N/A" })
**PostgreSQL mode:** $pgMode (no JDBC URL or credentials recorded)

## Gate Summary

| Gate | Command | Exit Code | Duration | Tests | Failures | Errors | Skipped | Verifier | Result |
|------|---------|-----------|----------|-------|----------|--------|---------|----------|--------|
| 1 | `mvn test` | $($gate1Cmd.ExitCode) | $($gate1Cmd.Duration)s | $gate1Tests | $gate1Failures | $gate1Errors | $gate1Skipped | -- | $(if ($gate1Passed) { "PASS" } else { "FAIL" }) |
| 2 | `mvn flyway:validate` | $($gate2Cmd.ExitCode) | $($gate2Cmd.Duration)s | -- | -- | -- | -- | -- | $(if ($gate2Passed) { "PASS" } else { "FAIL" }) |
| 3 | `mvn test -Ppostgresql-acceptance` | $($gate3Cmd.ExitCode) | $($gate3Cmd.Duration)s | $gate3Tests | $gate3Failures | $gate3Errors | $gate3Skipped | $(if ($gate3VerifierExitCode -eq -1) { "SKIP" } elseif ($gate3VerifierExitCode -eq 0) { "PASS" } else { "FAIL" }) | $(if ($gate3Passed) { "PASS" } else { "FAIL" }) |
| 4 | Sensitive code scan | $(if ($gate4.Passed) { 0 } else { 1 }) | $($gate4.Duration)s | -- | -- | -- | -- | -- | $(if ($gate4.Passed) { "PASS" } else { "FAIL" }) |

**Overall Result:** $(if ($overallPassed) { "**ALL GATES PASSED** - Release can proceed." } else { "**GATES FAILED** - Release is BLOCKED." })
$(if ($SkipPgAcceptance) { "`n**WARNING:** Gate 3 (PostgreSQL acceptance) was skipped by -SkipPgAcceptance flag. PG acceptance is MANDATORY for release." } else { "" })
$(if ($RequireExternalPg -and $pgEnvMissing.Count -gt 0) { "`n**WARNING:** Gate 3 (PostgreSQL acceptance) failed because -RequireExternalPg is set but PG environment variables are not configured: $($pgEnvMissing -join ', ')." } else { "" })

## Gate 1: Unit and Integration Tests (H2)

- **Command:** `mvn test`
- **Exit Code:** $($gate1Cmd.ExitCode)
- **Duration:** $($gate1Cmd.Duration)s
- **Tests Run:** $gate1Tests
- **Failures:** $gate1Failures
- **Errors:** $gate1Errors
- **Skipped:** $gate1Skipped
- **Result:** $(if ($gate1Passed) { "PASS" } else { "FAIL" })
- **Report Files:** $gate1FileNames

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
        '## Flyway Validation Output' + "`n`n" + '```' + "`n" + $gate2Cmd.Output + "`n" + '```'
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
- **Report Files:** $gate3FileNames$gate3PgReportLine
- **ReleaseGateVerifier:** $(if ($gate3VerifierExitCode -eq -1) { "SKIPPED" } elseif ($gate3VerifierExitCode -eq 0) { "PASS" } else { "FAIL (exit $gate3VerifierExitCode)" })

$(
    if ($RequireExternalPg -and $pgEnvMissing.Count -gt 0) {
        "**NOTE:** -RequireExternalPg is set but PG environment variables were missing: $($pgEnvMissing -join ', ')`n"
        "This gate FAILED because PG_URL, PG_USERNAME, and PG_PASSWORD are required when -RequireExternalPg is used.`n"
        "Set these environment variables and re-run the gate."
    }
    elseif ($SkipPgAcceptance) {
        "**NOTE:** PG acceptance tests were skipped by user request (-SkipPgAcceptance flag).`n"
        "This gate is marked FAIL because PG acceptance is MANDATORY for release.`n"
        "Remove the -SkipPgAcceptance flag and ensure PG environment variables are set."
    }
)

$(
    if ($gate3Reports -and $gate3Reports.TestFiles -and $gate3Reports.TestFiles.Count -gt 0) {
        $lines = @()
        $lines += "### PG Test Suite Details"
        $lines += ""
        $lines += "| Test Suite | Tests | Failures | Errors | Skipped | Time (s) |"
        $lines += "|------------|-------|----------|--------|---------|----------|"
        foreach ($tf in $gate3Reports.TestFiles) {
            $lines += "| $($tf.Name) | $($tf.Tests) | $($tf.Failures) | $($tf.Errors) | $($tf.Skipped) | $($tf.Time) |"
        }
        $lines -join "`n"
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
| 3 | Exit 0, Tests > 0, Failures = 0, Errors = 0, Skipped = 0. PG report file must exist and be valid. Default: Testcontainers auto-starts PostgreSQL. Override: -RequireExternalPg requires PG_URL/PG_USERNAME/PG_PASSWORD env vars. ReleaseGateVerifier exit 0 validates report. |
| 4 | Zero ERROR-level issues (WARN/INFO are informational) |

Any FAIL is a **BLOCKER** for the release.
"@

# Write the report with UTF-8 encoding (no BOM for cross-platform compatibility)
$reportContent | Out-File -FilePath $ReportFilePath -Encoding utf8 -Force
Write-Host "Report written to $ReportFilePath" -ForegroundColor Green

# -- ReleaseGateVerifier verify-report: cross-check the report against HEAD --
# Fail the overall gate if the report is stale/placeholder/inconsistent with
# the actual surefire XML or the current git commit.
$reportVerifierExitCode = 0
if ($gate3PgReportExists) {
    Write-Host "-- [ReleaseGateVerifier: verify-report consistency] --" -ForegroundColor Yellow
    $reportVerifyOutput = & java -cp $ClassesDir com.nocobase.release.ReleaseGateVerifier verify-report `
        --report $ReportFilePath --reports-dir $ReportsDir 2>&1
    $reportVerifyOutput | ForEach-Object { Write-Host "  $_" }
    $reportVerifierExitCode = $LASTEXITCODE
    if ($reportVerifierExitCode -ne 0) {
        Write-Host "  Report consistency check: FAIL (exit $reportVerifierExitCode)" -ForegroundColor Red
        $overallPassed = $false
    } else {
        Write-Host "  Report consistency check: PASS" -ForegroundColor Green
    }
} else {
    Write-Host "  Report consistency check: SKIPPED (no PG report file to cross-check)" -ForegroundColor Yellow
}

# -- Exit Code -----------------------------------------------------------------

if ($overallPassed) {
    exit 0
}
else {
    exit 1
}