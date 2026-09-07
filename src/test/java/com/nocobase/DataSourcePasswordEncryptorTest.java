package com.nocobase;

import com.nocobase.service.DataSourcePasswordEncryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DataSourcePasswordEncryptor}.
 * Covers P0-A: master key validation, known dev key rejection,
 * encrypt/decrypt round-trip, and edge cases.
 */
class DataSourcePasswordEncryptorTest {

    // ── Constants ────────────────────────────────────────────────────────

    /** Valid 32-byte key: "test-key-for-AES-256-GCM-mode!!" */
    private static final String VALID_32_BYTE_KEY =
            "dGVzdC1rZXktZm9yLUFFUy0yNTYtR0NNLW1vZGUhISE=";

    /** Actual dev key from application-dev.yml (AES256, 32 bytes) */
    private static final String ACTUAL_DEV_KEY =
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE=";

    /** Old invalid dev key (AES256, 31 bytes) */
    private static final String OLD_INVALID_DEV_KEY =
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhIQ==";

    /** Invalid base64 key */
    private static final String INVALID_BASE64_KEY = "this-is-not-valid-base64!!!";

    /** 16-byte key (too short): "1234567890123456" */
    private static final String SHORT_KEY_16_BYTES = "MTIzNDU2Nzg5MDEyMzQ1Ng==";

    // ── Helpers ──────────────────────────────────────────────────────────

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        if (profiles.length > 0) {
            e.setActiveProfiles(profiles);
        }
        return e;
    }

    // ── P0-A: Master key validation ──────────────────────────────────────

    @Test
    @DisplayName("P0-A: Missing master key in non-test profile → fail-fast")
    void missingKeyInNonTestProfile_fails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor(null, env()));
        assertTrue(ex.getMessage().contains("master-key"),
                "Error should mention master key");
        assertTrue(ex.getMessage().contains("required"),
                "Error should indicate key is required");
    }

    @Test
    @DisplayName("P0-A: Missing master key in test profile → uses ephemeral key")
    void missingKeyInTestProfile_passes() {
        assertDoesNotThrow(() -> new DataSourcePasswordEncryptor(null, env("test")));
    }

    @Test
    @DisplayName("P0-A: Invalid base64 master key → fail-fast")
    void invalidBase64_fails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor(INVALID_BASE64_KEY, env()));
        assertTrue(ex.getMessage().contains("base64"),
                "Error should mention base64 encoding");
    }

    @Test
    @DisplayName("P0-A: Master key too short (16 bytes) → fail-fast")
    void keyTooShort_fails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor(SHORT_KEY_16_BYTES, env()));
        assertTrue(ex.getMessage().contains("32") || ex.getMessage().contains("256"),
                "Error should mention expected key length");
    }

    @Test
    @DisplayName("P0-A: Known dev default key in non-dev/non-test profile → fail-fast")
    void devKeyInNonDevProfile_fails() {
        // No active profiles → non-dev, non-test
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor(ACTUAL_DEV_KEY, env()));
        assertTrue(ex.getMessage().contains("known dev default"),
                "Error should mention known dev default");
    }

    @Test
    @DisplayName("P0-A: Known dev default key in dev profile → passes")
    void devKeyInDevProfile_passes() {
        assertDoesNotThrow(() ->
                new DataSourcePasswordEncryptor(ACTUAL_DEV_KEY, env("dev")));
    }

    @Test
    @DisplayName("P0-A: Known dev default key in test profile → passes")
    void devKeyInTestProfile_passes() {
        // Test profile skips all checks, but the key must still be valid
        // The old 31-byte key would fail the length check, so we use the 32-byte one
        assertDoesNotThrow(() ->
                new DataSourcePasswordEncryptor(ACTUAL_DEV_KEY, env("test")));
    }

    @Test
    @DisplayName("P0-A: Old invalid dev key (31 bytes) → fail-fast with length error")
    void oldInvalidDevKey_fails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor(OLD_INVALID_DEV_KEY, env()));
        assertTrue(ex.getMessage().contains("31") || ex.getMessage().contains("256"),
                "Error should mention byte count");
    }

    @Test
    @DisplayName("P0-A: Valid 32-byte key constructs successfully")
    void validKey_constructs() {
        assertDoesNotThrow(() ->
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env()));
    }

    @Test
    @DisplayName("P0-A: Blank/empty master key in non-test → fail-fast")
    void blankKey_fails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DataSourcePasswordEncryptor("   ", env()));
        assertTrue(ex.getMessage().contains("required"),
                "Error should indicate key is required");
    }

    // ── P0-A: Encrypt/decrypt round-trip ─────────────────────────────────

    @Test
    @DisplayName("P0-A: Encrypt and decrypt round-trip")
    void encryptDecryptRoundTrip() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        String plaintext = "my-secret-password-123";
        String encrypted = encryptor.encrypt(plaintext);

        assertTrue(encryptor.isEncrypted(encrypted),
                "Encrypted value should have {AES-GCM} prefix");
        assertNotEquals(plaintext, encrypted,
                "Encrypted value should differ from plaintext");

        String decrypted = encryptor.decrypt(encrypted);
        assertEquals(plaintext, decrypted,
                "Decrypted value should match original plaintext");
    }

    @Test
    @DisplayName("P0-A: Encrypt already-encrypted value is idempotent")
    void encryptAlreadyEncrypted_isIdempotent() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        String encrypted = encryptor.encrypt("password1");
        String encryptedAgain = encryptor.encrypt(encrypted);
        assertEquals(encrypted, encryptedAgain,
                "Encrypting already-encrypted value should return it unchanged");
    }

    @Test
    @DisplayName("P0-A: Decrypt null or empty returns as-is")
    void decryptNullOrEmpty_returnsAsIs() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        assertNull(encryptor.decrypt(null));
        assertEquals("", encryptor.decrypt(""));
    }

    @Test
    @DisplayName("P0-A: Decrypt non-encrypted value returns as-is")
    void decryptPlaintext_returnsAsIs() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        String plaintext = "some-plaintext-password";
        assertEquals(plaintext, encryptor.decrypt(plaintext),
                "Decrypting plaintext should return it unchanged");
    }

    @Test
    @DisplayName("P0-A: isEncrypted detects prefix correctly")
    void isEncrypted_detectsPrefix() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        assertTrue(encryptor.isEncrypted("{AES-GCM}somebase64data"));
        assertFalse(encryptor.isEncrypted("plaintext"));
        assertTrue(encryptor.isEncrypted("{AES-GCM}")); // prefix alone is detected as encrypted
        assertFalse(encryptor.isEncrypted(null));
        assertFalse(encryptor.isEncrypted(""));
    }

    @Test
    @DisplayName("P0-A: Encrypt null or empty returns as-is")
    void encryptNullOrEmpty_returnsAsIs() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        assertNull(encryptor.encrypt(null));
        assertEquals("", encryptor.encrypt(""));
    }

    // ── P0-A: Bad ciphertext / wrong key ─────────────────────────────────

    @Test
    @DisplayName("P0-A: Decrypt with wrong key fails")
    void decryptWithWrongKey_fails() {
        DataSourcePasswordEncryptor encryptor1 =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());
        // Use a different valid 32-byte key
        String otherKey = "YW5vdGhlci12YWxpZC1rZXktZm9yLVRFU1RJTkchISE=";
        DataSourcePasswordEncryptor encryptor2 =
                new DataSourcePasswordEncryptor(otherKey, env());

        String encrypted = encryptor1.encrypt("password");
        assertThrows(RuntimeException.class, () -> encryptor2.decrypt(encrypted),
                "Decrypting with wrong key should fail");
    }

    @Test
    @DisplayName("P0-A: Decrypt tampered ciphertext fails")
    void decryptTamperedCiphertext_fails() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        String encrypted = encryptor.encrypt("password");
        // Tamper with the ciphertext (change a character in the base64)
        String tampered = encrypted.substring(0, encrypted.length() - 3) + "XXX";
        assertThrows(RuntimeException.class, () -> encryptor.decrypt(tampered),
                "Decrypting tampered ciphertext should fail");
    }

    @Test
    @DisplayName("P0-A: Dev key produces correct encrypt/decrypt in dev profile")
    void devKeyEncryptDecrypt_works() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(ACTUAL_DEV_KEY, env("dev"));

        String plaintext = "dev-password";
        String encrypted = encryptor.encrypt(plaintext);
        assertTrue(encryptor.isEncrypted(encrypted));
        assertEquals(plaintext, encryptor.decrypt(encrypted));
    }

    @Test
    @DisplayName("P0-A: Multiple encrypt calls produce different ciphertexts (different IV)")
    void multipleEncrypts_produceDifferentCiphertexts() {
        DataSourcePasswordEncryptor encryptor =
                new DataSourcePasswordEncryptor(VALID_32_BYTE_KEY, env());

        String encrypted1 = encryptor.encrypt("password");
        String encrypted2 = encryptor.encrypt("password");
        assertNotEquals(encrypted1, encrypted2,
                "Same plaintext should produce different ciphertexts due to random IV");
        assertEquals("password", encryptor.decrypt(encrypted1));
        assertEquals("password", encryptor.decrypt(encrypted2));
    }
}