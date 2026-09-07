package com.nocobase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.NocobaseApplication;
import com.nocobase.entity.Role;
import com.nocobase.entity.User;
import com.nocobase.entity.UserRole;
import com.nocobase.repository.RoleRepository;
import com.nocobase.repository.UserRepository;
import com.nocobase.repository.UserRoleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API-level tests for audit log access control.
 * Verifies: admin/root allowed, regular user (member) denied, unauthenticated denied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = NocobaseApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private String adminToken;
    private String memberToken;

    @BeforeEach
    void setUp() throws Exception {
        // Sign in as admin
        MvcResult adminResult = mockMvc.perform(post("/api/auth:signIn")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", "admin@nocobase.com", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> adminResp = objectMapper.readValue(
                adminResult.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> adminData = (Map<String, Object>) adminResp.get("data");
        adminToken = (String) adminData.get("token");

        // Create a regular member user directly in the database
        String memberEmail = "member-test-" + System.currentTimeMillis() + "@nocobase.com";
        User memberUser = new User();
        memberUser.setEmail(memberEmail);
        memberUser.setNickname("Member Test");
        memberUser.setPassword(encoder.encode("member123"));
        memberUser = userRepository.save(memberUser);

        // Assign the "member" role
        Role memberRole = roleRepository.findByName("member").orElseThrow();
        UserRole ur = new UserRole();
        ur.setUserId(memberUser.getId());
        ur.setRoleId(memberRole.getId());
        userRoleRepository.save(ur);

        // Sign in as member
        MvcResult memberResult = mockMvc.perform(post("/api/auth:signIn")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", memberEmail, "password", "member123"))))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> memberResp = objectMapper.readValue(
                memberResult.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> memberData = (Map<String, Object>) memberResp.get("data");
        memberToken = (String) memberData.get("token");
    }

    @Test
    @Order(1)
    @DisplayName("Admin can access audit logs")
    void adminCanAccessAuditLogs() throws Exception {
        mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.count").isNumber());
    }

    @Test
    @Order(2)
    @DisplayName("Regular user (member) is denied access to audit logs")
    void regularUserDeniedAuditLogs() throws Exception {
        mockMvc.perform(get("/api/auditLogs:list")
                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].message").exists());
    }

    @Test
    @Order(3)
    @DisplayName("Unauthenticated user is denied access to audit logs")
    void unauthenticatedDeniedAuditLogs() throws Exception {
        mockMvc.perform(get("/api/auditLogs:list"))
                .andExpect(status().is4xxClientError());
    }
}