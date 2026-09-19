# NEXT_PHASE18_REVIEW_FIX_AND_PHASE19_TASKS

> Target repo: `D:\Project\nocobase-java`
> Scope: backend only. Do not modify the existing NocoBase frontend.
> Execution rule: complete P0 tasks in order, then complete P1 tasks. Do not declare a task done from file existence alone; every acceptance command must pass.

## Current Baseline

The previous Phase17 completion summary is not a valid release record. Current evidence is:

- `mvn test` does not reach test execution. Test compilation fails because `PostgreSqlTestContainerSupport` references an undefined `log` at line 44.
- `mvn flyway:validate` passes for the six current migrations.
- `mvn package "-Dmaven.test.skip=true"` passes, so main production sources compile.
- `RELEASE_GATE_RESULT.md` is stale. It marks Gate 3 as skipped while reporting the overall result as passed.
- `.github/workflows/release-gate.yml` invokes the canonical script, but the script contains Windows-style paths and has not been proven on Ubuntu PowerShell.
- PostgreSQL test support always starts Testcontainers and overwrites database properties, even when external PostgreSQL mode is requested.
- The audit matrix claims 100% transactional coverage, but several mutation entry points do not define a business transaction boundary.
- Contract fixtures and error tests exist, but coverage against the Phase18 requirements has not been demonstrated.

## P0: Restore A Verifiable Test And Release Baseline

### Agent A: Fix Test Compilation And Logging

**Required changes**

- Fix `src/test/java/com/nocobase/postgresql/PostgreSqlTestContainerSupport.java` so it compiles using the logging convention already used by the project.
- Do not log the full JDBC URL, mapped port, username, password, or query parameters.
- Keep the startup message limited to safe mode/image information.
- Do not change the PostgreSQL acceptance contract merely to hide the compile error.

**Acceptance**

- `mvn test` reaches test execution.
- No raw JDBC URL or credential appears in the test output.
- `mvn test` passes with zero failures, errors, and skipped tests.

### Agent B: Correct PostgreSQL Testcontainers And External Modes

**Required changes**

- Define two explicit modes for PostgreSQL acceptance:
  - default Testcontainers mode when external PostgreSQL is not explicitly requested;
  - external mode when `-RequireExternalPg` is used with `PG_URL`, `PG_USERNAME`, and `PG_PASSWORD`.
- In external mode, never start Testcontainers and never overwrite external connection properties.
- In default mode, start Testcontainers and provide the connection values required by `PostgreSqlIntegrationTest` without logging them.
- Reject partially configured external mode with a clear non-secret error.
- Keep the Maven profile and the release script behavior consistent.

**Acceptance**

- Default `mvn test -Ppostgresql-acceptance` uses Testcontainers when Docker is available.
- `scripts/release-gate.ps1 -RequireExternalPg` uses only the supplied external PostgreSQL values.
- A partial external configuration fails before tests run and does not expose credentials.
- Tests prove that external mode does not call `POSTGRES.start()`.

### Agent C: Make The Canonical Release Script Cross-Platform

**Required changes**

- Replace hard-coded Windows path separators in `scripts/release-gate.ps1` with PowerShell path APIs such as `Join-Path`.
- Verify report, classpath, surefire, and project-root paths under both Windows PowerShell and PowerShell Core on Ubuntu.
- Keep `ReleaseGateVerifier` as the authority for PostgreSQL surefire report validation.
- Preserve non-zero exit status whenever any mandatory gate fails.
- Keep Gate 4 sensitive scanning in the canonical script; do not duplicate or omit it in CI YAML.

**Acceptance**

- `pwsh ./scripts/release-gate.ps1` runs from the Ubuntu GitHub Actions working directory.
- `.scripts\release-gate.ps1` runs from the Windows repository root.
- Both modes generate the same report structure and fail on missing/empty/failed PostgreSQL XML.
- No raw JDBC URL, password, or credential is written to the report.

### Agent D: Add Release Script Regression Tests

**Required changes**

- Add architecture or script-level tests for the canonical release script.
- Verify that the script:
  - uses `-MavenArgs` rather than PowerShell automatic `$Args`;
  - supports `-RequireExternalPg`;
  - does not require PG variables in default Testcontainers mode;
  - invokes `ReleaseGateVerifier`;
  - generates `RELEASE_GATE_RESULT.md`;
  - exits non-zero when a mandatory gate fails;
  - uses platform-independent path construction for CI paths.
- Keep tests deterministic and independent of Docker.

**Acceptance**

- Removing the verifier invocation makes a test fail.
- Reintroducing mandatory PG environment checks in default mode makes a test fail.
- `mvn test` passes with these regression tests enabled.

## P1: Close Audit Transaction And Coverage Gaps

### Agent E: Align Audit Transactions With Business Writes

**Required changes**

- Review and fix transaction boundaries for mutation entry points in:
  - `DynamicRepository`;
  - `PluginModuleRegistry`;
  - `DataSourceConfigService`;
  - any other write service listed in `AUDIT_COVERAGE_MATRIX.md`.
- Ensure a successful audit using `Propagation.REQUIRED` is in the same transaction as the business mutation when rollback is promised.
- Ensure failure paths that return a failure response, rather than throwing, still produce the documented failure audit.
- Either implement the missing behavior or mark the matrix behavior accurately; do not leave a claimed 100% row without executable evidence.

**Acceptance**

- Add representative rollback tests for dynamic CRUD, plugin mutation, and data source mutation.
- Add a test for the `DataSourceConfigService.testConnection` failure-return path.
- Audit failure rolls back the business write wherever the matrix says `REQUIRED`.
- `AUDIT_COVERAGE_MATRIX.md` matches the implementation and test coverage.

### Agent F: Complete Frontend Contract Trace Coverage

**Required changes**

- Review the existing sanitized fixtures under `src/test/resources/frontend-contract/` and `src/test/resources/frontend-traces/`.
- Add or update small sanitized traces for all required backend flows:
  - bootstrap/setup;
  - login, session, and current user;
  - collections and fields metadata;
  - dynamic CRUD list/get/create/update/destroy;
  - relation and association actions;
  - ACL roles, actions, and scopes;
  - UI schema;
  - plugins;
  - system settings;
  - data sources.
- Keep raw HAR files ignored and never commit credentials, tokens, cookies, or real JDBC URLs.

**Acceptance**

- Contract replay tests cover every flow listed above.
- Each fixture documents its source and sanitization assumptions.
- Response envelopes remain compatible with the existing frontend contract.
- No frontend source file is modified.

### Agent G: Add A Complete API Error Envelope Matrix

**Required changes**

- Document and test stable JSON error envelopes for:
  - validation error;
  - unauthenticated;
  - forbidden;
  - not found;
  - conflict;
  - service unavailable;
  - generic internal error.
- Verify status codes and response shapes at controller level.
- Ensure error responses do not expose stack traces, SQL, JDBC URLs, credentials, or internal implementation details.
- Keep both colon and slash routes covered where the backend supports both.

**Acceptance**

- Every listed error category has an executable test.
- All error responses use the documented envelope consistently.
- Tests fail if sensitive internals appear in an error response.

## P1: Documentation And Encoding Closure

### Agent H: Align Release Documentation With Actual Behavior

**Required changes**

- Update `README.md`, `BACKEND_OPERATION_GUIDE.md`, and `RELEASE_READINESS_CHECKLIST.md` to match the canonical release script.
- Document default Testcontainers mode and optional `-RequireExternalPg` mode separately.
- Document Java 17, Maven, and Docker prerequisites.
- Document health probes, rollback expectations, generated release artifacts, and the meaning of `RELEASE_GATE_RESULT.md`.
- Remove statements that claim Gate 3 is optional or that external PG variables are mandatory in default mode.

**Acceptance**

- A new developer can run the local release gate without setting PG variables when Docker is available.
- External PostgreSQL setup is documented as an explicit alternative.
- Documentation does not claim release success when Gate 3 is skipped.

### Agent I: Remove Mojibake And Stale Completion Claims

**Required changes**

- Rewrite mojibake in changed summaries, test JavaDoc, workflow comments, and source comments.
- Keep user-facing Chinese/English documentation valid UTF-8 and readable in PowerShell, GitHub, and IDEs.
- Update or regenerate completion summaries so they reflect actual test evidence.
- Do not manufacture a PASS report. The generated report must reflect the command that actually ran.

**Acceptance**

- The repository scan below returns no accidental mojibake in changed files:

```powershell
rg -n "�|鈹|鍚|瀹|涓|骞|绁|濈|煎|叩|鏃|娴|缁|鐩|鐨|诲|粨|骇|鍥|淇|敼|鍙" *.md scripts src .github
```

- `git diff --check` passes.
- Completion summaries agree with the latest test and release reports.

## Required Final Verification

Run from the repository root after all tasks are complete:

```powershell
mvn test
mvn flyway:validate
.\scripts\release-gate.ps1
git diff --check
```

Also verify in CI with PowerShell Core:

```bash
pwsh ./scripts/release-gate.ps1
```

Expected final state:

- `mvn test` passes with zero failures, errors, and skipped tests.
- PostgreSQL acceptance passes in the selected mode with a non-empty surefire report.
- `ReleaseGateVerifier` reports PASS for the PostgreSQL report.
- All four release gates pass; Gate 3 is never silently skipped.
- `RELEASE_GATE_RESULT.md` is current and says `ALL GATES PASSED` only after the commands actually pass.
- CI invokes the canonical script and fails the job when the script exits non-zero.
- No raw JDBC URLs, passwords, tokens, or stack traces appear in logs, reports, fixtures, or API error responses.
- No frontend files are modified.

## Definition Of Done

A task is complete only when its acceptance commands and tests pass, the relevant documentation is updated, and the result is reflected in the final release report. A file being present or a completion summary saying `Done` is not sufficient evidence.
