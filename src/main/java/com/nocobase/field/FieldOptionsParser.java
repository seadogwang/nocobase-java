package com.nocobase.field;

import java.util.Map;

/**
 * P1-E1: Parses field options from a JSON Map into a strongly-typed {@link FieldOptions} object.
 *
 * <p>Parses the following options:
 * <ul>
 *   <li>{@code nullable} -- boolean</li>
 *   <li>{@code default} -- string (default value expression)</li>
 *   <li>{@code length} -- integer (for string/text types)</li>
 *   <li>{@code precision} -- integer (for decimal/numeric types)</li>
 *   <li>{@code scale} -- integer (for decimal/numeric types)</li>
 * </ul>
 *
 * <p>Illegal option values (wrong type, negative integers, etc.) result in
 * {@link IllegalArgumentException} -- the parser is fail-fast, never producing
 * half-baked DDL.
 */
public final class FieldOptionsParser {

    private FieldOptionsParser() {
        // utility class
    }

    /**
     * Parse field options from a JSON Map.
     *
     * @param options the raw options map (may be null or empty)
     * @return a strongly-typed FieldOptions object (never null)
     * @throws IllegalArgumentException if any option value is invalid
     */
    public static FieldOptions parse(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return FieldOptions.builder().build();
        }

        FieldOptions.Builder builder = FieldOptions.builder();

        // nullable
        if (options.containsKey("nullable")) {
            Object nullableVal = options.get("nullable");
            if (nullableVal instanceof Boolean b) {
                builder.nullable(b);
            } else {
                throw new IllegalArgumentException(
                        "Field option 'nullable' must be a boolean, got: " + nullableVal);
            }
        }

        // default
        if (options.containsKey("default")) {
            Object defaultVal = options.get("default");
            if (defaultVal == null) {
                builder.defaultValue(null);
            } else {
                builder.defaultValue(FieldOptions.DefaultValue.parse(defaultVal));
            }
        }

        // length
        if (options.containsKey("length")) {
            builder.length(parsePositiveInt(options.get("length"), "length"));
        }

        // precision
        if (options.containsKey("precision")) {
            builder.precision(parsePositiveInt(options.get("precision"), "precision"));
        }

        // scale
        if (options.containsKey("scale")) {
            builder.scale(parseNonNegativeInt(options.get("scale"), "scale"));
        }

        return builder.build();
    }

    /**
     * Parse a positive integer value from an Object.
     *
     * @throws IllegalArgumentException if the value is not a valid positive integer
     */
    private static int parsePositiveInt(Object value, String optionName) {
        int intValue;
        if (value instanceof Number num) {
            intValue = num.intValue();
        } else if (value instanceof String str) {
            try {
                intValue = Integer.parseInt(str);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Field option '" + optionName + "' must be a valid integer, got: " + value);
            }
        } else {
            throw new IllegalArgumentException(
                    "Field option '" + optionName + "' must be an integer, got: " + value);
        }
        if (intValue <= 0) {
            throw new IllegalArgumentException(
                    "Field option '" + optionName + "' must be a positive integer, got: " + intValue);
        }
        return intValue;
    }

    /**
     * Parse a non-negative integer value from an Object.
     *
     * @throws IllegalArgumentException if the value is not a valid non-negative integer
     */
    private static int parseNonNegativeInt(Object value, String optionName) {
        int intValue;
        if (value instanceof Number num) {
            intValue = num.intValue();
        } else if (value instanceof String str) {
            try {
                intValue = Integer.parseInt(str);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Field option '" + optionName + "' must be a valid integer, got: " + value);
            }
        } else {
            throw new IllegalArgumentException(
                    "Field option '" + optionName + "' must be an integer, got: " + value);
        }
        if (intValue < 0) {
            throw new IllegalArgumentException(
                    "Field option '" + optionName + "' must be a non-negative integer, got: " + intValue);
        }
        return intValue;
    }
}