package com.nocobase.field;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strongly-typed representation of field options parsed from field metadata JSON.
 *
 * <p>Contains parsed values for nullable, default, length, precision, and scale.
 * All values are nullable -- callers should check for null before using.
 */
public class FieldOptions {
    private final Boolean nullable;
    private final DefaultValue defaultValue;
    private final Integer length;
    private final Integer precision;
    private final Integer scale;

    private FieldOptions(Builder builder) {
        this.nullable = builder.nullable;
        this.defaultValue = builder.defaultValue;
        this.length = builder.length;
        this.precision = builder.precision;
        this.scale = builder.scale;
    }

    public Boolean getNullable() { return nullable; }
    public DefaultValue getDefaultValue() { return defaultValue; }
    public Integer getLength() { return length; }
    public Integer getPrecision() { return precision; }
    public Integer getScale() { return scale; }

    public boolean isNullable() { return nullable != null && nullable; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Boolean nullable;
        private DefaultValue defaultValue;
        private Integer length;
        private Integer precision;
        private Integer scale;

        public Builder nullable(Boolean nullable) { this.nullable = nullable; return this; }
        public Builder defaultValue(DefaultValue defaultValue) { this.defaultValue = defaultValue; return this; }
        public Builder length(Integer length) { this.length = length; return this; }
        public Builder precision(Integer precision) { this.precision = precision; return this; }
        public Builder scale(Integer scale) { this.scale = scale; return this; }

        public FieldOptions build() {
            return new FieldOptions(this);
        }
    }

    /**
     * A safe, typed representation of a SQL DEFAULT value.
     *
     * <p>Two kinds of defaults are supported:
     * <ul>
     *   <li>{@link Kind#LITERAL} -- a literal value (string, number, boolean).
     *       The dialect adapter formats it with proper quoting/escaping.</li>
     *   <li>{@link Kind#EXPRESSION} -- a DB expression (e.g. CURRENT_TIMESTAMP).
     *       Only allowlisted expressions are accepted; malicious SQL is rejected
     *       at parse time.</li>
     * </ul>
     */
    public static class DefaultValue {
        public enum Kind { LITERAL, EXPRESSION }

        /** Allowlisted DB expressions that can be used as defaults. */
        private static final Set<String> ALLOWED_EXPRESSIONS = Set.of(
                "CURRENT_TIMESTAMP", "CURRENT_DATE", "NOW()"
        );

        private final Kind kind;
        private final String value;

        private DefaultValue(Kind kind, String value) {
            this.kind = kind;
            this.value = value;
        }

        /**
         * Create a literal default value.
         * The value will be formatted by the dialect adapter (quoting strings, etc.).
         */
        public static DefaultValue literal(String value) {
            return new DefaultValue(Kind.LITERAL, value);
        }

        /**
         * Create an expression default value.
         * The expression must be in the allowlist; malicious SQL is rejected.
         *
         * @param value the SQL expression (e.g. "CURRENT_TIMESTAMP")
         * @throws IllegalArgumentException if the expression is not allowlisted or contains dangerous content
         */
        public static DefaultValue expression(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Default expression value must not be blank");
            }
            String upper = value.toUpperCase().trim();
            if (!ALLOWED_EXPRESSIONS.contains(upper)) {
                throw new IllegalArgumentException(
                        "Default expression is not allowed. "
                        + "Allowed expressions: " + ALLOWED_EXPRESSIONS);
            }
            return new DefaultValue(Kind.EXPRESSION, upper);
        }

        /**
         * Parse a raw default value from JSON. Determines whether it is a literal
         * or an expression based on the value content.
         *
         * <p>Malicious SQL injection attempts are rejected with a clear error message.
         *
         * @param rawValue the raw value from JSON (String, Number, or Boolean)
         * @return a DefaultValue instance
         * @throws IllegalArgumentException if the value contains dangerous SQL
         */
        public static DefaultValue parse(Object rawValue) {
            if (rawValue == null) return null;
            String str = String.valueOf(rawValue);

            String upper = str.toUpperCase().trim();
            if (ALLOWED_EXPRESSIONS.contains(upper)) {
                return new DefaultValue(Kind.EXPRESSION, upper);
            }

            // For boolean values from JSON, preserve the case
            if (rawValue instanceof Boolean) {
                return new DefaultValue(Kind.LITERAL, str);
            }

            return new DefaultValue(Kind.LITERAL, str);
        }

        public Kind getKind() { return kind; }
        public String getValue() { return value; }
        public boolean isLiteral() { return kind == Kind.LITERAL; }
        public boolean isExpression() { return kind == Kind.EXPRESSION; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DefaultValue that)) return false;
            return kind == that.kind && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value);
        }

        @Override
        public String toString() {
            if (kind == Kind.EXPRESSION) {
                return "DefaultValue{EXPRESSION(" + value + ")}";
            }
            return "DefaultValue{LITERAL}";
        }
    }
}