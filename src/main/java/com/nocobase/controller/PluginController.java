package com.nocobase.controller;

import com.nocobase.entity.ApplicationPlugin;
import com.nocobase.plugin.PluginModuleRegistry;
import com.nocobase.web.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Legacy plugin API endpoints that delegate to PluginModuleRegistry.
 * <p>
 * All lifecycle operations go through the registry, which enforces
 * system-plugin protection and maintains the authoritative plugin state
 * in the {@code application_plugins} table.
 * </p>
 * <p>
 * Response fields are kept frontend-compatible with the original
 * PluginEntity-based API shape.
 * </p>
 */
@RestController
@RequestMapping("/api")
public class PluginController {

    @Autowired
    private PluginModuleRegistry pluginRegistry;

    @GetMapping({"/plugins:list", "/plugins/list"})
    public ResponseEntity<?> list() {
        List<ApplicationPlugin> plugins = pluginRegistry.getAll();
        List<Map<String, Object>> result = plugins.stream()
                .map(this::toPluginResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping({"/plugins:enabled", "/plugins/enabled"})
    public ResponseEntity<?> listEnabled() {
        List<ApplicationPlugin> plugins = pluginRegistry.getEnabled();
        List<Map<String, Object>> result = plugins.stream()
                .map(p -> Map.<String, Object>of(
                        "name", p.getName(),
                        "packageName", p.getPackageName() != null ? p.getPackageName() : "",
                        "enabled", p.getEnabled() != null && p.getEnabled()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/plugins:install", "/plugins/install"})
    public ResponseEntity<?> install(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String packageName = (String) body.get("packageName");
        String version = (String) body.get("version");
        String description = (String) body.get("description");

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Plugin name is required");
        }

        ApplicationPlugin plugin = pluginRegistry.install(
                name,
                packageName != null ? packageName : name,
                version,
                description
        );

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id", plugin.getId(),
                "name", plugin.getName(),
                "packageName", plugin.getPackageName() != null ? plugin.getPackageName() : "",
                "message", "Plugin installed successfully"
        )));
    }

    @PostMapping({"/plugins:enable", "/plugins/enable"})
    public ResponseEntity<?> enable(@RequestParam String name) {
        ApplicationPlugin plugin = pluginRegistry.enable(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", plugin.getName(),
                "enabled", true,
                "message", "Plugin enabled successfully"
        )));
    }

    @PostMapping({"/plugins:disable", "/plugins/disable"})
    public ResponseEntity<?> disable(@RequestParam String name) {
        ApplicationPlugin plugin = pluginRegistry.disable(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", plugin.getName(),
                "enabled", false,
                "message", "Plugin disabled successfully"
        )));
    }

    @PostMapping({"/plugins:uninstall", "/plugins/uninstall"})
    public ResponseEntity<?> uninstall(@RequestParam String name) {
        pluginRegistry.uninstall(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", name,
                "message", "Plugin uninstalled successfully"
        )));
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Map an ApplicationPlugin to the frontend-compatible response shape.
     * Fields match the original PluginEntity-based API: id, name, packageName,
     * version, description, enabled, installedAt.
     */
    private Map<String, Object> toPluginResponse(ApplicationPlugin p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("packageName", p.getPackageName() != null ? p.getPackageName() : "");
        map.put("version", p.getVersion() != null ? p.getVersion() : "");
        map.put("description", p.getDescription() != null ? p.getDescription() : "");
        map.put("enabled", p.getEnabled() != null && p.getEnabled());
        map.put("installedAt", (p.getInstalled() != null && p.getInstalled()
                && p.getCreatedAt() != null) ? p.getCreatedAt().toString() : "");
        return map;
    }
}