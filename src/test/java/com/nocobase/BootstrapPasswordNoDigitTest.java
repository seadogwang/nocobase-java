package com.nocobase;

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
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests that bootstrap rejects passwords that contain no digits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapPasswordNoDigitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("NOCOBASE_ADMIN_EMAIL", () -> "admin@nocobase.com");
        registry.add("NOCOBASE_ADMIN_PASSWORD", () -> "abcdefgh");
        registry.add("NOCOBASE_ADMIN_NICKNAME", () -> "Admin");
    }

    @BeforeEach
    @Transactional
    void setUp() {
        userRoleRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Bootstrap: password with no digit is rejected")
    void noDigitPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/bootstrap:setup")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        "Password must contain at least one digit"));
    }
}