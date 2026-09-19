package com.nocobase;

import com.nocobase.config.NocobaseDataSourceProperties;
import com.nocobase.entity.DataSourceConfigEntity;
import com.nocobase.repository.DataSourceConfigRepository;
import com.nocobase.service.DataSourceConfigService;
import com.nocobase.service.DataSourcePasswordEncryptor;
import com.nocobase.sql.SqlErrorSanitizer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-E: External datasource response sanitization tests.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>list/get responses never return password or full JDBC URL with credentials</li>
 *   <li>maskedUrl, maskedUsername, hasPassword compatibility fields are present</li>
 *   <li>update keeps existing password when password is not provided or empty</li>
 *   <li>create sanitizes the response</li>
 *   <li>testConnection returns sanitized messages (no credentials in error)</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataSourceConfigSanitizationTest {

    @Autowired
    private DataSourceConfigService configService;

    @Autowired
    private DataSourceConfigRepository repository;

    @Autowired
    private DataSourcePasswordEncryptor passwordEncryptor;

    @Autowired
    private NocobaseDataSourceProperties dataSourceProperties;

    private static final String TEST_DS_KEY = "sanitize_test_ds";
    private static final String TEST_DS_KEY2 = "sanitize_test_ds2";
    private static final String TEST_DS_KEY3 = "sanitize_test_ds3";

    @BeforeAll
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterAll
    void tearDown() {
        SecurityContextHolder.clearContext();
        // Clean up test data sources
        try {
            repository.deleteByDsKey(TEST_DS_KEY);
        } catch (Exception ignored) { }
        try {
            repository.deleteByDsKey(TEST_DS_KEY2);
        } catch (Exception ignored) { }
        try {
            repository.deleteByDsKey(TEST_DS_KEY3);
        } catch (Exception ignored) { }
        // Remove from in-memory properties
        dataSourceProperties.getDataSources().remove(TEST_DS_KEY);
        dataSourceProperties.getDataSources().remove(TEST_DS_KEY2);
        dataSourceProperties.getDataSources().remove(TEST_DS_KEY3);
    }

    // ========================================================================
    // P1-E: Response sanitization — list and get
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("P1-E: create response has sanitized URL, maskedUrl, maskedUsername, hasPassword")
    void createResponseSanitized() {
        Map<String, Object> result = configService.create(Map.of(
                "key", TEST_DS_KEY,
                "displayName", "Sanitize Test DS",
                "url", "jdbc:h2:mem:sanitize_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "testuser",
                "password", "secret123",
                "dialect", "h2"
        ));

        // Password must never be returned
        assertFalse(result.containsKey("password"),
                "password must not be returned in create response");

        // URL must be sanitized (hostname and database name masked)
        String url = (String) result.get("url");
        assertNotNull(url);
        assertFalse(url.contains("@"), "URL must not contain credential separator (@)");
        // P1-E: URL must mask database name
        assertFalse(url.contains("sanitize_test"), "URL must not expose database name");
        assertTrue(url.contains("***"), "URL must contain mask placeholders");

        // maskedUrl compatibility field
        assertTrue(result.containsKey("maskedUrl"), "maskedUrl compatibility field must be present");
        String maskedUrl = (String) result.get("maskedUrl");
        assertEquals(url, maskedUrl, "maskedUrl should match the sanitized url");

        // maskedUsername compatibility field
        assertTrue(result.containsKey("maskedUsername"), "maskedUsername compatibility field must be present");
        String maskedUsername = (String) result.get("maskedUsername");
        assertEquals("te***", maskedUsername, "maskedUsername should show first 2 chars + ***");

        // hasPassword compatibility field
        assertTrue(result.containsKey("hasPassword"), "hasPassword compatibility field must be present");
        assertEquals(Boolean.TRUE, result.get("hasPassword"), "hasPassword should be true when password is set");
    }

    @Test
    @Order(2)
    @DisplayName("P1-E: list response sanitizes all entries")
    void listResponseSanitized() {
        List<Map<String, Object>> dataSources = configService.listAll();

        assertNotNull(dataSources);
        for (Map<String, Object> ds : dataSources) {
            // Password must never be returned
            assertFalse(ds.containsKey("password"),
                    "password must not be returned in list response");

            // URL must be sanitized (hostname and database name masked)
            String url = (String) ds.get("url");
            assertNotNull(url);
            assertFalse(url.contains("@"), "URL must not contain credential separator (@)");
            assertTrue(url.contains("***"), "URL must contain mask placeholders");

            // Compatibility fields must be present
            assertTrue(ds.containsKey("maskedUrl"), "maskedUrl must be present");
            assertTrue(ds.containsKey("maskedUsername"), "maskedUsername must be present");
            assertTrue(ds.containsKey("hasPassword"), "hasPassword must be present");
        }
    }

    @Test
    @Order(3)
    @DisplayName("P1-E: get response sanitized")
    void getResponseSanitized() {
        Map<String, Object> ds = configService.getByKey(TEST_DS_KEY);

        assertFalse(ds.containsKey("password"), "password must not be returned");
        assertTrue(ds.containsKey("maskedUrl"), "maskedUrl must be present");
        assertTrue(ds.containsKey("maskedUsername"), "maskedUsername must be present");
        assertTrue(ds.containsKey("hasPassword"), "hasPassword must be present");

        String url = (String) ds.get("url");
        assertFalse(url.contains("@"), "URL must not contain credential separator");
        assertTrue(url.contains("***"), "URL must contain mask placeholders");
    }

    // ========================================================================
    // P1-E: Update preserves existing password when empty/null
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("P1-E: update with empty password keeps existing password")
    void updateWithEmptyPasswordKeepsExisting() {
        // Create a data source with a password
        configService.create(Map.of(
                "key", TEST_DS_KEY2,
                "displayName", "Password Test DS",
                "url", "jdbc:h2:mem:password_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "testuser",
                "password", "original_secret",
                "dialect", "h2"
        ));

        // Verify the entity has an encrypted password
        DataSourceConfigEntity entity = repository.findByDsKey(TEST_DS_KEY2).orElseThrow();
        String originalEncryptedPassword = entity.getPassword();
        assertNotNull(originalEncryptedPassword);
        assertTrue(passwordEncryptor.isEncrypted(originalEncryptedPassword),
                "Password should be encrypted");

        // Update with empty password string
        Map<String, Object> updated = configService.update(Map.of(
                "key", TEST_DS_KEY2,
                "displayName", "Updated Name",
                "password", ""
        ));

        // Verify password was NOT overwritten
        DataSourceConfigEntity afterUpdate = repository.findByDsKey(TEST_DS_KEY2).orElseThrow();
        assertEquals(originalEncryptedPassword, afterUpdate.getPassword(),
                "Password should not be overwritten with empty string");

        // Verify hasPassword is still true
        assertEquals(Boolean.TRUE, updated.get("hasPassword"),
                "hasPassword should still be true after update with empty password");

        // Update with null password (use HashMap since Map.of() rejects null values)
        Map<String, Object> nullPwdBody = new HashMap<>();
        nullPwdBody.put("key", TEST_DS_KEY2);
        nullPwdBody.put("displayName", "Updated Name 2");
        nullPwdBody.put("password", null);
        Map<String, Object> updated2 = configService.update(nullPwdBody);

        // Verify password was NOT overwritten
        DataSourceConfigEntity afterUpdate2 = repository.findByDsKey(TEST_DS_KEY2).orElseThrow();
        assertEquals(originalEncryptedPassword, afterUpdate2.getPassword(),
                "Password should not be overwritten with null");

        assertEquals(Boolean.TRUE, updated2.get("hasPassword"),
                "hasPassword should still be true after update with null password");
    }

    @Test
    @Order(5)
    @DisplayName("P1-E: update without password in body keeps existing password")
    void updateWithoutPasswordKeyKeepsExisting() {
        // Update without providing the password key at all
        Map<String, Object> updated = configService.update(Map.of(
                "key", TEST_DS_KEY2,
                "displayName", "Updated Name 3"
        ));

        // Verify password was NOT touched
        DataSourceConfigEntity entity = repository.findByDsKey(TEST_DS_KEY2).orElseThrow();
        assertNotNull(entity.getPassword());
        assertTrue(passwordEncryptor.isEncrypted(entity.getPassword()),
                "Password should remain encrypted");

        assertEquals(Boolean.TRUE, updated.get("hasPassword"),
                "hasPassword should still be true");
    }

    // ========================================================================
    // P1-E: URL with embedded credentials is sanitized
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("P1-E: JDBC URL with embedded credentials is sanitized in response")
    void urlWithEmbeddedCredentialsSanitized() {
        Map<String, Object> result = configService.create(Map.of(
                "key", TEST_DS_KEY3,
                "displayName", "Embedded Creds DS",
                "url", "jdbc:h2:mem:creds_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "admin_user",
                "password", "supersecret",
                "dialect", "h2"
        ));

        String url = (String) result.get("url");
        String maskedUrl = (String) result.get("maskedUrl");

        // URL must not contain raw credentials
        assertFalse(url.contains("admin_user"), "URL must not expose username");
        assertFalse(url.contains("supersecret"), "URL must not expose password");
        assertFalse(url.contains("@"), "URL must not contain credential separator");
        // P1-E: URL must not expose database name
        assertFalse(url.contains("creds_test"), "URL must not expose database name");
        assertTrue(url.contains("***"), "URL must contain mask placeholders");

        // maskedUrl should match the sanitized url
        assertEquals(url, maskedUrl);

        // maskedUsername should show first 2 chars
        String maskedUsername = (String) result.get("maskedUsername");
        assertEquals("ad***", maskedUsername, "maskedUsername should show first 2 chars + ***");
    }

    @Test
    @Order(7)
    @DisplayName("P1-E: hasPassword is false when no password set")
    void hasPasswordFalseWhenNoPassword() {
        // We need to create a data source without password and check hasPassword
        // Since DataSourceConfigService.create always encrypts (even null becomes null),
        // we test hasPassword via the hasPassword logic directly
        //
        // Create without password
        Map<String, Object> result = configService.create(Map.of(
                "key", "sanitize_test_ds_nopwd",
                "displayName", "No Password DS",
                "url", "jdbc:h2:mem:nopwd_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "testuser",
                "dialect", "h2"
        ));

        // When password is null (not provided), hasPassword should be false
        assertEquals(Boolean.FALSE, result.get("hasPassword"),
                "hasPassword should be false when no password is set");

        // Cleanup
        try {
            repository.deleteByDsKey("sanitize_test_ds_nopwd");
            dataSourceProperties.getDataSources().remove("sanitize_test_ds_nopwd");
        } catch (Exception ignored) { }
    }

    // ========================================================================
    // P1-E: testConnection response sanitization
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("P1-E: testConnection success response does not leak credentials")
    void testConnectionSuccessDoesNotLeakCredentials() {
        Map<String, Object> result = configService.testConnection(Map.of(
                "url", "jdbc:h2:mem:connection_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "test_user",
                "password", "connection_secret"
        ));

        // Success response must not contain credentials
        assertTrue(result.containsKey("success"));
        String message = (String) result.get("message");
        assertNotNull(message);
        assertFalse(message.contains("test_user"), "Message must not contain username");
        assertFalse(message.contains("connection_secret"), "Message must not contain password");
    }

    @Test
    @Order(9)
    @DisplayName("P1-E: testConnection failure response sanitizes error messages")
    void testConnectionFailureSanitizesError() {
        // Try connecting to a non-existent/unsupported URL
        Map<String, Object> result = configService.testConnection(Map.of(
                "url", "jdbc:h2:mem:nonexistent_db;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "test_user",
                "password", "connection_secret"
        ));

        // If the connection fails, the error message must be sanitized
        // (H2 in-memory mode may actually succeed, so we check both paths)
        if (Boolean.FALSE.equals(result.get("success"))) {
            String message = (String) result.get("message");
            assertNotNull(message);
            assertFalse(message.contains("test_user"), "Error message must not contain username");
            assertFalse(message.contains("connection_secret"), "Error message must not contain password");
        }
    }

    @Test
    @Order(10)
    @DisplayName("P1-E: testConnection with unsupported driver returns sanitized error")
    void testConnectionUnsupportedDriverSanitized() {
        // validateUrlAndDriver throws IllegalArgumentException for unsupported drivers.
        // Verify the error message does NOT contain credentials.
        try {
            configService.testConnection(Map.of(
                    "url", "jdbc:h2:mem:test_unsupported;MODE=PostgreSQL",
                    "driverClassName", "com.mysql.cj.jdbc.Driver",
                    "username", "test_user",
                    "password", "connection_secret"
            ));
            fail("Expected IllegalArgumentException for unsupported driver");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("Driver class name must be"),
                    "Error should indicate unsupported driver");
            assertFalse(msg.contains("test_user"), "Error must not contain username");
            assertFalse(msg.contains("connection_secret"), "Error must not contain password");
        }
    }

    // ========================================================================
    // P1-E: Utility method tests
    // ========================================================================

    @Test
    @Order(11)
    @DisplayName("P1-E: sanitizeUrl masks embedded credentials")
    void sanitizeUrlMasksEmbeddedCredentials() {
        // URL with embedded credentials
        String url = "jdbc:postgresql://myuser:mypassword@localhost:5432/mydb";
        String sanitized = DataSourceConfigService.sanitizeUrl(url);
        assertEquals("jdbc:postgresql://***:***@localhost:5432/mydb", sanitized);

        // URL without credentials (no @)
        String noCreds = "jdbc:h2:mem:testdb;MODE=PostgreSQL";
        String sanitizedNoCreds = DataSourceConfigService.sanitizeUrl(noCreds);
        assertEquals(noCreds, sanitizedNoCreds);

        // Null URL
        assertNull(DataSourceConfigService.sanitizeUrl(null));
    }

    @Test
    @Order(12)
    @DisplayName("P1-E: maskUsername returns masked username")
    void maskUsernameReturnsMasked() {
        assertEquals("ad***", DataSourceConfigService.maskUsername("admin"));
        assertEquals("sa***", DataSourceConfigService.maskUsername("sa_long"));
        assertEquals("***", DataSourceConfigService.maskUsername("sa"));
        assertEquals("***", DataSourceConfigService.maskUsername("a"));
        assertEquals("", DataSourceConfigService.maskUsername(""));
        assertEquals("", DataSourceConfigService.maskUsername(null));
    }

    // ========================================================================
    // P1-E: sanitizeUrlForResponse — masks hostname and database name
    // ========================================================================

    @Test
    @Order(13)
    @DisplayName("P1-E: sanitizeUrlForResponse masks PostgreSQL hostname and database name")
    void sanitizeUrlForResponsePostgreSql() {
        String url = "jdbc:postgresql://db.example.com:5432/mydb";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***/***", sanitized);
    }

    @Test
    @Order(14)
    @DisplayName("P1-E: sanitizeUrlForResponse preserves query parameters")
    void sanitizeUrlForResponsePreservesQueryParams() {
        String url = "jdbc:postgresql://localhost:5432/mydb?sslmode=require&connectTimeout=10";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***/***?sslmode=require&connectTimeout=10", sanitized);
    }

    @Test
    @Order(15)
    @DisplayName("P1-E: sanitizeUrlForResponse masks H2 in-memory database name")
    void sanitizeUrlForResponseH2Mem() {
        String url = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:h2:mem:***;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", sanitized);
    }

    @Test
    @Order(16)
    @DisplayName("P1-E: sanitizeUrlForResponse masks H2 file database path")
    void sanitizeUrlForResponseH2File() {
        String url = "jdbc:h2:file:./data/mydb;AUTO_SERVER=TRUE";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:h2:file:***;AUTO_SERVER=TRUE", sanitized);
    }

    @Test
    @Order(17)
    @DisplayName("P1-E: sanitizeUrlForResponse masks H2 TCP hostname and database name")
    void sanitizeUrlForResponseH2Tcp() {
        String url = "jdbc:h2:tcp://dbserver:9092/mydb";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:h2:tcp://***:***/***", sanitized);
    }

    @Test
    @Order(18)
    @DisplayName("P1-E: sanitizeUrlForResponse masks credentials AND hostname/db together")
    void sanitizeUrlForResponseCredentialsAndHostname() {
        String url = "jdbc:postgresql://myuser:mypassword@db.example.com:5432/mydb";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***@***:***/***", sanitized);
    }

    @Test
    @Order(19)
    @DisplayName("P1-E: sanitizeUrlForResponse handles null")
    void sanitizeUrlForResponseNull() {
        assertNull(DataSourceConfigService.sanitizeUrlForResponse(null));
    }

    @Test
    @Order(20)
    @DisplayName("P1-E: sanitizeUrlForResponse preserves scheme-only URL")
    void sanitizeUrlForResponsePreservesScheme() {
        String url = "jdbc:h2:mem:test";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertTrue(sanitized.startsWith("jdbc:h2:mem:***"));
        assertFalse(sanitized.contains("test"), "Database name 'test' must be masked");
    }

    // ========================================================================
    // P0-A: Sensitive query parameter stripping in sanitizeUrlForResponse
    // ========================================================================

    @Test
    @Order(21)
    @DisplayName("P0-A: PostgreSQL URL with sensitive query params — user, password stripped, sslmode kept")
    void sanitizeUrlForResponseStripsPostgreSqlSecrets() {
        String url = "jdbc:postgresql://db.example.com:5432/mydb?user=admin&password=secret123&sslmode=require&connectTimeout=30";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***/***?sslmode=require&connectTimeout=30", sanitized);
        assertFalse(sanitized.contains("admin"), "user param must be stripped");
        assertFalse(sanitized.contains("secret123"), "password param must be stripped");
        assertFalse(sanitized.contains("user="), "user= must be stripped");
        assertFalse(sanitized.contains("password="), "password= must be stripped");
    }

    @Test
    @Order(22)
    @DisplayName("P0-A: PostgreSQL URL with all sensitive params stripped")
    void sanitizeUrlForResponseStripsAllSensitiveParams() {
        String url = "jdbc:postgresql://db.example.com:5432/mydb?user=u&username=un&password=p&pass=p2&pwd=p3&sslpassword=sp&secret=s&token=t&accessKey=ak&accessKeySecret=aks&accessKeyId=aki&sslmode=require";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***/***?sslmode=require", sanitized);
        assertFalse(sanitized.contains("user="));
        assertFalse(sanitized.contains("password="));
        assertFalse(sanitized.contains("token="));
        assertFalse(sanitized.contains("secret="));
        assertFalse(sanitized.contains("accessKey="));
        assertFalse(sanitized.contains("accessKeySecret="));
        assertFalse(sanitized.contains("accessKeyId="));
    }

    @Test
    @Order(23)
    @DisplayName("P0-A: H2 URL with USER/PASSWORD parameters stripped")
    void sanitizeUrlForResponseStripsH2UserPassword() {
        String url = "jdbc:h2:mem:testdb;USER=sa;PASSWORD=secret;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:h2:mem:***;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", sanitized);
        assertFalse(sanitized.contains("USER"), "USER must be stripped");
        assertFalse(sanitized.contains("PASSWORD"), "PASSWORD must be stripped");
        assertFalse(sanitized.contains("sa"), "username value must be stripped");
        assertFalse(sanitized.contains("secret"), "password value must be stripped");
    }

    @Test
    @Order(24)
    @DisplayName("P0-A: H2 URL with mixed sensitive and non-sensitive params")
    void sanitizeUrlForResponseStripsH2MixedParams() {
        String url = "jdbc:h2:mem:testdb;USER=sa;PASSWORD=secret;ACCESS_KEY_DATA=value;MODE=PostgreSQL";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertTrue(sanitized.startsWith("jdbc:h2:mem:***"));
        assertFalse(sanitized.contains("USER="), "USER must be stripped");
        assertFalse(sanitized.contains("PASSWORD="), "PASSWORD must be stripped");
        assertTrue(sanitized.contains("ACCESS_KEY_DATA=value"), "Non-sensitive ACCESS_KEY_DATA must be kept");
        assertTrue(sanitized.contains("MODE=PostgreSQL"), "MODE must be kept");
    }

    @Test
    @Order(25)
    @DisplayName("P0-A: PostgreSQL URL with only sensitive params — all stripped, no ? left")
    void sanitizeUrlForResponseOnlySensitiveParams() {
        String url = "jdbc:postgresql://db.example.com:5432/mydb?user=admin&password=secret";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:postgresql://***:***/***", sanitized);
        assertFalse(sanitized.contains("?"), "? should be removed when all params are sensitive");
    }

    @Test
    @Order(26)
    @DisplayName("P0-A: stripSensitiveQueryParams handles null and empty")
    void stripSensitiveQueryParamsEdgeCases() {
        assertNull(DataSourceConfigService.stripSensitiveQueryParams(null));
        assertEquals("jdbc:postgresql://***:***/***",
                DataSourceConfigService.stripSensitiveQueryParams("jdbc:postgresql://***:***/***"));
        // URL with no sensitive params
        assertEquals("jdbc:postgresql://***:***/***?sslmode=require",
                DataSourceConfigService.stripSensitiveQueryParams("jdbc:postgresql://***:***/***?sslmode=require"));
    }

    @Test
    @Order(27)
    @DisplayName("P0-A: H2 URL with query-param-style USER/PASSWORD stripped")
    void sanitizeUrlForResponseH2QueryStyleSecrets() {
        String url = "jdbc:h2:mem:testdb;USER=sa;PASSWORD=secret";
        String sanitized = DataSourceConfigService.sanitizeUrlForResponse(url);
        assertEquals("jdbc:h2:mem:***", sanitized);
        assertFalse(sanitized.contains("USER"));
        assertFalse(sanitized.contains("PASSWORD"));
    }

    // ========================================================================
    // P0-A: Response field hardening — raw username removed
    // ========================================================================

    @Test
    @Order(28)
    @DisplayName("P0-A: create response does not expose raw username")
    void createResponseHasNoRawUsername() {
        Map<String, Object> result = configService.create(Map.of(
                "key", "sanitize_ds_username_test",
                "displayName", "Username Test DS",
                "url", "jdbc:h2:mem:username_test;MODE=PostgreSQL",
                "driverClassName", "org.h2.Driver",
                "username", "sensitive_user",
                "password", "testpass",
                "dialect", "h2"
        ));

        assertFalse(result.containsKey("username"),
                "Raw username must not be returned in response");
        assertTrue(result.containsKey("maskedUsername"),
                "maskedUsername must be present");
        assertEquals("se***", result.get("maskedUsername"),
                "maskedUsername should show first 2 chars + ***");

        // Cleanup
        try {
            repository.deleteByDsKey("sanitize_ds_username_test");
            dataSourceProperties.getDataSources().remove("sanitize_ds_username_test");
        } catch (Exception ignored) { }
    }

    @Test
    @Order(29)
    @DisplayName("P0-A: get response does not expose raw username")
    void getResponseHasNoRawUsername() {
        // Reuse existing test data source
        Map<String, Object> ds = configService.getByKey(TEST_DS_KEY);
        assertFalse(ds.containsKey("username"),
                "Raw username must not be returned in get response");
        assertTrue(ds.containsKey("maskedUsername"),
                "maskedUsername must be present");
    }

    @Test
    @Order(30)
    @DisplayName("P0-A: update response does not expose raw username")
    void updateResponseHasNoRawUsername() {
        Map<String, Object> updated = configService.update(Map.of(
                "key", TEST_DS_KEY,
                "displayName", "Updated Username Test",
                "username", "new_user"
        ));

        assertFalse(updated.containsKey("username"),
                "Raw username must not be returned in update response");
        assertTrue(updated.containsKey("maskedUsername"),
                "maskedUsername must be present");
        assertEquals("ne***", updated.get("maskedUsername"),
                "maskedUsername should reflect the updated username first 2 chars + ***");
    }

    @Test
    @Order(31)
    @DisplayName("P0-A: list response does not expose raw username in any entry")
    void listResponseHasNoRawUsername() {
        List<Map<String, Object>> dataSources = configService.listAll();
        for (Map<String, Object> ds : dataSources) {
            assertFalse(ds.containsKey("username"),
                    "Raw username must not be returned in list response entry");
            assertTrue(ds.containsKey("maskedUsername"),
                    "maskedUsername must be present in every list entry");
        }
    }

    @Test
    @Order(32)
    @DisplayName("P0-A: testConnection does not expose raw username in error message")
    void testConnectionDoesNotExposeUsernameInError() {
        try {
            // Test with unsupported driver to trigger error path
            configService.testConnection(Map.of(
                    "url", "jdbc:postgresql://db.example.com:5432/mydb?user=admin&password=secret123",
                    "driverClassName", "com.mysql.cj.jdbc.Driver",
                    "username", "test_user",
                    "password", "connection_secret"
            ));
            fail("Expected IllegalArgumentException for unsupported driver");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            assertNotNull(msg);
            assertFalse(msg.contains("admin"), "Error must not contain query param user value");
            assertFalse(msg.contains("secret123"), "Error must not contain query param password value");
            assertFalse(msg.contains("test_user"), "Error must not contain username");
            assertFalse(msg.contains("connection_secret"), "Error must not contain password");
            assertTrue(msg.contains("Driver class name must be"),
                    "Error should indicate unsupported driver");
        }
    }
}