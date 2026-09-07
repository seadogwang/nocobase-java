package com.nocobase.release;

/**
 * Represents a single test case result from a Surefire test report.
 *
 * @param className  fully qualified class name
 * @param name       test method name
 * @param status     test outcome (passed, failed, error, skipped)
 * @param timeSeconds execution time in seconds
 * @param message    failure/error message (null if passed)
 * @param type       exception type for failures/errors (null if passed)
 */
public record TestCaseResult(
        String className,
        String name,
        Status status,
        double timeSeconds,
        String message,
        String type
) {

    public enum Status {
        PASSED,
        FAILURE,
        ERROR,
        SKIPPED
    }

    public boolean isPassed() {
        return status == Status.PASSED;
    }

    @Override
    public String toString() {
        return String.format("%s.%s [%s] %.3fs", className, name, status, timeSeconds);
    }
}