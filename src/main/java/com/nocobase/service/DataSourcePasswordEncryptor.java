package com.nocobase.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Encrypts and decrypts data source passwords using AES-256-GCM.
 *
 * <p>Encrypted values are stored in the database with a {@code {AES-GCM}} prefix
 * so that plaintext records (from before encryption was introduced) can be
 * detected and migrated on read.
 *
 * <p>In the test profile, a default in-memory key is used when no master key is
 * configured. In all other profiles, a missing master key causes a fail-fast
 * startup error.
 */
@Component
public class DataSourcePasswordEncryptor {

    private static final Logger log = LoggerFactory.getLogger(DataSourcePasswordEncryptor.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int AES_KEY_LENGTH = 32; // 256 bits

    /** Prefix marking an encrypted value -- everything else is treated as plaintext. */
    static final String ENCRYPTION_PREFIX = "{AES-GCM}";

    private static final String[] KNOWN_DEV_MASTER_KEYS = {
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUy0yNTYhISEhISE=", // old dev default (AES-256 with hyphen)
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhIQ==",  // old dev default (AES256, 31 bytes, invalid)
            "ZGV2LW1hc3Rlci1rZXktZm9yLUFFUzI1NiEhISEhISE="  // current dev default (AES256, 32 bytes)
    };

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public DataSourcePasswordEncryptor(
            @Value("${nocobase.data-source-encryption.master-key:#{null}}") String masterKey,
            Environment environment) {

        if (masterKey == null || masterKey.isBlank()) {
            if (isTestProfile(environment)) {
                log.warn("No master key configured -- using ephemeral test key. "
                        + "Encrypted passwords will NOT survive a restart.");
                // 32-byte hard-coded key for tests only
                byte[] testKey = "test-key-for-AES-256-GCM-mode!!".getBytes(StandardCharsets.UTF_8);
                this.secretKey = new SecretKeySpec(testKey, "AES");
                return;
            }
            throw new IllegalStateException(
                    "nocobase.data-source-encryption.master-key is required for non-test profiles. "
                    + "Set it to a base64-encoded 256-bit (32-byte) key.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(masterKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "nocobase.data-source-encryption.master-key must be a valid base64-encoded string.", e);
        }

        if (keyBytes.length != AES_KEY_LENGTH) {
            throw new IllegalStateException(
                    "Master key must be " + AES_KEY_LENGTH + " bytes (256 bits) when decoded. "
                    + "Got " + keyBytes.length + " bytes.");
        }

        // P0-A: Reject known dev default keys in non-dev/non-test profiles
        boolean isDevOrTest = isDevOrTestProfile(environment);
        if (!isDevOrTest) {
            for (String knownDefault : KNOWN_DEV_MASTER_KEYS) {
                if (knownDefault.equals(masterKey)) {
                    throw new IllegalStateException(
                            "Data source encryption master key is set to a known dev default "
                            + "in a non-dev, non-test profile. "
                            + "Set nocobase.data-source-encryption.master-key "
                            + "to a securely generated base64-encoded 256-bit key.");
                }
            }
        }

        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("DataSource password encryption initialized with AES-256-GCM");
    }

    // -- public API ------------------------------------------------------

    /**
     * Encrypt a plaintext password. Returns the input unchanged if it is null,
     * empty, or already encrypted.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            return plaintext; // already encrypted
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);

            return ENCRYPTION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    /**
     * Decrypt an encrypted password. Returns the input unchanged if it is null,
     * empty, or not encrypted (plaintext / legacy data).
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (!isEncrypted(ciphertext)) {
            return ciphertext; // plaintext / legacy -- return as-is
        }
        try {
            String stripped = ciphertext.substring(ENCRYPTION_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(stripped);

            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }

    /**
     * Returns {@code true} if the value is encrypted (starts with the encryption prefix).
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTION_PREFIX);
    }

    // -- private helpers -------------------------------------------------

    private static boolean isTestProfile(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains("test");
    }

    private static boolean isDevOrTestProfile(Environment environment) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        return profiles.contains("dev") || profiles.contains("test");
    }
}