# Release Gate Result

**Generated:** 2026-09-19T19:07:56+08:00
**Project:** nocobase-java (NocoBase Java Backend)
**Branch/Commit:** 5c16a2e
**PostgreSQL mode:** External PostgreSQL (no JDBC URL or credentials recorded)

## Gate Summary

| Gate | Command | Exit Code | Duration | Tests | Failures | Errors | Skipped | Verifier | Result |
|------|---------|-----------|----------|-------|----------|--------|---------|----------|--------|
| 1 | mvn test | 0 | 88.17s | 1094 | 0 | 0 | 0 | -- | PASS |
| 2 | mvn flyway:validate | 0 | 6.25s | -- | -- | -- | -- | -- | PASS |
| 3 | mvn test -Ppostgresql-acceptance | 0 | 18.33s | 29 | 0 | 0 | 0 | PASS | PASS |
| 4 | Sensitive code scan | 0 | 0.28s | -- | -- | -- | -- | -- | PASS |

**Overall Result:** **ALL GATES PASSED** - Release can proceed.



## Gate 1: Unit and Integration Tests (H2)

- **Command:** mvn test
- **Exit Code:** 0
- **Duration:** 88.17s
- **Tests Run:** 1094
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Result:** PASS
- **Report Files:** TEST-com.nocobase.AclPermissionTest.xml, TEST-com.nocobase.ActionScopeRelationReviewTest.xml, TEST-com.nocobase.api.ErrorEnvelopeTest.xml, TEST-com.nocobase.ApiCompatibilityTest.xml, TEST-com.nocobase.ArchitectureBoundaryTest.xml, TEST-com.nocobase.BootstrapAdminTest.xml, TEST-com.nocobase.BootstrapConcurrencyTest.xml, TEST-com.nocobase.BootstrapPasswordNoDigitTest.xml, TEST-com.nocobase.BootstrapPasswordNoLetterTest.xml, TEST-com.nocobase.BootstrapWeakPasswordTest.xml, TEST-com.nocobase.CollectionAndFieldMetadataTest.xml, TEST-com.nocobase.config.NocobaseDataSourcePropertiesTest.xml, TEST-com.nocobase.config.ProductionConfigGuardTest.xml, TEST-com.nocobase.controller.AuditLogControllerTest.xml, TEST-com.nocobase.controller.HealthControllerTest.xml, TEST-com.nocobase.controller.HealthEndpointTest.xml, TEST-com.nocobase.data.PhysicalSqlBuilderTest.xml, TEST-com.nocobase.data.SqlPlanTest.xml, TEST-com.nocobase.DataLayerIntegrationTest.xml, TEST-com.nocobase.DataSourceConfigSanitizationTest.xml, TEST-com.nocobase.DataSourceConfigServiceTest$IsTableNotFound.xml, TEST-com.nocobase.DataSourceConfigServiceTest.xml, TEST-com.nocobase.DataSourcePasswordEncryptorTest.xml, TEST-com.nocobase.ddl.SchemaPlanTest.xml, TEST-com.nocobase.DdlBoundaryTest.xml, TEST-com.nocobase.DefaultValueAndDialectDiffTest.xml, TEST-com.nocobase.frontend.HarToContractConverterTest.xml, TEST-com.nocobase.NocobaseApplicationTests.xml, TEST-com.nocobase.P0P1FixTest.xml, TEST-com.nocobase.P1FixApiTest.xml, TEST-com.nocobase.postgresql.PostgreSqlExternalModeTest.xml, TEST-com.nocobase.release.EncodingGateTest.xml, TEST-com.nocobase.release.FrontendContractCoverageTest.xml, TEST-com.nocobase.release.NoSecretsInGitTest.xml, TEST-com.nocobase.release.ReleaseGateScriptTest.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest$EncodingTests.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest$ListTests.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest$UsageTests.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest$VerifyReportTests.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest$VerifyTests.xml, TEST-com.nocobase.release.ReleaseGateVerifierTest.xml, TEST-com.nocobase.release.ReleaseGateWorkflowTest.xml, TEST-com.nocobase.release.SurefireReportParserTest$MergeTests.xml, TEST-com.nocobase.release.SurefireReportParserTest$ParseDirectoryTests.xml, TEST-com.nocobase.release.SurefireReportParserTest$ParseFileTests.xml, TEST-com.nocobase.release.SurefireReportParserTest$ParseStreamTests.xml, TEST-com.nocobase.release.SurefireReportParserTest$TestCaseResultTests.xml, TEST-com.nocobase.release.SurefireReportParserTest$ValidationTests.xml, TEST-com.nocobase.release.SurefireReportParserTest.xml, TEST-com.nocobase.runtime.CollectionRuntimeServiceTest.xml, TEST-com.nocobase.runtime.IndexDefinitionTest$BuilderTests.xml, TEST-com.nocobase.runtime.IndexDefinitionTest$CollectionIndexValidationTests.xml, TEST-com.nocobase.runtime.IndexDefinitionTest$CollectionOptionsTests.xml, TEST-com.nocobase.runtime.IndexDefinitionTest$EffectiveColumnNameResolutionTests.xml, TEST-com.nocobase.runtime.IndexDefinitionTest$FieldOptionsTests.xml, TEST-com.nocobase.runtime.IndexDefinitionTest.xml, TEST-com.nocobase.service.AuditFailureIntegrationTest.xml, TEST-com.nocobase.service.AuditLogIntegrationTest.xml, TEST-com.nocobase.service.AuditLogSanitizationTest.xml, TEST-com.nocobase.service.AuditTransactionRollbackTest.xml, TEST-com.nocobase.sql.SqlDataSourceResolverIntegrationTest.xml, TEST-com.nocobase.sql.SqlDataSourceResolverTest.xml, TEST-com.nocobase.sql.SqlErrorSanitizerTest.xml, TEST-com.nocobase.sql.SqlIdentifierTest.xml, TEST-com.nocobase.sql.SqlNamedParameterParserTest.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$CurrentUserPathTypeValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$DeclaredButUnusedValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$DefaultValueTypeValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$DuplicateNameValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$EdgeCases.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$ListEntryTypeValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$NameFormatValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$ParameterParserResultImmutability.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$ParametersShapeValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$TypedValueNormalization.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest$UndeclaredSqlParameterValidation.xml, TEST-com.nocobase.sql.SqlParameterMetadataTest.xml, TEST-com.nocobase.sql.SqlParameterResolverTest$CurrentUserSourceTests.xml, TEST-com.nocobase.sql.SqlParameterResolverTest$ErrorHandlingTests.xml, TEST-com.nocobase.sql.SqlParameterResolverTest$MixedSourceTests.xml, TEST-com.nocobase.sql.SqlParameterResolverTest$StaticSourceTests.xml, TEST-com.nocobase.sql.SqlParameterResolverTest.xml, TEST-com.nocobase.sql.SqlQueryCollectionExecutorTest.xml, TEST-com.nocobase.sql.SqlQueryPlanTest.xml, TEST-com.nocobase.sql.SqlValidatorTest.xml, TEST-com.nocobase.SqlCollectionErrorTest.xml, TEST-com.nocobase.SqlQueryCollectionTest.xml, TEST-com.nocobase.UiSchemaP0BTest.xml, TEST-com.nocobase.web.GlobalExceptionHandlerTest.xml, TEST-com.nocobase.web.RequestIdFilterTest.xml

### Test Suite Details

| Test Suite | Tests | Failures | Errors | Skipped | Time (s) |
|------------|-------|----------|--------|---------|----------|
| TEST-com.nocobase.AclPermissionTest.xml | 23 | 0 | 0 | 0 | 7.608 |
| TEST-com.nocobase.ActionScopeRelationReviewTest.xml | 10 | 0 | 0 | 0 | 0.549 |
| TEST-com.nocobase.api.ErrorEnvelopeTest.xml | 13 | 0 | 0 | 0 | 2.281 |
| TEST-com.nocobase.ApiCompatibilityTest.xml | 104 | 0 | 0 | 0 | 9.999 |
| TEST-com.nocobase.ArchitectureBoundaryTest.xml | 29 | 0 | 0 | 0 | 0.178 |
| TEST-com.nocobase.BootstrapAdminTest.xml | 7 | 0 | 0 | 0 | 0.587 |
| TEST-com.nocobase.BootstrapConcurrencyTest.xml | 1 | 0 | 0 | 0 | 0.798 |
| TEST-com.nocobase.BootstrapPasswordNoDigitTest.xml | 1 | 0 | 0 | 0 | 0.818 |
| TEST-com.nocobase.BootstrapPasswordNoLetterTest.xml | 1 | 0 | 0 | 0 | 0.729 |
| TEST-com.nocobase.BootstrapWeakPasswordTest.xml | 1 | 0 | 0 | 0 | 0.755 |
| TEST-com.nocobase.CollectionAndFieldMetadataTest.xml | 17 | 0 | 0 | 0 | 0.08 |
| TEST-com.nocobase.config.NocobaseDataSourcePropertiesTest.xml | 20 | 0 | 0 | 0 | 0.692 |
| TEST-com.nocobase.config.ProductionConfigGuardTest.xml | 11 | 0 | 0 | 0 | 0.261 |
| TEST-com.nocobase.controller.AuditLogControllerTest.xml | 3 | 0 | 0 | 0 | 0.769 |
| TEST-com.nocobase.controller.HealthControllerTest.xml | 10 | 0 | 0 | 0 | 0.268 |
| TEST-com.nocobase.controller.HealthEndpointTest.xml | 3 | 0 | 0 | 0 | 1.63 |
| TEST-com.nocobase.data.PhysicalSqlBuilderTest.xml | 22 | 0 | 0 | 0 | 0.018 |
| TEST-com.nocobase.data.SqlPlanTest.xml | 3 | 0 | 0 | 0 | 0.003 |
| TEST-com.nocobase.DataLayerIntegrationTest.xml | 18 | 0 | 0 | 0 | 0.256 |
| TEST-com.nocobase.DataSourceConfigSanitizationTest.xml | 32 | 0 | 0 | 0 | 0.155 |
| TEST-com.nocobase.DataSourceConfigServiceTest$IsTableNotFound.xml | 19 | 0 | 0 | 0 | 0.008 |
| TEST-com.nocobase.DataSourceConfigServiceTest.xml | 0 | 0 | 0 | 0 | 0.026 |
| TEST-com.nocobase.DataSourcePasswordEncryptorTest.xml | 20 | 0 | 0 | 0 | 0.032 |
| TEST-com.nocobase.ddl.SchemaPlanTest.xml | 17 | 0 | 0 | 0 | 0.021 |
| TEST-com.nocobase.DdlBoundaryTest.xml | 17 | 0 | 0 | 0 | 0.437 |
| TEST-com.nocobase.DefaultValueAndDialectDiffTest.xml | 35 | 0 | 0 | 0 | 0.175 |
| TEST-com.nocobase.frontend.HarToContractConverterTest.xml | 12 | 0 | 0 | 0 | 0.031 |
| TEST-com.nocobase.NocobaseApplicationTests.xml | 1 | 0 | 0 | 0 | 0.005 |
| TEST-com.nocobase.P0P1FixTest.xml | 41 | 0 | 0 | 0 | 0.639 |
| TEST-com.nocobase.P1FixApiTest.xml | 80 | 0 | 0 | 0 | 6.914 |
| TEST-com.nocobase.postgresql.PostgreSqlExternalModeTest.xml | 6 | 0 | 0 | 0 | 0.007 |
| TEST-com.nocobase.release.EncodingGateTest.xml | 3 | 0 | 0 | 0 | 0.168 |
| TEST-com.nocobase.release.FrontendContractCoverageTest.xml | 2 | 0 | 0 | 0 | 0.009 |
| TEST-com.nocobase.release.NoSecretsInGitTest.xml | 2 | 0 | 0 | 0 | 0.492 |
| TEST-com.nocobase.release.ReleaseGateScriptTest.xml | 9 | 0 | 0 | 0 | 0.239 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest$EncodingTests.xml | 1 | 0 | 0 | 0 | 0.485 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest$ListTests.xml | 3 | 0 | 0 | 0 | 0.082 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest$UsageTests.xml | 5 | 0 | 0 | 0 | 0.016 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest$VerifyReportTests.xml | 6 | 0 | 0 | 0 | 0.209 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest$VerifyTests.xml | 7 | 0 | 0 | 0 | 0.125 |
| TEST-com.nocobase.release.ReleaseGateVerifierTest.xml | 0 | 0 | 0 | 0 | 0.966 |
| TEST-com.nocobase.release.ReleaseGateWorkflowTest.xml | 6 | 0 | 0 | 0 | 0.021 |
| TEST-com.nocobase.release.SurefireReportParserTest$MergeTests.xml | 4 | 0 | 0 | 0 | 0.063 |
| TEST-com.nocobase.release.SurefireReportParserTest$ParseDirectoryTests.xml | 4 | 0 | 0 | 0 | 0.076 |
| TEST-com.nocobase.release.SurefireReportParserTest$ParseFileTests.xml | 1 | 0 | 0 | 0 | 0.019 |
| TEST-com.nocobase.release.SurefireReportParserTest$ParseStreamTests.xml | 7 | 0 | 0 | 0 | 0.094 |
| TEST-com.nocobase.release.SurefireReportParserTest$TestCaseResultTests.xml | 5 | 0 | 0 | 0 | 0.033 |
| TEST-com.nocobase.release.SurefireReportParserTest$ValidationTests.xml | 4 | 0 | 0 | 0 | 0.052 |
| TEST-com.nocobase.release.SurefireReportParserTest.xml | 0 | 0 | 0 | 0 | 0.39 |
| TEST-com.nocobase.runtime.CollectionRuntimeServiceTest.xml | 46 | 0 | 0 | 0 | 12.111 |
| TEST-com.nocobase.runtime.IndexDefinitionTest$BuilderTests.xml | 7 | 0 | 0 | 0 | 0.015 |
| TEST-com.nocobase.runtime.IndexDefinitionTest$CollectionIndexValidationTests.xml | 12 | 0 | 0 | 0 | 0.104 |
| TEST-com.nocobase.runtime.IndexDefinitionTest$CollectionOptionsTests.xml | 6 | 0 | 0 | 0 | 0.039 |
| TEST-com.nocobase.runtime.IndexDefinitionTest$EffectiveColumnNameResolutionTests.xml | 3 | 0 | 0 | 0 | 0.009 |
| TEST-com.nocobase.runtime.IndexDefinitionTest$FieldOptionsTests.xml | 8 | 0 | 0 | 0 | 0.039 |
| TEST-com.nocobase.runtime.IndexDefinitionTest.xml | 2 | 0 | 0 | 0 | 0.225 |
| TEST-com.nocobase.service.AuditFailureIntegrationTest.xml | 17 | 0 | 0 | 0 | 3.814 |
| TEST-com.nocobase.service.AuditLogIntegrationTest.xml | 2 | 0 | 0 | 0 | 5.293 |
| TEST-com.nocobase.service.AuditLogSanitizationTest.xml | 7 | 0 | 0 | 0 | 0.006 |
| TEST-com.nocobase.service.AuditTransactionRollbackTest.xml | 5 | 0 | 0 | 0 | 0.237 |
| TEST-com.nocobase.sql.SqlDataSourceResolverIntegrationTest.xml | 36 | 0 | 0 | 0 | 17.554 |
| TEST-com.nocobase.sql.SqlDataSourceResolverTest.xml | 9 | 0 | 0 | 0 | 2.253 |
| TEST-com.nocobase.sql.SqlErrorSanitizerTest.xml | 32 | 0 | 0 | 0 | 0.02 |
| TEST-com.nocobase.sql.SqlIdentifierTest.xml | 15 | 0 | 0 | 0 | 0.038 |
| TEST-com.nocobase.sql.SqlNamedParameterParserTest.xml | 21 | 0 | 0 | 0 | 0.022 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$CurrentUserPathTypeValidation.xml | 5 | 0 | 0 | 0 | 0.011 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$DeclaredButUnusedValidation.xml | 4 | 0 | 0 | 0 | 0.003 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$DefaultValueTypeValidation.xml | 21 | 0 | 0 | 0 | 0.033 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$DuplicateNameValidation.xml | 1 | 0 | 0 | 0 | 0.006 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$EdgeCases.xml | 8 | 0 | 0 | 0 | 0.017 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$ListEntryTypeValidation.xml | 3 | 0 | 0 | 0 | 0.007 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$NameFormatValidation.xml | 3 | 0 | 0 | 0 | 0.045 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$ParameterParserResultImmutability.xml | 1 | 0 | 0 | 0 | 0.002 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$ParametersShapeValidation.xml | 5 | 0 | 0 | 0 | 0.017 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$TypedValueNormalization.xml | 12 | 0 | 0 | 0 | 0.025 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest$UndeclaredSqlParameterValidation.xml | 4 | 0 | 0 | 0 | 0.009 |
| TEST-com.nocobase.sql.SqlParameterMetadataTest.xml | 0 | 0 | 0 | 0 | 0.194 |
| TEST-com.nocobase.sql.SqlParameterResolverTest$CurrentUserSourceTests.xml | 6 | 0 | 0 | 0 | 0.196 |
| TEST-com.nocobase.sql.SqlParameterResolverTest$ErrorHandlingTests.xml | 2 | 0 | 0 | 0 | 0.024 |
| TEST-com.nocobase.sql.SqlParameterResolverTest$MixedSourceTests.xml | 1 | 0 | 0 | 0 | 0.002 |
| TEST-com.nocobase.sql.SqlParameterResolverTest$StaticSourceTests.xml | 5 | 0 | 0 | 0 | 0.009 |
| TEST-com.nocobase.sql.SqlParameterResolverTest.xml | 0 | 0 | 0 | 0 | 0.268 |
| TEST-com.nocobase.sql.SqlQueryCollectionExecutorTest.xml | 5 | 0 | 0 | 0 | 0.001 |
| TEST-com.nocobase.sql.SqlQueryPlanTest.xml | 17 | 0 | 0 | 0 | 0.038 |
| TEST-com.nocobase.sql.SqlValidatorTest.xml | 5 | 0 | 0 | 0 | 0.004 |
| TEST-com.nocobase.SqlCollectionErrorTest.xml | 9 | 0 | 0 | 0 | 0.648 |
| TEST-com.nocobase.SqlQueryCollectionTest.xml | 63 | 0 | 0 | 0 | 0.365 |
| TEST-com.nocobase.UiSchemaP0BTest.xml | 23 | 0 | 0 | 0 | 0.275 |
| TEST-com.nocobase.web.GlobalExceptionHandlerTest.xml | 7 | 0 | 0 | 0 | 0.005 |
| TEST-com.nocobase.web.RequestIdFilterTest.xml | 10 | 0 | 0 | 0 | 0.655 |

## Gate 2: Flyway Migration Validation

- **Command:** mvn flyway:validate
- **Exit Code:** 0
- **Duration:** 6.25s
- **Result:** PASS


## Gate 3: PostgreSQL Acceptance Tests

- **Command:** mvn test -Ppostgresql-acceptance
- **Exit Code:** 0
- **Duration:** 18.33s
- **Tests Run:** 29
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Result:** PASS
- **Report Files:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml
- **PG Report File:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml (found: YES, tests=29, failures=0, errors=0, skipped=0)
- **ReleaseGateVerifier:** PASS



### PG Test Suite Details

| Test Suite | Tests | Failures | Errors | Skipped | Time (s) |
|------------|-------|----------|--------|---------|----------|
| TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml | 29 | 0 | 0 | 0 | 13.289 |

## Gate 4: Sensitive Code Scan

- **Result:** PASS
- **Errors:** 0
- **Total Issues:** 25

### Issues Found

| Severity | Category | File | Line | Content |
|----------|----------|------|------|---------|
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1513 | `System.out.println(`"=== P1-F Contract Replay Results ===`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1514 | `System.out.println(`"Total: `" + totalContracts + `", Passed: `" + passed + `", Failed: `" + failed);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1516 | `System.out.println(`"Failures:`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1721 | `System.out.println(`"=== P1-H Trace Replay Results ===`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1722 | `System.out.println(`"Trace: `" + traceName);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1723 | `System.out.println(`"Total: `" + totalSteps + `", Passed: `" + passed + `", Failed: `" + failed);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ApiCompatibilityTest.java | 1725 | `System.out.println(`"Failures:`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ArchitectureBoundaryTest.java | 626 | `@DisplayName(`"P0-C: No System.out.println or printStackTrace in production code`")` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ArchitectureBoundaryTest.java | 666 | `assertFalse(codeOnly.contains(`"System.out.println`"),` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ArchitectureBoundaryTest.java | 667 | `relativePath + `" contains System.out.println. Use SLF4J logger instead.`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ArchitectureBoundaryTest.java | 668 | `assertFalse(codeOnly.contains(`"System.out.print`"),` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\ArchitectureBoundaryTest.java | 669 | `relativePath + `" contains System.out.print. Use SLF4J logger instead.`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 365 | `System.out.println(`"Converted `" + stepCount + `" steps from `" + inputFile + `" to `" + outputFile);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 366 | `System.out.println(`"Trace name: `" + traceName);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 368 | `System.out.println(`"URL prefix filter: `" + urlPrefix);` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 378 | `System.out.println(`"Usage: java com.nocobase.frontend.HarToContractConverter <input.har> <output.json> [options]`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 379 | `System.out.println();` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 380 | `System.out.println(`"Options:`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 381 | `System.out.println(`"  --prefix <path>      Filter requests by URL path prefix (e.g., /api/)`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 382 | `System.out.println(`"  --trace <name>       Set trace name (default: har-replay)`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 383 | `System.out.println(`"  --description <desc> Set trace description`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 384 | `System.out.println(`"  --pretty             Pretty-print output JSON`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 385 | `System.out.println();` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 386 | `System.out.println(`"Example:`");` |
| INFO | System.out.println (test) | .\src\test\java\com\nocobase\frontend\HarToContractConverter.java | 387 | `System.out.println(`"  java com.nocobase.frontend.HarToContractConverter recording.har trace.json --prefix /api/ --trace `\`"frontend-recording`\`"`");` |

## Gate Rules Reference

| Gate | Rule |
|------|------|
| 1 | Exit 0, Tests > 0, Failures = 0, Errors = 0 |
| 2 | Exit 0, no error output |
| 3 | Exit 0, Tests > 0, Failures = 0, Errors = 0, Skipped = 0. PG report file must exist and be valid. Default: Testcontainers auto-starts PostgreSQL. Override: -RequireExternalPg requires PG_URL/PG_USERNAME/PG_PASSWORD env vars. ReleaseGateVerifier exit 0 validates report. |
| 4 | Zero ERROR-level issues (WARN/INFO are informational) |

Any FAIL is a **BLOCKER** for the release.
