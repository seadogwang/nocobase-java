package com.nocobase.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract & evidence maintenance (Phase-21 Agent I): fixture drift guard.
 *
 * <p>Ensures the required frontend-contract fixtures exist and are well-formed
 * so the replay tests in {@code ApiCompatibilityTest} (replayFrontendTrace +
 * replayFrontendContracts) have their source data. Removing a fixture or
 * shrinking the startup trace below its documented step count fails this test.
 */
class FrontendContractCoverageTest {

    private static final Path TRACE = Paths.get("src/test/resources/frontend-traces/trace.json");
    private static final Path CONTRACT_DIR = Paths.get("src/test/resources/frontend-contract");
    private static final List<String> REQUIRED_CONTRACTS = List.of(
            "login.json", "crud.json", "collection-manager.json",
            "plugins.json", "system-settings.json", "ui-schema.json"
    );
    private static final int EXPECTED_TRACE_STEPS = 73;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("The 73-step frontend startup trace fixture exists and is well-formed")
    void startupTraceFixtureExists() throws Exception {
        assertTrue(Files.isRegularFile(TRACE), "trace.json must exist at " + TRACE);
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = objectMapper.readValue(Files.readString(TRACE), Map.class);
        assertEquals("noco-base-frontend-startup", trace.get("trace"),
                "trace name must be noco-base-frontend-startup");
        Object steps = trace.get("steps");
        assertInstanceOf(List.class, steps, "trace must have a steps array");
        assertEquals(EXPECTED_TRACE_STEPS, ((List<?>) steps).size(),
                "startup trace must have exactly " + EXPECTED_TRACE_STEPS + " steps");
    }

    @Test
    @DisplayName("All required frontend-contract fixtures exist and are non-empty arrays")
    void requiredContractFixturesExist() throws Exception {
        assertTrue(Files.isDirectory(CONTRACT_DIR), "frontend-contract dir must exist");
        for (String name : REQUIRED_CONTRACTS) {
            Path p = CONTRACT_DIR.resolve(name);
            assertTrue(Files.isRegularFile(p), "missing contract fixture: " + name);
            assertTrue(Files.size(p) > 2, "contract fixture must be non-empty: " + name);
            @SuppressWarnings("unchecked")
            List<Object> contracts = objectMapper.readValue(Files.readString(p), List.class);
            assertFalse(contracts.isEmpty(), "contract fixture must have at least one contract: " + name);
            for (Object o : contracts) {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) o;
                assertNotNull(c.get("name"), "contract in " + name + " must have a name");
                assertNotNull(c.get("method"), "contract in " + name + " must have a method");
                assertNotNull(c.get("path"), "contract in " + name + " must have a path");
            }
        }
    }
}
