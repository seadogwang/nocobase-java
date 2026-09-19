package com.nocobase.release;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architecture-level regression tests for the release gate script.
 *
 * <p>These tests read {@code scripts/release-gate.ps1} from the project
 * root and verify structural invariants. They do not execute the script
 * and are deterministic — no Docker or external services required.
 */
class ReleaseGateScriptTest {

    private String scriptContent;

    @BeforeEach
    void loadScript() throws IOException {
        // Read the actual script from the project root
        Path scriptPath = Path.of("scripts/release-gate.ps1");
        assertTrue(Files.isRegularFile(scriptPath),
                "Release gate script must exist at scripts/release-gate.ps1");
        scriptContent = Files.readString(scriptPath);
    }

    @Test
    @DisplayName("Release script invokes ReleaseGateVerifier for PG report validation")
    void scriptInvokesReleaseGateVerifier() {
        assertTrue(scriptContent.contains("ReleaseGateVerifier"),
                "Release script must invoke ReleaseGateVerifier for PG report validation");

        // The verifier must be invoked with the 'verify' subcommand
        assertTrue(scriptContent.contains("verify"),
                "Release script must use ReleaseGateVerifier verify subcommand");
    }

    @Test
    @DisplayName("Release script supports -RequireExternalPg parameter")
    void scriptSupportsRequireExternalPg() {
        assertTrue(scriptContent.contains("RequireExternalPg"),
                "Release script must define -RequireExternalPg parameter");
        assertTrue(scriptContent.contains("postgresql.external.pg"),
                "Release script must pass external PG flag to Maven");
    }

    @Test
    @DisplayName("Default mode does not require PG environment variables")
    void defaultModeDoesNotRequirePgEnvVars() {
        // PG env var checks must only be inside the $RequireExternalPg branch
        int requirePgIdx = scriptContent.indexOf("RequireExternalPg");
        int pgUrlCheckIdx = scriptContent.indexOf("PG_URL");

        // The PG_URL check should appear after RequireExternalPg is introduced
        assertTrue(pgUrlCheckIdx > requirePgIdx || pgUrlCheckIdx < 0,
                "PG_URL check must be conditional on -RequireExternalPg flag");
    }

    @Test
    @DisplayName("Release script generates RELEASE_GATE_RESULT.md report")
    void scriptGeneratesReleaseGateReport() {
        boolean generatesReport = scriptContent.contains("RELEASE_GATE_RESULT.md")
                || scriptContent.contains("$ReportPath");
        assertTrue(generatesReport,
                "Release script must generate a release gate report file");

        assertTrue(scriptContent.contains("Out-File"),
                "Release script must write the report to a file");
    }

    @Test
    @DisplayName("Release script uses Join-Path for cross-platform path construction")
    void scriptUsesJoinPathForPaths() {
        assertTrue(scriptContent.contains("Join-Path"),
                "Release script must use Join-Path for cross-platform path construction");

        // Must not use hardcoded backslashes in paths
        assertFalse(scriptContent.contains("\"$ProjectRoot\\target"),
                "Release script must not use hardcoded Windows backslash paths");
    }

    @Test
    @DisplayName("Release script exits non-zero on gate failure")
    void scriptExitsNonZeroOnGateFailure() {
        assertTrue(scriptContent.contains("exit 1"),
                "Release script must exit with code 1 on gate failure");
        assertTrue(scriptContent.contains("exit 0"),
                "Release script must exit with code 0 on all gates passed");
    }

    @Test
    @DisplayName("Release script supports -MavenArgs pattern (not PowerShell automatic Args)")
    void scriptUsesMavenArgsNotAutomaticArgs() {
        // The script must accept -MavenArgs explicitly, not rely on PowerShell $Args
        // (implicit from param block — param() defines named parameters, not $Args)
        assertTrue(scriptContent.contains("MavenArgs"),
                "Release script must use -MavenArgs (explicit parameter) for Maven command building");
    }

    @Test
    @DisplayName("External PG mode checks each credential independently (partial config fails)")
    void externalPgModeChecksEachCredential() {
        // In -RequireExternalPg mode the script must check PG_URL, PG_USERNAME,
        // and PG_PASSWORD independently and fail Gate 3 if ANY is missing
        // (partial configuration must not silently fall back).
        int reqIdx = scriptContent.indexOf("RequireExternalPg");
        assertTrue(reqIdx >= 0, "script must define -RequireExternalPg");
        // All three credential checks must appear after the RequireExternalPg branch.
        for (String v : new String[]{"PG_URL", "PG_USERNAME", "PG_PASSWORD"}) {
            int idx = scriptContent.indexOf(v, reqIdx);
            assertTrue(idx > reqIdx,
                    "script must check " + v + " inside the -RequireExternalPg branch");
        }
        // A missing credential must force a non-zero Gate 3 outcome.
        assertTrue(scriptContent.contains("pgEnvMissing"),
                "script must collect missing PG credentials into a list for partial-config reporting");
    }

    @Test
    @DisplayName("postgresql-acceptance profile does not silently exclude the PG acceptance test")
    void postgresqlAcceptanceProfileIncludesPgTest() throws Exception {
        // The pom profile must include PostgreSqlIntegrationTest (not silently
        // exclude all acceptance tests). Read pom.xml from the project root.
        java.nio.file.Path pomPath = java.nio.file.Path.of("pom.xml");
        assertTrue(java.nio.file.Files.isRegularFile(pomPath), "pom.xml must exist");
        String pom = java.nio.file.Files.readString(pomPath);

        int profileIdx = pom.indexOf("postgresql-acceptance");
        assertTrue(profileIdx >= 0, "pom must define the postgresql-acceptance profile");
        // The profile's surefire <includes> must reference PostgreSqlIntegrationTest.
        String profileBlock = pom.substring(profileIdx);
        assertTrue(profileBlock.contains("PostgreSqlIntegrationTest"),
                "postgresql-acceptance profile must include PostgreSqlIntegrationTest");
        assertTrue(profileBlock.contains("<exclude>NONE</exclude>")
                        || profileBlock.contains("<excludes><exclude>NONE</exclude>"),
                "postgresql-acceptance profile must override the global PG exclude so the test runs");
    }
}