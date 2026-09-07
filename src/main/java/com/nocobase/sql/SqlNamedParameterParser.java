package com.nocobase.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses named parameters (:param) from a configured SQL string and converts them
 * to JDBC-compatible "?" placeholders.
 *
 * <p>Actively used in production by {@link SqlQueryCollectionExecutor#hydrateSql} to
 * pre-process configured SQL before execution.</p>
 *
 * <h3>Parsing rules</h3>
 * <ul>
 *   <li>Parameter names: {@code [A-Za-z_][A-Za-z0-9_]*}</li>
 *   <li>Skip content inside single-quoted string literals</li>
 *   <li>Skip content inside double-quoted identifiers</li>
 *   <li>Skip PostgreSQL {@code ::type} casts (e.g. {@code :value::integer})</li>
 *   <li>Skip URL-like patterns (e.g. {@code http://}, {@code jdbc:postgresql://})</li>
 *   <li>Skip time literals (e.g. {@code 12:00:00})</li>
 * </ul>
 */
public class SqlNamedParameterParser {

    private static final Pattern NAMED_PARAM = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Parse result containing the converted JDBC SQL and ordered parameter names.
     */
    public static class Result {
        private final String sql;
        private final List<String> parameterNames;

        public Result(String sql, List<String> parameterNames) {
            this.sql = sql;
            this.parameterNames = Collections.unmodifiableList(new ArrayList<>(parameterNames));
        }

        /** JDBC-compatible SQL with all named params converted to {@code ?}. */
        public String getSql() {
            return sql;
        }

        /** Ordered list of parameter names, in the order they appear in the original SQL. */
        public List<String> getParameterNames() {
            return parameterNames;
        }
    }

    /**
     * Parse a SQL string, converting all {@code :name} parameters to {@code ?}.
     *
     * @param sql the configured SQL containing named parameters
     * @return result with converted SQL and ordered parameter name list
     */
    public static Result parse(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new Result(sql, List.of());
        }

        StringBuilder out = new StringBuilder();
        List<String> paramNames = new ArrayList<>();

        int i = 0;
        int len = sql.length();

        while (i < len) {
            char c = sql.charAt(i);

            if (c == '\'') {
                // Single-quoted string literal — skip entire content
                int start = i;
                i = skipSingleQuotedString(sql, i);
                out.append(sql, start, i);
                continue;
            }

            if (c == '"') {
                // Double-quoted identifier — skip entire content
                int start = i;
                i = skipDoubleQuotedIdentifier(sql, i);
                out.append(sql, start, i);
                continue;
            }

            if (c == ':') {
                if (i + 1 >= len) {
                    // Dangling colon at end of string
                    throw new IllegalArgumentException("Malformed named parameter token: ':'");
                }

                char next = sql.charAt(i + 1);

                // Check for PostgreSQL ::type cast — colon followed by another colon
                if (next == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }

                // Check for URL-like patterns — colon preceded by a protocol-like word
                if (isPrecededByProtocolLike(sql, i)) {
                    out.append(':');
                    i++;
                    continue;
                }

                // Check for time literal — colon between digits
                if (isTimeLiteral(sql, i)) {
                    out.append(':');
                    i++;
                    continue;
                }

                // Try to match a named parameter
                Matcher m = NAMED_PARAM.matcher(sql.substring(i));
                if (m.find() && m.start() == 0) {
                    String paramName = m.group(1);
                    int matchEnd = i + m.group().length();

                    // Check if the character after the match is an identifier-like character
                    // that our regex does not accept (e.g., hyphen in :bad-name)
                    if (matchEnd < len) {
                        char after = sql.charAt(matchEnd);
                        if (Character.isLetterOrDigit(after) || after == '_' || after == '-') {
                            int tokenEnd = matchEnd;
                            while (tokenEnd < len && (Character.isLetterOrDigit(sql.charAt(tokenEnd))
                                    || sql.charAt(tokenEnd) == '_' || sql.charAt(tokenEnd) == '-')) {
                                tokenEnd++;
                            }
                            String fullToken = sql.substring(i, tokenEnd);
                            throw new IllegalArgumentException(
                                    "Malformed named parameter token: '" + fullToken + "'");
                        }
                    }

                    out.append('?');
                    paramNames.add(paramName);
                    i = matchEnd;
                    continue;
                }

                // Malformed colon usage — not a valid named param, not ::cast, not URL, not time
                int tokenEnd = i + 1;
                while (tokenEnd < len && !Character.isWhitespace(sql.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                String malformedToken = sql.substring(i, tokenEnd);
                throw new IllegalArgumentException(
                        "Malformed named parameter token: '" + malformedToken + "'");
            }

            out.append(c);
            i++;
        }

        return new Result(out.toString(), paramNames);
    }

    /**
     * Skip past a single-quoted string literal starting at the opening quote.
     * Handles escaped quotes ('').
     */
    private static int skipSingleQuotedString(String sql, int start) {
        int i = start + 1; // skip opening quote
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    // Escaped quote
                    i += 2;
                } else {
                    // Closing quote
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        // Unterminated string — return to end
        return len;
    }

    /**
     * Skip past a double-quoted identifier starting at the opening quote.
     * Handles escaped quotes ("").
     */
    private static int skipDoubleQuotedIdentifier(String sql, int start) {
        int i = start + 1; // skip opening quote
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    // Escaped quote
                    i += 2;
                } else {
                    // Closing quote
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        // Unterminated identifier — return to end
        return len;
    }

    /**
     * Check if the colon at position {@code colPos} is preceded by a protocol-like
     * word (e.g., "http", "https", "jdbc", "postgresql").
     */
    private static boolean isPrecededByProtocolLike(String sql, int colPos) {
        // Look backwards for a word character sequence followed by a word boundary
        int j = colPos - 1;
        while (j >= 0 && Character.isLetterOrDigit(sql.charAt(j))) {
            j--;
        }
        // The word preceding the colon
        String word = sql.substring(j + 1, colPos);
        // Protocol-like words that precede a colon
        return word.equalsIgnoreCase("http") || word.equalsIgnoreCase("https")
                || word.equalsIgnoreCase("jdbc") || word.equalsIgnoreCase("postgresql")
                || word.equalsIgnoreCase("file") || word.equalsIgnoreCase("ftp");
    }

    /**
     * Check if the colon at position {@code colPos} is part of a time literal
     * (digit before and digit after, e.g., "12:00:00").
     */
    private static boolean isTimeLiteral(String sql, int colPos) {
        if (colPos == 0 || colPos + 1 >= sql.length()) {
            return false;
        }
        return Character.isDigit(sql.charAt(colPos - 1))
                && Character.isDigit(sql.charAt(colPos + 1));
    }
}