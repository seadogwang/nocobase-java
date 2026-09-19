# NEXT_PHASE20_REVIEW_FIX_AND_PHASE21_TASKS

> Target repo: `D:\Project\nocobase-java`
> Scope: backend only. Do not modify the existing NocoBase frontend.
> Goal: close the remaining contract, reproducibility, encoding, and production-evidence gaps.

## Review Of The Previous Nine Agents

The current commit is `5c16a2e`, and the generated report points to the same commit.

| Agent | Review result | Remaining concern |
|---|---|---|
| A Security sanitization | Implemented and covered by tests | Keep log-capture coverage so future changes cannot reintroduce raw URL logging |
| B PostgreSQL acceptance | Verified for default Testcontainers mode | External PostgreSQL mode still needs a real execution record |
| C Error contract | Mostly implemented | The 503 method in `ErrorEnvelopeTest` is not annotated with `@Test`; the 500 test accepts any 5xx instead of exactly 500 |
| D Release documentation | Real four-gate report exists | Documentation and source comments still contain mojibake outside the report scan |
| E CI hardening | Static workflow test exists | No completed Ubuntu CI run and artifact evidence is present in the repository |
| F Environment reproducibility | Partially implemented | Partial external configuration and external-mode execution need end-to-end evidence |
| G Audit matrix | Implemented with rollback tests | Continue protecting the matrix and log sanitization contract |
| H Frontend trace | Replay coverage is present | Keep the flow inventory and prevent fixture drift |
| I Production operations | Health/config tests and rollback docs exist | No packaged production-profile smoke test or actual restore/rollback drill is evidenced |

## Verified Evidence

- `mvn test`: 1074 tests, zero failures, errors, and skipped tests in the current release report.
- `mvn flyway:validate`: passed.
- PostgreSQL acceptance: 29 tests, zero failures, errors, and skipped tests through Testcontainers.
- `ReleaseGateVerifier`: PASS for `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml`.
- Gate 4: zero error-level sensitive scan findings.
- `git diff --check`: must remain required after every task.

## P0: Close Contract And Reproducibility Gaps

### Agent A: Enforce Exact API Error Contracts

**Required changes**

- Make the service-unavailable test an actual `@Test` and exercise a real API path that returns HTTP 503.
- Change the internal-error test to require exactly HTTP 500, not any 5xx status.
- Keep exact assertions for 400, 401, 403, 404, 409, 500, and 503.
- Verify every category uses the documented `{ "errors": [{ "message": "..." }] }` envelope.
- Preserve sensitive-data scrubbing for SQL, JDBC URLs, credentials, tokens, stack traces, and class names.

**Acceptance**

- `ErrorEnvelopeTest` has executable tests for all seven status categories.
- No assertion accepts a range or a set of alternative status codes for a category.
- `mvn -Dtest=ErrorEnvelopeTest,GlobalExceptionHandlerTest test` passes.
- Removing the 503 handler or changing 500 to a different status causes a test failure.

### Agent B: Prove External PostgreSQL Mode End To End

**Required changes**

- Run `postgresql.external.pg=true` against a real external PostgreSQL instance, not Testcontainers.
- Verify the test support class does not start a container and does not overwrite external connection properties.
- Exercise the partial configuration path with one or two missing PG values and verify it fails before tests run.
- Keep secrets outside source control and redact all connection data from output and reports.
- Add a deterministic test for the mode-selection decision so default and external modes cannot silently switch.

**Acceptance**

- External mode produces a non-empty PostgreSQL surefire report with zero failures, errors, and skipped tests.
- Partial external configuration exits non-zero with only missing variable names, never values.
- The report records the selected mode without recording the JDBC URL or credentials.
- If no external PostgreSQL environment is available, mark this task BLOCKED with the missing prerequisite; do not mark it complete from static tests alone.

### Agent C: Execute The Canonical Gate In Ubuntu CI

**Required changes**

- Run `.github/workflows/release-gate.yml` on a pull request or controlled branch push.
- Confirm Ubuntu PowerShell Core executes `pwsh ./scripts/release-gate.ps1` successfully with Docker-backed Testcontainers.
- Download and inspect the CI artifacts for `RELEASE_GATE_RESULT.md` and `target/surefire-reports`.
- Verify the final Gate Decision fails when the canonical script exits non-zero while still uploading diagnostics.
- Record the CI run URL or run identifier in the completion summary without exposing secrets.

**Acceptance**

- A real CI run completes all four gates and produces the expected artifacts.
- A controlled failure test proves the workflow blocks release when the script fails.
- No fake `PG_URL`, `PG_USERNAME`, or `PG_PASSWORD` values are injected by the workflow.
- The completion summary includes reproducible CI evidence, not only a static YAML test result.

### Agent D: Add A Strict Encoding Gate

**Required changes**

- Remove remaining mojibake from changed JavaDoc, source comments, task summaries, workflow comments, and operational documentation.
- Extend the encoding test or release scan to inspect changed files under `src`, `scripts`, `.github`, `docs`, and root Markdown files.
- Use UTF-8 validation plus an explicit accidental-mojibake pattern list.
- Ensure generated release reports are checked by the same encoding gate.

**Acceptance**

- The scan catches strings such as `鈥?` and the replacement character `�` in changed files.
- The scan returns no findings after cleanup.
- `git diff --check` passes.
- A test fixture containing known mojibake makes the encoding test fail.

## P1: Production Evidence And Rollback

### Agent E: Add Packaged Production Smoke Tests

**Required changes**

- Build the application artifact and start it with a production-like profile, not only `@ActiveProfiles("test")` MockMvc tests.
- Supply JWT and data-source encryption secrets through environment variables.
- Verify startup fails when required secrets are missing, weak, or default.
- Verify startup succeeds with valid secrets and a real PostgreSQL database.
- Exercise unauthenticated `/api/health/live` and `/api/health/ready` against the running packaged application.

**Acceptance**

- A documented smoke script or integration test starts and stops the packaged application deterministically.
- Health endpoints return the documented status and do not expose secrets or JDBC URLs.
- Missing production secrets produce a clear non-secret failure before serving traffic.
- The evidence records the profile, artifact version, database mode, and command results.

### Agent F: Perform An Executable Backup And Restore Drill

**Required changes**

- Use an isolated PostgreSQL environment or Testcontainers volume to create a backup before migration.
- Apply the current Flyway migrations and representative metadata writes.
- Restore the backup into a clean database and verify schema, `flyway_schema_history`, and representative data.
- Verify application rollback to the previous JAR or packaged artifact after database restore.
- Document limitations clearly: Flyway does not automatically undo applied migrations.

**Acceptance**

- The drill is executable from documented commands without production credentials.
- Restore verification checks both schema and data, not only process exit codes.
- A smoke request confirms the restored application is serving traffic.
- The drill produces a timestamped, non-secret evidence record.

### Agent G: Strengthen Release Evidence Consistency

**Required changes**

- Add a verifier test that compares the report commit, test totals, PostgreSQL XML totals, and current generated artifacts.
- Reject a report whose commit does not match `HEAD` when running in a clean release workspace.
- Reject `ALL GATES PASSED` when any gate is skipped, missing, stale, or represented only by a placeholder.
- Keep informational Gate 4 findings separate from error-level findings.

**Acceptance**

- Tampering with the report commit, PG test count, or Gate 3 status makes verification fail.
- A placeholder report cannot pass the release verifier.
- A freshly generated report passes with the actual current commit and surefire totals.

## P2: Post-Release Engineering

### Agent H: Add Operational Observability Checks

**Required changes**

- Add health and startup diagnostics that expose component state without secrets, SQL, credentials, or internal URLs.
- Define structured fields for release version, migration state, database mode, and request ID.
- Verify error and audit logs remain sanitized at INFO, WARN, and ERROR levels.
- Add bounded log-volume or repeated-failure checks for unavailable external data sources.

**Acceptance**

- Observability output is documented and tested in the packaged smoke environment.
- Sensitive-value regression tests cover all three log levels.
- Repeated datasource failures do not create unbounded sensitive logs.

### Agent I: Freeze Contract And Evidence Maintenance

**Required changes**

- Add a checklist test that requires each frontend contract flow to map to a fixture and replay test.
- Require release summaries to include test, PG, CI, encoding, and rollback evidence links or identifiers.
- Update task documents so completed work is not reclassified as complete without current evidence.
- Keep all raw HAR files, credentials, tokens, and generated database dumps excluded from Git.

**Acceptance**

- Removing a required contract fixture or evidence field causes a test failure.
- The next completion summary can be audited from repository files and CI artifacts.
- `git status` contains no untracked secrets, raw HAR files, database dumps, or runtime credentials.

## Required Final Verification

Run after P0 and P1 tasks:

```powershell
mvn test
mvn flyway:validate
.\scripts\release-gate.ps1
git diff --check
```

Also complete:

```bash
pwsh ./scripts/release-gate.ps1
```

Expected final state:

- All seven API error categories have exact executable contracts.
- Default Testcontainers and external PostgreSQL modes both have evidence, or the unavailable external prerequisite is explicitly recorded as BLOCKED.
- Ubuntu CI has a real successful run with uploaded artifacts.
- Changed files contain no mojibake.
- Packaged production smoke test and backup/restore drill have executable evidence.
- Release report matches `HEAD`, surefire XML, verifier output, and current gate results.
- No frontend files are modified.

## Definition Of Done

A task is complete only when implementation, targeted tests, environment execution, documentation, and evidence agree. Static assertions alone do not close CI, external PostgreSQL, packaged production, or rollback tasks.
