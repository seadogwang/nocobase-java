package com.nocobase.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.acl.CurrentUserContext;
import com.nocobase.repository.AuditLogRepository;
import com.nocobase.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Operational observability (Phase-21 Agent H): sensitive-value regression
 * across log levels.
 *
 * <p>Captures real Logback events at WARN, DEBUG, and ERROR levels and asserts
 * that no raw JDBC URL, host/port, username, or password appears in any
 * captured log line — proving the sanitization net holds regardless of level.
 *
 * <ul>
 *   <li>WARN: {@link GlobalExceptionHandler#handleIllegalArgument} logs
 *       {@code log.warn("Bad request: ...", scrubMessage(...))}.</li>
 *   <li>DEBUG: {@link AuditLogService#auditSuccess} success path logs
 *       {@code log.debug("Audit: ... - success", safeKey)}.</li>
 *   <li>ERROR: {@link AuditLogService#auditSuccess} with a failing repository
 *       logs {@code log.error("Failed to write audit log ...", safeKey, ...)}.</li>
 * </ul>
 *
 * <p>No Spring context, no Docker — pure unit test with Logback appender capture.
 */
class LogSanitizationLevelTest {

    private static final String LEAKY_URL =
            "jdbc:postgresql://localhost:5432/nocobase?user=admin&password=secret123";

    private ListAppender<ILoggingEvent> auditAppender;
    private ListAppender<ILoggingEvent> handlerAppender;
    private Level originalAuditLevel;
    private Level originalHandlerLevel;

    @BeforeEach
    void attachAppenders() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AuditLogService.class);
        originalAuditLevel = auditLogger.getLevel();
        auditLogger.setLevel(Level.ALL);
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        originalHandlerLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.ALL);
        handlerAppender = new ListAppender<>();
        handlerAppender.start();
        handlerLogger.addAppender(handlerAppender);
    }

    @AfterEach
    void detachAppenders() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AuditLogService.class);
        auditLogger.detachAppender(auditAppender);
        auditLogger.setLevel(originalAuditLevel);
        auditAppender.stop();

        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        handlerLogger.detachAppender(handlerAppender);
        handlerLogger.setLevel(originalHandlerLevel);
        handlerAppender.stop();
    }

    private AuditLogService serviceWithRepo(AuditLogRepository repo) {
        CurrentUserContext userContext = mock(CurrentUserContext.class);
        when(userContext.getCurrentUserId()).thenReturn(Optional.of(1L));
        return new AuditLogService(repo, userContext, new ObjectMapper());
    }

    private void assertNoSecrets(String label, String logs) {
        assertFalse(logs.contains("secret123"), label + ": password leaked: " + logs);
        assertFalse(logs.contains("admin"), label + ": username leaked: " + logs);
        assertFalse(logs.contains("localhost:5432"), label + ": host/port leaked: " + logs);
        assertFalse(logs.contains("jdbc:postgresql"), label + ": JDBC URL leaked: " + logs);
    }

    @Test
    @DisplayName("WARN-level GlobalExceptionHandler log scrubs JDBC URL and credentials")
    void warnLevelIsSanitized() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        IllegalArgumentException ex = new IllegalArgumentException("bad value: " + LEAKY_URL);
        handler.handleIllegalArgument(ex);

        String logs = format(handlerAppender);
        // Must have produced a WARN event.
        assertTrue(handlerAppender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                "expected a WARN log; got: " + logs);
        assertNoSecrets("WARN", logs);
    }

    @Test
    @DisplayName("DEBUG-level audit success log scrubs the resource key")
    void debugLevelIsSanitized() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuditLogService service = serviceWithRepo(repo);

        service.auditSuccess("create", "dataSource", LEAKY_URL, Map.of("success", true));

        String logs = format(auditAppender);
        assertTrue(auditAppender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG),
                "expected a DEBUG success log; got: " + logs);
        assertNoSecrets("DEBUG", logs);
    }

    @Test
    @DisplayName("ERROR-level audit write-failure log scrubs the resource key and exception")
    void errorLevelIsSanitized() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("boom; jdbc:postgresql://localhost:5432/x?user=admin&password=secret123"));
        AuditLogService service = serviceWithRepo(repo);

        assertThrows(AuditLogService.AuditLogWriteException.class,
                () -> service.auditSuccess("create", "dataSource", LEAKY_URL, Map.of()));

        String logs = format(auditAppender);
        assertTrue(auditAppender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR),
                "expected an ERROR log; got: " + logs);
        assertNoSecrets("ERROR", logs);
    }

    @Test
    @DisplayName("DEBUG-level audit failure log scrubs the resource key")
    void debugFailureLevelIsSanitized() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AuditLogService service = serviceWithRepo(repo);

        service.auditFailure("testConnection", "dataSource", LEAKY_URL, Map.of("error", "x"));

        String logs = format(auditAppender);
        assertTrue(auditAppender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG),
                "expected a DEBUG failure log; got: " + logs);
        assertNoSecrets("DEBUG-failure", logs);
    }

    private static String format(ListAppender<ILoggingEvent> appender) {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent ev : appender.list) {
            sb.append(ev.getLevel()).append(' ').append(ev.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
