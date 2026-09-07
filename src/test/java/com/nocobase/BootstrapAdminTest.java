package com.nocobase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the first-admin bootstrap flow.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Empty DB creates admin user via POST /api/bootstrap:setup</li>
 *   <li>Duplicate bootstrap call returns 409 Conflict</li>
 *   <li>Sign-in with the bootstrapped admin credentials works</li>
 *   <li>Admin user can access protected admin endpoints</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("NOCOBASE_ADMIN_EMAIL", () -> "admin@nocobase.com");
        registry.add("NOCOBASE_ADMIN_PASSWORD", () -> "Admin123!");
        registry.add("NOCOBASE_ADMIN_NICKNAME", () -> "Super Admin");
    }

    /**
     * Clean up any users created by TestDataInitializer so the bootstrap
     * endpoint sees an empty users table.
     */
    @BeforeEach
    @Transactional
    void setUp() {
        userRoleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Bootstrap: empty DB creates admin user successfully")
    void bootstrapEmptyDbCreatesAdmin() throws Exception {
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("admin@nocobase.com"))
                .andExpect(jsonPath("$.data.nickname").value("Super Admin"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @DisplayName("Bootstrap: duplicate call returns 409 Conflict")
    void bootstrapDuplicateReturns409() throws Exception {
        // First call should succeed
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Second call should be rejected
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value(
                        "Cannot bootstrap: users already exist in the database. "
                        + "Bootstrap is only available on a fresh installation."));
    }

    @Test
    @DisplayName("Bootstrap: sign in with admin credentials works after bootstrap")
    void loginWorksAfterBootstrap() throws Exception {
        // Bootstrap
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Sign in
        mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@nocobase.com\",\"password\":\"Admin123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.email").value("admin@nocobase.com"))
                .andExpect(jsonPath("$.data.user.nickname").value("Super Admin"))
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
    }

    @Test
    @DisplayName("Bootstrap: admin user can access protected admin endpoints")
    void adminPermissionsWork() throws Exception {
        // Bootstrap
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Sign in to get a JWT token
        MvcResult signInResult = mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@nocobase.com\",\"password\":\"Admin123!\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = signInResult.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(responseBody);
        String token = root.get("data").get("token").asText();

        // Access admin-protected endpoint (GET /api/users:list)
        mockMvc.perform(get("/api/users:list")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // Also verify the /api/auth:check endpoint works
        mockMvc.perform(get("/api/auth:check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("admin@nocobase.com"));
    }
}