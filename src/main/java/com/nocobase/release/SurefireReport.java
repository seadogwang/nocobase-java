package com.nocobase.release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable value object holding aggregated Surefire test report results.
 *
 * @param name          report name (usually the testsuite class name)
 * @param tests         total number of tests
 * @param failures      number of test failures
 * @param errors        number of test errors
 * @param skipped       number of skipped tests
 * @param timeSeconds   total execution time in seconds
 * @param testCases     individual test case results
 */
public record SurefireReport(
        String name,
        int tests,
        int failures,
        int errors,
        int skipped,
        double timeSeconds,
        List<TestCaseResult> testCases
) {

    /**
     * Creates an empty report with all zeros.
     */
    public static SurefireReport empty() {
        return new SurefireReport("empty", 0, 0, 0, 0, 0.0, Collections.emptyList());
    }

    /**
     * Merges multiple suite reports into one aggregated report.
     * Sums tests, failures, errors, skipped, and time.
     */
    public static SurefireReport merge(List<SurefireReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return empty();
        }
        if (reports.size() == 1) {
            return reports.get(0);
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0.0;
        List<TestCaseResult> allCases = new ArrayList<>();

        for (SurefireReport r : reports) {
            totalTests += r.tests;
            totalFailures += r.failures;
            totalErrors += r.errors;
            totalSkipped += r.skipped;
            totalTime += r.timeSeconds;
            allCases.addAll(r.testCases);
        }

        return new SurefireReport("aggregated", totalTests, totalFailures,
                totalErrors, totalSkipped, totalTime, Collections.unmodifiableList(allCases));
    }

    /**
     * Total tests that were actually executed (excluding skipped).
     */
    public int totalExecuted() {
        return tests - skipped;
    }

    /**
     * Total tests that passed (executed minus failures and errors).
     */
    public int totalPassed() {
        return tests - skipped - failures - errors;
    }

    /**
     * Returns true if there are zero failures and zero errors with at least one test.
     */
    public boolean allPassed() {
        return tests > 0 && failures == 0 && errors == 0;
    }

    /**
     * Returns true if this report has any test results at all.
     */
    public boolean hasTests() {
        return tests > 0;
    }

    @Override
    public String toString() {
        return String.format("SurefireReport{tests=%d, failures=%d, errors=%d, skipped=%d, passed=%d, time=%.2fs}",
                tests, failures, errors, skipped, totalPassed(), timeSeconds);
    }
}