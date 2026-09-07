package com.nocobase.config;

import com.nocobase.entity.*;
import com.nocobase.plugin.PluginModuleRegistry;
import com.nocobase.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Production data initializer.
 * Never creates default users or outputs credentials.
 * Only ensures system roles, collections, settings, UI schemas,
 * and plugins are present idempotently.
 */
@Configuration
@Profile("!test")
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    CommandLineRunner initDatabase(
            UserRepository userRepository,
            RoleRepository roleRepository,
            CollectionRepository collectionRepository,
            ApplicationPluginRepository pluginRepository,
            SystemSettingsRepository settingsRepository,
            UiSchemaRepository uiSchemaRepository
    ) {
        return args -> {
            boolean hasUsers = userRepository.count() > 0;

            if (!hasUsers) {
                log.warn("No admin user exists. An admin user must be created before the application can be used.");
            }

            // Idempotently ensure roles exist
            ensureRoles(roleRepository);

            if (hasUsers) {
                // Users exist: idempotently ensure system data is present
                ensureSystemCollections(collectionRepository);
                ensureSystemSettings(settingsRepository);
                ensureDefaultUiSchema(uiSchemaRepository);
                ensureCorePlugins(pluginRepository);
            } else {
                // Fresh install: create all system data
                createSystemCollections(collectionRepository);
                createSystemSettings(settingsRepository);
                createDefaultUiSchema(uiSchemaRepository);
                createCorePlugins(pluginRepository);
                log.info("System data initialized. No admin user exists yet.");
            }
        };
    }

    private void ensureRoles(RoleRepository roleRepository) {
        ensureRole(roleRepository, "admin", "Admin", false);
        ensureRole(roleRepository, "root", "Root", false);
        ensureRole(roleRepository, "member", "Member", true);
    }

    private void ensureRole(RoleRepository roleRepository, String name, String title, boolean isDefault) {
        if (!roleRepository.existsByName(name)) {
            Role role = new Role();
            role.setName(name);
            role.setTitle(title);
            role.setIsDefault(isDefault);
            roleRepository.save(role);
            log.info("Created missing role: {}", name);
        }
    }

    private void createSystemCollections(CollectionRepository collectionRepository) {
        if (collectionRepository.count() == 0) {
            CollectionEntity usersCollection = new CollectionEntity("users", "用户", "physical");
            usersCollection.setTableName("users");
            usersCollection.setSystem(true);
            usersCollection.setFields("[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"email\",\"type\":\"string\"},{\"name\":\"nickname\",\"type\":\"string\"}]");
            collectionRepository.save(usersCollection);

            CollectionEntity rolesCollection = new CollectionEntity("roles", "角色", "physical");
            rolesCollection.setTableName("roles");
            rolesCollection.setSystem(true);
            rolesCollection.setFields("[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
            collectionRepository.save(rolesCollection);

            CollectionEntity collectionsCollection = new CollectionEntity("collections", "数据表", "physical");
            collectionsCollection.setTableName("collections");
            collectionsCollection.setSystem(true);
            collectionsCollection.setFields("[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
            collectionRepository.save(collectionsCollection);
        }
    }

    private void ensureSystemCollections(CollectionRepository collectionRepository) {
        ensureCollection(collectionRepository, "users", "用户", "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"email\",\"type\":\"string\"},{\"name\":\"nickname\",\"type\":\"string\"}]");
        ensureCollection(collectionRepository, "roles", "角色", "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
        ensureCollection(collectionRepository, "collections", "数据表", "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
    }

    private void ensureCollection(CollectionRepository collectionRepository, String name, String title, String fields) {
        if (!collectionRepository.existsByName(name)) {
            CollectionEntity collection = new CollectionEntity(name, title, "physical");
            collection.setTableName(name);
            collection.setSystem(true);
            collection.setFields(fields);
            collectionRepository.save(collection);
            log.info("Created missing system collection: {}", name);
        }
    }

    private void createSystemSettings(SystemSettingsRepository settingsRepository) {
        if (settingsRepository.count() == 0) {
            SystemSettings titleSetting = new SystemSettings();
            titleSetting.setSettingKey("title");
            titleSetting.setSettingValue("NocoBase Java");
            settingsRepository.save(titleSetting);

            SystemSettings versionSetting = new SystemSettings();
            versionSetting.setSettingKey("version");
            versionSetting.setSettingValue("1.0.0");
            settingsRepository.save(versionSetting);
        }
    }

    private void ensureSystemSettings(SystemSettingsRepository settingsRepository) {
        ensureSetting(settingsRepository, "title", "NocoBase Java");
        ensureSetting(settingsRepository, "version", "1.0.0");
    }

    private void ensureSetting(SystemSettingsRepository settingsRepository, String key, String value) {
        if (settingsRepository.findBySettingKey(key).isEmpty()) {
            SystemSettings setting = new SystemSettings();
            setting.setSettingKey(key);
            setting.setSettingValue(value);
            settingsRepository.save(setting);
            log.info("Created missing system setting: {}", key);
        }
    }

    private void createDefaultUiSchema(UiSchemaRepository uiSchemaRepository) {
        if (uiSchemaRepository.count() == 0) {
            UiSchema root = new UiSchema();
            root.setUid("root");
            root.setSchemaUid("root");
            root.setSchema("{\"type\":\"void\",\"x-component\":\"AdminLayout\"}");
            uiSchemaRepository.save(root);

            UiSchema menu = new UiSchema();
            menu.setUid("menu");
            menu.setSchemaUid("menu");
            menu.setParentUid("root");
            menu.setSchema("{\"type\":\"void\",\"x-component\":\"Menu\"}");
            menu.setSortOrder(0);
            uiSchemaRepository.save(menu);
        }
    }

    private void ensureDefaultUiSchema(UiSchemaRepository uiSchemaRepository) {
        if (uiSchemaRepository.findByUid("root").isEmpty()) {
            UiSchema root = new UiSchema();
            root.setUid("root");
            root.setSchemaUid("root");
            root.setSchema("{\"type\":\"void\",\"x-component\":\"AdminLayout\"}");
            uiSchemaRepository.save(root);
            log.info("Created missing UI schema: root");
        }
        if (uiSchemaRepository.findByUid("menu").isEmpty()) {
            UiSchema menu = new UiSchema();
            menu.setUid("menu");
            menu.setSchemaUid("menu");
            menu.setParentUid("root");
            menu.setSchema("{\"type\":\"void\",\"x-component\":\"Menu\"}");
            menu.setSortOrder(0);
            uiSchemaRepository.save(menu);
            log.info("Created missing UI schema: menu");
        }
    }

    private void createCorePlugins(ApplicationPluginRepository pluginRepository) {
        for (PluginModuleRegistry.BuiltInPlugin builtIn : PluginModuleRegistry.BUILT_IN_PLUGINS) {
            if (pluginRepository.findByName(builtIn.name).isPresent()) {
                continue;
            }
            ApplicationPlugin plugin = new ApplicationPlugin();
            plugin.setName(builtIn.name);
            plugin.setPackageName(builtIn.packageName);
            plugin.setEnabled(true);
            plugin.setInstalled(true);
            plugin.setBuiltIn(true);
            plugin.setVersion(builtIn.version);
            pluginRepository.save(plugin);
        }
    }

    private void ensureCorePlugins(ApplicationPluginRepository pluginRepository) {
        createCorePlugins(pluginRepository);
    }
}