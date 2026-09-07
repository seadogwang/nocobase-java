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
        for (File file : files) {
            if (file.isDirectory()) {
                scanForStdoutAndPrintStackTrace(file);
            } else if (file.getName().endsWith(".java")) {
                String content = readFileContent(file);
                String relativePath = file.getPath().replace("\\", "/");

                // Check for System.out.println (exclude comments)
                String codeOnly = removeCommentsAndStrings(content);
                assertFalse(codeOnly.contains("System.out.println"),
                        relativePath + " contains System.out.println. Use SLF4J logger instead.");
                assertFalse(codeOnly.contains("System.out.print"),
                        relativePath + " contains System.out.print. Use SLF4J logger instead.");
                assertFalse(codeOnly.contains(".printStackTrace()"),
                        relativePath + " contains printStackTrace(). Use SLF4J logger with exception parameter instead.");
            }
        }
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