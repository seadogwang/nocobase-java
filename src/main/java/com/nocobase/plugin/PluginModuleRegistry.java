package com.nocobase.plugin;

import com.nocobase.entity.ApplicationPlugin;
import com.nocobase.repository.ApplicationPluginRepository;
import com.nocobase.service.AuditLogService;
import com.nocobase.web.ForbiddenException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registry for built-in plugin module definitions.
 *
 * <p>Holds the authoritative list of built-in plugins and syncs them with the
 * {@code application_plugins} table on startup (idempotent -- ensures all built-in
 * plugins exist with correct metadata). Provides methods for querying and managing
 * plugin state.</p>
 *
 * <p>Built-in system modules cannot be disabled, uninstalled, or removed.
 * The list of built-in plugins is defined in {@link #BUILT_IN_PLUGINS}.</p>
 */
@Component
public class PluginModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginModuleRegistry.class);

    private final ApplicationPluginRepository repository;
    private final AuditLogService auditLogService;

    public PluginModuleRegistry(ApplicationPluginRepository repository,
                                 AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /**
     * Built-in plugin definitions (name, packageName, version).
     * These are the authoritative set of system modules.
     */
    public static final List<BuiltInPlugin> BUILT_IN_PLUGINS = List.of(
            new BuiltInPlugin("users", "@nocobase/plugin-users", "1.0.0"),
            new BuiltInPlugin("auth", "@nocobase/plugin-auth", "1.0.0"),
            new BuiltInPlugin("acl", "@nocobase/plugin-acl", "1.0.0"),
            new BuiltInPlugin("collection-manager", "@nocobase/plugin-collection-manager", "1.0.0"),
            new BuiltInPlugin("data-source-main", "@nocobase/plugin-data-source-main", "1.0.0"),
            new BuiltInPlugin("ui-schema-storage", "@nocobase/plugin-ui-schema-storage", "1.0.0"),
            new BuiltInPlugin("system-settings", "@nocobase/plugin-system-settings", "1.0.0"),
            new BuiltInPlugin("application-plugins", "@nocobase/plugin-application-plugins", "1.0.0")
    );

    private static final Set<String> SYSTEM_PLUGIN_NAMES = Collections.unmodifiableSet(
            BUILT_IN_PLUGINS.stream()
                    .map(p -> p.name)
                    .collect(Collectors.toSet())
    );

    /**
     * Sync built-in plugins with the database on startup.
     * Idempotent: creates missing plugins AND corrects existing plugins
     * whose metadata (enabled/installed/builtIn/packageName/version)
     * does not match the authoritative definition.
     * System plugins must always be: enabled=true, installed=true, builtIn=true.
     */
    @PostConstruct
    public void syncOnStartup() {
        int synced = 0;
        int corrected = 0;
        for (BuiltInPlugin builtIn : BUILT_IN_PLUGINS) {
            Optional<ApplicationPlugin> existing = repository.findByName(builtIn.name);
            if (existing.isEmpty()) {
                // Create new plugin
                ApplicationPlugin plugin = new ApplicationPlugin();
                plugin.setName(builtIn.name);
                plugin.setPackageName(builtIn.packageName);
                plugin.setEnabled(true);
                plugin.setInstalled(true);
                plugin.setBuiltIn(true);
                plugin.setVersion(builtIn.version);
                repository.save(plugin);
                synced++;
                log.info("Registered built-in plugin: {} ({})", builtIn.name, builtIn.packageName);
            } else {
                // Correct existing plugin if metadata is wrong
                ApplicationPlugin plugin = existing.get();
                boolean needsSave = false;

                if (!Boolean.TRUE.equals(plugin.getEnabled())) {
                    plugin.setEnabled(true);
                    needsSave = true;
                }
                if (!Boolean.TRUE.equals(plugin.getInstalled())) {
                    plugin.setInstalled(true);
                    needsSave = true;
                }
                if (!Boolean.TRUE.equals(plugin.getBuiltIn())) {
                    plugin.setBuiltIn(true);
                    needsSave = true;
                }
                if (!builtIn.packageName.equals(plugin.getPackageName())) {
                    plugin.setPackageName(builtIn.packageName);
                    needsSave = true;
                }
                if (!builtIn.version.equals(plugin.getVersion())) {
                    plugin.setVersion(builtIn.version);
                    needsSave = true;
                }

                if (needsSave) {
                    repository.save(plugin);
                    corrected++;
                    log.info("Corrected built-in plugin metadata: {} ({})", builtIn.name, builtIn.packageName);
                }
            }
        }
        if (synced > 0) {
            log.info("Synced {} built-in plugins to application_plugins table", synced);
        }
        if (corrected > 0) {
            log.info("Corrected {} built-in plugin metadata entries", corrected);
        }
    }

    /**
     * Get all plugins (both built-in and user-installed).
     */
    public List<ApplicationPlugin> getAll() {
        return repository.findAll();
    }

    /**
     * Get all enabled plugins.
     * System plugins are always returned because the sync ensures they are enabled=true.
     */
    public List<ApplicationPlugin> getEnabled() {
        return repository.findByEnabled(true);
    }

    /**
     * Disable a plugin by name.
     *
     * @param name the plugin name
     * @return the updated plugin entity
     * @throws ForbiddenException         if the plugin is a built-in system module
     * @throws IllegalArgumentException if the plugin is not found
     */
    @Transactional
    public ApplicationPlugin disable(String name) {
        try {
            if (SYSTEM_PLUGIN_NAMES.contains(name)) {
                throw new ForbiddenException("Cannot disable system plugin: " + name);
            }
            ApplicationPlugin plugin = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + name));
            plugin.setEnabled(false);
            ApplicationPlugin saved = repository.save(plugin);
            auditLogService.auditSuccess("disable", "plugin", name, Map.of("name", name));
            return saved;
        } catch (Exception e) {
            auditLogService.auditFailure("disable", "plugin", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Enable a plugin by name.
     *
     * @param name the plugin name
     * @return the updated plugin entity
     * @throws IllegalArgumentException if the plugin is not found
     */
    @Transactional
    public ApplicationPlugin enable(String name) {
        try {
            ApplicationPlugin plugin = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + name));
            plugin.setEnabled(true);
            ApplicationPlugin saved = repository.save(plugin);
            auditLogService.auditSuccess("enable", "plugin", name, Map.of("name", name));
            return saved;
        } catch (Exception e) {
            auditLogService.auditFailure("enable", "plugin", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Uninstall a plugin by name.
     * System plugins cannot be uninstalled.
     *
     * @param name the plugin name
     * @throws ForbiddenException         if the plugin is a built-in system module
     * @throws IllegalArgumentException if the plugin is not found
     */
    @Transactional
    public void uninstall(String name) {
        try {
            if (SYSTEM_PLUGIN_NAMES.contains(name)) {
                throw new ForbiddenException("Cannot uninstall system plugin: " + name);
            }
            ApplicationPlugin plugin = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + name));
            plugin.setInstalled(false);
            repository.save(plugin);
            auditLogService.auditSuccess("uninstall", "plugin", name, Map.of("name", name));
        } catch (Exception e) {
            auditLogService.auditFailure("uninstall", "plugin", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Delete a plugin by name.
     * System plugins cannot be deleted.
     *
     * @param name the plugin name
     * @throws ForbiddenException         if the plugin is a built-in system module
     * @throws IllegalArgumentException if the plugin is not found
     */
    @Transactional
    public void delete(String name) {
        try {
            if (SYSTEM_PLUGIN_NAMES.contains(name)) {
                throw new ForbiddenException("Cannot delete system plugin: " + name);
            }
            ApplicationPlugin plugin = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + name));
            repository.delete(plugin);
            auditLogService.auditSuccess("delete", "plugin", name, Map.of("name", name));
        } catch (Exception e) {
            auditLogService.auditFailure("delete", "plugin", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Get a plugin by name.
     *
     * @param name the plugin name
     * @return the plugin entity
     * @throws IllegalArgumentException if the plugin is not found
     */
    public ApplicationPlugin getByName(String name) {
        return repository.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + name));
    }

    /**
     * Check if a plugin exists by name.
     */
    public boolean exists(String name) {
        return repository.existsByName(name);
    }

    /**
     * Check if a plugin name is a built-in system module.
     */
    public static boolean isSystemPlugin(String name) {
        return SYSTEM_PLUGIN_NAMES.contains(name);
    }

    /**
     * Install a new (non-built-in) plugin.
     *
     * @param name        the plugin name
     * @param packageName the plugin package name (defaults to name if null)
     * @param version     the plugin version
     * @param description the plugin description
     * @return the created plugin entity
     * @throws IllegalArgumentException if the plugin already exists
     */
    @Transactional
    public ApplicationPlugin install(String name, String packageName, String version, String description) {
        try {
            if (exists(name)) {
                throw new IllegalArgumentException("Plugin already installed: " + name);
            }
            ApplicationPlugin plugin = new ApplicationPlugin();
            plugin.setName(name);
            plugin.setPackageName(packageName != null ? packageName : name);
            plugin.setVersion(version);
            plugin.setDescription(description);
            plugin.setEnabled(false);
            plugin.setInstalled(true);
            plugin.setBuiltIn(false);
            ApplicationPlugin saved = repository.save(plugin);
            auditLogService.auditSuccess("install", "plugin", name,
                    Map.of("name", name, "packageName", saved.getPackageName(), "version", version));
            return saved;
        } catch (Exception e) {
            auditLogService.auditFailure("install", "plugin", name,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            throw e;
        }
    }

    /**
     * Get the set of built-in system plugin names.
     */
    public static Set<String> getSystemPluginNames() {
        return SYSTEM_PLUGIN_NAMES;
    }

    /**
     * Immutable descriptor for a built-in plugin definition.
     */
    public static class BuiltInPlugin {
        public final String name;
        public final String packageName;
        public final String version;

        public BuiltInPlugin(String name, String packageName, String version) {
            this.name = name;
            this.packageName = packageName;
            this.version = version;
        }
    }
}