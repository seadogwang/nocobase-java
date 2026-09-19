package com.nocobase.release;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static architecture tests for the CI release-gate workflow.
 *
 * <p>Reads {@code .github/workflows/release-gate.yml} from the project root and
 * verifies the invariants required by the Phase 19/20 CI hardening task
 * (Agent E):
 * <ul>
 *   <li>The workflow delegates to the canonical {@code scripts/release-gate.ps1}
 *       rather than reimplementing gates in YAML.</li>
 *   <li>The default job injects no fake {@code PG_URL}/{@code PG_USERNAME}/
 *       {@code PG_PASSWORD} credentials (Testcontainers mode is the default).</li>
 *   <li>Release report and surefire reports are uploaded on both success and
 *       failure ({@code if: always()}).</li>
 *   <li>The job's final result fails when the canonical script exits non-zero.</li>
 *   <li>Script paths are platform-independent (forward slashes, {@code pwsh}).</li>
 * </ul>
 *
 * <p>Deterministic — no Docker, no network, no GitHub API required.
 */
class ReleaseGateWorkflowTest {

    private String yaml;

    @BeforeEach
    void loadWorkflow() throws IOException {
        Path workflowPath = Path.of(".github/workflows/release-gate.yml");
        assertTrue(Files.isRegularFile(workflowPath),
                "Release-gate workflow must exist at .github/workflows/release-gate.yml");
        yaml = Files.readString(workflowPath);
    }

    @Test
    @DisplayName("Workflow delegates to the canonical release-gate script (no YAML gate reimplementation)")
    void workflowDelegatesToCanonicalScript() {
        // The workflow must invoke the canonical script via pwsh, not reimplement gates.
        assertTrue(yaml.contains("pwsh ./scripts/release-gate.ps1")
                        || yaml.contains("pwsh scripts/release-gate.ps1"),
                "Workflow must run the canonical scripts/release-gate.ps1 via pwsh");
        // Must not reimplement mvn test / flyway gates directly in YAML steps.
        assertFalse(yaml.contains("run: mvn test"),
                "Workflow must not reimplement Gate 1 (mvn test) in YAML; delegate to the script");
        assertFalse(yaml.contains("run: mvn flyway"),
                "Workflow must not reimplement Gate 2 (flyway) in YAML; delegate to the script");
    }

    @Test
    @DisplayName("Default job injects no fake PostgreSQL credentials")
    void defaultJobHasNoFakePgCredentials() {
        // The default (Testcontainers) job must not set PG_URL/PG_USERNAME/PG_PASSWORD.
        assertFalse(yaml.contains("PG_URL:"),
                "Default workflow job must not inject PG_URL (no fake external PG creds)");
        assertFalse(yaml.contains("PG_USERNAME:"),
                "Default workflow job must not inject PG_USERNAME (no fake external PG creds)");
        assertFalse(yaml.contains("PG_PASSWORD:"),
                "Default workflow job must not inject PG_PASSWORD (no fake external PG creds)");
        assertFalse(yaml.contains("-RequireExternalPg"),
                "Default workflow job must not pass -RequireExternalPg (Testcontainers is the default)");
    }

    @Test
    @DisplayName("Workflow runs on Ubuntu with Docker available for Testcontainers")
    void workflowRunsOnUbuntuWithDocker() {
        assertTrue(yaml.contains("runs-on: ubuntu-latest"),
                "Workflow must run on ubuntu-latest so Docker is available for Testcontainers");
        assertTrue(yaml.contains("pwsh"),
                "Workflow must invoke the script with pwsh (PowerShell Core on Ubuntu)");
    }

    @Test
    @DisplayName("Release report and surefire reports are uploaded on success and failure")
    void artifactsUploadedAlways() {
        // Both artifact uploads must use if: always() so diagnostics survive a gate failure.
        long alwaysCount = yaml.lines().filter(l -> l.contains("if: always()")).count();
        assertTrue(alwaysCount >= 3,
                "Workflow must use if: always() on both artifact uploads and the gate-decision step; found " + alwaysCount);
        assertTrue(yaml.contains("RELEASE_GATE_RESULT.md"),
                "Workflow must upload RELEASE_GATE_RESULT.md as an artifact");
        assertTrue(yaml.contains("target/surefire-reports/"),
                "Workflow must upload target/surefire-reports/ as an artifact");
    }

    @Test
    @DisplayName("Final gate decision fails when the canonical script exits non-zero")
    void finalDecisionFailsOnNonZeroExit() {
        // The gate step uses continue-on-error so artifacts still upload on failure,
        // but the final decision step must exit 1 when the gate outcome was not success.
        assertTrue(yaml.contains("continue-on-error: true"),
                "Gate step must use continue-on-error so artifacts upload on failure");
        assertTrue(yaml.contains("exit 1"),
                "Final gate-decision step must exit 1 when the canonical script failed");
    }

    @Test
    @DisplayName("Workflow uses platform-independent (forward-slash) script paths")
    void usesPlatformIndependentPaths() {
        // No Windows backslash paths in the invocation.
        assertFalse(yaml.contains("\\scripts\\"),
                "Workflow must use forward-slash platform-independent script paths");
    }
}
