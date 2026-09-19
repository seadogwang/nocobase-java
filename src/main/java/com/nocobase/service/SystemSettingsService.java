package com.nocobase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.SystemSettings;
import com.nocobase.repository.SystemSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for system settings management.
 * Encapsulates all repository access -- controllers must NOT directly inject repositories.
 * Transaction boundaries are in the service layer.
 */
@Service
public class SystemSettingsService {

    private final SystemSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    /** Sensitive keywords that must NEVER be exposed or updated through this API */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "secret", "password", "token", "privatekey", "credential", "jwt", "database"
    );

    public SystemSettingsService(SystemSettingsRepository settingsRepository,
                                 ObjectMapper objectMapper,
                                 AuditLogService auditLogService) {
        this.settingsRepository = settingsRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * Checks whether a setting key contains any sensitive keyword (case-insensitive).
     * Normalizes the key by removing non-alphanumeric characters before matching.
     */
    public boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase().replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /**
     * Returns all system settings as a frontend-compatible object.
     * Sensitive keys are excluded from the response.
     * Uses valueType for explicit type restoration; falls back to type guessing
     * for old records without valueType data.
     */
    public Map<String, Object> get() {
        Map<String, Object> settings = new LinkedHashMap<>();

        settingsRepository.findAll().forEach(s -> {
            String key = s.getSettingKey();
            if (!isSensitiveKey(key)) {
                settings.put(key, parseValueWithType(s.getSettingValue(), s.getValueType()));
            }
        });

        // If no settings exist, return defaults
        if (settings.isEmpty()) {
            settings.put("title", "NocoBase Java");
            settings.put("logo", "");
            settings.put("version", "1.0.0");
        }

        return settings;
    }

    /**
     * Partial update: accepts a map of key-value pairs.
     * Preserves existing settings that are not in the request.
     * Sensitive keys are rejected (case-insensitive).
     * Object/array values are serialized as JSON strings.
     * Explicit valueType is recorded so reads return the correct type.
     * Update is immediately readable and transactionally consistent.
     */
    @Transactional
    public Map<String, Object> update(Map<String, Object> body) {
        try {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                String key = entry.getKey().trim();

                // Reject sensitive keys (case-insensitive)
                if (isSensitiveKey(key)) {
                    throw new IllegalArgumentException("Cannot update sensitive setting: " + key);
                }

                Object rawValue = entry.getValue();
                String value = serializeValue(rawValue);
                String type = determineValueType(rawValue);

                // Upsert
                settingsRepository.findBySettingKey(key).ifPresentOrElse(
                        existing -> {
                            existing.setSettingValue(value);
                            existing.setValueType(type);
                            settingsRepository.save(existing);
                        },
                        () -> {
                            SystemSettings setting = new SystemSettings();
                            setting.setSettingKey(key);
                            setting.setSettingValue(value);
                            setting.setValueType(type);
                            settingsRepository.save(setting);
                        }
                );
            }

            // Audit the update (keys only, never values which may contain secrets)
            auditLogService.auditSuccess("update", "systemSettings", "settings",
                    Map.of("updatedKeys", body.keySet().stream()
                            .filter(k -> !isSensitiveKey(k))
                            .collect(java.util.stream.Collectors.toList())));

            // Return the updated settings (immediately readable after flush)
            return get();
        } catch (Exception e) {
            auditLogService.auditFailure("update", "systemSettings", "settings",
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Determine the value type for storage.
     * Returns one of: string, boolean, number, json, null
     */
    private String determineValueType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map || value instanceof List) {
            return "json";
        }
        return "string";
    }

    /**
     * Serialize a value to a string for storage.
     * Booleans are stored as "true"/"false", numbers as their string representation,
     * objects and arrays as JSON strings. Plain strings are stored as-is.
     */
    private String serializeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map || value instanceof List) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize value", e);
            }
        }
        return value.toString();
    }

    /**
     * Parse a stored string value back to its native type using the explicit valueType.
     * When valueType is present, uses it to determine the correct Java type.
     * When valueType is null (old records), falls back to heuristic type guessing.
     *
     * Key guarantees:
     * - "00123" stored as string reads back as string "00123" (not 123)
     * - "false" stored as string reads back as string "false" (not Boolean)
     * - Old records without valueType continue to work via type guessing
     */
    private Object parseValueWithType(String value, String valueType) {
        // Null type: explicitly stored as null
        if ("null".equals(valueType)) {
            return null;
        }

        if (value == null || value.isEmpty()) {
            return value;
        }

        if (valueType != null) {
            switch (valueType) {
                case "string":
                    return value;
                case "boolean":
                    return Boolean.parseBoolean(value.trim());
                case "number":
                    return parseNumber(value.trim());
                case "json":
                    try {
                        return objectMapper.readValue(value.trim(), Object.class);
                    } catch (JsonProcessingException e) {
                        return value;
                    }
                case "null":
                    return null;
                default:
                    break;
            }
        }

        return parseJsonValueHeuristic(value);
    }

    /**
     * Parse a number string to the appropriate numeric type.
     */
    private Object parseNumber(String trimmed) {
        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            } else {
                return Long.parseLong(trimmed);
            }
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    /**
     * Heuristic type guessing for backward compatibility with old records.
     */
    private Object parseJsonValueHeuristic(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String trimmed = value.trim();

        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (JsonProcessingException e) {
                return value;
            }
        }

        if ("true".equals(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equals(trimmed)) {
            return Boolean.FALSE;
        }

        try {
            if (trimmed.contains(".")) {
                return Double.parseDouble(trimmed);
            } else {
                return Long.parseLong(trimmed);
            }
        } catch (NumberFormatException e) {
            // Not a number, return as plain string
        }

        return value;
    }
}