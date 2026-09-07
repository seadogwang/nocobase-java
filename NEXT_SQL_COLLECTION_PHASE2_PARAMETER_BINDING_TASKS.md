# SQL Collection Phase 2 Parameter Binding - Next Development Tasks

> Created by architect review after `SQL_COLLECTION_PHASE2_CORE_COMPLETION_SUMMARY.md`.
> Scope: Java backend only. Do not change the NocoBase frontend contract or UI behavior.

## Review Result

The Phase 2 core work is mostly accepted:

- `mvn -q test` passes locally: 165 tests, 0 failures, 0 errors.
- `SqlQueryPlan` is introduced and used by SQL collection list queries.
- `SqlNamedParameterParser` exists with focused unit coverage.
- Member identity setup is improved and no longer depends on hard-coded user IDs.
- Architecture boundary tests are stronger than before.
- SQL design documentation no longer implies that Phase 1 supports active named parameters.

The implementation is not fully closed yet:

- `SqlQueryCollectionExecutor.executeGet()` still builds SQL inline and does not use `SqlQueryPlan`.
- Query plan behavior has no direct tests for generated SQL, parameter order, and count SQL.
- `DdlBoundaryTest` still mutates shared collection metadata and is not truly isolated.
- `ArchitectureBoundaryTest` only checks import statements for `SqlQueryCollectionExecutor`, so fully qualified or same-package usage can still bypass the boundary.
- Several test setups still skip role binding silently when the `member` role is missing instead of failing fast.

## Global Constraints

- Keep all frontend API response shapes compatible with current NocoBase frontend usage.
- Keep `DynamicRepository` as the only public data-layer execution gateway for controllers and association/relation services.
- SQL collections remain read-only unless a later task explicitly defines write semantics.
- Do not add a new ORM abstraction for SQL collections. Continue using the existing `JdbcTemplate` boundary inside approved low-level data components.
- All SQL generated from user filters, scopes, sorts, pagination, and collection SQL parameters must be parameterized.
- Any new backend behavior must have focused tests.

## P0-A: Converge SQL Get Onto Query Plan

**Problem**

`executeGet()` currently duplicates wrapper and WHERE construction manually. This creates drift risk between list/get/count behavior.

**Required changes**

- Add `buildGetPlan(...)` or a shared `buildSelectPlan(...)` in `SqlQueryCollectionExecutor`.
- Make `executeGet()` execute through `SqlQueryPlan`.
- Preserve current semantics:
  - validate configured SQL first;
  - wrap as `SELECT * FROM (<configuredSql>) _nocobase_sub`;
  - apply merged ACL scope + primary-key filter through the outer WHERE;
  - use `LIMIT 1`;
  - return `null` when no row matches.
- Do not change controller response format.

**Tests**

- Add direct tests for generated get SQL and parameter order.
- Add regression test proving `get` applies both primary key and action scope.
- Add regression test proving `executeGet()` does not bypass SQL collection field/scope rules via handwritten SQL.

**Acceptance**

- No duplicated inline get SQL construction remains outside the query-plan builder.
- Existing SQL collection list/get tests still pass.

## P0-B: Add Query Plan Unit Coverage

**Problem**

`SqlQueryPlan` exists, but there is no direct proof that list/get/count SQL and parameter order stay stable.

**Required changes**

- Add package-level tests for `SqlQueryCollectionExecutor.buildListPlan(...)`.
- Add package-level tests for the new get plan builder from P0-A.
- Cover empty filter, non-empty filter, sort, pagination, and count SQL.

**Tests**

- Verify SQL text contains the configured SQL only inside the subquery.
- Verify outer filters are appended outside the subquery.
- Verify count plan excludes sort and pagination.
- Verify parameter order is:
  - configured SQL parameters first, after parameter binding is introduced;
  - ACL/filter parameters next;
  - pagination parameters last, if represented as bind values.

**Acceptance**

- A future implementation change that reorders parameters incorrectly must fail a unit test.

## P0-C: Make DDL Boundary Tests Isolated

**Problem**

`DdlBoundaryTest` removed method ordering, but it still uses shared static collection names and mutates metadata across tests.

**Required changes**

- Replace shared mutable SQL/view collection names with per-test unique names, or fully reset metadata before each test.
- Do not rely on side effects from `@BeforeAll`.
- Ensure tests that add/drop fields or collections cannot affect other tests in the same class.
- Keep physical DDL assertions unchanged.

**Tests**

- Run `DdlBoundaryTest` alone.
- Run the full Maven test suite.
- If practical, run `DdlBoundaryTest` twice in the same Maven invocation pattern used by CI.

**Acceptance**

- DDL boundary tests remain deterministic when method order changes.
- No SQL/view test depends on metadata mutated by another test.

## P0-D: Strengthen Architecture Boundary Enforcement

**Problem**

The current boundary test checks only imports and can miss direct or fully qualified references.

**Required changes**

- Scan source files for forbidden references to `SqlQueryCollectionExecutor`, not just imports.
- Allow only approved files:
  - `DynamicRepository`;
  - `SqlQueryCollectionExecutor`;
  - SQL executor/query-plan tests;
  - architecture boundary tests.
- Keep the existing `JdbcTemplate` boundary scan.
- Fail fast on file-read errors.

**Tests**

- Add or update architecture tests so a direct usage like `new com.nocobase.sql.SqlQueryCollectionExecutor(...)` outside allowlist would fail.
- Keep controllers, relation services, and association services forbidden from importing or referencing SQL executor directly.

**Acceptance**

- Controllers and relation/association services cannot bypass `DynamicRepository` for SQL collection execution.

## P0-E: Fail Fast on Missing Test Roles

**Problem**

Some tests skip member-role binding if the role lookup returns null. This can hide broken test setup.

**Required changes**

- Replace silent `if (memberRole != null)` setup with explicit failure when a required role is missing.
- Apply to ACL, action-scope/relation, and SQL collection permission tests.
- Keep test users loaded by email, not hard-coded IDs.

**Acceptance**

- A missing `member` role fails the test setup immediately.
- No test accidentally passes as admin because member setup failed.

## P1-F: Implement SQL Parameter Metadata Model

**Problem**

Named parameters cannot be enabled safely until metadata declares which parameters are allowed and where values come from.

**Required changes**

- Add backend model classes for SQL collection parameter definitions from collection options.
- Support this initial schema:

```json
{
  "parameters": [
    {
      "name": "status",
      "type": "string",
      "source": "static",
      "defaultValue": "active",
      "required": true
    }
  ]
}
```

- Validate:
  - name matches parser rules;
  - duplicate names are rejected;
  - unsupported type/source is rejected;
  - required static parameter must have `defaultValue`;
  - configured SQL cannot reference undeclared parameters;
  - declared but unused parameters should be rejected or clearly documented as rejected.

**Acceptance**

- Metadata validation errors are deterministic and test-covered.
- Phase 1 collections without parameters continue to work unchanged.

## P1-G: Enable Static Named Parameter Binding

**Problem**

The parser exists but production execution still rejects named parameters.

**Required changes**

- Integrate `SqlNamedParameterParser` into SQL collection execution.
- Convert configured SQL named parameters to JDBC `?` placeholders.
- Bind values from static parameter metadata.
- Apply the same bound configured SQL to:
  - list data query;
  - list count query;
  - get query.
- Keep JDBC positional `?` in configured SQL rejected.
- Keep SQL collection writes rejected.

**Tests**

- List SQL collection with `WHERE status = :status`.
- Count query uses the same configured parameter values.
- Get query applies configured parameter + outer ACL scope + primary-key filter.
- Missing required parameter fails before execution.
- Undeclared parameter fails before execution.
- Repeated named parameters bind repeated values in occurrence order.

**Acceptance**

- Static named parameters are usable without changing the frontend.
- All configured SQL parameter values are bound, not string-concatenated.

## P1-H: Harden SQL Validator Lexical Rules

**Problem**

Current validation is intentionally conservative. Before enabling named parameters broadly, lexical handling must avoid false positives and false negatives.

**Required changes**

- Replace broad text matching with a lexical scanner that understands:
  - single-quoted strings;
  - double-quoted identifiers;
  - escaped quotes;
  - SQL comments;
  - semicolon rejection outside strings;
  - PostgreSQL `::` casts;
  - named parameters outside strings and identifiers.
- Continue to allow only read-only SQL collection queries.
- Reject DML/DDL/control statements outside strings/comments:
  - `insert`, `update`, `delete`, `drop`, `alter`, `truncate`, `create`, `grant`, `revoke`, `merge`, `call`, `execute`.

**Tests**

- Dangerous words inside string literals do not fail validation.
- Dangerous words outside strings fail validation.
- Comments are rejected or ignored consistently according to the chosen rule, and the rule is documented.
- Multi-statement SQL is rejected.
- `WITH` queries remain read-only and cannot hide DML.

**Acceptance**

- Validator decisions are explainable from token boundaries, not substring accidents.

## P2-I: Prepare Current-User Parameter Source

**Problem**

NocoBase SQL collections commonly need user-context parameters, but enabling them before static parameters are stable would increase risk.

**Required changes**

- Add design and optional skeleton for `source: "currentUser"`.
- Do not expose it unless tests cover auth and anonymous behavior.
- Define initial allowed fields:
  - `id`;
  - `email`;
  - role names, only if existing auth context exposes them safely.

**Acceptance**

- Static parameter binding remains the only enabled runtime behavior unless this task is fully implemented and tested.

## P2-J: Update Documentation and Completion Summary

**Required changes**

- Update `SQL_QUERY_COLLECTION_DESIGN.md` to describe the exact supported parameter capability.
- Clearly separate:
  - unsupported Phase 1 behavior;
  - supported static named parameters;
  - future current-user/runtime parameters.
- After development, write `SQL_COLLECTION_PHASE2_PARAMETER_BINDING_COMPLETION_SUMMARY.md`.

**Completion summary must include**

- Files changed.
- Behavior changed.
- Tests added.
- Exact Maven command and result.
- Any unsupported parameter types/sources.
- Any known gaps for the next architecture review.

