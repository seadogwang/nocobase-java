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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Test data initializer — active only under the "test" profile.
 * Creates the built-in admin user, roles, and all system seed data
 * so that integration tests using {@code @ActiveProfiles("test")} have
 * a predictable starting state.
 * <p>
 * This is intentionally isolated from the production DataInitializer
 * (which never creates default users) via {@code @Profile("test"} vs
 * {@code @Profile("!test")}.
 */
@Configuration
@Profile("test")
public class TestDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(TestDataInitializer.class);

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Bean
    CommandLineRunner initTestData(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            CollectionRepository collectionRepository,
            ApplicationPluginRepository pluginRepository,
            SystemSettingsRepository settingsRepository,
            UiSchemaRepository uiSchemaRepository
    ) {
        return args -> {
            // 1. Roles — idempotent
            Role adminRole = ensureRole(roleRepository, "admin", "Admin", false);
            Role rootRole = ensureRole(roleRepository, "root", "Root", false);
            Role memberRole = ensureRole(roleRepository, "member", "Member", true);

            // 2. Admin user — idempotent
            User adminUser = userRepository.findByEmail("admin@nocobase.com").orElse(null);
            if (adminUser == null) {
                adminUser = new User();
                adminUser.setEmail("admin@nocobase.com");
                adminUser.setNickname("Admin");
                adminUser.setPassword(encoder.encode("admin123"));
                adminUser = userRepository.save(adminUser);
                log.info("Created test admin user (admin@nocobase.com)");
            } else {
                log.info("Test admin user already exists (id={})", adminUser.getId());
            }

            // 3. Bind admin user to admin role — idempotent
            boolean alreadyBound = userRoleRepository.findByUserId(adminUser.getId()).stream()
                    .anyMatch(ur -> ur.getRoleId().equals(adminRole.getId()));
            if (!alreadyBound) {
                UserRole ur = new UserRole();
                ur.setUserId(adminUser.getId());
                ur.setRoleId(adminRole.getId());
                userRoleRepository.save(ur);
                log.info("Bound admin user to admin role");
            }

            // 4. System collections — idempotent
            ensureSystemCollections(collectionRepository);

            // 5. System settings — idempotent
            ensureSystemSettings(settingsRepository);

            // 6. UI schemas — idempotent
            ensureDefaultUiSchema(uiSchemaRepository);

            // 7. Core plugins — idempotent
            ensureCorePlugins(pluginRepository);

            log.info("Test data initialization complete");
        };
    }

    // ──────────────── helpers ────────────────

    private Role ensureRole(RoleRepository repo, String name, String title, boolean isDefault) {
        return repo.findByName(name).orElseGet(() -> {
            Role role = new Role();
            role.setName(name);
            role.setTitle(title);
            role.setIsDefault(isDefault);
            role = repo.save(role);
            log.info("Created test role: {}", name);
            return role;
        });
    }

    private void ensureSystemCollections(CollectionRepository repo) {
        ensureCollection(repo, "users", "用户",
                "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"email\",\"type\":\"string\"},{\"name\":\"nickname\",\"type\":\"string\"}]");
        ensureCollection(repo, "roles", "角色",
                "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
        ensureCollection(repo, "collections", "数据表",
                "[{\"name\":\"id\",\"type\":\"bigInt\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"title\",\"type\":\"string\"}]");
    }

    private void ensureCollection(CollectionRepository repo, String name, String title, String fields) {
        if (!repo.existsByName(name)) {
            CollectionEntity c = new CollectionEntity(name, title, "physical");
            c.setTableName(name);
            c.setSystem(true);
            c.setFields(fields);
            repo.save(c);
            log.info("Created missing system collection: {}", name);
        }
    }

    private void ensureSystemSettings(SystemSettingsRepository repo) {
        ensureSetting(repo, "title", "NocoBase Java");
        ensureSetting(repo, "version", "1.0.0");
    }

    private void ensureSetting(SystemSettingsRepository repo, String key, String value) {
        if (repo.findBySettingKey(key).isEmpty()) {
            SystemSettings s = new SystemSettings();
            s.setSettingKey(key);
            s.setSettingValue(value);
            repo.save(s);
            log.info("Created missing system setting: {}", key);
        }
    }

    private void ensureDefaultUiSchema(UiSchemaRepository repo) {
        if (repo.findByUid("root").isEmpty()) {
            UiSchema root = new UiSchema();
            root.setUid("root");
            root.setSchemaUid("root");
            root.setSchema("{\"type\":\"void\",\"x-component\":\"AdminLayout\"}");
            repo.save(root);
            log.info("Created missing UI schema: root");
        }
        if (repo.findByUid("menu").isEmpty()) {
            UiSchema menu = new UiSchema();
            menu.setUid("menu");
            menu.setSchemaUid("menu");
            menu.setParentUid("root");
            menu.setSchema("{\"type\":\"void\",\"x-component\":\"Menu\"}");
            menu.setSortOrder(0);
            repo.save(menu);
            log.info("Created missing UI schema: menu");
        }
    }

    private void ensureCorePlugins(ApplicationPluginRepository repo) {
        for (PluginModuleRegistry.BuiltInPlugin builtIn : PluginModuleRegistry.BUILT_IN_PLUGINS) {
            if (repo.findByName(builtIn.name).isPresent()) {
                continue;
            }
            ApplicationPlugin p = new ApplicationPlugin();
            p.setName(builtIn.name);
            p.setPackageName(builtIn.packageName);
            p.setEnabled(true);
            p.setInstalled(true);
            p.setBuiltIn(true);
            p.setVersion(builtIn.version);
            repo.save(p);
        }
    }
}