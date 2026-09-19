package com.nocobase.release;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReleaseGateVerifier CLI.
 *
 * <p>Since the verifier calls System.exit(), each test runs in a sub-process
 * by invoking the main method via reflection and catching the exit.
 */
@DisplayName("ReleaseGateVerifier")
class ReleaseGateVerifierTest {

    // ── Test XML fixtures ───────────────────────────────────────────────────

    private static final String ALL_PASSING_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd"
                       version="3.0"
                       name="com.example.FooTest"
                       time="3.456"
                       tests="5"
                       errors="0"
                       skipped="0"
                       failures="0">
              <testcase name="testOne" classname="com.example.FooTest" time="0.101"/>
              <testcase name="testTwo" classname="com.example.FooTest" time="0.202"/>
              <testcase name="testThree" classname="com.example.FooTest" time="0.303"/>
              <testcase name="testFour" classname="com.example.FooTest" time="0.404"/>
              <testcase name="testFive" classname="com.example.FooTest" time="0.505"/>
            </testsuite>
            """;

    private static final String MIXED_RESULTS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd"
                       version="3.0"
                       name="com.example.MixedTest"
                       time="2.500"
                       tests="6"
                       errors="1"
                       skipped="2"
                       failures="1">
              <testcase name="testPass" classname="com.example.MixedTest" time="0.100"/>
              <testcase name="testFail" classname="com.example.MixedTest" time="0.200">
                <failure message="expected: true but was: false" type="org.opentest4j.AssertionFailedError"/>
              </testcase>
              <testcase name="testError" classname="com.example.MixedTest" time="0.300">
                <error message="Connection refused" type="java.net.ConnectException"/>
              </testcase>
              <testcase name="testSkipped" classname="com.example.MixedTest" time="0.001">
                <skipped message="Not ready yet"/>
              </testcase>
              <testcase name="testSkipped2" classname="com.example.MixedTest" time="0.000">
                <skipped/>
              </testcase>
              <testcase name="testPass2" classname="com.example.MixedTest" time="0.400"/>
            </testsuite>
            """;

    private static final String ZERO_TESTS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd"
                       version="3.0"
                       name="com.example.EmptyTest"
                       time="0.001"
                       tests="0"
                       errors="0"
                       skipped="0"
                       failures="0">
            </testsuite>
            """;

    // ── Exit-trap infrastructure ────────────────────────────────────────────

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final SecurityManager originalSecurityManager = System.getSecurityManager();

    @BeforeEach
    void setUpStreams() {
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.setSecurityManager(originalSecurityManager);
    }

    /**
     * Runs the verifier's main method and captures the exit code.
     * Uses a SecurityManager to intercept System.exit() calls.
     */
    private int runVerifier(String... args) {
        SecurityManager prevSm = System.getSecurityManager();
        try {
            System.setSecurityManager(new NoExitSecurityManager());
            ReleaseGateVerifier.main(args);
            return 0; // main returned normally
        } catch (ExitException e) {
            return e.getExitCode();
        } finally {
            System.setSecurityManager(prevSm);
        }
    }

    /**
     * Returns the captured stdout content.
     */
    private String stdout() {
        System.out.flush();
        return outContent.toString(StandardCharsets.UTF_8);
    }

    /**
     * Returns the captured stderr content.
     */
    private String stderr() {
        System.err.flush();
        return errContent.toString(StandardCharsets.UTF_8);
    }

    // ── Verify subcommand tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("verify subcommand")
    class VerifyTests {

        @Test
        @DisplayName("passes when expected file exists with all-passing results")
        void shouldPassForAllPassingReport(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.FooTest.xml");
            Files.writeString(xmlFile, ALL_PASSING_XML, StandardCharsets.UTF_8);

            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.FooTest.xml",
                    "--gate-name", "TestGate");

            String output = stdout();
            assertEquals(0, exit, "Should exit 0 for all-passing report. Output:\n" + output);
            assertTrue(output.contains("RESULT|PASS"), "Should contain PASS result");
            assertTrue(output.contains("tests-count-sufficient|5"), "Should report 5 tests");
            assertTrue(output.contains("no-failures"), "Should report no failures");
            assertTrue(output.contains("no-errors"), "Should report no errors");
            assertTrue(output.contains("no-skipped"), "Should report no skipped");
        }

        @Test
        @DisplayName("fails when expected file is missing")
        void shouldFailWhenExpectedFileMissing(@TempDir Path tempDir) {
            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-nonexistent.xml",
                    "--gate-name", "TestGate");

            String output = stdout();
            assertEquals(1, exit, "Should exit 1 for missing file. Output:\n" + output);
            assertTrue(output.contains("expected-file-missing"), "Should report missing file");
        }

        @Test
        @DisplayName("fails when reports directory is missing")
        void shouldFailWhenReportsDirMissing() {
            int exit = runVerifier("verify",
                    "--reports-dir", "/nonexistent/path/xyz",
                    "--expected-file", "TEST-anything.xml",
                    "--gate-name", "TestGate");

            String output = stdout();
            assertEquals(1, exit, "Should exit 1 for missing directory. Output:\n" + output);
            assertTrue(output.contains("reports-dir-missing"), "Should report missing directory");
        }

        @Test
        @DisplayName("fails when tests = 0")
        void shouldFailWhenZeroTests(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.EmptyTest.xml");
            Files.writeString(xmlFile, ZERO_TESTS_XML, StandardCharsets.UTF_8);

            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.EmptyTest.xml",
                    "--gate-name", "TestGate");

            String output = stdout();
            assertEquals(1, exit, "Should exit 1 for zero tests. Output:\n" + output);
            assertTrue(output.contains("insufficient-tests"), "Should report insufficient tests");
        }

        @Test
        @DisplayName("fails when failures > 0")
        void shouldFailWhenFailuresExist(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.Mixed.xml");
            Files.writeString(xmlFile, MIXED_RESULTS_XML, StandardCharsets.UTF_8);

            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.Mixed.xml",
                    "--gate-name", "TestGate");

            String output = stdout();
            assertEquals(1, exit, "Should exit 1 for failures. Output:\n" + output);
            assertTrue(output.contains("failures-detected"), "Should report failures detected");
            assertTrue(output.contains("errors-detected"), "Should report errors detected");
            assertTrue(output.contains("tests-skipped"), "Should report skipped tests");
        }

        @Test
        @DisplayName("respects --min-tests option")
        void shouldRespectMinTests(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.FooTest.xml");
            Files.writeString(xmlFile, ALL_PASSING_XML, StandardCharsets.UTF_8);

            // With min-tests=3, 5 tests should pass
            int exit1 = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.FooTest.xml",
                    "--gate-name", "TestGate",
                    "--min-tests", "3");
            assertEquals(0, exit1, "Should pass with min-tests=3 (5 actual)");

            // With min-tests=10, 5 tests should fail
            int exit2 = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.FooTest.xml",
                    "--gate-name", "TestGate",
                    "--min-tests", "10");
            assertEquals(1, exit2, "Should fail with min-tests=10 (5 actual)");
            assertTrue(stdout().contains("insufficient-tests"), "Should report insufficient");
        }

        @Test
        @DisplayName("output contains all file names found when expected file missing")
        void shouldListFoundFilesWhenExpectedMissing(@TempDir Path tempDir) throws Exception {
            // Create two test files
            Files.writeString(tempDir.resolve("TEST-A.xml"), ALL_PASSING_XML, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("TEST-B.xml"), ALL_PASSING_XML, StandardCharsets.UTF_8);

            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-nonexistent.xml",
                    "--gate-name", "TestGate");

            assertEquals(1, exit);
            String output = stdout();
            assertTrue(output.contains("TEST-A.xml") || output.contains("TEST-A") || output.contains("files-found"),
                    "Should list found files. Output:\n" + output);
        }
    }

    // ── List subcommand tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("list subcommand")
    class ListTests {

        @Test
        @DisplayName("lists all TEST-*.xml files with counts")
        void shouldListAllTestFiles(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("TEST-A.xml"), ALL_PASSING_XML, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("TEST-B.xml"), MIXED_RESULTS_XML, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("random.txt"), "not xml", StandardCharsets.UTF_8);

            int exit = runVerifier("list", "--reports-dir", tempDir.toString());

            String output = stdout();
            assertEquals(0, exit);
            assertTrue(output.contains("LIST|2|files-found"), "Should list 2 files. Output:\n" + output);
            assertTrue(output.contains("TEST-A.xml"), "Should include TEST-A.xml");
            assertTrue(output.contains("TEST-B.xml"), "Should include TEST-B.xml");
            assertTrue(output.contains("|5|0|0|0|"), "Should show TEST-A stats (5,0,0,0)");
            assertTrue(output.contains("|6|1|1|2|"), "Should show TEST-B stats (6,1,1,2)");
        }

        @Test
        @DisplayName("handles empty directory gracefully")
        void shouldHandleEmptyDirectory(@TempDir Path tempDir) {
            int exit = runVerifier("list", "--reports-dir", tempDir.toString());

            String output = stdout();
            assertEquals(0, exit);
            assertTrue(output.contains("LIST|0|"), "Should report 0 files. Output:\n" + output);
        }

        @Test
        @DisplayName("handles missing directory gracefully")
        void shouldHandleMissingDirectory() {
            int exit = runVerifier("list", "--reports-dir", "/nonexistent/path");

            String output = stdout();
            assertEquals(0, exit);
            assertTrue(output.contains("reports-dir-not-found"), "Should report dir not found. Output:\n" + output);
        }
    }

    // ── Usage / help tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("usage and help")
    class UsageTests {

        @Test
        @DisplayName("--help prints usage and exits 0")
        void shouldPrintHelp() {
            int exit = runVerifier("--help");
            assertEquals(0, exit);
            String output = stdout();
            assertTrue(output.contains("Usage:"), "Should print usage. Output:\n" + output);
            assertTrue(output.contains("verify"), "Should mention verify subcommand");
            assertTrue(output.contains("list"), "Should mention list subcommand");
        }

        @Test
        @DisplayName("-h prints usage and exits 0")
        void shouldPrintHelpShort() {
            int exit = runVerifier("-h");
            assertEquals(0, exit);
            assertTrue(stdout().contains("Usage:"));
        }

        @Test
        @DisplayName("no arguments prints usage and exits 2")
        void shouldPrintUsageForNoArgs() {
            int exit = runVerifier();
            assertEquals(2, exit);
            assertTrue(stdout().contains("Usage:"));
        }

        @Test
        @DisplayName("unknown subcommand exits 2")
        void shouldFailForUnknownSubcommand() {
            int exit = runVerifier("unknown");
            assertEquals(2, exit);
            assertTrue(stderr().contains("Unknown subcommand"));
        }

        @Test
        @DisplayName("verify without required args exits 2")
        void shouldFailForMissingArgs() {
            int exit = runVerifier("verify");
            assertEquals(2, exit);
            assertTrue(stderr().contains("required"));
        }
    }

    // ── Encoding tests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("encoding")
    class EncodingTests {

        @Test
        @DisplayName("output is ASCII-clean (no special Unicode characters)")
        void shouldOutputAsciiClean(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.FooTest.xml");
            Files.writeString(xmlFile, ALL_PASSING_XML, StandardCharsets.UTF_8);

            int exit = runVerifier("verify",
                    "--reports-dir", tempDir.toString(),
                    "--expected-file", "TEST-com.example.FooTest.xml",
                    "--gate-name", "Gate 3");

            assertEquals(0, exit);
            String output = stdout();
            // Verify all characters are ASCII
            for (int i = 0; i < output.length(); i++) {
                char c = output.charAt(i);
                assertTrue(c < 128, "Non-ASCII character found at position " + i + ": U+" + Integer.toHexString(c));
            }
        }
    }

    // ── verify-report subcommand tests ─────────────────────────────────────

    private static final String PG_29_PASS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite version="3.0" name="com.nocobase.postgresql.PostgreSqlIntegrationTest"
                       time="8.0" tests="29" errors="0" skipped="0" failures="0">
              <testcase name="t1" classname="com.nocobase.postgresql.PostgreSqlIntegrationTest" time="0.1"/>
            </testsuite>
            """;

    private static final String PG_25_PASS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite version="3.0" name="com.nocobase.postgresql.PostgreSqlIntegrationTest"
                       time="7.0" tests="25" errors="0" skipped="0" failures="0">
              <testcase name="t1" classname="com.nocobase.postgresql.PostgreSqlIntegrationTest" time="0.1"/>
            </testsuite>
            """;

    /** A consistent report with the given commit + Gate 3 counts. */
    private static String consistentReport(String commit, int tests, int failures, int errors, int skipped) {
        return """
                # Release Gate Result

                **Generated:** 2026-09-19T16:43:40+08:00
                **Project:** nocobase-java (NocoBase Java Backend)
                **Branch/Commit:** %s
                **PostgreSQL mode:** External PostgreSQL (no JDBC URL or credentials recorded)

                ## Gate Summary

                | Gate | Command | Exit Code | Duration | Tests | Failures | Errors | Skipped | Verifier | Result |
                |------|---------|-----------|----------|-------|----------|--------|---------|----------|--------|
                | 1 | `mvn test` | 0 | 10s | 100 | 0 | 0 | 0 | -- | PASS |
                | 2 | `mvn flyway:validate` | 0 | 1s | -- | -- | -- | -- | -- | PASS |
                | 3 | mvn test -Ppostgresql-acceptance | 0 | 2s | %d | %d | %d | %d | PASS | PASS |
                | 4 | Sensitive code scan | 0 | 1s | -- | -- | -- | -- | -- | PASS |

                **Overall Result:** **ALL GATES PASSED** - Release can proceed.

                ## Gate 3: PostgreSQL Acceptance Tests

                - **PG Report File:** TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml (found: YES, tests=%d, failures=%d, errors=%d, skipped=%d)
                """.formatted(commit, tests, failures, errors, skipped, tests, failures, errors, skipped);
    }

    @Nested
    @DisplayName("verify-report subcommand")
    class VerifyReportTests {

        @Test
        @DisplayName("passes for a freshly-generated consistent report")
        void passesForConsistentReport(@TempDir Path tempDir) throws Exception {
            Path xml = tempDir.resolve("TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml");
            Files.writeString(xml, PG_29_PASS_XML, StandardCharsets.UTF_8);
            Path report = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(report, consistentReport("abc1234", 29, 0, 0, 0), StandardCharsets.UTF_8);

            int exit = runVerifier("verify-report",
                    "--report", report.toString(),
                    "--reports-dir", tempDir.toString(),
                    "--git-head", "abc1234");

            String out = stdout();
            assertEquals(0, exit, "Should exit 0 for consistent report. Output:\n" + out);
            assertTrue(out.contains("VERIFY-REPORT|RESULT|PASS"), out);
            assertTrue(out.contains("gate3-counts-match"), out);
            assertTrue(out.contains("commit-matches"), out);
        }

        @Test
        @DisplayName("rejects a placeholder report")
        void rejectsPlaceholder(@TempDir Path tempDir) throws Exception {
            Path report = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(report, "**Generated:** NOT YET GENERATED (stale copy removed)\n", StandardCharsets.UTF_8);

            int exit = runVerifier("verify-report", "--report", report.toString(), "--git-head", "abc1234");
            String out = stdout();
            assertEquals(1, exit, out);
            assertTrue(out.contains("placeholder"), out);
            assertTrue(out.contains("RESULT|FAIL"), out);
        }

        @Test
        @DisplayName("rejects a report whose commit does not match HEAD")
        void rejectsCommitMismatch(@TempDir Path tempDir) throws Exception {
            Path xml = tempDir.resolve("TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml");
            Files.writeString(xml, PG_29_PASS_XML, StandardCharsets.UTF_8);
            Path report = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(report, consistentReport("old0000", 29, 0, 0, 0), StandardCharsets.UTF_8);

            int exit = runVerifier("verify-report",
                    "--report", report.toString(),
                    "--reports-dir", tempDir.toString(),
                    "--git-head", "new1234");
            String out = stdout();
            assertEquals(1, exit, out);
            assertTrue(out.contains("commit-mismatch"), out);
        }

        @Test
        @DisplayName("rejects a report whose Gate 3 counts do not match the surefire XML")
        void rejectsCountMismatch(@TempDir Path tempDir) throws Exception {
            Path xml = tempDir.resolve("TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml");
            Files.writeString(xml, PG_25_PASS_XML, StandardCharsets.UTF_8);   // actual 25
            Path report = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(report, consistentReport("abc1234", 29, 0, 0, 0), StandardCharsets.UTF_8); // claims 29

            int exit = runVerifier("verify-report",
                    "--report", report.toString(),
                    "--reports-dir", tempDir.toString(),
                    "--git-head", "abc1234");
            String out = stdout();
            assertEquals(1, exit, out);
            assertTrue(out.contains("gate3-counts-mismatch"), out);
        }

        @Test
        @DisplayName("rejects ALL GATES PASSED when a gate row is FAIL")
        void rejectsInconsistentOverall(@TempDir Path tempDir) throws Exception {
            Path xml = tempDir.resolve("TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml");
            Files.writeString(xml, PG_29_PASS_XML, StandardCharsets.UTF_8);
            // Gate 4 row says FAIL but overall claims ALL GATES PASSED.
            String report = consistentReport("abc1234", 29, 0, 0, 0)
                    .replace("| 4 | Sensitive code scan | 0 | 1s | -- | -- | -- | -- | -- | PASS |",
                            "| 4 | Sensitive code scan | 1 | 1s | -- | -- | -- | -- | -- | FAIL |");
            Path reportPath = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(reportPath, report, StandardCharsets.UTF_8);

            int exit = runVerifier("verify-report",
                    "--report", reportPath.toString(),
                    "--reports-dir", tempDir.toString(),
                    "--git-head", "abc1234");
            String out = stdout();
            assertEquals(1, exit, out);
            assertTrue(out.contains("inconsistent-overall"), out);
        }

        @Test
        @DisplayName("rejects a report missing the PostgreSQL mode line")
        void rejectsMissingMode(@TempDir Path tempDir) throws Exception {
            Path xml = tempDir.resolve("TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml");
            Files.writeString(xml, PG_29_PASS_XML, StandardCharsets.UTF_8);
            Path report = tempDir.resolve("RELEASE_GATE_RESULT.md");
            Files.writeString(report, consistentReport("abc1234", 29, 0, 0, 0)
                    .replaceFirst("\\*\\*PostgreSQL mode:\\*\\*.*\\n", ""), StandardCharsets.UTF_8);

            int exit = runVerifier("verify-report",
                    "--report", report.toString(),
                    "--reports-dir", tempDir.toString(),
                    "--git-head", "abc1234");
            String out = stdout();
            assertEquals(1, exit, out);
            assertTrue(out.contains("pg-mode-missing"), out);
        }
    }

    // ── SecurityManager hack to intercept System.exit() ─────────────────────

    @SuppressWarnings("removal")
    private static class NoExitSecurityManager extends SecurityManager {
        @Override
        public void checkPermission(Permission perm) {
            // allow everything
        }

        @Override
        public void checkPermission(Permission perm, Object context) {
            // allow everything
        }

        @Override
        public void checkExit(int status) {
            throw new ExitException(status);
        }
    }

    private static class ExitException extends SecurityException {
        private final int exitCode;

        ExitException(int exitCode) {
            super("System.exit(" + exitCode + ") called");
            this.exitCode = exitCode;
        }

        int getExitCode() {
            return exitCode;
        }
    }
}