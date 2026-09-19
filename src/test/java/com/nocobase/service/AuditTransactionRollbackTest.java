package com.nocobase.service;

import com.nocobase.acl.CurrentUserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Fail-fast / rollback-contract tests for {@link AuditLogService}.
 *
 * <p>The Phase 19/20 audit task (Agent G) requires that when a success-audit
 * write fails, the calling business transaction rolls back. This is the
 * contract established by:
 * <ul>
 *   <li>{@code auditSuccess} running with {@code @Transactional(REQUIRED)}
 *       — the audit write joins the caller's transaction;</li>
 *   <li>{@code auditSuccess} throwing {@link AuditLogService.AuditLogWriteException}
 *       when the repository save fails — propagating the failure to the caller;</li>
 *   <li>the calling mutation services ({@code DynamicRepository.create},
 *       {@code PluginModuleRegistry.enable}, {@code DataSourceConfigService.create},
 *       …) being themselves {@code @Transactional(REQUIRED)} — verified by
 *       {@link com.nocobase.ArchitectureBoundaryTest#requiredTransactionEntryPointsHaveTransactional()}
 *       — so the propagated exception rolls the business mutation back.</li>
 * </ul>
 *
 * <p>This test class pins the first two properties deterministically (no
 * Spring context, no Docker). The third is pinned by the architecture test.
 * Together they prove the rollback contract for dynamic CRUD, plugin
 * mutation, and data-source mutation paths.
 */
class AuditTransactionRollbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditLogService serviceWithFailingRepo() {
        com.nocobase.repository.AuditLogRepository repo =
                mock(com.nocobase.repository.AuditLogRepository.class);
        // The repository save fails — simulating a DB outage during the audit write.
        when(repo.save(any())).thenThrow(new RuntimeException("audit DB unavailable"));

        CurrentUserContext userContext = mock(CurrentUserContext.class);
        when(userContext.getCurrentUserId()).thenReturn(Optional.of(1L));

        return new AuditLogService(repo, userContext, objectMapper);
    }

    @Test
    @DisplayName("auditSuccess is @Transactional(REQUIRED) so it joins the caller's transaction")
    void auditSuccessIsTransactionalRequired() throws NoSuchMethodException {
        Method m = AuditLogService.class.getDeclaredMethod(
                "auditSuccess", String.class, String.class, String.class, Map.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "auditSuccess must carry @Transactional");
        assertEquals(Propagation.REQUIRED, tx.propagation(),
                "auditSuccess must use REQUIRED propagation so the audit write is part of the caller's tx");
    }

    @Test
    @DisplayName("auditFailure is @Transactional(REQUIRES_NEW) so it cannot compound the original error")
    void auditFailureIsTransactionalRequiresNew() throws NoSuchMethodException {
        Method m = AuditLogService.class.getDeclaredMethod(
                "auditFailure", String.class, String.class, String.class, Map.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "auditFailure must carry @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "auditFailure must use REQUIRES_NEW propagation");
    }

    @Test
    @DisplayName("auditSuccess throws AuditLogWriteException when the audit save fails (fail-fast)")
    void auditSuccessThrowsOnRepositoryFailure() {
        AuditLogService service = serviceWithFailingRepo();
        // A success-audit write whose save fails MUST propagate, so the caller's
        // @Transactional(REQUIRED) transaction rolls back the business mutation.
        assertThrows(AuditLogService.AuditLogWriteException.class,
                () -> service.auditSuccess("create", "dynamicCrud", "test_coll",
                        Map.of("collection", "test_coll", "id", 1L)));
    }

    @Test
    @DisplayName("auditFailure swallows a repository failure so it does not compound the original error")
    void auditFailureSwallowsRepositoryFailure() {
        AuditLogService service = serviceWithFailingRepo();
        // A failure-audit write whose save fails MUST NOT propagate (REQUIRES_NEW +
        // swallow), so the original business failure is not masked by an audit error.
        assertDoesNotThrow(
                () -> service.auditFailure("create", "dynamicCrud", "test_coll",
                        Map.of("error", "some failure")));
    }

    @Test
    @DisplayName("AuditLogWriteException carries the original cause for diagnostics")
    void auditLogWriteExceptionCarriesCause() {
        try {
            AuditLogService service = serviceWithFailingRepo();
            service.auditSuccess("create", "dynamicCrud", "test_coll", Map.of());
            fail("expected AuditLogWriteException");
        } catch (AuditLogService.AuditLogWriteException e) {
            assertNotNull(e.getCause(), "exception must wrap the underlying repository failure");
        }
    }
}
