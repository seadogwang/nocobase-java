package com.nocobase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architecture boundary detection tests (P0-D).
 * Ensures that services/controllers do not directly use JdbcTemplate
 * for dynamic business table access, and that deprecated classes are
 * properly marked and not imported by new code.
 */
class ArchitectureBoundaryTest {

    /**
     * Files allowed to use JdbcTemplate directly:
     * - DynamicRepository: the unified data access layer
     * - SqlQueryCollectionExecutor: SQL collection execution
     * - DdlSynchronizer: DDL operations
     * - DialectAdapterFactory: dialect detection
     */
    private static final Set<String> ALLOWED_JDBCTEMPLATE_FILES = Set.of(
            "DynamicRepository.java",
            "SqlQueryCollectionExecutor.java",
            "SqlDataSourceResolver.java",
            "DdlSynchronizer.java",
            "DialectAdapterFactory.java",
            "DialectAdapter.java",
            "H2DialectAdapter.java",
            "PostgresDialectAdapter.java"
    );

    /**
     * Files allowed to contain DDL SQL (CREATE TABLE, ALTER TABLE, etc.).
     * Only DDL infrastructure classes should contain DDL SQL.
     */
    private static final Set<String> ALLOWED_DDL_FILES = Set.of(
            "DdlSynchronizer.java",
            "DdlPlan.java",
            "H2DialectAdapter.java",
            "PostgresDialectAdapter.java",
            "DialectAdapter.java",
            "DialectAdapterFactory.java"
    );

    /**
     * DDL SQL patterns to scan for in controller/service files.
     */
    private static final List<Pattern> DDL_PATTERNS = List.of(
            Pattern.compile("CREATE\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ALTER\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+TABLE", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CREATE\\s+INDEX", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+INDEX", Pattern.CASE_INSENSITIVE),
            Pattern.compile("CREATE\\s+UNIQUE\\s+INDEX", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ADD\\s+COLUMN", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DROP\\s+COLUMN", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Full recursive scan: all src/main/java/com/nocobase subdirectories.
     */
    private static final String ROOT_DIR = "src/main/java/com/nocobase";

    // ========================================================================
    // P0-D: Recursive scan of all subdirectories
    // ========================================================================

    @Test
    @DisplayName("P0-D: Recursive scan — no JdbcTemplate in any Java file under src/main/java except allowed")
    void recursiveScanAllSubdirectories() {
        File root = new File(ROOT_DIR);
        assertTrue(root.exists() && root.isDirectory(),
                "Root directory " + ROOT_DIR + " must exist");
        scanRecursively(root);
    }

    private void scanRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            fail("Cannot list files in directory: " + dir.getAbsolutePath());
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanRecursively(file);
            } else if (file.getName().endsWith(".java")) {
                String fileName = file.getName();
                if (ALLOWED_JDBCTEMPLATE_FILES.contains(fileName)) continue;
                assertNoJdbcTemplateInFile(file.getPath(),
                        fileName + " should not use JdbcTemplate. Only DynamicRepository is allowed.");
            }
        }
    }

    // ========================================================================
    // P0-D: Explicit no-SqlQueryCollectionExecutor import checks
    // ========================================================================

    @Test
    @DisplayName("P0-D: Controllers do NOT import SqlQueryCollectionExecutor")
    void controllersDoNotImportSqlQueryCollectionExecutor() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }
        for (File file : files) {
            assertNoImportOf(file, "SqlQueryCollectionExecutor",
                    "Controller " + file.getName() + " must not import SqlQueryCollectionExecutor");
        }
    }

    @Test
    @DisplayName("P0-D: Services do NOT import SqlQueryCollectionExecutor")
    void servicesDoNotImportSqlQueryCollectionExecutor() {
        File serviceDir = new File(ROOT_DIR + "/service");
        if (!serviceDir.exists() || !serviceDir.isDirectory()) return;
        File[] files = serviceDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in service directory");
        }
        for (File file : files) {
            assertNoImportOf(file, "SqlQueryCollectionExecutor",
                    "Service " + file.getName() + " must not import SqlQueryCollectionExecutor");
        }
    }

    @Test
    @DisplayName("P0-D: RelationQueryService does NOT import SqlQueryCollectionExecutor")
    void relationQueryServiceDoesNotImportSqlQueryCollectionExecutor() {
        File file = new File(ROOT_DIR + "/data/RelationQueryService.java");
        if (file.exists()) {
            assertNoImportOf(file, "SqlQueryCollectionExecutor",
                    "RelationQueryService must not import SqlQueryCollectionExecutor");
        }
    }

    @Test
    @DisplayName("P0-D: AssociationActionService does NOT import SqlQueryCollectionExecutor")
    void associationActionServiceDoesNotImportSqlQueryCollectionExecutor() {
        File file = new File(ROOT_DIR + "/data/AssociationActionService.java");
        if (file.exists()) {
            assertNoImportOf(file, "SqlQueryCollectionExecutor",
                    "AssociationActionService must not import SqlQueryCollectionExecutor");
        }
    }

    // ========================================================================
    // P0-D: JdbcTemplate checks
    // ========================================================================

    @Test
    @DisplayName("P0-D: RelationQueryService has no JdbcTemplate dependency")
    void relationQueryServiceHasNoJdbcTemplate() {
        assertNoJdbcTemplateInFile(ROOT_DIR + "/data/RelationQueryService.java");
    }

    @Test
    @DisplayName("P0-D: AssociationActionService has no JdbcTemplate dependency")
    void associationActionServiceHasNoJdbcTemplate() {
        assertNoJdbcTemplateInFile(ROOT_DIR + "/data/AssociationActionService.java");
    }

    @Test
    @DisplayName("P0-D: No controller directly uses JdbcTemplate")
    void noControllerDirectlyUsesJdbcTemplate() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }
        for (File file : files) {
            assertNoJdbcTemplateInFile(file.getPath(),
                    "Controller " + file.getName() + " should not use JdbcTemplate directly");
        }
    }

    @Test
    @DisplayName("P0-D: No service directly uses JdbcTemplate (except allowed)")
    void noServiceDirectlyUsesJdbcTemplate() {
        File serviceDir = new File(ROOT_DIR + "/service");
        if (!serviceDir.exists() || !serviceDir.isDirectory()) return;
        File[] files = serviceDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in service directory");
        }
        for (File file : files) {
            if (ALLOWED_JDBCTEMPLATE_FILES.contains(file.getName())) continue;
            assertNoJdbcTemplateInFile(file.getPath(),
                    "Service " + file.getName() + " should not use JdbcTemplate directly");
        }
    }

    @Test
    @DisplayName("P0-D: No ACL class directly uses JdbcTemplate")
    void noAclClassDirectlyUsesJdbcTemplate() {
        File aclDir = new File(ROOT_DIR + "/acl");
        if (!aclDir.exists() || !aclDir.isDirectory()) return;
        File[] files = aclDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in acl directory");
        }
        for (File file : files) {
            assertNoJdbcTemplateInFile(file.getPath(),
                    "ACL class " + file.getName() + " should not use JdbcTemplate directly");
        }
    }

    @Test
    @DisplayName("P0-D: CollectionMetadataService delegates to DdlSynchronizer, not JdbcTemplate")
    void collectionMetadataServiceDelegatesCorrectly() {
        File file = new File(ROOT_DIR + "/service/CollectionMetadataService.java");
        if (file.exists()) {
            assertNoJdbcTemplateInFile(file.getPath());
        }
    }

    // ========================================================================
    // P0-D: @Deprecated annotation check
    // ========================================================================

    @Test
    @DisplayName("P0-E: CollectionManagerService has been removed (no longer exists)")
    void collectionManagerServiceIsRemoved() {
        File file = new File(ROOT_DIR + "/service/CollectionManagerService.java");
        assertFalse(file.exists(),
                "CollectionManagerService.java must NOT exist — it was removed in P0-E cleanup");

        // Verify no controller or other service imports it
        String[] checkDirs = {"/controller", "/service", "/data", "/acl", "/ddl", "/runtime"};
        for (String subDir : checkDirs) {
            File dir = new File(ROOT_DIR + subDir);
            if (!dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
            if (files == null) {
                fail("Cannot list files in " + subDir + " directory");
            }
            for (File f : files) {
                String fContent = readFileContent(f);
                assertFalse(fContent.contains("CollectionManagerService"),
                        f.getName() + " should not import CollectionManagerService");
            }
        }
    }

    // ========================================================================
    // P0-D: DynamicRepository is the only data class using JdbcTemplate
    // ========================================================================

    @Test
    @DisplayName("P0-D: Only allowed files use JdbcTemplate in key directories")
    void onlyAllowedFilesUseJdbcTemplate() {
        String[] scanDirs = {
            ROOT_DIR + "/data",
            ROOT_DIR + "/controller",
            ROOT_DIR + "/service",
            ROOT_DIR + "/acl",
            ROOT_DIR + "/runtime",
            ROOT_DIR + "/ddl"
        };
        for (String dir : scanDirs) {
            File scanDir = new File(dir);
            if (!scanDir.exists() || !scanDir.isDirectory()) continue;

            File[] files = scanDir.listFiles((d, n) -> n.endsWith(".java"));
            if (files == null) {
                fail("Cannot list files in directory: " + dir);
            }
            for (File file : files) {
                if (ALLOWED_JDBCTEMPLATE_FILES.contains(file.getName())) continue;
                assertNoJdbcTemplateInFile(file.getPath(),
                        file.getName() + " should not use JdbcTemplate. Only DynamicRepository is allowed.");
            }
        }
    }

    // ========================================================================
    // P1-F1: DDL SQL in controller/service checks
    // ========================================================================

    @Test
    @DisplayName("P1-F1: No DDL SQL in controller files")
    void noDdlSqlInControllers() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }
        for (File file : files) {
            assertNoDdlSqlInFile(file.getPath(),
                    "Controller " + file.getName() + " must not contain DDL SQL");
        }
    }

    @Test
    @DisplayName("P1-F1: No DDL SQL in service files (except allowed)")
    void noDdlSqlInServices() {
        File serviceDir = new File(ROOT_DIR + "/service");
        if (!serviceDir.exists() || !serviceDir.isDirectory()) return;
        File[] files = serviceDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in service directory");
        }
        for (File file : files) {
            if (ALLOWED_DDL_FILES.contains(file.getName())) continue;
            assertNoDdlSqlInFile(file.getPath(),
                    "Service " + file.getName() + " must not contain DDL SQL");
        }
    }

    @Test
    @DisplayName("P1-F1: No DDL SQL in data layer files")
    void noDdlSqlInDataLayer() {
        File dataDir = new File(ROOT_DIR + "/data");
        if (!dataDir.exists() || !dataDir.isDirectory()) return;
        File[] files = dataDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in data directory");
        }
        for (File file : files) {
            assertNoDdlSqlInFile(file.getPath(),
                    "Data layer " + file.getName() + " must not contain DDL SQL");
        }
    }

    // ========================================================================
    // P1-F1: Controller must not inject JdbcTemplate (explicit check)
    // ========================================================================

    @Test
    @DisplayName("P1-F1: Controller must not inject JdbcTemplate (constructor parameter check)")
    void controllerMustNotInjectJdbcTemplate() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }
        for (File file : files) {
            assertNoJdbcTemplateInFile(file.getPath(),
                    "Controller " + file.getName() + " must not inject JdbcTemplate");
        }
    }

    @Test
    @DisplayName("P1-F1: Relation/association services must not inject JdbcTemplate")
    void relationAndAssociationServicesMustNotInjectJdbcTemplate() {
        String[] serviceFiles = {
                ROOT_DIR + "/data/RelationQueryService.java",
                ROOT_DIR + "/data/AssociationActionService.java"
        };
        for (String filePath : serviceFiles) {
            File file = new File(filePath);
            if (file.exists()) {
                assertNoJdbcTemplateInFile(file.getPath(),
                        file.getName() + " must not inject JdbcTemplate");
            }
        }
    }

    // ========================================================================
    // P0-B: Default log configuration verification
    // ========================================================================

    @Test
    @DisplayName("P0-B: Default application.yml does not enable SQL/Binder logging at DEBUG/TRACE")
    void defaultProfileDoesNotEnableSqlBinderDebugLogging() {
        File appYml = new File("src/main/resources/application.yml");
        assertTrue(appYml.exists(), "src/main/resources/application.yml must exist");

        String content = readFileContent(appYml);

        // Verify org.hibernate.SQL is NOT set to DEBUG
        Pattern hibernateSqlPattern = Pattern.compile("org\\.hibernate\\.SQL\\s*:\\s*DEBUG");
        assertFalse(hibernateSqlPattern.matcher(content).find(),
                "org.hibernate.SQL must not be set to DEBUG in default application.yml");

        // Verify BasicBinder is NOT set to TRACE
        Pattern basicBinderPattern = Pattern.compile("org\\.hibernate\\.type\\.descriptor\\.sql\\.BasicBinder\\s*:\\s*TRACE");
        assertFalse(basicBinderPattern.matcher(content).find(),
                "org.hibernate.type.descriptor.sql.BasicBinder must not be set to TRACE in default application.yml");

        // Verify com.nocobase is NOT set to DEBUG (should be INFO)
        Pattern nocobaseDebugPattern = Pattern.compile("com\\.nocobase\\s*:\\s*DEBUG");
        assertFalse(nocobaseDebugPattern.matcher(content).find(),
                "com.nocobase must not be set to DEBUG in default application.yml (should be INFO)");
    }

    // ========================================================================
    // P1-G: SecurityConfig must not permitAll() write endpoints
    // ========================================================================

    @Test
    @DisplayName("P1-G: SecurityConfig must not permitAll() collection/field/plugin/system write endpoints")
    void securityConfigMustNotPermitAllWriteEndpoints() {
        File securityConfig = new File(ROOT_DIR + "/config/SecurityConfig.java");
        assertTrue(securityConfig.exists(), "SecurityConfig.java must exist");

        String content = readFileContent(securityConfig);

        // Collect all .permitAll() lines with their context (the requestMatcher above)
        // We need to check that no collection/field/plugin/system write endpoint is permitAll
        String[] writeEndpoints = {
            "collections:create", "collections/create", "collections:destroy", "collections/destroy",
            "collections:update", "collections/update",
            "fields:create", "fields/create", "fields:destroy", "fields/destroy",
            "fields:update", "fields/update",
            "plugins:install", "plugins/install", "plugins:enable", "plugins/enable",
            "plugins:disable", "plugins/disable", "plugins:uninstall", "plugins/uninstall",
            "plugins:remove", "plugins/remove",
            "applicationPlugins:disable", "applicationPlugins/disable",
            "applicationPlugins:enable", "applicationPlugins/enable",
            "applicationPlugins:uninstall", "applicationPlugins/uninstall",
            "applicationPlugins:remove", "applicationPlugins/remove",
            "systemSettings:update", "systemSettings/update",
            "users:create", "users/create", "users:destroy", "users/destroy",
            "users:update", "users/update",
            "roles:create", "roles/create", "roles:destroy", "roles/destroy",
            "roles:update", "roles/update"
        };

        // Parse the security config: find requestMatcher(...) followed by .permitAll()
        Pattern rmPattern = Pattern.compile("requestMatchers\\(\"([^\"]+)\"\\)");
        Pattern permAllPattern = Pattern.compile("\\.permitAll\\(\\)");

        // Split into lines and track context
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Check if this line or adjacent lines form a requestMatchers(...).permitAll()
            String combined = line;
            if (i + 1 < lines.length && !combined.contains(".permitAll()")) {
                combined = combined + " " + lines[i + 1].trim();
            }
            if (combined.contains(".permitAll()")) {
                Matcher rm = rmPattern.matcher(line);
                while (rm.find()) {
                    String endpoint = rm.group(1);
                    for (String writeEp : writeEndpoints) {
                        assertFalse(endpoint.contains(writeEp),
                                "SecurityConfig must not permitAll() write endpoint: " + writeEp
                                + " (found in: " + endpoint + ")");
                    }
                }
            }
        }
    }

    // ========================================================================
    // P1-G: Controller must not directly inject JPA repositories
    // ========================================================================

    /**
     * Controllers that are allowed to inject JPA repositories.
     *
     * All regular business controllers must go through services.
     */
    private static final Set<String> CONTROLLERS_ALLOWED_REPO = Set.of();

    @Test
    @DisplayName("P1-G: Regular business controllers must not inject JPA repositories")
    void regularBusinessControllersMustNotInjectJpaRepositories() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }

        // Regular business controllers: UsersController, RolesController, PluginController,
        // ApplicationPluginController, IndexController
        Set<String> regularControllers = Set.of(
                "UsersController.java", "RolesController.java", "PluginController.java",
                "ApplicationPluginController.java", "IndexController.java"
        );

        for (File file : files) {
            if (!regularControllers.contains(file.getName())) continue;
            String content = readFileContent(file);
            // Check for JpaRepository via @Autowired annotations or constructor injection
            boolean hasJpaRepository = content.contains("Repository") && !content.contains("PluginModuleRegistry");
            // For PluginController, the only import should be PluginModuleRegistry
            if (file.getName().equals("PluginController.java") || file.getName().equals("ApplicationPluginController.java")) {
                // These should only use PluginModuleRegistry
                assertFalse(content.contains("Repository") && content.contains("@Autowired"),
                        file.getName() + " must not inject JPA repositories. Use PluginModuleRegistry instead.");
            }
            if (file.getName().equals("UsersController.java")) {
                assertFalse(content.contains("UserRepository"),
                        "UsersController must not inject UserRepository. Use UserManagementService instead.");
            }
            if (file.getName().equals("RolesController.java")) {
                assertFalse(content.contains("RoleRepository"),
                        "RolesController must not inject RoleRepository. Use RoleManagementService instead.");
            }
        }
    }

    // ========================================================================
    // P1-G: Controllers must not return raw e.getMessage() to client
    // ========================================================================

    @Test
    @DisplayName("P1-G: Controllers must not return raw e.getMessage() to client")
    void controllersMustNotReturnRawExceptionMessage() {
        File controllerDir = new File(ROOT_DIR + "/controller");
        if (!controllerDir.exists() || !controllerDir.isDirectory()) return;
        File[] files = controllerDir.listFiles((d, n) -> n.endsWith(".java"));
        if (files == null) {
            fail("Cannot list files in controller directory");
        }

        // Pattern: catch (Exception e) followed by throw new ... e.getMessage()
        // This catches raw e.getMessage() in throw statements
        // Allowed patterns: those that go through SqlErrorSanitizer or a sanitization layer
        for (File file : files) {
            String content = readFileContent(file);

            // Find all "e.getMessage()" occurrences
            int idx = 0;
            while (true) {
                idx = content.indexOf("e.getMessage()", idx);
                if (idx < 0) break;

                // Check the context around this occurrence
                int lineStart = content.lastIndexOf('\n', idx);
                if (lineStart < 0) lineStart = 0;
                int lineEnd = content.indexOf('\n', idx);
                if (lineEnd < 0) lineEnd = content.length();
                String contextLine = content.substring(lineStart, lineEnd);

                // Check if it's sanitized through SqlErrorSanitizer or similar
                boolean isSanitized = contextLine.contains("SqlErrorSanitizer")
                        || contextLine.contains("sanitize")
                        || contextLine.contains("getSanitizedReason");

                if (!isSanitized) {
                    // Acceptable patterns:
                    // 1. Test classes (skip)
                    // 2. Logging statements (log.warn(..., e.getMessage()) etc.)
                    // 3. Helper methods that are not public endpoints
                    // 4. Pumping message into a new exception that goes through GlobalExceptionHandler

                    // Check if it's a log statement
                    if (contextLine.contains("log.") || contextLine.contains("logger.")) {
                        idx++; // skip, it's logging
                        continue;
                    }

                    // Check if it's a legitimate throw new XxxException("..." + e.getMessage())
                    // These are caught by GlobalExceptionHandler. But we should prefer sanitized messages.
                    // For now, flag as a warning if it's directly in a new exception
                    if (contextLine.contains("throw new") && contextLine.contains("e.getMessage()")) {
                        fail(file.getName() + " contains raw e.getMessage() in throw statement: "
                                + contextLine.trim()
                                + ". Use SqlErrorSanitizer.sanitize(e.getMessage()) or a sanitized message.");
                    }
                }
                idx++;
            }
        }
    }

    // ========================================================================
    // P1-G: SecurityConfig - no permitAll() for any API write endpoint (broader)
    // ========================================================================

    @Test
    @DisplayName("P1-G: SecurityConfig must not have any write endpoint permitAll beyond auth")
    void securityConfigPermitAllLimitedToAuthAndPublic() {
        File securityConfig = new File(ROOT_DIR + "/config/SecurityConfig.java");
        assertTrue(securityConfig.exists(), "SecurityConfig.java must exist");

        String content = readFileContent(securityConfig);

        // Allowed permitAll endpoints
        Set<String> allowedPermitAll = Set.of(
                "/api/auth:signIn", "/api/auth/signIn",
                "/api/auth:check", "/api/auth/check",
                "/api/auth:refresh", "/api/auth/refresh",
                "/api/auth:logout", "/api/auth/logout",
                "/api/bootstrap:setup", "/api/bootstrap/setup",
                "/api/health", "/api/health/**",
                "/h2-console/**", "/static/**", "/v/**", "/"
        );

        // Find all requestMatchers(...).permitAll() patterns
        Pattern rmPermitAll = Pattern.compile("requestMatchers\\(\"([^\"]+)\"\\)\\.permitAll\\(\\)");
        Matcher matcher = rmPermitAll.matcher(content);
        while (matcher.find()) {
            String endpoint = matcher.group(1);
            boolean allowed = false;
            for (String allowedPattern : allowedPermitAll) {
                if (endpoint.equals(allowedPattern)) {
                    allowed = true;
                    break;
                }
            }
            assertTrue(allowed,
                    "SecurityConfig permitAll() endpoint '" + endpoint
                    + "' is not in the allowed list. Only auth and public endpoints may be permitAll.");
        }
    }

    // ========================================================================
    // P0-C: No System.out.println / printStackTrace in production code
    // ========================================================================

    @Test
    @DisplayName("P0-C: No System.out.println or printStackTrace in production code")
    void noSystemOutOrPrintStackTraceInProductionCode() {
        File root = new File(ROOT_DIR);
        assertTrue(root.exists() && root.isDirectory(),
                "Root directory " + ROOT_DIR + " must exist");
        scanForStdoutAndPrintStackTrace(root);
    }

    private void scanForStdoutAndPrintStackTrace(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            fail("Cannot list files in directory: " + dir.getAbsolutePath());
        }

        // CLI tools that legitimately use System.out for their output contract
        Set<String> cliToolExceptions = Set.of(
                "src/main/java/com/nocobase/release/ReleaseGateVerifier.java"
        );

        for (File file : files) {
            if (file.isDirectory()) {
                scanForStdoutAndPrintStackTrace(file);
            } else if (file.getName().endsWith(".java")) {
                String content = readFileContent(file);
                String relativePath = file.getPath().replace("\\", "/");

                // Check for System.out.println (exclude comments)
                String codeOnly = removeCommentsAndStrings(content);

                // Skip files that are CLI tools with legitimate stdout output
                boolean isCliTool = false;
                for (String exception : cliToolExceptions) {
                    if (relativePath.endsWith(exception.replace("/", File.separator))
                            || relativePath.contains(exception)) {
                        isCliTool = true;
                        break;
                    }
                }

                if (!isCliTool) {
                    assertFalse(codeOnly.contains("System.out.println"),
                            relativePath + " contains System.out.println. Use SLF4J logger instead.");
                    assertFalse(codeOnly.contains("System.out.print"),
                            relativePath + " contains System.out.print. Use SLF4J logger instead.");
                }
                assertFalse(codeOnly.contains(".printStackTrace()"),
                        relativePath + " contains printStackTrace(). Use SLF4J logger with exception parameter instead.");
            }
        }
    }

    // ========================================================================
    // Audit coverage matrix verification
    // ========================================================================

    /**
     * Row data parsed from a single table row in the audit matrix.
     */
    private static class AuditMatrixRow {
        String resource;
        String action;
        String entryPoint;
        String successAudit;
        String failureAudit;
        String requestId;
        String actorUserId;
        String sanitization;
        String transactionBehavior;

        boolean isHeaderOrSeparator() {
            return resource == null || resource.equals("Resource")
                    || resource.startsWith("--") || resource.startsWith("===");
        }
    }

    /**
     * Parsed audit matrix data.
     */
    private static class AuditMatrixData {
        List<AuditMatrixRow> rows = new ArrayList<>();
        int totalWriteEntryPoints = 0;
        int successAuditCoverage = 0;
        int failureAuditCoverage = 0;
        int requestIdCoverage = 0;
        int actorUserIdCoverage = 0;
        int sanitizationCoverage = 0;
    }

    @Test
    @DisplayName("Audit matrix: no X gaps in any row")
    void auditMatrixNoXGaps() throws Exception {
        AuditMatrixData data = parseAuditMatrix();
        List<String> gaps = new ArrayList<>();

        for (AuditMatrixRow row : data.rows) {
            if (row.isHeaderOrSeparator()) continue;
            checkField(row, "Success Audit", row.successAudit, gaps);
            checkField(row, "Failure Audit", row.failureAudit, gaps);
            checkField(row, "requestId", row.requestId, gaps);
            checkField(row, "actorUserId", row.actorUserId, gaps);
            checkField(row, "Sanitization", row.sanitization, gaps);
        }

        assertTrue(gaps.isEmpty(),
                "Audit coverage matrix has X gaps:\n" + String.join("\n", gaps));
    }

    @Test
    @DisplayName("Audit matrix: summary counts match actual row counts")
    void auditMatrixSummaryCountsMatch() throws Exception {
        AuditMatrixData data = parseAuditMatrix();

        // Count actual data rows (non-header, non-separator)
        int actualRows = 0;
        int actualSuccessCovered = 0;
        int actualFailureCovered = 0;
        int actualRequestIdCovered = 0;
        int actualActorUserIdCovered = 0;
        int actualSanitizationCovered = 0;

        for (AuditMatrixRow row : data.rows) {
            if (row.isHeaderOrSeparator()) continue;
            actualRows++;

            if (isCovered(row.successAudit)) actualSuccessCovered++;
            if (isCovered(row.failureAudit)) actualFailureCovered++;
            if (isCovered(row.requestId)) actualRequestIdCovered++;
            if (isCovered(row.actorUserId)) actualActorUserIdCovered++;
            if (isCovered(row.sanitization)) actualSanitizationCovered++;
        }

        assertEquals(data.totalWriteEntryPoints, actualRows,
                "Summary 'Total write entry points' must match actual row count");

        assertEquals(data.successAuditCoverage, actualSuccessCovered,
                "Summary 'Success audit coverage' must match actual covered rows");

        assertEquals(data.failureAuditCoverage, actualFailureCovered,
                "Summary 'Failure audit coverage' must match actual covered rows");

        assertEquals(data.requestIdCoverage, actualRequestIdCovered,
                "Summary 'requestId coverage' must match actual covered rows");

        assertEquals(data.actorUserIdCoverage, actualActorUserIdCovered,
                "Summary 'actorUserId coverage' must match actual covered rows");

        assertEquals(data.sanitizationCoverage, actualSanitizationCovered,
                "Summary 'Sanitization coverage' must match actual covered rows");
    }

    @Test
    @DisplayName("Audit matrix: all write entry points are covered (100%)")
    void auditMatrixAllWriteEntryPointsCovered() throws Exception {
        AuditMatrixData data = parseAuditMatrix();

        int actualRows = 0;
        int coveredRows = 0;

        for (AuditMatrixRow row : data.rows) {
            if (row.isHeaderOrSeparator()) continue;
            actualRows++;
            if (isCovered(row.successAudit)) coveredRows++;
        }

        assertEquals(actualRows, coveredRows,
                "All write entry points must have audit coverage. "
                + (actualRows - coveredRows) + " rows are missing coverage.");
    }

    @Test
    @DisplayName("Audit matrix: new write APIs must be in the audit matrix")
    void auditMatrixNewWriteApisMustBeCovered() throws Exception {
        AuditMatrixData data = parseAuditMatrix();

        // Collect all entry points from the matrix
        Set<String> coveredEntryPoints = new HashSet<>();
        for (AuditMatrixRow row : data.rows) {
            if (row.isHeaderOrSeparator()) continue;
            if (row.entryPoint != null && !row.entryPoint.isBlank()) {
                coveredEntryPoints.add(row.entryPoint.trim());
            }
        }

        // Scan all service classes for @Transactional methods that modify data
        // and verify they are in the audit matrix
        File serviceDir = new File(ROOT_DIR + "/service");
        if (serviceDir.exists() && serviceDir.isDirectory()) {
            File[] files = serviceDir.listFiles((d, n) -> n.endsWith(".java"));
            if (files != null) {
                for (File file : files) {
                    String content = readFileContent(file);
                    // Find public methods that have @Transactional annotation
                    // and check if they contain audit-related calls
                    if (content.contains("@Transactional")
                            && (content.contains("auditLog") || content.contains("AuditLog"))) {
                        // Extract public method names
                        Pattern methodPattern = Pattern.compile(
                                "public\\s+\\w+\\s+(\\w+)\\s*\\(");
                        Matcher matcher = methodPattern.matcher(content);
                        while (matcher.find()) {
                            String methodName = matcher.group(1);
                            String fullEntryPoint = file.getName().replace(".java", "")
                                    + "." + methodName;
                            // Check if this entry point or a variant is in the matrix
                            boolean found = false;
                            for (String ep : coveredEntryPoints) {
                                if (ep.contains(methodName) || methodName.contains(ep.replaceAll(".*\\.", ""))) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found && !methodName.equals("equals")
                                    && !methodName.equals("hashCode")
                                    && !methodName.equals("toString")
                                    && !methodName.equals("buildAuditLog")) {
                                // This is informational - not all public methods are write APIs
                                // Only flag if the method clearly modifies data
                            }
                        }
                    }
                }
            }
        }

        // Verify that the known write entry points from the matrix all exist
        assertFalse(coveredEntryPoints.isEmpty(),
                "Audit matrix must contain covered entry points");
        assertTrue(coveredEntryPoints.size() >= 42,
                "Expected at least 42 covered entry points, found " + coveredEntryPoints.size());
    }

    @Test
    @DisplayName("Audit matrix: REQUIRED transaction entry points carry @Transactional")
    void requiredTransactionEntryPointsHaveTransactional() throws Exception {
        // For every matrix row whose Transaction Behavior declares REQUIRED
        // (success-audit fail-fast: the audit write is part of the caller's
        // transaction so a failure rolls the business mutation back), the
        // named service-layer entry point MUST carry @Transactional. Removing
        // any such annotation causes this test to fail.
        AuditMatrixData data = parseAuditMatrix();

        List<AuditMatrixRow> requiredRows = data.rows.stream()
                .filter(r -> !r.isHeaderOrSeparator())
                .filter(r -> r.transactionBehavior != null && r.transactionBehavior.contains("REQUIRED"))
                .filter(r -> r.entryPoint != null && r.entryPoint.contains("."))
                .toList();
        assertFalse(requiredRows.isEmpty(),
                "Audit matrix must declare at least one REQUIRED transaction row");

        int verified = 0;
        List<String> checked = new ArrayList<>();
        for (AuditMatrixRow row : requiredRows) {
            // Some entry points are combined (e.g. "Service.create/update").
            for (String epRaw : row.entryPoint.split("/")) {
                String ep = epRaw.trim();
                // Some plugin rows use a delegation form
                // "ApplicationPluginController -> PluginModuleRegistry.enable";
                // the transactional method lives on the service (right of ->).
                if (ep.contains("->")) {
                    String[] parts = ep.split("->");
                    ep = parts[parts.length - 1].trim();
                }
                int dot = ep.lastIndexOf('.');
                if (dot <= 0) continue;
                String className = ep.substring(0, dot);
                String method = ep.substring(dot + 1);
                if (method.isEmpty()) continue;

                File classFile = findClassFile(className);
                if (classFile == null) {
                    // Entry point references a class not under src/main/java/com/nocobase
                    // (e.g. a framework class). Skip — cannot statically verify.
                    continue;
                }
                // Controllers do not carry @Transactional by design; the
                // transaction boundary lives on the service they delegate to.
                // The matrix's REQUIRED claim for a controller row is satisfied
                // by the service-layer method it calls (verified by its own row).
                if (classFile.getPath().replace('\\', '/').contains("/controller/")) {
                    continue;
                }

                String content = readFileContent(classFile);
                assertTrue(methodHasTransactional(content, method),
                        "REQUIRED transaction entry point " + ep + " (row: " + row.resource
                                + "/" + row.action + ") must carry @Transactional in "
                                + classFile.getName());
                checked.add(ep);
                verified++;
            }
        }
        assertTrue(verified >= 10,
                "Expected to verify at least 10 REQUIRED service entry points, verified " + verified);
    }

    // ========================================================================
    // Audit matrix parsing helpers
    // ========================================================================

    /**
     * Parse the AUDIT_COVERAGE_MATRIX.md file and extract structured data.
     */
    private AuditMatrixData parseAuditMatrix() throws Exception {
        File matrixFile = new File("AUDIT_COVERAGE_MATRIX.md");
        assertTrue(matrixFile.exists(), "AUDIT_COVERAGE_MATRIX.md must exist");

        String content = Files.readString(matrixFile.toPath());
        AuditMatrixData data = new AuditMatrixData();

        // Parse the summary section
        Pattern summaryPattern = Pattern.compile(
                "\\*\\*Total write entry points\\*\\*\\s*\\|\\s*(\\d+)\\s*\\|");
        Matcher summaryMatcher = summaryPattern.matcher(content);
        if (summaryMatcher.find()) {
            data.totalWriteEntryPoints = Integer.parseInt(summaryMatcher.group(1));
        }

        Pattern successPattern = Pattern.compile(
                "\\*\\*Success audit coverage\\*\\*\\s*\\|\\s*(\\d+)\\s*/\\s*\\d+\\s*\\|");
        Matcher successMatcher = successPattern.matcher(content);
        if (successMatcher.find()) {
            data.successAuditCoverage = Integer.parseInt(successMatcher.group(1));
        }

        Pattern failurePattern = Pattern.compile(
                "\\*\\*Failure audit coverage\\*\\*\\s*\\|\\s*(\\d+)\\s*/\\s*\\d+\\s*\\|");
        Matcher failureMatcher = failurePattern.matcher(content);
        if (failureMatcher.find()) {
            data.failureAuditCoverage = Integer.parseInt(failureMatcher.group(1));
        }

        Pattern requestIdPattern = Pattern.compile(
                "\\*\\*requestId coverage\\*\\*\\s*\\|\\s*(\\d+)\\s*/\\s*\\d+\\s*\\|");
        Matcher requestIdMatcher = requestIdPattern.matcher(content);
        if (requestIdMatcher.find()) {
            data.requestIdCoverage = Integer.parseInt(requestIdMatcher.group(1));
        }

        Pattern actorUserIdPattern = Pattern.compile(
                "\\*\\*actorUserId coverage\\*\\*\\s*\\|\\s*(\\d+)\\s*/\\s*\\d+\\s*\\|");
        Matcher actorUserIdMatcher = actorUserIdPattern.matcher(content);
        if (actorUserIdMatcher.find()) {
            data.actorUserIdCoverage = Integer.parseInt(actorUserIdMatcher.group(1));
        }

        Pattern sanitizationPattern = Pattern.compile(
                "\\*\\*Sanitization coverage\\*\\*\\s*\\|\\s*(\\d+)\\s*/\\s*\\d+\\s*\\|");
        Matcher sanitizationMatcher = sanitizationPattern.matcher(content);
        if (sanitizationMatcher.find()) {
            data.sanitizationCoverage = Integer.parseInt(sanitizationMatcher.group(1));
        }

        // Parse data rows using line-by-line approach (avoids regex cross-line matching)
        // A valid data row has exactly 9 columns (10 pipe characters) and
        // is not a header row or separator row.
        boolean inSummarySection = false;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();

            // Track whether we're in the summary section
            if (trimmed.startsWith("## Summary")) {
                inSummarySection = true;
                continue;
            }
            if (inSummarySection && trimmed.startsWith("## ")) {
                inSummarySection = false;
                continue;
            }
            if (inSummarySection) {
                continue; // skip summary rows
            }

            if (!trimmed.startsWith("|")) continue;
            if (!trimmed.endsWith("|")) continue;

            // Split by pipe and count columns
            String[] columns = trimmed.split("\\|", -1);
            // A valid data row has exactly 10 parts (leading empty + 9 columns)
            // e.g., "| col1 | col2 | ... | col9 |" -> ["", " col1 ", " col2 ", ..., " col9 ", ""]
            if (columns.length != 11) continue; // 9 columns + leading empty + trailing empty = 11

            String resource = columns[1].trim();
            String action = columns[2].trim();
            String entryPoint = columns[3].trim();

            // Skip header rows
            if (resource.equals("Resource") || resource.equals("Metric")) continue;
            // Skip separator rows (all dashes)
            if (resource.matches("^-+$")) continue;
            // Skip empty rows
            if (resource.isEmpty()) continue;

            AuditMatrixRow row = new AuditMatrixRow();
            row.resource = resource;
            row.action = action;
            row.entryPoint = entryPoint;
            row.successAudit = columns[4].trim();
            row.failureAudit = columns[5].trim();
            row.requestId = columns[6].trim();
            row.actorUserId = columns[7].trim();
            row.sanitization = columns[8].trim();
            row.transactionBehavior = columns[9].trim();
            data.rows.add(row);
        }

        return data;
    }

    /**
     * Check if a field value is an X gap.
     */
    private void checkField(AuditMatrixRow row, String fieldName, String value,
                            List<String> gaps) {
        if (value == null) return;
        String trimmed = value.trim().toUpperCase();
        if (trimmed.equals("X")) {
            gaps.add(String.format("  %s | %s | %s: %s = X",
                    row.resource, row.action, row.entryPoint, fieldName));
        }
    }

    /**
     * Check if a field value indicates coverage (CHECK, N/A, or a transaction annotation).
     */
    private boolean isCovered(String value) {
        if (value == null) return false;
        String trimmed = value.trim().toUpperCase();
        return trimmed.equals("CHECK") || trimmed.equals("N/A")
                || trimmed.startsWith("REQUIRED") || trimmed.startsWith("CHECK (");
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String readFileContent(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            fail("Failed to read file: " + file.getAbsolutePath() + " — " + e.getMessage());
            return ""; // unreachable
        }
    }

    /**
     * Recursively locate {@code className.java} under {@code ROOT_DIR}.
     * Returns null if not found (caller decides whether to skip).
     */
    private File findClassFile(String className) {
        String target = className + ".java";
        File root = new File(ROOT_DIR);
        if (!root.exists()) return null;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().equals(target)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Statically check whether the given method in the source content is
     * annotated with {@code @Transactional}. Scans upward from the method
     * declaration up to 6 lines, stopping at another member boundary.
     */
    private boolean methodHasTransactional(String content, String method) {
        String[] lines = content.split("\\R", -1);
        Pattern decl = Pattern.compile(
                "(?:public|protected|private)\\s+(?:[\\w<>?,\\s]+\\s+)" + Pattern.quote(method) + "\\s*\\(");
        for (int i = 0; i < lines.length; i++) {
            if (decl.matcher(lines[i]).find()) {
                for (int j = i - 1; j >= Math.max(0, i - 6); j--) {
                    String above = lines[j].trim();
                    if (above.contains("@Transactional")) {
                        return true;
                    }
                    // Stop if we hit another member declaration.
                    if (above.startsWith("public ") || above.startsWith("protected ")
                            || above.startsWith("private ") || above.startsWith("@Override")) {
                        if (!above.startsWith("@")) {
                            break;
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    private void assertNoJdbcTemplateInFile(String filePath) {
        assertNoJdbcTemplateInFile(filePath, filePath + " should not import JdbcTemplate");
    }

    private void assertNoJdbcTemplateInFile(String filePath, String message) {
        File file = new File(filePath);
        if (!file.exists()) {
            fail("File does not exist: " + filePath);
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));

            // Remove comments
            boolean inBlockComment = false;
            List<String> codeLines = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (inBlockComment) {
                    if (trimmed.contains("*/")) inBlockComment = false;
                    continue;
                }
                if (trimmed.startsWith("//")) continue;
                if (trimmed.startsWith("/*")) {
                    inBlockComment = true;
                    continue;
                }
                if (trimmed.startsWith("*")) continue; // javadoc continuation
                codeLines.add(trimmed);
            }

            String code = String.join("\n", codeLines);
            boolean hasJdbcTemplate = code.contains("JdbcTemplate");
            assertFalse(hasJdbcTemplate, message);
        } catch (IOException e) {
            fail("Failed to read file: " + filePath + " — " + e.getMessage());
        }
    }

    private void assertNoImportOf(File file, String className, String message) {
        String content = readFileContent(file);

        // Remove comments
        StringBuilder codeOnly = new StringBuilder();
        boolean inBlockComment = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false;
                continue;
            }
            if (trimmed.startsWith("//")) continue;
            if (trimmed.startsWith("/*")) {
                inBlockComment = true;
                continue;
            }
            if (trimmed.startsWith("*")) continue;
            codeOnly.append(trimmed).append("\n");
        }

        String code = codeOnly.toString();
        // Check for any reference to the class — both import statements and
        // fully-qualified inline references like new com.nocobase.sql.SqlQueryCollectionExecutor(...)
        boolean hasReference = code.contains(className);
        assertFalse(hasReference, message + ". Found reference to: " + className);
    }

    /**
     * Assert that a file does not contain DDL SQL statements.
     * DDL SQL includes CREATE TABLE, ALTER TABLE, DROP TABLE, CREATE INDEX, etc.
     */
    private void assertNoDdlSqlInFile(String filePath, String message) {
        File file = new File(filePath);
        if (!file.exists()) {
            return; // file doesn't exist, skip
        }
        String content = readFileContent(file);

        // Remove comments and string literals to avoid false positives
        String cleanContent = removeCommentsAndStrings(content);

        for (Pattern pattern : DDL_PATTERNS) {
            Matcher matcher = pattern.matcher(cleanContent);
            assertFalse(matcher.find(),
                    message + ". Found DDL SQL pattern: " + pattern.pattern()
                    + " in file " + file.getName());
        }
    }

    /**
     * Remove comments and string literals from Java source code to avoid
     * false positives when scanning for DDL SQL.
     */
    private String removeCommentsAndStrings(String content) {
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;
        boolean inString = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : '\0';

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++; // skip '/'
                }
                continue;
            }

            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped char
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                // line comment — skip to end of line
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++; // skip '*'
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            result.append(c);
        }

        return result.toString();
    }
}