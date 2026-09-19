# NEXT_PHASE16_REVIEW_FIX_AND_PHASE17_TASKS

> Target repo: `D:\Project\nocobase-java`  
> Principle: backend only. Do not change the existing NocoBase frontend contract unless a task explicitly says to add backend-compatible coverage.

## Architect Review Conclusion

Phase16 improves the release hardening baseline, and the normal Maven test evidence is valid: local surefire reports currently show `1014 tests, 0 failures, 0 errors, 0 skipped`.

However, Phase16 is **not release-closed yet**. `RELEASE_GATE_RESULT.md` still records `GATES FAILED`, and the release script still short-circuits PostgreSQL acceptance when `PG_URL`, `PG_USERNAME`, and `PG_PASSWORD` are absent. This conflicts with the reported Testcontainers fallback. The next batch must close this before adding new feature work.

## P0: Release Gate Must Produce A Real PASS

### Agent A: Fix Gate 3 PostgreSQL Execution Mode

**Problem**

`scripts/release-gate.ps1` checks `PG_URL`, `PG_USERNAME`, and `PG_PASSWORD` before invoking `mvn test -Ppostgresql-acceptance`, so Testcontainers never gets a chance to start when env vars are absent.

**Required changes**

- Support two valid Gate 3 modes:
  - `external`: requires `PG_URL`, `PG_USERNAME`, `PG_PASSWORD` and runs against external PostgreSQL.
  - `testcontainers`: does not require PG env vars and runs `mvn test -Ppostgresql-acceptance` so `PostgreSqlTestContainerSupport` starts PostgreSQL.
- Make `testcontainers` the default local/CI mode unless explicit external PG vars are provided.
- Do not log raw JDBC URLs, usernames, passwords, or container credentials in script output or test logs.
- Update `PostgreSqlIntegrationTest` comments/assertion messages so they no longer claim env vars are always mandatory when Testcontainers mode is used.

**Acceptance**

- Running `scripts/release-gate.ps1` without PG env vars executes Gate 3 instead of failing at precheck.
- `RELEASE_GATE_RESULT.md` includes `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` with tests > 0.
- Gate 3 fails if the PG test report is missing, has 0 tests, failures, errors, or skipped tests.

### Agent B: Integrate ReleaseGateVerifier Into The Script

**Problem**

`ReleaseGateVerifier` is implemented and tested, but `scripts/release-gate.ps1` still duplicates PG report validation internally. This makes the verifier a side artifact rather than the release gate authority.

**Required changes**

- Invoke `com.nocobase.release.ReleaseGateVerifier verify` from `release-gate.ps1` after Gate 3 test execution.
- Ensure `target/classes` or the relevant test/runtime classpath exists before invoking the verifier.
- Parse the verifier exit code and include verifier output in `RELEASE_GATE_RESULT.md`.
- Keep PowerShell parsing minimal; report correctness should come from the Java verifier.

**Acceptance**

- Unit tests still cover `ReleaseGateVerifier` edge cases.
- Script-level output includes a clear verifier PASS/FAIL line.
- A missing PG XML report causes overall release gate failure through the verifier path.

### Agent C: Add CI Release Gate Workflow

**Problem**

`.github/workflows` is absent, so the reported CI workflow was not delivered.

**Required changes**

- Add `.github/workflows/release-gate.yml`.
- Run on pull request and push to `main`.
- Set up Java 17 and Maven cache.
- Run the release gate script in Testcontainers mode.
- Upload `RELEASE_GATE_RESULT.md` and `target/surefire-reports` as artifacts on success and failure.

**Acceptance**

- Workflow YAML is present and syntactically valid.
- CI command does not require external PG secrets by default.
- Documentation explains how to switch to external PostgreSQL mode later if needed.

### Agent D: Regenerate And Validate Release Gate Result

**Problem**

Current `RELEASE_GATE_RESULT.md` still says all release gates failed, including the old `NoGoalSpecifiedException`.

**Required changes**

- Rerun the final release gate after Agents A-C are complete.
- Replace `RELEASE_GATE_RESULT.md` with a current result.
- Ensure the final report says `ALL GATES PASSED` only when all four gates actually pass.

**Acceptance**

- `RELEASE_GATE_RESULT.md` shows Gate 1 PASS, Gate 2 PASS, Gate 3 PASS, Gate 4 PASS.
- Overall result is PASS.
- Test count in the report is consistent with surefire XML totals.

## P1: Production Hardening Gaps

### Agent E: Add Health Endpoint Tests

**Required changes**

- Add integration tests for:
  - `GET /api/health/live` returns HTTP 200 and `status=UP`.
  - `GET /api/health/ready` returns HTTP 200 when DB/Flyway are healthy.
  - `GET /api/health/ready` returns HTTP 503 and `status=DOWN` when DB check fails.
  - `GET /api/health` remains backward-compatible.
- Verify `/api/health`, `/api/health/live`, and `/api/health/ready` are accessible without authentication.

**Acceptance**

- Health tests run under normal `mvn test`.
- No secrets, JDBC URLs, usernames, or internal stack traces appear in responses.

### Agent F: Stabilize Audit Matrix Against Drift

**Required changes**

- Add a test that validates `AUDIT_COVERAGE_MATRIX.md` has no `X` gaps and summary counts match the declared rows.
- Add architecture tests for write entry points that should call audit logging on success and failure.
- Keep matrix updates required when new write APIs are added.

**Acceptance**

- The matrix cannot claim 100% if any row still contains an uncovered success/failure/requestId/actor/sanitization cell.
- Tests fail when a new write service is added without corresponding audit coverage documentation or implementation.

### Agent G: Clean Encoding-Sensitive Source Comments And Docs

**Required changes**

- Replace decorative Unicode separator comments in Java/PowerShell files with ASCII-safe comments.
- Ensure markdown docs are valid UTF-8 and render correctly in PowerShell, GitHub, and IDEs.
- Keep bilingual README/CHANGELOG text readable; do not leave mojibake.

**Acceptance**

- `rg -n "�|鈹|鍚|瀹|涓|骞|绁|濈|煎|叩" *.md scripts src` returns no accidental mojibake in changed files.
- `git diff --check` passes.

## P2: Phase17 Backend Compatibility Expansion

### Agent H: Extend Frontend Contract Trace Coverage

**Required changes**

- Convert at least one real sanitized HAR from current frontend flows into backend contract trace JSON.
- Cover login/bootstrap, collections list, fields list, ACL/roles, UI schema, plugins, system settings, and data source APIs.
- Keep HAR fixtures sanitized and small; raw HAR files must remain ignored.

**Acceptance**

- Contract replay tests prove all required backend endpoints return frontend-compatible response envelopes.
- Trace docs record source, sanitization steps, and covered APIs.

### Agent I: Add Backend Release Checklist Docs

**Required changes**

- Update README and operation guide with:
  - release gate commands,
  - Testcontainers vs external PostgreSQL mode,
  - health probe endpoints,
  - rollback path,
  - expected release artifacts.
- Keep Chinese and English summaries aligned.

**Acceptance**

- A new developer can run the full gate using only README/operation guide instructions.
- Docs match the actual script behavior and CI workflow.

## Required Final Verification

After all P0/P1 tasks finish, run:

```powershell
mvn test
.\scripts\release-gate.ps1
```

Expected final state:

- `mvn test`: all tests pass, 0 skipped.
- `release-gate.ps1`: all four gates pass.
- `RELEASE_GATE_RESULT.md`: current timestamp and `ALL GATES PASSED`.
- `git diff --check`: no whitespace errors.
- No frontend files changed.
