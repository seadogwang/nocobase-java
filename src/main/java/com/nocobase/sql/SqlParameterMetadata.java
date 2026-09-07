package com.nocobase.sql;

import com.nocobase.runtime.CollectionDefinition;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads and validates parameter metadata from a CollectionDefinition's options.
 *
 * <p>Expected options structure (JSON deserialized to Map):
 * <pre>
 * {
 *   "parameters": [
 *     {"name": "status", "type": "string", "source": "static", "defaultValue": "active", "required": true},
 *     {"name": "userId", "type": "number", "source": "currentUser", "path": "id", "required": true}
 *   ]
 * }
 * </pre>
 *
 * <h3>Validation rules</h3>
 * <ul>
 *   <li>Parameter names must match {@code [A-Za-z_][A-Za-z0-9_]*}.</li>
 *   <li>Duplicate parameter names are rejected.</li>
 *   <li>Unsupported type rejected (only: string, number, boolean, date, datetime).</li>
 *   <li>Unsupported source rejected (only: static, currentUser).</li>
 *   <li>Required static parameter must have a non-null defaultValue.</li>
 *   <li>currentUser parameter must have a valid path (id, email).</li>
 *   <li>options.parameters must be a list when present.</li>
 *   <li>Each list entry must be an object/map.</li>
 *   <li>defaultValue must be compatible with the declared type.</li>
 *   <li>All named parameters in SQL must be declared in metadata.</li>
 *   <li>All declared parameters must be used in the SQL.</li>
 * </ul>
 */
public class SqlParameterMetadata {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "number", "boolean", "date", "datetime");
    private static final Set<String> SUPPORTED_SOURCES = Set.of("static", "currentUser");
    private static final Set<String> ALLOWED_CURRENT_USER_PATHS = Set.of("id", "email");

    private final List<ParameterDef> parameters;

    private SqlParameterMetadata(List<ParameterDef> parameters) {
        this.parameters = Collections.unmodifiableList(parameters);
    }

    /**
     * A single parameter definition.
     */
    public static class ParameterDef {
        private final String name;
        private final String type;
        private final String source;
        private final Object defaultValue;
        private final boolean required;
        private final String path;

        public ParameterDef(String name, String type, String source, Object defaultValue, boolean required, String path) {
            this.name = name;
            this.type = type;
            this.source = source;
            this.defaultValue = normalizeValue(type, defaultValue);
            this.required = required;
            this.path = path;
        }

        /**
         * Normalize a raw defaultValue to its proper Java type based on the declared parameter type.
         * This ensures that JDBC can bind the correct type (e.g., Integer instead of String "42").
         */
        private static Object normalizeValue(String type, Object value) {
            if (value == null) return null;
            switch (type) {
                case "string":
                    return value.toString();
                case "number":
                    if (value instanceof Number) return value;
                    if (value instanceof String s) {
                        try {
                            return Integer.parseInt(s);
                        } catch (NumberFormatException e1) {
                            try {
                                return Long.parseLong(s);
                            } catch (NumberFormatException e2) {
                                return new BigDecimal(s);
                            }
                        }
                    }
                    return value;
                case "boolean":
                    if (value instanceof Boolean) return value;
                    if (value instanceof String s) {
                        return Boolean.parseBoolean(s);
                    }
                    return value;
                case "date":
                    if (value instanceof String s) {
                        return java.sql.Date.valueOf(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE));
                    }
                    return value;
                case "datetime":
                    if (value instanceof String s) {
                        return Timestamp.valueOf(LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    return value;
                default:
                    return value;
            }
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public String getSource() { return source; }
        public Object getDefaultValue() { return defaultValue; }
        public boolean isRequired() { return required; }
        public String getPath() { return path; }
    }

    /**
     * Returns the ordered list of parameter definitions.
     */
    public List<ParameterDef> getParameters() {
        return parameters;
    }

    /**
     * Look up a parameter definition by name.
     *
     * @param name the parameter name
     * @return the parameter definition, or null if not found
     */
    public ParameterDef getParameter(String name) {
        return parameters.stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns true if there are no parameter definitions.
     */
    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    /**
     * Build parameter metadata from a CollectionDefinition's options map.
     * Validates all parameter definitions and cross-references with SQL.
     *
     * @param def the collection definition
     * @return parsed and validated parameter metadata
     * @throws IllegalArgumentException if validation fails
     */
    @SuppressWarnings("unchecked")
    public static SqlParameterMetadata from(CollectionDefinition def) {
        if (def == null) {
            return new SqlParameterMetadata(List.of());
        }

        Map<String, Object> options = def.getOptions();
        List<ParameterDef> params = new ArrayList<>();

        if (options != null && !options.isEmpty()) {
            Object paramsObj = options.get("parameters");
            if (paramsObj != null) {
                // Reject when parameters exists but is not a list
                if (!(paramsObj instanceof List)) {
                    throw new IllegalArgumentException(
                            "options.parameters in collection '" + def.getName() + "' must be a list");
                }

                List<?> rawList = (List<?>) paramsObj;
                if (!rawList.isEmpty()) {
                    Set<String> seenNames = new HashSet<>();

                    for (int i = 0; i < rawList.size(); i++) {
                        Object entry = rawList.get(i);
                        if (!(entry instanceof Map)) {
                            throw new IllegalArgumentException(
                                    "options.parameters[" + i + "] in collection '" + def.getName() + "' must be an object");
                        }

                        Map<String, Object> raw = (Map<String, Object>) entry;

                        String name = getString(raw, "name");
                        if (name == null || name.isBlank()) {
                            throw new IllegalArgumentException("Parameter definition missing 'name'");
                        }

                        // Name format validation
                        try {
                            SqlIdentifier.validate(name);
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException(
                                    "Invalid parameter name '" + name + "' in collection '" + def.getName()
                                            + "'. Names must match [A-Za-z_][A-Za-z0-9_]*", e);
                        }

                        // Duplicate check
                        if (!seenNames.add(name)) {
                            throw new IllegalArgumentException(
                                    "Duplicate parameter name '" + name + "' in collection '" + def.getName() + "'");
                        }

                        String type = getString(raw, "type");
                        if (type == null || type.isBlank()) {
                            type = "string"; // default type
                        }
                        if (!SUPPORTED_TYPES.contains(type)) {
                            throw new IllegalArgumentException(
                                    "Unsupported parameter type '" + type + "' for parameter '" + name
                                            + "' in collection '" + def.getName() + "'. "
                                            + "Supported types: " + SUPPORTED_TYPES);
                        }

                        String source = getString(raw, "source");
                        if (source == null || source.isBlank()) {
                            source = "static"; // default source
                        }
                        if (!SUPPORTED_SOURCES.contains(source)) {
                            throw new IllegalArgumentException(
                                    "Unsupported parameter source '" + source + "' for parameter '" + name
                                            + "' in collection '" + def.getName() + "'. "
                                            + "Supported sources: " + SUPPORTED_SOURCES);
                        }

                        // currentUser source validation
                        String path = null;
                        if ("currentUser".equals(source)) {
                            path = getString(raw, "path");
                            if (path == null || path.isBlank()) {
                                throw new IllegalArgumentException(
                                        "currentUser parameter '" + name + "' in collection '" + def.getName()
                                                + "' must have a 'path' field (one of: " + ALLOWED_CURRENT_USER_PATHS + ")");
                            }
                            if (!ALLOWED_CURRENT_USER_PATHS.contains(path)) {
                                throw new IllegalArgumentException(
                                        "currentUser parameter '" + name + "' in collection '" + def.getName()
                                                + "' has invalid path '" + path + "'. "
                                                + "Allowed paths: " + ALLOWED_CURRENT_USER_PATHS);
                            }
                        }

                        Object defaultValue = raw.get("defaultValue");
                        boolean required = getBoolean(raw, "required");

                        // currentUser path/type compatibility validation
                        if ("currentUser".equals(source)) {
                            if ("id".equals(path) && !"number".equals(type)) {
                                throw new IllegalArgumentException(
                                        "currentUser parameter '" + name + "' in collection '" + def.getName()
                                                + "' has path '" + path + "' which requires type 'number', but type is '" + type + "'");
                            }
                            if ("email".equals(path) && !"string".equals(type)) {
                                throw new IllegalArgumentException(
                                        "currentUser parameter '" + name + "' in collection '" + def.getName()
                                                + "' has path '" + path + "' which requires type 'string', but type is '" + type + "'");
                            }
                            if (defaultValue != null) {
                                throw new IllegalArgumentException(
                                        "currentUser parameter '" + name + "' in collection '" + def.getName()
                                                + "' must not have a defaultValue");
                            }
                        }

                        // Required static params must have a defaultValue
                        if ("static".equals(source) && required && defaultValue == null) {
                            throw new IllegalArgumentException(
                                    "Required static parameter '" + name + "' in collection '" + def.getName()
                                            + "' must have a defaultValue");
                        }

                        // Validate defaultValue against declared type (when present)
                        validateDefaultValue(name, type, defaultValue, def.getName());

                        params.add(new ParameterDef(name, type, source, defaultValue, required, path));
                    }
                }
            }
        }

        // Cross-validate with SQL (always, if SQL is present)
        String sql = def.getSql();
        if (sql != null && !sql.isEmpty()) {
            validateParameterUsage(sql, params, def.getName());
        }

        return new SqlParameterMetadata(params);
    }

    /**
     * Validate that defaultValue is compatible with the declared type.
     */
    private static void validateDefaultValue(String name, String type, Object defaultValue, String collectionName) {
        if (defaultValue == null) return;

        switch (type) {
            case "string":
                if (!(defaultValue instanceof String)) {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'string' but defaultValue is not a string");
                }
                break;
            case "number":
                if (defaultValue instanceof Number) break;
                if (defaultValue instanceof String s) {
                    try {
                        Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Parameter '" + name + "' in collection '" + collectionName
                                        + "' has type 'number' but defaultValue '" + s + "' is not a valid number");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'number' but defaultValue is not a number or numeric string");
                }
                break;
            case "boolean":
                if (defaultValue instanceof Boolean) break;
                if (defaultValue instanceof String s) {
                    if (!"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                        throw new IllegalArgumentException(
                                "Parameter '" + name + "' in collection '" + collectionName
                                        + "' has type 'boolean' but defaultValue '" + s + "' is not 'true' or 'false'");
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'boolean' but defaultValue is not a boolean or 'true'/'false'");
                }
                break;
            case "date":
                if (!(defaultValue instanceof String s)) {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'date' but defaultValue is not a string");
                }
                try {
                    LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'date' but defaultValue '" + s + "' is not a valid ISO date (YYYY-MM-DD)");
                }
                break;
            case "datetime":
                if (!(defaultValue instanceof String s)) {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'datetime' but defaultValue is not a string");
                }
                try {
                    LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "Parameter '" + name + "' in collection '" + collectionName
                                    + "' has type 'datetime' but defaultValue '" + s + "' is not a valid ISO datetime");
                }
                break;
        }
    }

    /**
     * Cross-validate parameter metadata against SQL: ensure all SQL parameters are declared
     * and all declared parameters are used in the SQL.
     */
    private static void validateParameterUsage(String sql, List<ParameterDef> params, String collectionName) {
        SqlNamedParameterParser.Result parseResult = SqlNamedParameterParser.parse(sql);
        Set<String> sqlParams = new LinkedHashSet<>(parseResult.getParameterNames());
        Set<String> declaredNames = params.stream()
                .map(ParameterDef::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Check SQL references to undeclared parameters
        for (String sqlParam : sqlParams) {
            if (!declaredNames.contains(sqlParam)) {
                throw new IllegalArgumentException(
                        "SQL references parameter '" + sqlParam
                                + "' which is not declared in options.parameters for collection '"
                                + collectionName + "'");
            }
        }

        // Check declared-but-unused parameters
        for (ParameterDef param : params) {
            if (!sqlParams.contains(param.getName())) {
                throw new IllegalArgumentException(
                        "Parameter '" + param.getName()
                                + "' is declared in options.parameters but not used in SQL for collection '"
                                + collectionName + "'");
            }
        }
    }

    /**
     * Build a value map from parameter metadata for binding to JDBC parameters.
     * The values are ordered by the parameter names list (from SqlNamedParameterParser).
     *
     * @param paramNames ordered list of parameter names from the SQL parse
     * @return ordered list of bound values
     * @throws IllegalArgumentException if a referenced parameter is not defined
     */
    List<Object> buildValueList(List<String> paramNames) {
        List<Object> values = new ArrayList<>();
        for (String name : paramNames) {
            ParameterDef param = getParameter(name);
            if (param == null) {
                throw new IllegalArgumentException(
                        "SQL references parameter '" + name + "' which is not defined in collection options");
            }
            // Values are pre-normalized in the ParameterDef constructor,
            // but we normalize again here as a safety net for direct ParameterDef construction.
            values.add(normalizeValue(param.getType(), param.getDefaultValue()));
        }
        return values;
    }

    /**
     * Normalize a raw value to its proper Java type based on the declared parameter type.
     */
    private static Object normalizeValue(String type, Object value) {
        if (value == null) return null;
        switch (type) {
            case "string":
                return value.toString();
            case "number":
                if (value instanceof Number) return value;
                if (value instanceof String s) {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e1) {
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException e2) {
                            return new BigDecimal(s);
                        }
                    }
                }
                return value;
            case "boolean":
                if (value instanceof Boolean) return value;
                if (value instanceof String s) {
                    return Boolean.parseBoolean(s);
                }
                return value;
            case "date":
                if (value instanceof String s) {
                    return java.sql.Date.valueOf(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE));
                }
                return value;
            case "datetime":
                if (value instanceof String s) {
                    return Timestamp.valueOf(LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }
                return value;
            default:
                return value;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private static boolean getBoolean(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}