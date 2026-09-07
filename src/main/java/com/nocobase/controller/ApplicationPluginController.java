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

@RestController
@RequestMapping("/api")
public class ApplicationPluginController {

    @Autowired
    private PluginModuleRegistry pluginRegistry;

    @GetMapping({"/applicationPlugins:listEnabled", "/applicationPlugins/listEnabled"})
    public ResponseEntity<?> listEnabled() {
        List<ApplicationPlugin> plugins = pluginRegistry.getEnabled();

        List<Map<String, Object>> result = plugins.stream()
                .map(p -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", p.getName());
                    map.put("packageName", p.getPackageName());
                    map.put("enabled", p.getEnabled());
                    map.put("installed", p.getInstalled());
                    map.put("builtIn", p.getBuiltIn() != null ? p.getBuiltIn() : false);
                    map.put("version", p.getVersion() != null ? p.getVersion() : "");
                    map.put("displayName", p.getName());
                    map.put("description", "");
                    return map;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping({"/applicationPlugins:disable", "/applicationPlugins/disable"})
    public ResponseEntity<?> disable(@RequestParam String name) {
        ApplicationPlugin plugin = pluginRegistry.disable(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", plugin.getName(),
                "enabled", false,
                "message", "Plugin disabled successfully"
        )));
    }

    @PostMapping({"/applicationPlugins:enable", "/applicationPlugins/enable"})
    public ResponseEntity<?> enable(@RequestParam String name) {
        ApplicationPlugin plugin = pluginRegistry.enable(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", plugin.getName(),
                "enabled", true,
                "message", "Plugin enabled successfully"
        )));
    }

    @PostMapping({"/applicationPlugins:uninstall", "/applicationPlugins/uninstall"})
    public ResponseEntity<?> uninstall(@RequestParam String name) {
        pluginRegistry.uninstall(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", name,
                "installed", false,
                "message", "Plugin uninstalled successfully"
        )));
    }

    @PostMapping({"/applicationPlugins:remove", "/applicationPlugins/remove"})
    public ResponseEntity<?> remove(@RequestParam String name) {
        pluginRegistry.delete(name);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "name", name,
                "message", "Plugin removed successfully"
        )));
    }
}