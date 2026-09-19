# NEXT_PHASE17_REVIEW_FIX_AND_PHASE18_TASKS

> Target repo: `D:\Project\nocobase-java`  
> Scope: backend only. Keep the existing NocoBase frontend unchanged.

## Architect Review Conclusion

Phase17 fixed the main release-gate direction: Gate 3 no longer blocks Testcontainers before Maven starts, `ReleaseGateVerifier` supports positional report paths, health tests were added, and audit matrix drift tests exist.

But this phase is **not fully release-closed** yet because the final release evidence is incomplete:

- `RELEASE_GATE_RESULT.md` is missing, so there is no committed/current PASS report proving all gates actually ran.
- `.github/workflows/release-gate.yml` does not invoke `scripts/release-gate.ps1`; it reimplements only Gates 1-3 and omits Gate 4 sensitive scan and ReleaseGateVerifier authority.
- CI sets `PG_URL=jdbc:postgresql://localhost:5432/testdb` without a PostgreSQL service. Testcontainers currently masks this through system properties, but the workflow is misleading and should not inject fake external PG env vars.
- `PostgreSqlTestContainerSupport` logs the raw Testcontainers JDBC URL. This violates the no-secret/no-internal-URL principle.
- Some docs/source comments still render as mojibake in PowerShell output, including the Phase17 summary and PostgreSQL test JavaDoc.

## P0: Release Evidence And CI Must Match The Real Gate

### Agent A: Produce Real Local Release Gate PASS Evidence

**Required changes**

- Run the actual release script from the repo root:

```powershell
.\scripts\release-gate.ps1
```

- Keep the generated `RELEASE_GATE_RESULT.md` in the repo as the current release evidence.
- The report must include all four gates:
  - Gate 1: `mvn test`
  - Gate 2: `mvn flyway:validate`
  - Gate 3: `mvn test -Ppostgresql-acceptance`
  - Gate 4: Sensitive code scan
- The report must include ReleaseGateVerifier status for Gate 3.

**Acceptance**

- `RELEASE_GATE_RESULT.md` exists.
- `Overall Result` is `ALL GATES PASSED` only if all four gates pass.
- Gate 3 report includes `TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml` with tests > 0, failures = 0, errors = 0, skipped = 0.
- No stale `NoGoalSpecifiedException` text remains.

### Agent B: Make CI Execute The Same Release Gate Script

**Required changes**

- Refactor `.github/workflows/release-gate.yml` so CI invokes the canonical script instead of reimplementing gates in YAML.
- Use PowerShell on Ubuntu:

```yaml
run: pwsh ./scripts/release-gate.ps1
```

- Do not set fake `PG_URL`, `PG_USERNAME`, or `PG_PASSWORD` in CI by default.
- Let Testcontainers start PostgreSQL in the default CI mode.
- Upload `RELEASE_GATE_RESULT.md` and `target/surefire-reports` artifacts on both success and failure.

**Acceptance**

- CI runs all four gates through the same path as local release validation.
- CI fails if Gate 4 sensitive scan fails.
- CI fails if ReleaseGateVerifier rejects the PG report.
- CI does not contain duplicate shell parsing of surefire XML.

### Agent C: Add Script-Level Release Gate Tests

**Required changes**

- Add tests or a lightweight verifier that validates `scripts/release-gate.ps1` contains the required canonical checks:
  - uses `-MavenArgs`, not PowerShell `$Args`;
  - supports `-RequireExternalPg`;
  - default path does not require PG env vars;
  - invokes `ReleaseGateVerifier` for PG XML;
  - generates `RELEASE_GATE_RESULT.md`;
  - exits non-zero on failed gates.
- Prefer Java architecture tests if the current test suite already uses that pattern.

**Acceptance**

- A regression that removes ReleaseGateVerifier from the script fails tests.
- A regression that reintroduces mandatory PG env precheck in default mode fails tests.

## P0: Remove Secret/Internal URL Leakage

### Agent D: Sanitize PostgreSQL Testcontainers Logging

**Required changes**

- Replace raw `POSTGRES.getJdbcUrl()` logging in `PostgreSqlTestContainerSupport` with a sanitized host/db-only or mode-only message.
- Do not print username, password, mapped port, full JDBC URL, or query params.
- Make sensitive scan catch raw JDBC URL logging in test infrastructure if possible, or add a focused test around the support class/source.

**Acceptance**

- `rg -n "getJdbcUrl\(\)|jdbc:postgresql|PG_PASSWORD|PG_USERNAME" src/test/java/com/nocobase/postgresql scripts .github README.md BACKEND_OPERATION_GUIDE.md` has no unsafe logging or fake CI env usage.
- `mvn test` passes.

## P1: Encoding And Documentation Closure

### Agent E: Fix Mojibake In Changed Files

**Required changes**

- Rewrite mojibake in Phase summary docs, PostgreSQL test JavaDoc, workflow comments, and any changed source comments.
- Prefer ASCII in scripts/workflows/source comments unless Chinese text is necessary.
- Keep README/operation docs bilingual where user-facing.

**Acceptance**

- This command returns no accidental mojibake in changed files:

```powershell
rg -n "�|鈹|鍚|瀹|涓|骞|绁|濈|煎|叩|鏃|娴|缁|鐩|鐨|诲|粨|骇|鍥|淇|敼|鍙" *.md scripts src .github
```

- `git diff --check` passes.

### Agent F: Align Release Documentation With Actual Behavior

**Required changes**

- Update README and operation guide so release instructions match the canonical script.
- Document both modes:
  - default Testcontainers mode;
  - `-RequireExternalPg` mode for external PostgreSQL.
- Document required local prerequisites: Java 17, Maven, Docker for Testcontainers.
- Explain that `RELEASE_GATE_RESULT.md` is the release evidence artifact.

**Acceptance**

- A new developer can run the full release gate from docs without setting PG env vars.
- External PG mode is documented as optional/explicit, not the default.

## P1: Phase18 Backend Compatibility Expansion

### Agent G: Frontend Contract Trace Expansion

**Required changes**

- Add or update sanitized backend contract traces for these frontend-visible backend flows:
  - bootstrap/setup;
  - login/session/current user;
  - collections and fields metadata;
  - dynamic CRUD list/get/create/update/destroy;
  - relation/association actions;
  - ACL roles/actions/scopes;
  - UI schema;
  - plugins;
  - system settings;
  - data sources.
- Use sanitized fixtures only. Raw HAR files must stay ignored.

**Acceptance**

- Contract replay tests cover all listed areas.
- Response envelopes remain compatible with current frontend expectations.
- No frontend files are modified.

### Agent H: API Error Envelope Compatibility Matrix

**Required changes**

- Document and test standard backend error envelopes for:
  - validation error;
  - unauthenticated;
  - forbidden;
  - not found;
  - conflict;
  - service unavailable;
  - generic internal error.
- Verify controllers return stable JSON shapes consumed by the existing frontend.

**Acceptance**

- Error envelope tests pass under `mvn test`.
- No stack traces, SQL, JDBC URLs, or secrets appear in API error responses.

## Required Final Verification

After all tasks complete, run:

```powershell
mvn test
.\scripts\release-gate.ps1
git diff --check
```

Expected final state:

- `mvn test`: all tests pass, 0 skipped.
- `RELEASE_GATE_RESULT.md`: exists and says `ALL GATES PASSED`.
- CI workflow runs `scripts/release-gate.ps1`, not a separate partial gate implementation.
- No raw JDBC URLs or credentials are logged.
- No frontend files changed.
