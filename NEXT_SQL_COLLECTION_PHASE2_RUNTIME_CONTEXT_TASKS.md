# SQL Collection Phase 2 Runtime Context - Next Development Tasks

> Created by architect review after `SQL_COLLECTION_PHASE2_ACCEPTANCE_HARDENING_COMPLETION_SUMMARY.md`.
> Scope: Java backend only. Do not change frontend files, frontend routes, or response envelopes consumed by the existing NocoBase UI.

## Review Result

The previous batch is accepted with follow-up items:

- `mvn -q test` passes locally: 257 tests, 0 failures, 0 errors.
- Static named parameters are now covered through `DynamicRepository.list()` and `DynamicRepository.get()`.
- Metadata validation now covers invalid shape, duplicate names, undeclared parameters, declared-but-unused parameters, unsupported source/type, and default-value type compatibility.
- SQL validation now avoids false positives for `;`, `?`, and comment markers inside string literals.
- SQL collection validation now runs during collection definition build/reload.

Remaining risks before expanding SQL collection usage:

- Malformed colon tokens such as `:1bad`, `:bad-name`, or a dangling `:` may still reach the database instead of failing during SQL metadata validation.
- Numeric and boolean string defaults are validated but still bound as strings; date/datetime defaults are also bound as raw strings.
- Runtime tests create invalid metadata records and then call `reload()` in cleanup, which cannot remove invalid DB rows and causes repeated loadAll error logs.
- Parameter error "API compatibility" tests are mostly direct metadata tests, not controller-level response tests.
- `loadAll()` skips invalid SQL collections by design, but there is no clean way for operators/admin APIs to inspect skipped collections.

## Global Constraints

- Backend only. Keep the existing frontend unchanged.
- Keep `DynamicRepository` as the public data-layer gateway.
- Keep SQL collections read-only.
- Keep configured SQL wrapped as a subquery and apply ACL/filter/sort/pagination outside the wrapper.
- Do not concatenate runtime values into SQL.
- Do not enable multi-datasource execution in this batch.

## P0-A: Reject Malformed Named Parameter Tokens

**Problem**

`SqlNamedParameterParser` recognizes valid `:name` tokens, but malformed colon tokens can be left in SQL and fail later at JDBC/database execution time.

**Required changes**

- Add lexical validation for colon usage outside strings and quoted identifiers.
- Allow only:
  - valid named parameters matching `[A-Za-z_][A-Za-z0-9_]*`;
  - PostgreSQL `::type` casts;
  - time literals between digits, if already supported.
- Reject malformed examples:
  - `:1bad`;
  - `:bad-name` unless the intended token is explicitly documented and tested;
  - dangling `:`;
  - `: status`;
  - `:=`;
  - unsupported driver-style or dialect-specific colon syntax.
- Error messages must name the malformed token without exposing the full configured SQL.

**Tests**

- Add parser and metadata validation tests for every malformed case above.
- Add collection reload tests proving malformed named parameters fail before execution.
- Add one `DynamicRepository` regression test proving a bad SQL collection never reaches `JdbcTemplate`.

**Acceptance**

- Invalid colon usage fails during metadata validation or reload, not at query execution.

## P0-B: Bind Typed Parameter Values

**Problem**

`SqlParameterMetadata` validates numeric/boolean/date/datetime strings, but `buildValueList()` still returns the original raw `defaultValue`.

**Required changes**

- Store a normalized/bound value in `ParameterDef`.
- Normalize supported types:
  - `string` -> `String`;
  - `number` -> `Integer`, `Long`, or `BigDecimal` according to value shape, with a documented choice;
  - `boolean` -> `Boolean`;
  - `date` -> `LocalDate` or `java.sql.Date`, with a documented choice;
  - `datetime` -> `LocalDateTime` for local datetime and `OffsetDateTime` if offset datetime remains documented as supported.
- `buildValueList()` must return normalized values, not raw JSON strings.
- Preserve `required: false` with missing `defaultValue` as `null` if that behavior remains documented.

**Tests**

- Unit tests assert actual Java value classes returned by `buildValueList()`.
- Integration tests execute numeric and boolean parameters through `DynamicRepository.list()`.
- Add date/datetime tests if the H2 test schema supports them cleanly.

**Acceptance**

- Bound parameter values match declared types and do not depend on database implicit string casts.

## P0-C: Clean Invalid Metadata in Runtime Tests

**Problem**

`CollectionRuntimeServiceTest` creates intentionally invalid collection metadata. Cleanup currently calls `runtimeService.reload()`, which cannot delete invalid DB rows and produces repeated loadAll error logs.

**Required changes**

- Inject repositories needed to delete test metadata rows directly.
- Delete related field metadata before deleting collection metadata.
- Use unique test collection names where practical.
- Ensure `loadAll()` tests do not leave invalid metadata behind.
- Keep test cleanup non-destructive outside test-owned collection prefixes.

**Tests**

- Run `CollectionRuntimeServiceTest` alone twice.
- Run full `mvn -q test`.
- Verify surefire logs no longer contain repeated leftover invalid collection names after cleanup.

**Acceptance**

- Invalid test metadata is removed from the database after each test.

## P0-D: Add Real Controller Error Compatibility Tests

**Problem**

Parameter error tests currently validate exceptions directly, but not the HTTP error response that the frontend receives.

**Required changes**

- Add MockMvc/controller-level tests for SQL collection parameter errors.
- Verify status codes and response shape:
  - `{ "errors": [{ "message": "..." }] }`.
- Verify errors do not expose full configured SQL or bound parameter values.
- Cover:
  - malformed named parameter;
  - unsupported parameter type;
  - missing required static default;
  - undeclared named parameter.

**Acceptance**

- Frontend-visible error behavior is explicitly locked by tests.

## P0-E: Track Invalid Collections Skipped by loadAll

**Problem**

`loadAll()` skips invalid SQL collections and logs the error, but the runtime has no structured record of what was skipped.

**Required changes**

- Add backend-only invalid collection tracking inside `CollectionRuntimeService`.
- Track collection name and sanitized validation error.
- Clear tracking at the start of each `loadAll()`.
- Do not include full SQL text or parameter values.
- Expose a service-level getter for tests and future admin APIs.
- Do not add frontend UI in this batch.

**Tests**

- Valid collections are not listed as invalid.
- Invalid SQL collections skipped by `loadAll()` are listed with sanitized reason.
- A later successful `loadAll()` clears stale invalid entries.

**Acceptance**

- Operators can inspect skipped collection metadata from backend service state.

## P1-F: Implement `source: "currentUser"` Static Runtime Context

**Problem**

NocoBase SQL collections need user-aware query parameters. The design exists, but runtime support is still intentionally disabled.

**Required changes**

- Extend parameter metadata source support to include `currentUser`.
- Supported schema:

```json
{
  "name": "userId",
  "type": "number",
  "source": "currentUser",
  "path": "id",
  "required": true
}
```

- Initial allowed paths:
  - `id`;
  - `email`;
  - `roles`, only if it can be read from existing auth context without unsafe direct SQL.
- Resolve values through existing auth/current-user infrastructure.
- Do not add request-body or query-string parameter support.
- Anonymous behavior must be explicit:
  - if required, fail with unauthorized/bad request according to existing auth semantics;
  - if optional, bind `null`.

**Tests**

- SQL collection with `currentUser.id` returns only rows for the authenticated user.
- Admin and member users bind their own IDs correctly.
- ACL scope still applies outside the configured SQL.
- Anonymous required current-user parameter fails predictably.
- Unsupported currentUser path fails during reload or execution with sanitized error.

**Acceptance**

- `currentUser` parameters are usable through `DynamicRepository.list()` and `get()` without frontend changes.

## P1-G: Separate Parameter Resolution From Metadata Parsing

**Problem**

Static default values and runtime context values should not be resolved in the metadata parser forever.

**Required changes**

- Introduce a small `SqlParameterResolver` or equivalent backend component.
- Keep `SqlParameterMetadata` responsible for schema parsing and validation.
- Keep resolver responsible for producing ordered bound values for parsed parameter names.
- Resolver must support:
  - `static`;
  - `currentUser`, if P1-F is implemented.
- Keep `SqlQueryCollectionExecutor` small: validate, parse, resolve, plan, execute.

**Tests**

- Pure unit tests for resolver behavior.
- Existing executor integration tests still pass.

**Acceptance**

- Adding future sources does not require bloating `SqlParameterMetadata`.

## P1-H: Improve SQL Collection Error Sanitization

**Problem**

Current exception messages are generally safe, but future current-user and multi-datasource support increases the risk of leaking configured SQL or bound values.

**Required changes**

- Add a dedicated SQL collection configuration exception if helpful.
- Ensure controller-visible errors do not contain:
  - full configured SQL;
  - bound values;
  - stack traces;
  - datasource connection details.
- Keep logs useful for developers, but do not log secrets or parameter values.

**Tests**

- HTTP error response contains sanitized reason.
- Logs may include collection name but not full SQL text or values.

**Acceptance**

- Parameter and validation failures are safe to expose to the existing frontend.

## P2-I: Multi-Datasource Boundary Design Only

**Problem**

SQL collection execution is still tied to the main datasource. Multi-datasource support is required later, but should not be implemented until runtime parameter semantics are stable.

**Required changes**

- Update `SQL_QUERY_COLLECTION_DESIGN.md` with a Phase 3 design section for `dataSourceKey`.
- Define where executor selection happens:
  - `DynamicRepository`;
  - `SqlQueryCollectionExecutor`;
  - or a future `DataSourceExecutorRegistry`.
- Define ACL behavior:
  - permissions remain controlled by collection metadata in main datasource;
  - remote/external SQL data is still filtered through outer scope where possible.
- Do not implement actual multi-datasource connections in this batch.

**Acceptance**

- Phase 3 datasource work has a concrete backend boundary without changing current runtime behavior.

## Completion Summary Required

After development, write:

`SQL_COLLECTION_PHASE2_RUNTIME_CONTEXT_COMPLETION_SUMMARY.md`

The summary must include:

- Files changed.
- Behavior changed.
- Tests added, grouped by class.
- Exact Maven command and result.
- Whether malformed colon syntax is fail-fast.
- Whether typed values are normalized before JDBC binding.
- Whether `currentUser` is implemented or still design-only.
- Remaining risks for multi-datasource SQL collections.

