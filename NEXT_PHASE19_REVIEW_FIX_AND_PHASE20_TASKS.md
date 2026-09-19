# NEXT_PHASE19_REVIEW_FIX_AND_PHASE20_TASKS

> Target repo: `D:\Project\nocobase-java`
> Scope: backend only. Do not modify the existing NocoBase frontend.
> Goal: close the remaining release, security, and contract gaps before new feature work.
> Execution rule: P0 tasks must be completed and evidenced before P1 tasks are accepted.

## Current Verified Baseline

The latest independent verification found:

- `mvn test`: `1047 tests, 0 failures, 0 errors, 0 skipped`.
- `mvn flyway:validate`: `BUILD SUCCESS`, six migrations validated.
- `ReleaseGateScriptTest`: seven tests pass.
- `git diff --check`: passes.
- PostgreSQL acceptance was not executed in the current workspace.
- `RELEASE_GATE_RESULT.md` is still a placeholder and does not contain a real PASS report.
- `AuditLogService` logs `resourceKey` directly. `DataSourceConfigService.testConnection` can pass a raw JDBC URL as that key on its outer exception path.
- Error-envelope tests exist, but some assertions accept multiple status codes and do not enforce a stable contract.
- The release report still contains mojibake, so the encoding cleanup is not closed.

## P0: Security And Release Closure

### Agent A: Remove Raw JDBC URL And Credential Leakage

**Required changes**

- Stop logging arbitrary `resourceKey` values directly in `AuditLogService` success, failure, and error messages.
- Define a safe audit resource-key policy. A connection test must use a stable identifier such as `connection-test`, a data-source key, or a fully sanitized value; it must never use the original URL.
- Fix every `DataSourceConfigService.testConnection` path, including validation exceptions thrown before the SQL connection attempt.
- Sanitize exception messages before they reach audit details, logs, or API responses.
- Preserve useful diagnostics through safe categories such as `invalid-url`, `connection-refused`, and `unsupported-driver`.

**Acceptance**

- No runtime log contains a raw JDBC URL, username, password, token, or query credential.
- Add a regression test that submits a URL containing `user=admin&password=secret123` and verifies that neither application logs nor persisted audit details contain those values.
- Add coverage for both the `SQLException` path and the outer validation-exception path.
- `mvn -Dtest=DataSourceConfigSanitizationTest,AuditFailureIntegrationTest test` passes.
- A source scan finds no direct logging of the raw connection URL or password.

### Agent B: Execute Real PostgreSQL Acceptance And Generate Evidence

**Required changes**

- Run the canonical release gate from the repository root in default Testcontainers mode.
- Confirm that Gate 3 runs `PostgreSqlIntegrationTest`, produces a non-empty surefire XML report, and passes `ReleaseGateVerifier`.
- If external mode is also validated, use `-RequireExternalPg` with real `PG_URL`, `PG_USERNAME`, and `PG_PASSWORD` values supplied outside source control.
- Regenerate `RELEASE_GATE_RESULT.md` from the script. Do not hand-edit a PASS result.
- Ensure the report contains all four gates and the actual timestamp, mode, test count, failures, errors, skipped count, and verifier result.

**Acceptance**

- `.\scripts\release-gate.ps1` exits zero in a Docker-enabled environment.
- `target/surefire-reports/TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` exists with tests greater than zero and zero failures, errors, and skipped tests.
- `ReleaseGateVerifier` reports PASS for the PostgreSQL report.
- `RELEASE_GATE_RESULT.md` says `ALL GATES PASSED` only after all four gates actually pass.
- If Docker or external PostgreSQL is unavailable, the task remains BLOCKED and the report must not claim success.

### Agent C: Make Error Status And Envelope Contracts Strict

**Required changes**

- Convert the existing error-envelope tests from permissive assertions to exact contract assertions.
- Define the expected HTTP status and JSON shape for:
  - validation: 400;
  - unauthenticated: 401;
  - forbidden: 403;
  - not found: 404;
  - conflict: 409;
  - service unavailable: 503;
  - generic internal error: 500.
- Fix exception handlers or controller behavior where the implementation does not provide the documented status.
- Ensure every error response excludes stack traces, SQL, JDBC URLs, credentials, tokens, and internal class names.
- Cover both supported colon and slash route forms.

**Acceptance**

- Every listed category has at least one test asserting one exact status code, not a set of alternatives.
- Every listed category has the same documented top-level error envelope.
- Sensitive-data assertions fail if any raw credential, JDBC URL, SQL fragment, or stack trace appears.
- `mvn -Dtest=ErrorEnvelopeTest,ApiCompatibilityTest test` passes.

### Agent D: Regenerate And Clean Release Documentation

**Required changes**

- Replace the placeholder `RELEASE_GATE_RESULT.md` only through the canonical release script.
- Remove mojibake from the release report, completion summaries, test JavaDoc, workflow comments, and changed documentation.
- Ensure `README.md`, `BACKEND_OPERATION_GUIDE.md`, and `RELEASE_READINESS_CHECKLIST.md` describe the same PostgreSQL modes and gate requirements.
- Keep Gate 3 mandatory for release. A skipped Gate 3 must result in a non-zero gate outcome.

**Acceptance**

- The repository-wide changed-file scan finds no accidental mojibake.
- `git diff --check` passes.
- Documentation states the exact commands for default Testcontainers mode and explicit external mode.
- The release report is current and consistent with surefire XML counts.

## P1: CI And Environment Reproducibility

### Agent E: Harden The CI Release Gate Workflow

**Required changes**

- Keep CI on the canonical `scripts/release-gate.ps1` path; do not reimplement gates in YAML.
- Run the script with PowerShell Core on Ubuntu and Docker available for Testcontainers.
- Do not inject fake `PG_URL`, `PG_USERNAME`, or `PG_PASSWORD` values in the default job.
- Upload the release report and surefire reports on both success and failure.
- Make the final job result fail whenever the canonical script exits non-zero, while preserving artifacts for diagnosis.

**Acceptance**

- A workflow test or static architecture test verifies the canonical script invocation and absence of fake PG credentials.
- CI fails when Gate 3, Gate 4, or `ReleaseGateVerifier` fails.
- CI artifacts include `RELEASE_GATE_RESULT.md` and `target/surefire-reports` on failure.
- The workflow YAML is valid and uses platform-independent script paths.

### Agent F: Add Environment And PostgreSQL Reproducibility Checks

**Required changes**

- Document the exact Docker, Java 17, Maven, and PowerShell Core prerequisites.
- Add a deterministic check for partial external PostgreSQL configuration.
- Add a deterministic check that default mode does not require external PG variables.
- Verify that the PostgreSQL profile does not silently exclude all acceptance tests.
- Where practical, add a fresh PostgreSQL migration validation path using Testcontainers without committing generated database files.

**Acceptance**

- Default mode, external mode, and partial external mode each have executable coverage.
- A missing or empty PostgreSQL XML report fails the gate.
- Fresh PostgreSQL migration validation succeeds in the Docker-enabled environment.
- No test requires hard-coded local usernames, passwords, or host paths.

## P1: Audit And Compatibility Regression Protection

### Agent G: Enforce Audit Matrix Against Implementation

**Required changes**

- Update `AUDIT_COVERAGE_MATRIX.md` only after verifying implementation behavior.
- Add architecture checks that mutation entry points use an explicit transaction boundary where the matrix claims `REQUIRED`.
- Add rollback tests for dynamic CRUD, plugin mutation, and data-source mutation when a success audit write fails.
- Verify failure audits for both thrown exceptions and returned failure maps.
- Ensure audit details contain metadata only and never full records, credentials, SQL, or raw URLs.

**Acceptance**

- Matrix counts match executable rows and transaction behavior.
- Removing a required transaction annotation causes an architecture test to fail.
- Audit failure rollback tests pass for representative mutation paths.
- `mvn -Dtest=ArchitectureBoundaryTest,AuditFailureIntegrationTest test` passes.

### Agent H: Freeze Frontend Contract Trace Coverage

**Required changes**

- Keep the current 73-step sanitized startup trace passing.
- Add explicit trace coverage for any remaining relation, ACL action/scope, data-source, bootstrap, and dynamic CRUD paths not already represented by the fixture set.
- Keep unauthenticated requests at 401 and authenticated-but-forbidden requests at 403.
- Add a fixture coverage report listing each required backend flow and its source fixture.

**Acceptance**

- All required flows are represented in sanitized fixtures and replay tests.
- Trace replay reports zero failures and no raw secrets.
- No frontend files or raw HAR files are committed.

## P2: Production Readiness After Release Closure

### Agent I: Production Operations And Rollback Validation

**Required changes**

- Validate health endpoints in a packaged application profile.
- Document and test startup with production-style secrets supplied through environment variables.
- Verify Flyway migration, backup, rollback, and previous-version deployment procedures in an isolated environment.
- Confirm production logs do not expose generated development passwords, credentials, JDBC URLs, or stack traces.

**Acceptance**

- `/api/health/live` and `/api/health/ready` behave as documented in the packaged application.
- Production configuration fails fast when required secrets are missing or weak.
- Rollback steps are executable and documented with expected artifacts.
- No production-readiness claim is made without environment-specific evidence.

## Required Final Verification

Run from the repository root after P0 and P1 tasks:

```powershell
mvn test
mvn flyway:validate
.\scripts\release-gate.ps1
git diff --check
```

Run the canonical gate in CI or PowerShell Core as well:

```bash
pwsh ./scripts/release-gate.ps1
```

Expected final state:

- `mvn test`: all tests pass with zero failures, errors, and skipped tests.
- PostgreSQL acceptance runs against real PostgreSQL through Testcontainers or explicitly configured external PostgreSQL.
- `ReleaseGateVerifier` passes a non-empty PostgreSQL surefire report.
- All four release gates pass; no gate is silently skipped.
- `RELEASE_GATE_RESULT.md` is generated from the actual run and reports `ALL GATES PASSED`.
- No raw JDBC URL, credential, token, SQL, or stack trace appears in logs, audit records, reports, fixtures, or API responses.
- Error status codes and envelopes are exact and documented.
- CI runs the canonical gate and fails correctly.
- No frontend files are modified.

## Definition Of Done

A task is complete only when its implementation, targeted tests, acceptance commands, documentation, and generated evidence agree. Passing `mvn test` alone does not close the release task while PostgreSQL acceptance, sensitive-log verification, or the canonical release report remains incomplete.
