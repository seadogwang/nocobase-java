package com.nocobase.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CLI-based release gate verifier for surefire test reports.
 *
 * <p>Intended to be invoked from release-gate.ps1 for robust, unit-testable
 * verification of gate conditions. All output is ASCII-only to ensure
 * cross-platform encoding compatibility.
 *
 * <h3>Usage</h3>
 * <pre>
 * # Named argument syntax (full control):
 * java -cp target/classes com.nocobase.release.ReleaseGateVerifier \
 *   verify --reports-dir target/surefire-reports \
 *   --expected-file TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml \
 *   --gate-name "Gate 3"
 *
 * # Positional argument syntax (simple path):
 * java -cp target/classes com.nocobase.release.ReleaseGateVerifier \
 *   verify target/surefire-reports/TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 -- gate passed (all conditions met)</li>
 *   <li>1 -- gate failed (one or more conditions not met)</li>
 *   <li>2 -- usage error (invalid arguments)</li>
 * </ul>
 */
public final class ReleaseGateVerifier {

    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_USAGE = 2;

    private ReleaseGateVerifier() {
        // utility class
    }

    /**
     * Entry point: parses arguments and dispatches to the appropriate subcommand.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(EXIT_USAGE);
            return;
        }

        String subcommand = args[0];
        switch (subcommand) {
            case "verify":
                handleVerify(args);
                break;
            case "verify-report":
                handleVerifyReport(args);
                break;
            case "list":
                handleList(args);
                break;
            case "--help":
            case "-h":
                printUsage();
                System.exit(0);
                break;
            default:
                System.err.println("ERROR: Unknown subcommand: " + subcommand);
                printUsage();
                System.exit(EXIT_USAGE);
        }
    }

    /**
     * Handles the "verify" subcommand.
     *
     * <p>Verifies that a specific TEST-*.xml file exists in the reports directory
     * and meets the gate criteria: tests &gt; 0, failures = 0, errors = 0, skipped = 0.
     *
     * <p>Arguments:
     * <ul>
     *   <li>--reports-dir PATH   : path to surefire reports directory (required)</li>
     *   <li>--expected-file NAME : expected TEST-*.xml file name (required)</li>
     *   <li>--gate-name NAME     : human-readable gate name for output (optional)</li>
     *   <li>--min-tests N        : minimum number of tests required (default: 1)</li>
     * </ul>
     */
    private static void handleVerify(String[] args) {
        String reportsDir = null;
        String expectedFile = null;
        String gateName = "Gate";
        int minTests = 1;

        // Parse arguments
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--reports-dir":
                    if (i + 1 < args.length) reportsDir = args[++i];
                    break;
                case "--expected-file":
                    if (i + 1 < args.length) expectedFile = args[++i];
                    break;
                case "--gate-name":
                    if (i + 1 < args.length) gateName = args[++i];
                    break;
                case "--min-tests":
                    if (i + 1 < args.length) {
                        try {
                            minTests = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("ERROR: --min-tests must be an integer");
                            System.exit(EXIT_USAGE);
                            return;
                        }
                    }
                    break;
                default:
                    // Positional argument: treat as the full path to the report file
                    if (!args[i].startsWith("-")) {
                        Path filePath = Paths.get(args[i]);
                        expectedFile = filePath.getFileName().toString();
                        Path parent = filePath.getParent();
                        reportsDir = (parent != null) ? parent.toString() : ".";
                    } else {
                        System.err.println("ERROR: Unknown option: " + args[i]);
                        System.exit(EXIT_USAGE);
                        return;
                    }
                    break;
            }
        }

        if (reportsDir == null || expectedFile == null) {
            System.err.println("ERROR: --reports-dir and --expected-file are required");
            printUsage();
            System.exit(EXIT_USAGE);
            return;
        }

        Path reportsPath = Paths.get(reportsDir);
        Path expectedFilePath = reportsPath.resolve(expectedFile);
        boolean passed = true;

        // Check 1: reports directory exists
        System.out.println("VERIFY|" + gateName + "|check|reports-dir|" + reportsPath.toAbsolutePath());
        if (!Files.isDirectory(reportsPath)) {
            System.out.println("VERIFY|" + gateName + "|FAIL|reports-dir-missing|Reports directory not found: " + reportsPath.toAbsolutePath());
            System.exit(EXIT_FAIL);
            return;
        }
        System.out.println("VERIFY|" + gateName + "|PASS|reports-dir-exists");

        // Check 2: expected file exists
        System.out.println("VERIFY|" + gateName + "|check|expected-file|" + expectedFile);
        if (!Files.isRegularFile(expectedFilePath)) {
            System.out.println("VERIFY|" + gateName + "|FAIL|expected-file-missing|Expected report file not found: " + expectedFile);

            // List what files are actually there
            try (Stream<Path> files = Files.list(reportsPath)) {
                String found = files
                        .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.joining(", "));
                if (!found.isEmpty()) {
                    System.out.println("VERIFY|" + gateName + "|INFO|files-found|" + found);
                } else {
                    System.out.println("VERIFY|" + gateName + "|INFO|no-files-found|No TEST-*.xml files found");
                }
            } catch (IOException ignored) {
                // ignore
            }
            System.exit(EXIT_FAIL);
            return;
        }
        System.out.println("VERIFY|" + gateName + "|PASS|expected-file-exists");

        // Check 3: parse the report and verify conditions
        System.out.println("VERIFY|" + gateName + "|check|parsing-report");
        try {
            SurefireReport report = SurefireReportParser.parseFile(expectedFilePath);

            // Check 3a: tests > 0
            System.out.println("VERIFY|" + gateName + "|check|tests-count|" + report.tests());
            if (report.tests() < minTests) {
                System.out.println("VERIFY|" + gateName + "|FAIL|insufficient-tests|Expected at least " + minTests + " test(s), found " + report.tests());
                passed = false;
            } else {
                System.out.println("VERIFY|" + gateName + "|PASS|tests-count-sufficient|" + report.tests());
            }

            // Check 3b: failures = 0
            System.out.println("VERIFY|" + gateName + "|check|failures|" + report.failures());
            if (report.failures() > 0) {
                System.out.println("VERIFY|" + gateName + "|FAIL|failures-detected|" + report.failures() + " test failure(s)");
                passed = false;
            } else {
                System.out.println("VERIFY|" + gateName + "|PASS|no-failures");
            }

            // Check 3c: errors = 0
            System.out.println("VERIFY|" + gateName + "|check|errors|" + report.errors());
            if (report.errors() > 0) {
                System.out.println("VERIFY|" + gateName + "|FAIL|errors-detected|" + report.errors() + " test error(s)");
                passed = false;
            } else {
                System.out.println("VERIFY|" + gateName + "|PASS|no-errors");
            }

            // Check 3d: skipped = 0
            System.out.println("VERIFY|" + gateName + "|check|skipped|" + report.skipped());
            if (report.skipped() > 0) {
                System.out.println("VERIFY|" + gateName + "|FAIL|tests-skipped|" + report.skipped() + " test(s) skipped");
                passed = false;
            } else {
                System.out.println("VERIFY|" + gateName + "|PASS|no-skipped");
            }

            // Summary
            if (passed) {
                System.out.println("VERIFY|" + gateName + "|RESULT|PASS|All conditions met: " + report.tests() + " tests, 0 failures, 0 errors, 0 skipped");
                System.exit(EXIT_PASS);
            } else {
                System.out.println("VERIFY|" + gateName + "|RESULT|FAIL|One or more conditions not met");
                System.exit(EXIT_FAIL);
            }

        } catch (Exception e) {
            // Re-throw SecurityException (used by tests to intercept System.exit())
            if (e instanceof SecurityException) {
                throw (SecurityException) e;
            }
            System.out.println("VERIFY|" + gateName + "|FAIL|parse-error|Failed to parse report: " + e.getMessage());
            System.exit(EXIT_FAIL);
        }
    }

    /**
     * Handles the "list" subcommand: lists all TEST-*.xml files in the reports directory
     * with their test counts.
     *
     * <p>Useful for debugging and for populating the RELEASE_GATE_RESULT.md report.
     *
     * <p>Arguments:
     * <ul>
     *   <li>--reports-dir PATH : path to surefire reports directory (required)</li>
     * </ul>
     */
    private static void handleList(String[] args) {
        String reportsDir = null;

        for (int i = 1; i < args.length; i++) {
            if ("--reports-dir".equals(args[i]) && i + 1 < args.length) {
                reportsDir = args[++i];
            }
        }

        if (reportsDir == null) {
            System.err.println("ERROR: --reports-dir is required for list subcommand");
            System.exit(EXIT_USAGE);
            return;
        }

        Path reportsPath = Paths.get(reportsDir);
        if (!Files.isDirectory(reportsPath)) {
            System.out.println("LIST|0|reports-dir-not-found|" + reportsPath.toAbsolutePath());
            System.exit(0);
            return;
        }

        try (Stream<Path> files = Files.list(reportsPath)) {
            var xmlFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("TEST-")
                            && p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();

            System.out.println("LIST|" + xmlFiles.size() + "|files-found");

            for (Path file : xmlFiles) {
                try {
                    SurefireReport report = SurefireReportParser.parseFile(file);
                    System.out.println("LIST|FILE|" + file.getFileName() + "|" + report.tests()
                            + "|" + report.failures() + "|" + report.errors() + "|" + report.skipped()
                            + "|" + String.format("%.2f", report.timeSeconds()));
                } catch (Exception e) {
                    System.out.println("LIST|FILE|" + file.getFileName() + "|PARSE-ERROR|" + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("ERROR: Failed to list reports directory: " + e.getMessage());
            System.exit(EXIT_FAIL);
        }

        System.exit(0);
    }

    /**
     * Handles the "verify-report" subcommand: cross-checks the generated
     * RELEASE_GATE_RESULT.md against the actual git HEAD and surefire XML
     * totals, and rejects placeholder/stale/skipped reports.
     *
     * <p>Arguments:
     * <ul>
     *   <li>--report PATH        : path to RELEASE_GATE_RESULT.md (default: RELEASE_GATE_RESULT.md)</li>
     *   <li>--reports-dir PATH   : surefire reports directory (required for XML cross-check)</li>
     *   <li>--expected-file NAME : PG surefire file name (default: TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml)</li>
     *   <li>--git-head SHA       : expected short HEAD hash (default: computed via git rev-parse --short HEAD)</li>
     * </ul>
     */
    private static void handleVerifyReport(String[] args) {
        String reportPath = "RELEASE_GATE_RESULT.md";
        String reportsDir = null;
        String expectedFile = "TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml";
        String gitHead = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--report":
                    if (i + 1 < args.length) { reportPath = args[++i]; }
                    break;
                case "--reports-dir":
                    if (i + 1 < args.length) { reportsDir = args[++i]; }
                    break;
                case "--expected-file":
                    if (i + 1 < args.length) { expectedFile = args[++i]; }
                    break;
                case "--git-head":
                    if (i + 1 < args.length) { gitHead = args[++i]; }
                    break;
            }
        }

        Path report = Paths.get(reportPath);
        boolean passed = true;

        // Check 1: report exists and is not a placeholder.
        System.out.println("VERIFY-REPORT|report-exists|" + report);
        if (!Files.isRegularFile(report)) {
            System.out.println("VERIFY-REPORT|FAIL|report-missing|Report not found: " + report.toAbsolutePath());
            System.exit(EXIT_FAIL);
            return;
        }
        String content;
        try {
            content = Files.readString(report);
        } catch (IOException e) {
            System.out.println("VERIFY-REPORT|FAIL|read-error|" + e.getMessage());
            System.exit(EXIT_FAIL);
            return;
        }
        if (content.contains("NOT YET GENERATED") || content.contains("stale copy removed")) {
            System.out.println("VERIFY-REPORT|FAIL|placeholder|Report is a placeholder, not a real run");
            passed = false;
        } else {
            System.out.println("VERIFY-REPORT|PASS|not-placeholder");
        }

        // Check 2: has a PostgreSQL mode line.
        if (content.contains("**PostgreSQL mode:**")) {
            System.out.println("VERIFY-REPORT|PASS|pg-mode-recorded");
        } else {
            System.out.println("VERIFY-REPORT|FAIL|pg-mode-missing|Report does not record the PostgreSQL mode");
            passed = false;
        }

        // Check 3: ALL GATES PASSED must be consistent with every gate row.
        boolean claimsAllPassed = content.contains("ALL GATES PASSED");
        // Find summary table gate rows: "| N | ... | PASS|FAIL |"
        Pattern gateRow = Pattern.compile("^\\|\\s*([1-9])\\s*\\|.*?\\|\\s*(PASS|FAIL)\\s*\\|\\s*$",
                Pattern.MULTILINE);
        Matcher m = gateRow.matcher(content);
        boolean allRowsPass = true;
        boolean anyRowFound = false;
        while (m.find()) {
            anyRowFound = true;
            if (!"PASS".equals(m.group(2))) {
                allRowsPass = false;
            }
        }
        if (!anyRowFound) {
            System.out.println("VERIFY-REPORT|FAIL|no-gate-rows|No gate summary rows found in report");
            passed = false;
        } else if (claimsAllPassed && !allRowsPass) {
            System.out.println("VERIFY-REPORT|FAIL|inconsistent-overall|Report claims ALL GATES PASSED but a gate row is not PASS");
            passed = false;
        } else if (!claimsAllPassed && allRowsPass) {
            System.out.println("VERIFY-REPORT|FAIL|inconsistent-overall|All gate rows PASS but overall does not say ALL GATES PASSED");
            passed = false;
        } else {
            System.out.println("VERIFY-REPORT|PASS|overall-consistent");
        }

        // Check 4: report commit == git HEAD (if computable).
        String expectedHead = gitHead != null ? gitHead : computeGitShortHead();
        if (expectedHead == null) {
            System.out.println("VERIFY-REPORT|INFO|git-head-unavailable|Could not compute git HEAD; commit check skipped");
        } else {
            Matcher cm = Pattern.compile("\\*\\*Branch/Commit:\\*\\*\\s*(\\S+)").matcher(content);
            if (cm.find()) {
                String reportCommit = cm.group(1);
                if (expectedHead.equals(reportCommit)) {
                    System.out.println("VERIFY-REPORT|PASS|commit-matches|" + reportCommit);
                } else {
                    System.out.println("VERIFY-REPORT|FAIL|commit-mismatch|Report commit=" + reportCommit + " but HEAD=" + expectedHead);
                    passed = false;
                }
            } else {
                System.out.println("VERIFY-REPORT|FAIL|commit-missing|Report has no Branch/Commit line");
                passed = false;
            }
        }

        // Check 5: Gate 3 counts in the report == actual PG surefire XML.
        if (reportsDir == null) {
            System.out.println("VERIFY-REPORT|INFO|no-reports-dir|--reports-dir not provided; XML cross-check skipped");
        } else {
            Path xmlPath = Paths.get(reportsDir).resolve(expectedFile);
            if (!Files.isRegularFile(xmlPath)) {
                System.out.println("VERIFY-REPORT|FAIL|xml-missing|Surefire XML not found: " + xmlPath);
                passed = false;
            } else {
                try {
                    SurefireReport actual = SurefireReportParser.parseFile(xmlPath);
                    // Extract Gate 3 row counts from the report. The Gate 3 summary
                    // row is the one containing "postgresql-acceptance".
                    int[] reported = extractGate3Counts(content);
                    if (reported == null) {
                        System.out.println("VERIFY-REPORT|FAIL|gate3-row-missing|Could not find Gate 3 counts in report");
                        passed = false;
                    } else {
                        boolean countsMatch = reported[0] == actual.tests()
                                && reported[1] == actual.failures()
                                && reported[2] == actual.errors()
                                && reported[3] == actual.skipped();
                        if (countsMatch) {
                            System.out.println("VERIFY-REPORT|PASS|gate3-counts-match|report=" + reported[0] + "/" + reported[1] + "/" + reported[2] + "/" + reported[3]
                                    + " xml=" + actual.tests() + "/" + actual.failures() + "/" + actual.errors() + "/" + actual.skipped());
                        } else {
                            System.out.println("VERIFY-REPORT|FAIL|gate3-counts-mismatch|report=" + reported[0] + "/" + reported[1] + "/" + reported[2] + "/" + reported[3]
                                    + " xml=" + actual.tests() + "/" + actual.failures() + "/" + actual.errors() + "/" + actual.skipped());
                            passed = false;
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof SecurityException) {
                        throw (SecurityException) e;
                    }
                    System.out.println("VERIFY-REPORT|FAIL|xml-parse-error|" + e.getMessage());
                    passed = false;
                }
            }
        }

        if (passed) {
            System.out.println("VERIFY-REPORT|RESULT|PASS|Report is consistent with HEAD and surefire XML");
            System.exit(EXIT_PASS);
        } else {
            System.out.println("VERIFY-REPORT|RESULT|FAIL|Report is stale, placeholder, or inconsistent");
            System.exit(EXIT_FAIL);
        }
    }

    /** Extract Gate 3 [tests, failures, errors, skipped] from the report summary table. */
    private static int[] extractGate3Counts(String content) {
        for (String line : content.split("\\R", -1)) {
            if (line.contains("postgresql-acceptance") && line.startsWith("|") && line.endsWith("|")) {
                // columns: gate | command | exit | duration | tests | failures | errors | skipped | verifier | result
                String[] cols = line.split("\\|", -1);
                if (cols.length >= 10) {
                    try {
                        int tests = Integer.parseInt(cols[5].trim());
                        int failures = Integer.parseInt(cols[6].trim());
                        int errors = Integer.parseInt(cols[7].trim());
                        int skipped = Integer.parseInt(cols[8].trim());
                        return new int[]{tests, failures, errors, skipped};
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** Compute the short HEAD hash via git, or null if git is unavailable. */
    private static String computeGitShortHead() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return (p.exitValue() == 0 && !out.isEmpty()) ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ReleaseGateVerifier <subcommand> [options]");
        System.out.println();
        System.out.println("Subcommands:");
        System.out.println("  verify        Verify a specific gate condition against surefire reports");
        System.out.println("  verify-report Cross-check RELEASE_GATE_RESULT.md against HEAD and surefire XML");
        System.out.println("  list          List all TEST-*.xml files in the reports directory");
        System.out.println();
        System.out.println("verify options:");
        System.out.println("  --reports-dir PATH    Path to surefire reports directory (required)");
        System.out.println("  --expected-file NAME  Expected TEST-*.xml file name (required)");
        System.out.println("  --gate-name NAME      Human-readable gate name (optional, default: Gate)");
        System.out.println("  --min-tests N         Minimum number of tests required (optional, default: 1)");
        System.out.println();
        System.out.println("verify-report options:");
        System.out.println("  --report PATH         Path to RELEASE_GATE_RESULT.md (default: RELEASE_GATE_RESULT.md)");
        System.out.println("  --reports-dir PATH    Surefire reports directory (for XML cross-check)");
        System.out.println("  --expected-file NAME  PG surefire file name (default: TEST-com.nocobase.postgresql.PostgreSqlIntegrationTest.xml)");
        System.out.println("  --git-head SHA        Expected short HEAD hash (default: computed via git rev-parse --short HEAD)");
        System.out.println();
        System.out.println("list options:");
        System.out.println("  --reports-dir PATH    Path to surefire reports directory (required)");
        System.out.println();
        System.out.println("Exit codes: 0=pass, 1=fail, 2=usage error");
    }
}