package com.nocobase.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SurefireReportParser and related model classes.
 */
@DisplayName("Surefire Report Parser")
class SurefireReportParserTest {

    // ── XML Fixtures ──────────────────────────────────────────────────────

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
                <failure message="expected: true but was: false" type="org.opentest4j.AssertionFailedError">
                  org.opentest4j.AssertionFailedError: expected: true but was: false
                </failure>
              </testcase>
              <testcase name="testError" classname="com.example.MixedTest" time="0.300">
                <error message="Connection refused" type="java.net.ConnectException">
                  java.net.ConnectException: Connection refused
                </error>
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

    private static final String EMPTY_SUITE_XML = """
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

    // ── ParseStream Tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("parseStream")
    class ParseStreamTests {

        @Test
        @DisplayName("parses all-passing suite correctly")
        void shouldParseAllPassingSuite() throws Exception {
            InputStream is = new ByteArrayInputStream(ALL_PASSING_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "all-passing.xml");

            assertNotNull(report);
            assertEquals("com.example.FooTest", report.name());
            assertEquals(5, report.tests());
            assertEquals(0, report.failures());
            assertEquals(0, report.errors());
            assertEquals(0, report.skipped());
            assertEquals(3.456, report.timeSeconds(), 0.001);
            assertEquals(5, report.totalPassed());
            assertEquals(5, report.totalExecuted());
            assertTrue(report.allPassed());
            assertTrue(report.hasTests());
            assertEquals(5, report.testCases().size());

            // Verify all test cases are PASSED
            for (TestCaseResult tc : report.testCases()) {
                assertEquals(TestCaseResult.Status.PASSED, tc.status());
                assertNull(tc.message());
                assertNull(tc.type());
            }
        }

        @Test
        @DisplayName("parses mixed results (failures, errors, skipped, passed) correctly")
        void shouldParseMixedResults() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            assertNotNull(report);
            assertEquals(6, report.tests());
            assertEquals(1, report.failures());
            assertEquals(1, report.errors());
            assertEquals(2, report.skipped());
            assertEquals(2, report.totalPassed());
            assertEquals(4, report.totalExecuted()); // 6 - 2 skipped
            assertFalse(report.allPassed());

            // Count by status
            long passed = report.testCases().stream().filter(tc -> tc.status() == TestCaseResult.Status.PASSED).count();
            long failed = report.testCases().stream().filter(tc -> tc.status() == TestCaseResult.Status.FAILURE).count();
            long errors = report.testCases().stream().filter(tc -> tc.status() == TestCaseResult.Status.ERROR).count();
            long skipped = report.testCases().stream().filter(tc -> tc.status() == TestCaseResult.Status.SKIPPED).count();

            assertEquals(2, passed);
            assertEquals(1, failed);
            assertEquals(1, errors);
            assertEquals(2, skipped);
        }

        @Test
        @DisplayName("failure test case has correct message and type")
        void shouldCaptureFailureDetails() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            TestCaseResult failure = report.testCases().stream()
                    .filter(tc -> tc.name().equals("testFail"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(TestCaseResult.Status.FAILURE, failure.status());
            assertEquals("expected: true but was: false", failure.message());
            assertEquals("org.opentest4j.AssertionFailedError", failure.type());
            assertEquals("com.example.MixedTest", failure.className());
        }

        @Test
        @DisplayName("error test case has correct message and type")
        void shouldCaptureErrorDetails() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            TestCaseResult error = report.testCases().stream()
                    .filter(tc -> tc.name().equals("testError"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(TestCaseResult.Status.ERROR, error.status());
            assertEquals("Connection refused", error.message());
            assertEquals("java.net.ConnectException", error.type());
        }

        @Test
        @DisplayName("skipped test case has correct status")
        void shouldCaptureSkippedStatus() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            TestCaseResult skipped = report.testCases().stream()
                    .filter(tc -> tc.name().equals("testSkipped"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(TestCaseResult.Status.SKIPPED, skipped.status());
            assertEquals("Not ready yet", skipped.message());
        }

        @Test
        @DisplayName("skipped without message has null message")
        void shouldHandleSkippedWithoutMessage() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            TestCaseResult skipped2 = report.testCases().stream()
                    .filter(tc -> tc.name().equals("testSkipped2"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(TestCaseResult.Status.SKIPPED, skipped2.status());
            assertEquals("", skipped2.message());
        }

        @Test
        @DisplayName("handles empty suite (0 tests)")
        void shouldHandleEmptySuite() throws Exception {
            InputStream is = new ByteArrayInputStream(EMPTY_SUITE_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "empty.xml");

            assertEquals(0, report.tests());
            assertFalse(report.hasTests());
            assertFalse(report.allPassed()); // 0 tests, not >0
            assertTrue(report.testCases().isEmpty());
        }
    }

    // ── ParseFile Tests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseFile")
    class ParseFileTests {

        @Test
        @DisplayName("parses a real XML file on disk")
        void shouldParseFileFromDisk(@TempDir Path tempDir) throws Exception {
            Path xmlFile = tempDir.resolve("TEST-com.example.BarTest.xml");
            Files.writeString(xmlFile, ALL_PASSING_XML, StandardCharsets.UTF_8);

            SurefireReport report = SurefireReportParser.parseFile(xmlFile);

            assertEquals(5, report.tests());
            assertEquals(0, report.failures());
            assertTrue(report.allPassed());
        }
    }

    // ── ParseDirectory Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("parseDirectory")
    class ParseDirectoryTests {

        @Test
        @DisplayName("parses multiple XML files from a directory")
        void shouldParseMultipleFilesFromDirectory(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("TEST-com.example.A.xml"), ALL_PASSING_XML, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("TEST-com.example.B.xml"), MIXED_RESULTS_XML, StandardCharsets.UTF_8);

            SurefireReport report = SurefireReportParser.parseDirectory(tempDir);

            // 5 from A + 6 from B = 11
            assertEquals(11, report.tests());
            assertEquals(1, report.failures());
            assertEquals(1, report.errors());
            assertEquals(2, report.skipped());
            assertEquals(7, report.totalPassed()); // 5 from A + 2 from B
            assertFalse(report.allPassed());
            assertEquals(11, report.testCases().size());
        }

        @Test
        @DisplayName("returns empty report for non-existent directory")
        void shouldReturnEmptyForMissingDirectory() throws Exception {
            SurefireReport report = SurefireReportParser.parseDirectory(
                    Path.of("/nonexistent/surefire-reports"));

            assertEquals(0, report.tests());
            assertTrue(report.testCases().isEmpty());
        }

        @Test
        @DisplayName("returns empty report for empty directory")
        void shouldReturnEmptyForEmptyDirectory(@TempDir Path tempDir) throws Exception {
            SurefireReport report = SurefireReportParser.parseDirectory(tempDir);

            assertEquals(0, report.tests());
            assertTrue(report.testCases().isEmpty());
        }

        @Test
        @DisplayName("skips non-XML files in the directory")
        void shouldSkipNonXmlFiles(@TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("TEST-com.example.A.xml"), ALL_PASSING_XML, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("random.txt"), "not xml", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("ignored"), "nope", StandardCharsets.UTF_8);

            SurefireReport report = SurefireReportParser.parseDirectory(tempDir);

            assertEquals(5, report.tests()); // only from A.xml
            assertTrue(report.allPassed());
        }
    }

    // ── SurefireReport.merge Tests ─────────────────────────────────────────

    @Nested
    @DisplayName("SurefireReport.merge")
    class MergeTests {

        @Test
        @DisplayName("merges multiple reports correctly")
        void shouldMergeReports() throws Exception {
            InputStream is1 = new ByteArrayInputStream(ALL_PASSING_XML.getBytes(StandardCharsets.UTF_8));
            InputStream is2 = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));

            SurefireReport r1 = SurefireReportParser.parseStream(is1, "a.xml");
            SurefireReport r2 = SurefireReportParser.parseStream(is2, "b.xml");

            SurefireReport merged = SurefireReport.merge(List.of(r1, r2));

            assertEquals(11, merged.tests());
            assertEquals(1, merged.failures());
            assertEquals(1, merged.errors());
            assertEquals(2, merged.skipped());
            assertEquals(7, merged.totalPassed());
            assertEquals("aggregated", merged.name());
        }

        @Test
        @DisplayName("merging empty list returns empty report")
        void shouldReturnEmptyForEmptyList() {
            SurefireReport merged = SurefireReport.merge(List.of());
            assertEquals(0, merged.tests());
            assertFalse(merged.hasTests());
        }

        @Test
        @DisplayName("merging null returns empty report")
        void shouldReturnEmptyForNull() {
            SurefireReport merged = SurefireReport.merge(null);
            assertEquals(0, merged.tests());
        }

        @Test
        @DisplayName("merging single report returns it unchanged")
        void shouldReturnSingleReportUnchanged() throws Exception {
            InputStream is = new ByteArrayInputStream(ALL_PASSING_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport r1 = SurefireReportParser.parseStream(is, "a.xml");

            SurefireReport merged = SurefireReport.merge(List.of(r1));

            assertEquals(r1, merged);
            assertEquals(5, merged.tests());
        }
    }

    // ── SurefireReport Validation Tests ────────────────────────────────────

    @Nested
    @DisplayName("SurefireReport validation")
    class ValidationTests {

        @Test
        @DisplayName("allPassed is true when tests > 0, failures = 0, errors = 0")
        void shouldPassWhenAllTestsPass() throws Exception {
            InputStream is = new ByteArrayInputStream(ALL_PASSING_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "all.xml");

            assertTrue(report.allPassed());
        }

        @Test
        @DisplayName("allPassed is false when tests = 0")
        void shouldFailWhenZeroTests() throws Exception {
            InputStream is = new ByteArrayInputStream(EMPTY_SUITE_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "empty.xml");

            assertFalse(report.allPassed());
            assertFalse(report.hasTests());
        }

        @Test
        @DisplayName("allPassed is false when failures > 0")
        void shouldFailWhenFailuresExist() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            assertFalse(report.allPassed());
            assertTrue(report.hasTests());
        }

        @Test
        @DisplayName("totalExecuted excludes skipped tests")
        void shouldExcludeSkippedFromExecuted() throws Exception {
            InputStream is = new ByteArrayInputStream(MIXED_RESULTS_XML.getBytes(StandardCharsets.UTF_8));
            SurefireReport report = SurefireReportParser.parseStream(is, "mixed.xml");

            assertEquals(4, report.totalExecuted()); // 6 tests - 2 skipped
            assertEquals(2, report.totalPassed());   // 6 - 2 skipped - 1 fail - 1 error
        }
    }

    // ── TestCaseResult Tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("TestCaseResult")
    class TestCaseResultTests {

        @Test
        @DisplayName("isPassed returns true for PASSED status")
        void shouldReturnTrueForPassed() {
            TestCaseResult tc = new TestCaseResult("C", "m", TestCaseResult.Status.PASSED, 0.1, null, null);
            assertTrue(tc.isPassed());
        }

        @Test
        @DisplayName("isPassed returns false for FAILURE status")
        void shouldReturnFalseForFailure() {
            TestCaseResult tc = new TestCaseResult("C", "m", TestCaseResult.Status.FAILURE, 0.1, "msg", "Type");
            assertFalse(tc.isPassed());
        }

        @Test
        @DisplayName("isPassed returns false for ERROR status")
        void shouldReturnFalseForError() {
            TestCaseResult tc = new TestCaseResult("C", "m", TestCaseResult.Status.ERROR, 0.1, "msg", "Type");
            assertFalse(tc.isPassed());
        }

        @Test
        @DisplayName("isPassed returns false for SKIPPED status")
        void shouldReturnFalseForSkipped() {
            TestCaseResult tc = new TestCaseResult("C", "m", TestCaseResult.Status.SKIPPED, 0.0, "reason", null);
            assertFalse(tc.isPassed());
        }

        @Test
        @DisplayName("toString produces readable output")
        void shouldProduceReadableToString() {
            TestCaseResult tc = new TestCaseResult("com.example.FooTest", "testOne",
                    TestCaseResult.Status.PASSED, 0.123, null, null);
            String str = tc.toString();
            assertTrue(str.contains("com.example.FooTest.testOne"));
            assertTrue(str.contains("PASSED"));
            assertTrue(str.contains("0.123"));
        }
    }
}