# SQL Collection Phase 2 Acceptance Hardening - Next Development Tasks

> Created by architect review after `SQL_COLLECTION_PHASE2_PARAMETER_BINDING_COMPLETION_SUMMARY.md`.
> Scope: Java backend only. Do not change frontend files, API response envelopes, or existing UI behavior.

## Review Result

The previous batch is partially accepted:

- `mvn -q test` passes locally: 177 tests, 0 failures, 0 errors.
- `executeGet()` now uses `SqlQueryPlan`.
- DDL boundary tests are now isolated with per-test names and cleanup.
- Test role setup now fails fast when `member` role is missing.
- `SqlNamedParameterParser`, `SqlParameterMetadata`, and query-plan tests exist.

The implementation is not ready to expand beyond static SQL parameters yet:

- There is no integration test that executes a SQL collection with `:param` through `DynamicRepository.list()` or `DynamicRepository.get()`.
- `SqlParameterMetadata` does not validate parameter-name pattern, invalid `parameters` shape, declared-but-unused parameters, or default-value type compatibility.
- The validator still rejects `;`, `?`, `--`, and `/*` by raw substring before lexical stripping, so safe literals such as `'a;b'` or `'what?'` fail incorrectly.
- Documentation and JavaDoc still contain stale Phase 1 statements that named parameters are not enabled.
- Query-plan tests do not yet lock parameter order when named SQL parameters, ACL/filter parameters, and pagination parameters are combined.

## Global Constraints

- Backend only. Do not modify NocoBase frontend code.
- Keep `DynamicRepository` as the single public data-layer gateway.
- SQL collections remain read-only.
- Keep configured SQL wrapped as a subquery and apply ACL/filter/sort/pagination outside the wrapper.
- All dynamic values must be bound as JDBC parameters. Do not concatenate parameter values into SQL.
- Preserve existing frontend-compatible response shapes.

## P0-A: Add Real Named-Parameter Integration Tests

**Problem**

Current tests prove parser behavior and validator allowance, but not the real SQL collection execution path.

**Required changes**

- Add SQL collection integration tests that create a collection whose configured SQL contains named parameters.
- Execute through `DynamicRepository`, not `SqlQueryCollectionExecutor` directly.
- Use collection options like:

```json
{
  "primaryKey": "id",
  "parameters": [
    {"name": "status", "type": "string", "source": "static", "defaultValue": "active", "required": true}
  ]
}
```

**Tests**

- `list()` with `WHERE status = :status` returns only matching rows.
- `list()` count uses the same named parameter and returns the exact count.
- `get()` with `WHERE status = :status` returns an active row and returns `null` for an inactive row.
- Named parameter + ACL scope both apply on the outer/inner expected sides.
- Repeated named parameter binds the repeated value in occurrence order.
- Undeclared named parameter fails before JDBC execution.

**Acceptance**

- A broken `hydrateSql()` or parameter order must fail a `DynamicRepository` integration test.

## P0-B: Complete Parameter Metadata Validation

**Problem**

`SqlParameterMetadata` currently accepts several invalid configurations and does not enforce its documented schema.

**Required changes**

- Validate parameter names with the same rule as `SqlNamedParameterParser`: `[A-Za-z_][A-Za-z0-9_]*`.
- Reject `options.parameters` when it exists but is not a list.
- Reject list entries that are not objects/maps.
- Reject duplicate names.
- Reject declared-but-unused parameters for SQL collections.
- Reject SQL references to undeclared parameters.
- Validate `defaultValue` against declared type:
  - `string`: string only;
  - `number`: Java `Number` or numeric string converted to an appropriate numeric value;
  - `boolean`: boolean or `"true"`/`"false"` converted to boolean;
  - `date`: ISO local date string;
  - `datetime`: ISO local datetime or offset datetime string.
- Keep `required: false` with missing `defaultValue` as explicit `null`, only if this behavior is documented.

**Tests**

- Add unit tests for every invalid metadata shape.
- Add type conversion/validation tests for all supported types.
- Add integration test proving an invalid SQL collection parameter config fails deterministically.

**Acceptance**

- Metadata behavior matches `SQL_QUERY_COLLECTION_DESIGN.md`.
- No invalid parameter config is silently ignored.

## P0-C: Move SQL Parameter Validation to Collection Reload

**Problem**

Parameter and SQL validation currently happen at execution time. NocoBase metadata errors should surface when the collection is loaded or reloaded.

**Required changes**

- During `CollectionRuntimeService.reload()` and `loadAll()`, validate SQL collections:
  - SQL text is safe;
  - named parameters referenced by SQL are declared;
  - declared parameters are used;
  - parameter metadata shape and types are valid.
- Keep `loadAll()` behavior explicit:
  - if the current project policy is fail-fast, fail startup for invalid SQL metadata;
  - if the current project policy is skip-invalid, log and skip that collection, but add a test documenting the behavior.
- Do not execute the configured SQL during validation.

**Tests**

- Reload valid SQL collection with static parameter succeeds.
- Reload SQL collection with undeclared parameter fails or is skipped according to chosen policy.
- Reload SQL collection with invalid parameter type fails or is skipped according to chosen policy.

**Acceptance**

- Invalid SQL collection metadata is caught before the first user request.

## P0-D: Fix SQL Validator Lexical False Positives

**Problem**

The validator strips strings and identifiers for DDL/DML keywords, but semicolon, comment markers, and `?` are still checked by raw substring.

**Required changes**

- Replace raw `contains(";")`, raw comment regex, and raw `contains("?")` checks with lexical checks.
- Reject semicolons only outside strings and identifiers.
- Reject SQL comments only outside strings and identifiers.
- Reject JDBC `?` only outside strings and identifiers.
- Keep DDL/DML keyword detection outside strings and identifiers.
- Keep named parameters allowed outside strings and identifiers.
- Preserve PostgreSQL `::type` cast compatibility.

**Tests**

- Allow `SELECT 'a;b' AS text_value`.
- Allow `SELECT 'what?' AS text_value`.
- Allow `SELECT '-- not a comment' AS text_value`.
- Allow `SELECT '/* not a comment */' AS text_value`.
- Reject `SELECT 1; SELECT 2`.
- Reject `SELECT * FROM t -- comment`.
- Reject `SELECT * FROM t WHERE id = ?`.
- Reject DDL/DML keywords outside strings.

**Acceptance**

- Validator decisions are based on lexical position, not substring matches.

## P0-E: Lock Query Plan Parameter Ordering

**Problem**

Parameter order is central to correctness once configured SQL parameters are combined with ACL/filter and pagination.

**Required changes**

- Extend `SqlQueryPlanTest` to include named SQL parameter values.
- Verify list data query parameter order:
  - named SQL params;
  - filter/scope params;
  - limit;
  - offset.
- Verify list count query parameter order:
  - named SQL params;
  - filter/scope params.
- Verify get query parameter order:
  - named SQL params;
  - primary-key/scope filter params.

**Acceptance**

- Any future reorder of named/filter/pagination values breaks a focused unit test.

## P1-F: Clean Up Documentation and JavaDoc

**Problem**

The project now has contradictory documentation around named parameter support.

**Required changes**

- Update `SQL_QUERY_COLLECTION_DESIGN.md`:
  - mark static named parameters as supported in Phase 2;
  - remove stale notes saying production still rejects `:param`;
  - document the exact supported `parameters` schema;
  - document unsupported sources: `currentUser`, `context`, request/runtime parameters.
- Update JavaDoc in `SqlNamedParameterParser` and `SqlValidator`.
- Update validator error messages that still mention Phase 1 for `?` rejection.

**Acceptance**

- Documentation, comments, and runtime behavior agree.

## P1-G: Add Parameter Error API Compatibility Tests

**Problem**

The Java backend must keep frontend-compatible error behavior when SQL collection metadata is wrong.

**Required changes**

- Add controller-level or `DynamicRepository` tests for parameter errors.
- Verify exceptions are mapped consistently by `GlobalExceptionHandler`.
- Avoid exposing raw SQL text or bound values in client-facing error responses.

**Tests**

- Undeclared SQL parameter.
- Invalid parameter type.
- Missing required static default.
- Invalid parameter metadata shape.

**Acceptance**

- Frontend receives predictable errors without leaking sensitive SQL details.

## P2-H: Prepare Current User Parameter Design

**Problem**

`currentUser` parameters are important for real NocoBase usage, but should not be enabled until static parameters are fully stable.

**Required changes**

- Write a design note in `SQL_QUERY_COLLECTION_DESIGN.md` for future `source: "currentUser"`.
- Define allowed fields:
  - `id`;
  - `email`;
  - roles only if available from authenticated context without extra unsafe queries.
- Define anonymous-user behavior.
- Do not enable runtime support in this batch unless all auth tests are included.

**Acceptance**

- Current-user parameters remain explicitly unsupported at runtime unless fully tested.

## P2-I: Prepare Multi-Database SQL Collection Boundary

**Problem**

NocoBase SQL collections may later point to different data sources. The current implementation is tied to the main `JdbcTemplate`.

**Required changes**

- Add a short design section for future `dataSourceKey` support.
- Identify where SQL collection execution should resolve a `JdbcTemplate` or data-source executor.
- Keep the current batch on main datasource only.
- Do not introduce a new datasource implementation yet.

**Acceptance**

- The next architecture review can decide Phase 3 data-source work from a concrete backend design.

## Completion Summary Required

After development, write:

`SQL_COLLECTION_PHASE2_ACCEPTANCE_HARDENING_COMPLETION_SUMMARY.md`

The summary must include:

- Files changed.
- Exact behavior changes.
- Tests added, grouped by test class.
- Exact command run and result.
- Whether named parameters are now verified through `DynamicRepository.list()` and `DynamicRepository.get()`.
- Known unsupported parameter sources/types.
- Any remaining risks for current-user parameters or multi-datasource SQL collections.

