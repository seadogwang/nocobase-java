package com.nocobase.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production operations (Agent I): health endpoint contract in a packaged
 * application profile.
 *
 * <p>Verifies {@code /api/health/live} (liveness, lightweight) and
 * {@code /api/health/ready} (readiness, with component checks) behave as
 * documented: both are publicly reachable (no auth), liveness is always UP,
 * and readiness reports component status and 200 when the app can serve
 * traffic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void liveReturnsUpWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.releaseVersion").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void readyReturnsComponentsWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database").exists())
                .andExpect(jsonPath("$.components.flyway").exists())
                .andExpect(jsonPath("$.components.runtimeRegistry").exists())
                // Phase-21 Agent H: structured observability fields.
                .andExpect(jsonPath("$.releaseVersion").exists())
                .andExpect(jsonPath("$.databaseMode").exists())
                .andExpect(jsonPath("$.migrationState").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void healthRootIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Phase-21 H: health responses expose no JDBC URLs or credentials")
    void healthResponsesContainNoSecrets() throws Exception {
        String live = mockMvc.perform(get("/api/health/live"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ready = mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String body : new String[]{live, ready}) {
            String lower = body.toLowerCase();
            assertFalse(lower.contains("jdbc:"), "health body must not contain JDBC URLs: " + body);
            assertFalse(lower.contains("password"), "health body must not contain 'password': " + body);
            assertFalse(lower.contains("secret"), "health body must not contain 'secret': " + body);
        }
    }
}
