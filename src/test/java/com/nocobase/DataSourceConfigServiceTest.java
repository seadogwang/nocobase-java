package com.nocobase;

import com.nocobase.config.NocobaseDataSourceProperties;
import com.nocobase.entity.DataSourceConfigEntity;
import com.nocobase.repository.DataSourceConfigRepository;
import com.nocobase.service.AuditLogService;
import com.nocobase.service.DataSourceConfigService;
import com.nocobase.service.DataSourcePasswordEncryptor;
import com.nocobase.sql.SqlDataSourceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.SQLException;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataSourceConfigService}.
 * Covers P0-C: startup loading exceptions, datasource cipher lifecycle,
 * isTableNotFound classification, and migration count accuracy.
 */
class DataSourceConfigServiceTest {

    // ── Constants ────────────────────────────────────────────────────────

    private static final String VALID_32_BYTE_KEY =
            "dGVzdC1rZXktZm9yLUFFUy0yNTYtR0NNLW1vZGUhISE=";

    // ── Helpers ──────────────────────────────────────────────────────────

    private static MockEnvironment testEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        return env;
    }

    private static DataSourcePasswordEncryptor testEncryptor() {
        return new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, testEnv());
    }

    // ── P0-C: isTableNotFound classification ─────────────────────────────

    @Nested
    @DisplayName("P0-C: isTableNotFound exception classification")
    class IsTableNotFound {

        @Test
        @DisplayName("H2 table not found → true")
        void h2TableNotFound_returnsTrue() {
            SQLException ex = new SQLException(
                    "Table \"EXTERNAL_DATA_SOURCES\" not found; SQL statement: ... [42102-224]");
            assertTrue(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("PostgreSQL relation does not exist → true")
        void postgresqlTableNotFound_returnsTrue() {
            SQLException ex = new SQLException(
                    "ERROR: relation \"external_data_sources\" does not exist");
            assertTrue(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("Generic table not found → true")
        void genericTableNotFound_returnsTrue() {
            SQLException ex = new SQLException("Table 'external_data_sources' not found");
            assertTrue(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("Table not found through cause chain → true")
        void tableNotFoundThroughCause_returnsTrue() {
            SQLException cause = new SQLException("Table \"EXTERNAL_DATA_SOURCES\" not found");
            RuntimeException wrapper = new RuntimeException("Could not load", cause);
            assertTrue(DataSourceConfigService.isTableNotFound(wrapper));
        }

        @Test
        @DisplayName("H2 error code 42102 → true")
        void h2ErrorCode42102_returnsTrue() {
            SQLException ex = new SQLException("Error code 42102");
            assertTrue(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("Decryption failure → false (not table not found)")
        void decryptionFailure_returnsFalse() {
            RuntimeException ex = new RuntimeException("Failed to decrypt password");
            assertFalse(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("Connection refused → false")
        void connectionRefused_returnsFalse() {
            SQLException ex = new SQLException("Connection refused: connect");
            assertFalse(DataSourceConfigService.isTableNotFound(ex));
        }

        @Test
        @DisplayName("Null exception → false")
        void nullException_returnsFalse() {
            assertFalse(DataSourceConfigService.isTableNotFound(null));
        }

        @Test
        @DisplayName("Generic runtime exception → false")
        void genericRuntimeException_returnsFalse() {
            RuntimeException ex = new RuntimeException("Something went wrong");
            assertFalse(DataSourceConfigService.isTableNotFound(ex));
        }
    }

    // ── P0-C: Migration count accuracy ───────────────────────────────────

    @Test
    @DisplayName("P0-C: Plaintext migration count is accurate")
    void plaintextMigrationCountAccurate() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();

        DataSourceConfigEntity entity1 = new DataSourceConfigEntity();
        entity1.setDsKey("ds1");
        entity1.setUrl("jdbc:h2:mem:test1");
        entity1.setDriverClassName("org.h2.Driver");
        entity1.setUsername("user1");
        entity1.setPassword("plaintext-password-1"); // plaintext
        entity1.setEnabled(true);
        entity1.setReadOnly(true);

        DataSourceConfigEntity entity2 = new DataSourceConfigEntity();
        entity2.setDsKey("ds2");
        entity2.setUrl("jdbc:h2:mem:test2");
        entity2.setDriverClassName("org.h2.Driver");
        entity2.setUsername("user2");
        entity2.setPassword("plaintext-password-2"); // plaintext
        entity2.setEnabled(true);
        entity2.setReadOnly(true);

        DataSourceConfigEntity entity3 = new DataSourceConfigEntity();
        entity3.setDsKey("ds3");
        entity3.setUrl("jdbc:h2:mem:test3");
        entity3.setDriverClassName("org.h2.Driver");
        entity3.setUsername("user3");
        // Already encrypted
        entity3.setPassword(encryptor.encrypt("already-encrypted-password"));
        entity3.setEnabled(true);
        entity3.setReadOnly(true);

        List<DataSourceConfigEntity> configs = List.of(entity1, entity2, entity3);

        int migratedCount = 0;
        for (DataSourceConfigEntity entity : configs) {
            String storedPwd = entity.getPassword();
            if (storedPwd != null && !storedPwd.isEmpty()
                    && !encryptor.isEncrypted(storedPwd)) {
                entity.setPassword(encryptor.encrypt(storedPwd));
                migratedCount++;
            }
        }

        assertEquals(2, migratedCount,
                "Only 2 out of 3 entities should be counted as migrated");
        assertTrue(encryptor.isEncrypted(entity1.getPassword()),
                "Entity 1 should now be encrypted");
        assertTrue(encryptor.isEncrypted(entity2.getPassword()),
                "Entity 2 should now be encrypted");
        assertTrue(encryptor.isEncrypted(entity3.getPassword()),
                "Entity 3 was already encrypted");
    }

    // ── P0-C: Legacy plaintext migration ─────────────────────────────────

    @Test
    @DisplayName("P0-C: Legacy plaintext password is detected and migratable")
    void legacyPlaintextIsDetected() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        String plaintext = "legacy-clear-text-password";

        assertFalse(encryptor.isEncrypted(plaintext),
                "Plaintext should not be detected as encrypted");
        String encrypted = encryptor.encrypt(plaintext);
        assertTrue(encryptor.isEncrypted(encrypted),
                "Encrypted value should be detected as encrypted");
        assertEquals(plaintext, encryptor.decrypt(encrypted),
                "Decrypt should return original plaintext");
    }

    @Test
    @DisplayName("P0-C: Null password is not migrated")
    void nullPasswordNotMigrated() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        assertNull(encryptor.encrypt(null));
        assertFalse(encryptor.isEncrypted(null));
    }

    // ── P0-C: Bad ciphertext / wrong key ─────────────────────────────────

    @Test
    @DisplayName("P0-C: Bad ciphertext (not valid base64 after prefix) fails decrypt")
    void badCiphertext_failsDecrypt() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();

        String badCiphertext = "{AES-GCM}!!!not-valid-base64!!!";
        assertThrows(RuntimeException.class, () -> encryptor.decrypt(badCiphertext),
                "Decrypting bad ciphertext should fail");
    }

    @Test
    @DisplayName("P0-C: Encrypt with one key, decrypt with another key fails")
    void wrongMasterKey_failsDecrypt() {
        DataSourcePasswordEncryptor encryptor1 = testEncryptor();
        String otherKey = "YW5vdGhlci12YWxpZC1rZXktZm9yLVRFU1RJTkchISE=";
        DataSourcePasswordEncryptor encryptor2 =
                new DataSourcePasswordEncryptor(otherKey, testEnv());

        String encrypted = encryptor1.encrypt("my-password");
        assertThrows(RuntimeException.class, () -> encryptor2.decrypt(encrypted),
                "Decrypting with wrong master key should fail");
    }

    @Test
    @DisplayName("P0-C: Corrupted ciphertext (truncated) fails decrypt")
    void corruptedCiphertext_failsDecrypt() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        String encrypted = encryptor.encrypt("password");

        // Truncate the base64 data
        String truncated = encrypted.substring(0, encrypted.length() - 10);
        assertThrows(RuntimeException.class, () -> encryptor.decrypt(truncated),
                "Decrypting truncated ciphertext should fail");
    }

    // ── P0-C: Invalid config scenarios ───────────────────────────────────

    @Test
    @DisplayName("P0-C: isEncrypted returns false for empty string")
    void isEncryptedEmptyString_returnsFalse() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        assertFalse(encryptor.isEncrypted(""));
    }

    @Test
    @DisplayName("P0-C: isEncrypted returns false for string without prefix")
    void isEncryptedWithoutPrefix_returnsFalse() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        assertFalse(encryptor.isEncrypted("some-random-string"));
        assertFalse(encryptor.isEncrypted("AES-GCM-without-braces"));
    }

    @Test
    @DisplayName("P0-C: Encrypt with special characters round-trips")
    void encryptSpecialCharacters_roundTrips() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        String password = "p@ssw0rd!$%^&*()_+-=[]{}|;':\",./<>?";
        String encrypted = encryptor.encrypt(password);
        assertEquals(password, encryptor.decrypt(encrypted));
    }

    @Test
    @DisplayName("P0-C: Encrypt with unicode characters round-trips")
    void encryptUnicode_roundTrips() {
        DataSourcePasswordEncryptor encryptor = testEncryptor();
        String password = "密码passwordパスワード";
        String encrypted = encryptor.encrypt(password);
        assertEquals(password, encryptor.decrypt(encrypted));
    }
}