package com.nocobase.service;

import com.nocobase.NocobaseApplication;
import com.nocobase.entity.SystemSettings;
import com.nocobase.repository.AuditLogRepository;
import com.nocobase.repository.SystemSettingsRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Integration tests verifying audit log transactional behavior.
 * <p>
 * Audit log writes use Propagation.REQUIRED, so if the audit write fails,
 * the calling business transaction MUST roll back (fail-fast).
 */
@SpringBootTest(classes = NocobaseApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogIntegrationTest {

    @Autowired
    private SystemSettingsService systemSettingsService;

    @Autowired
    private SystemSettingsRepository settingsRepository;

    @MockBean
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @Test
    @Order(1)
    @DisplayName("Audit write failure causes business transaction rollback")
    void auditWriteFailureCausesRollback() {
        // Record the current value to restore later
        String originalTitle = settingsRepository.findBySettingKey("title")
                .map(SystemSettings::getSettingValue).orElse("NocoBase Java");

        // Make audit log repository throw on save
        doThrow(new RuntimeException("Simulated audit write failure"))
                .when(auditLogRepository).save(any());

        try {
            systemSettingsService.update(Map.of("title", "Should Be Rolled Back"));
            fail("Expected exception was not thrown");
        } catch (AuditLogService.AuditLogWriteException e) {
            // Expected - audit write failed
            assertTrue(e.getMessage().contains("Audit log write failed"),
                    "Exception should indicate audit write failure");
        }

        // Verify business data was rolled back
        String currentTitle = settingsRepository.findBySettingKey("title")
                .map(SystemSettings::getSettingValue).orElse(null);
        assertEquals(originalTitle, currentTitle,
                "Business operation should be rolled back when audit write fails");
    }

    @Test
    @Order(2)
    @DisplayName("Normal audit write succeeds and business data persists")
    void normalAuditWriteSucceeds() {
        // Verify the system still works normally (mock was reset between tests)
        String testTitle = "Integration Test Title " + System.currentTimeMillis();
        systemSettingsService.update(Map.of("title", testTitle));

        String currentTitle = settingsRepository.findBySettingKey("title")
                .map(SystemSettings::getSettingValue).orElse(null);
        assertEquals(testTitle, currentTitle,
                "Business operation should persist when audit write succeeds");
    }
}