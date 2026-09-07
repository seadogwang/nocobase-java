# Release Readiness Checklist

This checklist must be completed before every production release of the NocoBase Java backend.
All items are mandatory unless explicitly marked as conditional.

---

## 1. Configuration & Secrets

- [ ] **JWT Secret** (`nocobase.jwt.secret`) is set to a strong, unique value in the production environment.
      Generate with: `openssl rand -base64 64`
- [ ] **Encryption Master Key** (`nocobase.data-source-encryption.master-key`) is a base64-encoded 256-bit
      key (32 bytes). Generate with: `openssl rand -base64 32`
- [ ] **No credentials in application.yml** -- all secrets are sourced from environment variables or
      an external secrets manager (Vault, AWS Secrets Manager, etc.).
- [ ] **Spring profile** is set to a non-dev profile (`prod`, `production`, etc.) that uses
      PostgreSQL configuration.
- [ ] **H2 console** is disabled in production (`spring.h2.console.enabled=false`).
- [ ] **Flyway** is enabled and set to `baseline-on-migrate: true` (for fresh installs) or
      `baseline-on-migrate: false` (for existing databases with manual baseline).

## 2. Database

- [ ] **PostgreSQL** is the target database for production. H2 is not supported for production use.
- [ ] **Flyway migrations** have all been applied. Run `mvn flyway:validate` (or `mvn flyway:migrate` for fresh installs) and verify the
      `flyway_schema_history` table shows all migrations as `SUCCESS`.
- [ ] **No pending schema changes** exist in the `db/migration` directory beyond what is recorded
      in `flyway_schema_history`.
- [ ] **Connection pool** settings are tuned for production load (max pool size, connection timeout,
      idle timeout).
- [ ] **Database backup** exists and restore has been tested within the last 30 days.

## 3. Flyway

- [ ] All migration files in `src/main/resources/db/migration/` follow the naming convention
      `V<version>__<description>.sql` (double underscore after version number).
- [ ] No migration files have been modified after being applied to any environment. New schema
      changes must use a new version number.
- [ ] `flyway.repair` has never been run against any production database (repair is for
      development only).
- [ ] All migrations use quoted identifiers for cross-database compatibility (H2 PostgreSQL mode
      and PostgreSQL).

## 4. ACL / Permission System

- [ ] At least one `admin` or `root` role user exists with login capability.
- [ ] System resources (`users`, `roles`, `collections`, `acl`, `systemSettings`, `uiSchemas`,
      `plugins`, `applicationPlugins`, `auth`) are not exposed to unauthenticated users.
- [ ] Built-in roles (`root`, `admin`, `member`) cannot be deleted or renamed.
- [ ] Data scope filtering is enabled for all non-admin roles accessing collection data.
- [ ] Role resource actions are validated against the actual collection fields.

## 5. SQL Collection

- [ ] SQL collections are validated against a whitelist of allowed SQL operations
      (SELECT only for read-only queries).
- [ ] SQL parameter binding follows the safe named-parameter pattern (`:paramName`).
      No string concatenation of user input into SQL queries.
- [ ] SQL error messages are sanitized before being returned to the client
      (see `SqlErrorSanitizer`).
- [ ] SQL collection runtime context resolves variables safely without exposing
      internal state.

## 6. External Data Sources

- [ ] Only `h2` and `postgresql` JDBC drivers are in the allowed driver whitelist.
- [ ] Only `jdbc:h2:` and `jdbc:postgresql:` URL prefixes are permitted.
- [ ] All external data sources are enforced as read-only (`readOnly=true`).
- [ ] External data source passwords are encrypted at rest with AES-256-GCM.
- [ ] Data source passwords are NEVER returned in API responses.
- [ ] `INIT`, `RUNSCRIPT`, and `ACCESS_MODE_DATA` are forbidden in H2 JDBC URLs.

## 7. Log Sanitization

- [ ] `SqlErrorSanitizer` is applied to all SQL error messages before they reach
      API responses or log output.
- [ ] No passwords, tokens, secrets, or JDBC URLs appear in application logs.
- [ ] Log level in production is set to `INFO` or higher (`WARN` recommended).
- [ ] Sensitive headers (Authorization, Cookie) are not logged.

## 8. Frontend Contract

- [ ] API response format matches the NocoBase frontend protocol:
    - Success: `{ "data": ... }`
    - List: `{ "data": [...], "meta": { "count": N, "page": N, "pageSize": N } }`
    - Error: `{ "errors": [{ "message": "..." }] }`
- [ ] All endpoints support both `/api/resource:action` and `/api/resource/action` URL patterns.
- [ ] Passwords are NEVER returned to the frontend in any response.
- [ ] Pagination parameters (`page`, `pageSize`, `sort`) are consistent across all list endpoints.
- [ ] **No frontend modifications are required** -- this release is backend-only. The frontend
      contract is maintained unchanged.

## 9. PostgreSQL Acceptance

- [ ] **MANDATORY** -- All PostgreSQL acceptance tests pass against a real PostgreSQL instance.
      Run with: `mvn test -Ppostgresql-acceptance`
      Set env vars: `PG_URL`, `PG_USERNAME`, `PG_PASSWORD`.
      The `postgresql-acceptance` Maven profile sets the `postgresql.acceptance=true` system property
      and overrides the surefire configuration to include `PostgreSqlIntegrationTest`.
      The test class `@BeforeAll` checks for `postgresql.acceptance=true`:
      if the profile is active but env vars are missing, the build **FAILS** (not skips).
      If the profile is not active, the tests are excluded by the default surefire config.
      This is NOT optional -- a release cannot proceed if PG acceptance tests fail or are skipped.
- [ ] PostgreSQL-specific SQL features (schemas, sequences, quoting) work correctly.
- [ ] Flyway migrations have been verified against a fresh PostgreSQL database.
- [ ] Connection timeout and retry behavior is tested under PostgreSQL.

## 10. Rollback Strategy

- [ ] Database backup was taken before migration.
- [ ] Flyway migrations are reversible: for each `V<N>__*.sql`, a corresponding
      `U<N>__*.sql` undo migration exists or a documented rollback procedure is available.
- [ ] Application rollback plan: deploy the previous artifact version and restore the
      pre-migration database backup.
- [ ] If the release includes data migrations (not just schema changes), a data-only
      rollback procedure is documented.
- [ ] Smoke tests are defined to verify the rollback was successful.

---

## Required Commands Before Release

All commands must pass with zero failures before a release can proceed.

### Automated Release Gate (Recommended)

Run all gates in a single automated pass:

**Windows (PowerShell):**
```powershell
.\scripts\release-gate.ps1
```

This script runs all four mandatory gates, parses surefire XML reports, performs
a sensitive code scan, and generates `RELEASE_GATE_RESULT.md` with structured
results. The script exits with code 0 if all gates pass, or code 1 if any gate fails.

**Prerequisites for the PG gate:**
```powershell
$env:PG_URL="jdbc:postgresql://localhost:5432/nocobase_test"
$env:PG_USERNAME="nocobase"
$env:PG_PASSWORD="<password>"
```

If `PG_URL`, `PG_USERNAME`, or `PG_PASSWORD` are not set, the PG acceptance gate
will FAIL with a non-zero exit (not skip). This is by design -- the PG gate is mandatory.

**Options:**
- `-SkipPgAcceptance`: Skip the PG acceptance gate (not recommended for release).
- `-SkipFlyway`: Skip the flyway validation gate.
- `-ReportPath <path>`: Custom path for the generated report file.

### 1. Unit and Integration Tests (Default H2)

```bash
mvn test
```

- **Requirement:** 0 failures, 0 errors.
- **Expected output:** `BUILD SUCCESS` with `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`.
- **Acceptable warnings:** Deprecation warnings, H2 console warnings in test profiles.

### 2. Flyway Migration Dry-Run (Validate Schema)

Verify that all Flyway migrations are valid and consistent with the database:

```bash
# Validate against the default H2 database (development)
mvn flyway:validate

# Validate against PostgreSQL (production target)
mvn flyway:validate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/nocobase \
  -Dflyway.user=nocobase \
  -Dflyway.password=<password>
```

**PowerShell (Windows):**
```powershell
mvn flyway:validate `
  "-Dflyway.url=jdbc:postgresql://localhost:5432/nocobase" `
  "-Dflyway.user=nocobase" `
  "-Dflyway.password=<password>"
```

- **Requirement:** Exit code 0, no error output.
- **Expected output:** `BUILD SUCCESS` (validate reports no pending changes).
- **Note:** For a fresh database, run `mvn flyway:migrate` first, then `mvn flyway:validate`.
- **DB Target:** The default `pom.xml` flyway config targets `jdbc:h2:file:./storage/db/nocobase`.
  For production release, override with `-Dflyway.url` pointing to the target PostgreSQL instance.

### 3. PostgreSQL Acceptance Tests

Run the full test suite against a real PostgreSQL instance:

**Linux / macOS:**
```bash
export PG_URL=jdbc:postgresql://localhost:5432/nocobase_test
export PG_USERNAME=nocobase
export PG_PASSWORD=<password>

mvn test -Ppostgresql-acceptance
```

**Windows (PowerShell):**
```powershell
$env:PG_URL="jdbc:postgresql://localhost:5432/nocobase_test"
$env:PG_USERNAME="nocobase"
$env:PG_PASSWORD="<password>"

mvn test -Ppostgresql-acceptance
```

- **Requirement:** 0 failures, 0 errors. The `postgresql-acceptance` Maven profile
  activates the `postgresql.acceptance=true` system property and overrides the
  surefire configuration to include `PostgreSqlIntegrationTest`. The test class
  `@BeforeAll` checks: if the profile is active but PG env vars are missing,
  the build **FAILS** (not skips). If the profile is not used, PG tests are
  excluded from the default surefire run.
- **MANDATORY:** This step MUST be run and pass before the release is approved.

### 4. Sensitive Configuration Scan

Scan configuration files for hardcoded secrets, credentials, or tokens:

**Linux / macOS (or Git Bash on Windows):**
```bash
grep -rn -E '(password|secret|token|key)\s*[:=]\s*[^${]' src/main/resources/ --include='*.yml' --include='*.yaml' --include='*.properties' | grep -v '${' | grep -v 'password:\s*$' | grep -v '#'
```

**Windows (PowerShell):**
```powershell
Select-String -Path src/main/resources/*.yml,src/main/resources/*.yaml,src/main/resources/*.properties -Pattern '(password|secret|token|key)\s*[:=]\s*[^${]' | Where-Object { $_.Line -notmatch '\$\{' -and $_.Line -notmatch 'password:\s*$' -and $_.Line -notmatch '^\s*#' }
```

- **Requirement:** No output. Any hit means a hardcoded secret is present.
- **Expected output:** Empty (no hardcoded credentials found).
- **Note:** The `password:` field in H2 dev config is excluded (empty string for dev).
  All production secrets MUST use `${ENV_VAR}` placeholders.

### 5. Frontend Trace Replay (Optional)

If the frontend team has provided a recorded trace of API calls (e.g., HAR file), replay
it against the backend to verify contract compatibility:

```bash
# Use curl-based replay script (provided in project root)
# ./scripts/replay-trace.sh traces/production_trace.har http://localhost:13000
```

- **Requirement:** All API responses match the expected NocoBase protocol format.
- **Condition:** This step is optional and may be skipped if no trace is available.

### 6. Release Gate Table

All commands must pass with zero failures before a release can proceed. Run each
command and record the results in the table below.

| # | Command | Exit Code | Tests Run | Failures | Errors | Skipped | Required |
|---|---------|-----------|-----------|----------|--------|---------|----------|
| 1 | `mvn test` | 0 | | 0 | 0 | 0 | YES |
| 2 | `mvn flyway:validate` | 0 | -- | -- | -- | -- | YES |
| 3 | `mvn test -Ppostgresql-acceptance` | 0 | >0 | 0 | 0 | 0 | YES |
| 4 | Sensitive config scan | 0 | -- | -- | -- | -- | YES |

**Gate rules:**
- Exit code 0 for all commands.
- `mvn test` (row 1): Tests run > 0, Failures = 0, Errors = 0, Skipped = 0.
- `mvn flyway:validate` (row 2): Exit code 0, no error output.
- `mvn test -Ppostgresql-acceptance` (row 3): Tests run > 0 (PG must be available),
  Failures = 0, Errors = 0, Skipped = 0.
- Sensitive config scan (row 4): No output (empty).
- Any non-zero exit or unexpected output is a **BLOCKER** for the release.

### 7. CI / Human Execution Pass/Fail Summary

After running all required commands, verify the outcome:

| Command | Expected Exit Code | Expected Output |
|---------|-------------------|-----------------|
| `mvn test` | 0 | BUILD SUCCESS, Tests run: N, Failures: 0, Errors: 0, Skipped: 0 |
| `mvn flyway:validate` | 0 | BUILD SUCCESS |
| `mvn test -Ppostgresql-acceptance` | 0 | BUILD SUCCESS, Tests run: >0, Failures: 0, Errors: 0 |
| Sensitive config scan | 0 | No output (empty) |

All commands must exit with code 0. Any non-zero exit or unexpected output is a BLOCKER
for the release.

---

## Acceptable Skipped Test Conditions

Tests may be skipped (marked as `@Disabled` or excluded from the test run) under the
following conditions:

1. **Environment-dependent tests**: Tests that require a specific database (PostgreSQL),
   network access, or external services that are not available in the CI environment.
   These MUST be documented and run manually before release.

2. **Long-running tests**: Tests that take more than 60 seconds to complete may be
   excluded from the default `mvn test` run and placed in a separate "slow" test group.
   These MUST be run as part of the release pipeline.

3. **Known flaky tests**: Tests that are known to be non-deterministic must be fixed
   or excluded. A tracking issue MUST exist for each flaky test that is excluded.

4. **Tests blocked by unresolved dependencies**: If a test depends on a feature that
   is not yet implemented, it may be skipped with a clear comment referencing the
   tracking issue.

Skipped tests MUST be explicitly annotated with `@Disabled("reason")` and include a
reference to the issue or condition that justifies the skip.

---

## Sign-off

| Role          | Name | Date       | Signature |
|---------------|------|------------|-----------|
| Developer     |      |            |           |
| Code Reviewer |      |            |           |
| QA            |      |            |           |
| Release Lead  |      |            |           |