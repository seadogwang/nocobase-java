package com.nocobase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the first-admin bootstrap flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BootstrapAdminTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private RoleRepository roleRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        userRoleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Smoke: public endpoint is accessible")
    void publicEndpointAccessible() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
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
    @DisplayName("Bootstrap: slash route also works")
    void bootstrapSlashRouteWorks() throws Exception {
        mockMvc.perform(post("/api/bootstrap/setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("admin@nocobase.com"))
                .andExpect(jsonPath("$.data.nickname").value("Super Admin"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @DisplayName("Bootstrap: admin user gets both admin and root roles")
    void bootstrapGrantsBothRoles() throws Exception {
        String responseBody = mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(responseBody);
        Long userId = root.get("data").get("id").asLong();

        var userRoles = userRoleRepository.findByUserId(userId);
        assertEquals(2, userRoles.size(), "Admin user should have exactly 2 roles");

        var roleNames = userRoles.stream()
                .map(ur -> roleRepository.findById(ur.getRoleId()).orElseThrow().getName())
                .toList();
        assertTrue(roleNames.contains("admin"), "Admin user should have admin role");
        assertTrue(roleNames.contains("root"), "Admin user should have root role");
    }

    @Test
    @DisplayName("Bootstrap: duplicate call returns 409 Conflict")
    void bootstrapDuplicateReturns409() throws Exception {
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

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
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

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
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        MvcResult signInResult = mockMvc.perform(post("/api/auth:signIn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@nocobase.com\",\"password\":\"Admin123!\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = signInResult.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(responseBody);
        String token = root.get("data").get("token").asText();

        mockMvc.perform(get("/api/users:list")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/auth:check")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("admin@nocobase.com"));
    }
}