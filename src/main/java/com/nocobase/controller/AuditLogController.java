package com.nocobase.controller;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.entity.AuditLog;
import com.nocobase.service.AuditLogService;
import com.nocobase.web.ApiResponse;
import com.nocobase.web.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin-only audit log query API.
 * All endpoints require admin/root role.
 */
@RestController
@RequestMapping("/api")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private CurrentUserContext currentUserContext;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ========================================================================
    // Query endpoints
    // ========================================================================

    @GetMapping({"/auditLogs:list", "/auditLogs/list"})
    public ResponseEntity<?> list(
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        requireAdmin();

        int pageNum = parsePage(page, 1);
        int pageSizeNum = parsePageSize(pageSize, 20);

        Page<AuditLog> result;

        if (startDate != null && endDate != null) {
            LocalDateTime start = LocalDateTime.parse(startDate, ISO_FORMATTER);
            LocalDateTime end = LocalDateTime.parse(endDate, ISO_FORMATTER);
            result = auditLogService.listByDateRange(start, end, pageNum, pageSizeNum, sort);
        } else if (actorUserId != null) {
            result = auditLogService.listByActor(actorUserId, pageNum, pageSizeNum, sort);
        } else if (resource != null && resourceKey != null) {
            result = auditLogService.listByResourceAndKey(resource, resourceKey, pageNum, pageSizeNum, sort);
        } else if (resource != null) {
            result = auditLogService.listByResource(resource, pageNum, pageSizeNum, sort);
        } else {
            result = auditLogService.listAuditLogs(pageNum, pageSizeNum, sort);
        }

        List<Map<String, Object>> entries = result.getContent().stream()
                .map(auditLogService::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.list(entries, result.getTotalElements(), pageNum, pageSizeNum));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private void requireAdmin() {
        if (!currentUserContext.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }

    private int parsePage(String raw, int defaultVal) {
        if (raw == null || raw.trim().isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 1) return 1;
            if (val > 10000) return 10000;
            return val;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private int parsePageSize(String raw, int defaultVal) {
        if (raw == null || raw.trim().isEmpty()) return defaultVal;
        try {
            int val = Integer.parseInt(raw.trim());
            if (val < 1) return 1;
            if (val > 200) return 200;
            return val;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}