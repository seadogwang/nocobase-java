package com.nocobase.sql;

import com.nocobase.runtime.CollectionDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SqlParameterMetadata validation rules.
 * All tests are pure unit tests — no Spring context needed.
 */
@DisplayName("SqlParameterMetadata")
class SqlParameterMetadataTest {

    /**
     * Helper: build a minimal CollectionDefinition with the given options and SQL.
     */
    private static CollectionDefinition buildDef(String name, Map<String, Object> options, String sql) {
        return CollectionDefinition.builder(name)
                .type("sql")
                .sql(sql)
                .options(options)
                .build();
    }

    private static Map<String, Object> optionsWithParams(Object params) {
        Map<String, Object> opts = new HashMap<>();
        opts.put("parameters", params);
        return opts;
    }

    private static Map<String, Object> paramEntry(String name, String type, Object defaultValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        if (type != null) entry.put("type", type);
        if (defaultValue != null) entry.put("defaultValue", defaultValue);
        return entry;
    }

    private static Map<String, Object> currentUserParam(String name, String type, String path) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("type", type);
        entry.put("source", "currentUser");
        entry.put("path", path);
        entry.put("required", true);
        return entry;
    }

    // ========== P0-B.1: Name format validation ==========

    @Nested
    @DisplayName("Name format validation")
    class NameFormatValidation {

        @Test
        @DisplayName("valid names accepted")
        void validNamesAccepted() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("status", "string", "active"),
                    paramEntry("min_age", "number", "18"),
                    paramEntry("_internal", "string", "x"),
                    paramEntry("x2", "string", "y")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE status = :status AND age > :min_age AND _flag = :_internal AND tag = :x2");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("name starting with digit rejected")
        void nameStartingWithDigitRejected() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("1bad", "string", "x")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE x = :1bad");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("1bad"));
        }

        @Test
        @DisplayName("name with special characters rejected")
        void nameWithSpecialCharsRejected() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("bad-name", "string", "x")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE x = :bad-name");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("bad-name"));
        }
    }

    // ========== P0-B.2: options.parameters must be a list ==========

    @Nested
    @DisplayName("options.parameters shape validation")
    class ParametersShapeValidation {

        @Test
        @DisplayName("parameters is a string — rejected")
        void parametersIsStringRejected() {
            CollectionDefinition def = buildDef("test", optionsWithParams("not_a_list"), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be a list"));
        }

        @Test
        @DisplayName("parameters is a number — rejected")
        void parametersIsNumberRejected() {
            CollectionDefinition def = buildDef("test", optionsWithParams(42), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be a list"));
        }

        @Test
        @DisplayName("parameters is a map — rejected")
        void parametersIsMapRejected() {
            CollectionDefinition def = buildDef("test", optionsWithParams(Map.of("x", "y")), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be a list"));
        }

        @Test
        @DisplayName("parameters is null — returns empty (no params)")
        void parametersIsNullReturnsEmpty() {
            CollectionDefinition def = buildDef("test", new HashMap<>(Map.of("other", "value")), null);
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("options is null — returns empty")
        void optionsIsNullReturnsEmpty() {
            CollectionDefinition def = CollectionDefinition.builder("test").type("sql").build();
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertTrue(meta.isEmpty());
        }
    }

    // ========== P0-B.3: list entries must be objects ==========

    @Nested
    @DisplayName("List entry type validation")
    class ListEntryTypeValidation {

        @Test
        @DisplayName("string entry rejected")
        void stringEntryRejected() {
            List<Object> badList = List.of("not_a_map");
            CollectionDefinition def = buildDef("test", optionsWithParams(badList), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be an object"));
        }

        @Test
        @DisplayName("number entry rejected")
        void numberEntryRejected() {
            List<Object> badList = List.of(123);
            CollectionDefinition def = buildDef("test", optionsWithParams(badList), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be an object"));
        }

        @Test
        @DisplayName("mixed valid and invalid entries — first invalid caught")
        void mixedEntriesFirstInvalid() {
            List<Object> mixedList = List.of(
                    paramEntry("good", "string", "x"),
                    "bad_entry"
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(mixedList),
                    "SELECT * FROM t WHERE x = :good");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must be an object"));
        }
    }

    // ========== P0-B.4: duplicate names ==========

    @Nested
    @DisplayName("Duplicate name validation")
    class DuplicateNameValidation {

        @Test
        @DisplayName("duplicate names rejected")
        void duplicateNamesRejected() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("status", "string", "active"),
                    paramEntry("status", "string", "inactive")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE status = :status");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("Duplicate"));
            assertTrue(ex.getMessage().contains("status"));
        }
    }

    // ========== P0-B.5: defaultValue type validation ==========

    @Nested
    @DisplayName("defaultValue type validation")
    class DefaultValueTypeValidation {

        @Test
        @DisplayName("string: string value accepted")
        void stringDefaultAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("s", "string", "hello"))),
                    "SELECT * FROM t WHERE x = :s");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("string: non-string value rejected")
        void stringDefaultNonStringRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("s", "string", 42))),
                    "SELECT * FROM t WHERE x = :s");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a string"));
        }

        @Test
        @DisplayName("number: integer value accepted")
        void numberIntegerAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", 42))),
                    "SELECT * FROM t WHERE x = :n");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("number: double value accepted")
        void numberDoubleAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", 3.14))),
                    "SELECT * FROM t WHERE x = :n");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("number: numeric string accepted")
        void numberNumericStringAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", "42"))),
                    "SELECT * FROM t WHERE x = :n");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("number: non-numeric string rejected")
        void numberNonNumericStringRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", "abc"))),
                    "SELECT * FROM t WHERE x = :n");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a valid number"));
        }

        @Test
        @DisplayName("number: boolean rejected")
        void numberBooleanRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", true))),
                    "SELECT * FROM t WHERE x = :n");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a number or numeric string"));
        }

        @Test
        @DisplayName("boolean: true accepted")
        void booleanTrueAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", true))),
                    "SELECT * FROM t WHERE x = :b");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("boolean: false accepted")
        void booleanFalseAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", false))),
                    "SELECT * FROM t WHERE x = :b");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("boolean: string 'true' accepted")
        void booleanStringTrueAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", "true"))),
                    "SELECT * FROM t WHERE x = :b");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("boolean: string 'false' accepted")
        void booleanStringFalseAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", "false"))),
                    "SELECT * FROM t WHERE x = :b");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("boolean: non-boolean string rejected")
        void booleanNonBooleanStringRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", "yes"))),
                    "SELECT * FROM t WHERE x = :b");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not 'true' or 'false'"));
        }

        @Test
        @DisplayName("boolean: number rejected")
        void booleanNumberRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", 1))),
                    "SELECT * FROM t WHERE x = :b");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a boolean or 'true'/'false'"));
        }

        @Test
        @DisplayName("date: valid ISO date accepted")
        void dateValidAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("d", "date", "2024-01-15"))),
                    "SELECT * FROM t WHERE d = :d");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("date: non-string rejected")
        void dateNonStringRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("d", "date", 20240115))),
                    "SELECT * FROM t WHERE d = :d");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a string"));
        }

        @Test
        @DisplayName("date: invalid format rejected")
        void dateInvalidFormatRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("d", "date", "01/15/2024"))),
                    "SELECT * FROM t WHERE d = :d");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a valid ISO date"));
        }

        @Test
        @DisplayName("date: out-of-range rejected")
        void dateOutOfRangeRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("d", "date", "2024-13-01"))),
                    "SELECT * FROM t WHERE d = :d");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a valid ISO date"));
        }

        @Test
        @DisplayName("datetime: valid ISO datetime accepted")
        void datetimeValidAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("dt", "datetime", "2024-01-15T10:30:00"))),
                    "SELECT * FROM t WHERE dt = :dt");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("datetime: non-string rejected")
        void datetimeNonStringRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("dt", "datetime", 20240115103000L))),
                    "SELECT * FROM t WHERE dt = :dt");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a string"));
        }

        @Test
        @DisplayName("datetime: invalid format rejected")
        void datetimeInvalidFormatRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("dt", "datetime", "not a datetime"))),
                    "SELECT * FROM t WHERE dt = :dt");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not a valid ISO datetime"));
        }

        @Test
        @DisplayName("null defaultValue always accepted")
        void nullDefaultAccepted() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("s", "string", null))),
                    "SELECT * FROM t WHERE x = :s");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }
    }

    // ========== P0-B.6: Declared-but-unused parameters ==========

    @Nested
    @DisplayName("Declared-but-unused parameter validation")
    class DeclaredButUnusedValidation {

        @Test
        @DisplayName("declared but not used in SQL — rejected")
        void declaredButNotUsedRejected() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("used", "string", "x"),
                    paramEntry("unused", "string", "y")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE x = :used");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("declared in options.parameters but not used in SQL"));
            assertTrue(ex.getMessage().contains("unused"));
        }

        @Test
        @DisplayName("all declared parameters used — accepted")
        void allDeclaredUsedAccepted() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("x", "string", "a"),
                    paramEntry("y", "number", "1")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE a = :x AND b = :y");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("no SQL — cross-validation skipped")
        void noSqlCrossValidationSkipped() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("unused", "string", "x")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params), null);
            // No SQL to cross-reference, so declared-but-unused is not checked
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertFalse(meta.isEmpty());
            assertEquals("unused", meta.getParameters().get(0).getName());
        }

        @Test
        @DisplayName("empty SQL — cross-validation skipped")
        void emptySqlCrossValidationSkipped() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("unused", "string", "x")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params), "");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertFalse(meta.isEmpty());
        }
    }

    // ========== P0-B.7: SQL references to undeclared parameters ==========

    @Nested
    @DisplayName("Undeclared SQL parameter validation")
    class UndeclaredSqlParameterValidation {

        @Test
        @DisplayName("SQL references undeclared parameter — rejected")
        void sqlReferencesUndeclaredRejected() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("declared", "string", "x")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE x = :declared AND y = :undeclared");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not declared in options.parameters"));
            assertTrue(ex.getMessage().contains("undeclared"));
        }

        @Test
        @DisplayName("SQL with no params and no declarations — accepted")
        void noSqlParamsNoDeclarationsAccepted() {
            CollectionDefinition def = buildDef("test", new HashMap<>(),
                    "SELECT * FROM t WHERE x = 1");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("SQL with params but no declarations — rejected")
        void sqlParamsButNoDeclarationsRejected() {
            CollectionDefinition def = buildDef("test", new HashMap<>(),
                    "SELECT * FROM t WHERE x = :undeclared");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("not declared"));
        }

        @Test
        @DisplayName("repeated param in SQL with single declaration — accepted")
        void repeatedParamInSqlAccepted() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("x", "string", "val")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM t WHERE a = :x OR b = :x");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }
    }

    // ========== P0-B.8: Edge cases ==========

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null definition returns empty")
        void nullDefinitionReturnsEmpty() {
            SqlParameterMetadata meta = SqlParameterMetadata.from(null);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("empty parameter list returns empty")
        void emptyParameterListReturnsEmpty() {
            CollectionDefinition def = buildDef("test", optionsWithParams(List.of()), null);
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertTrue(meta.isEmpty());
        }

        @Test
        @DisplayName("missing name field rejected")
        void missingNameRejected() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("type", "string");
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(entry)), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("missing 'name'"));
        }

        @Test
        @DisplayName("blank name rejected")
        void blankNameRejected() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", "   ");
            entry.put("type", "string");
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(entry)), null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("missing 'name'"));
        }

        @Test
        @DisplayName("unsupported type rejected")
        void unsupportedTypeRejected() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("x", "array", "[]"))),
                    "SELECT * FROM t WHERE x = :x");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("Unsupported parameter type"));
        }

        @Test
        @DisplayName("required without defaultValue rejected")
        void requiredWithoutDefaultRejected() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "x");
            entry.put("type", "string");
            entry.put("required", true);
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(entry)),
                    "SELECT * FROM t WHERE x = :x");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("must have a defaultValue"));
        }

        @Test
        @DisplayName("multiple parameters with SQL cross-validation")
        void multipleParamsCrossValidation() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("status", "string", "active"),
                    paramEntry("min_age", "number", "18"),
                    paramEntry("is_active", "boolean", true)
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT * FROM users WHERE status = :status AND age > :min_age AND active = :is_active");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertEquals(3, meta.getParameters().size());
        }

        @Test
        @DisplayName("PostgreSQL ::cast in SQL with params is handled correctly")
        void postgresCastWithParams() {
            List<Map<String, Object>> params = List.of(
                    paramEntry("value", "number", "100")
            );
            CollectionDefinition def = buildDef("test", optionsWithParams(params),
                    "SELECT :value::integer FROM t");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            assertEquals(1, meta.getParameters().size());
        }
    }

    // ========== P0-B: Typed parameter value normalization ==========

    @Nested
    @DisplayName("Typed parameter value normalization")
    class TypedValueNormalization {

        @Test
        @DisplayName("string value remains String")
        void stringValueRemainsString() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("s", "string", "hello"))),
                    "SELECT * FROM t WHERE x = :s");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("s"));
            assertEquals(1, values.size());
            assertInstanceOf(String.class, values.get(0));
            assertEquals("hello", values.get(0));
        }

        @Test
        @DisplayName("number string '42' becomes Integer")
        void numberStringBecomesInteger() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", "42"))),
                    "SELECT * FROM t WHERE x = :n");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("n"));
            assertEquals(1, values.size());
            assertInstanceOf(Integer.class, values.get(0));
            assertEquals(42, values.get(0));
        }

        @Test
        @DisplayName("number integer literal remains Integer")
        void numberIntegerLiteralRemainsInteger() {
            // Jackson deserializes 42 as Integer
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", 42))),
                    "SELECT * FROM t WHERE x = :n");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("n"));
            assertEquals(1, values.size());
            assertInstanceOf(Integer.class, values.get(0));
            assertEquals(42, values.get(0));
        }

        @Test
        @DisplayName("number long string '9999999999' becomes Long")
        void numberLongStringBecomesLong() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", "9999999999"))),
                    "SELECT * FROM t WHERE x = :n");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("n"));
            assertEquals(1, values.size());
            assertInstanceOf(Long.class, values.get(0));
            assertEquals(9999999999L, values.get(0));
        }

        @Test
        @DisplayName("number decimal string '3.14' becomes BigDecimal")
        void numberDecimalStringBecomesBigDecimal() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("n", "number", "3.14"))),
                    "SELECT * FROM t WHERE x = :n");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("n"));
            assertEquals(1, values.size());
            assertInstanceOf(BigDecimal.class, values.get(0));
            assertEquals(new BigDecimal("3.14"), values.get(0));
        }

        @Test
        @DisplayName("boolean true string becomes Boolean.TRUE")
        void booleanTrueStringBecomesBoolean() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", "true"))),
                    "SELECT * FROM t WHERE x = :b");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("b"));
            assertEquals(1, values.size());
            assertInstanceOf(Boolean.class, values.get(0));
            assertEquals(Boolean.TRUE, values.get(0));
        }

        @Test
        @DisplayName("boolean false string becomes Boolean.FALSE")
        void booleanFalseStringBecomesBoolean() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", "false"))),
                    "SELECT * FROM t WHERE x = :b");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("b"));
            assertEquals(1, values.size());
            assertInstanceOf(Boolean.class, values.get(0));
            assertEquals(Boolean.FALSE, values.get(0));
        }

        @Test
        @DisplayName("boolean literal true remains Boolean")
        void booleanLiteralRemainsBoolean() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("b", "boolean", true))),
                    "SELECT * FROM t WHERE x = :b");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("b"));
            assertEquals(1, values.size());
            assertInstanceOf(Boolean.class, values.get(0));
            assertEquals(Boolean.TRUE, values.get(0));
        }

        @Test
        @DisplayName("date string '2024-01-15' becomes java.sql.Date")
        void dateStringBecomesSqlDate() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("d", "date", "2024-01-15"))),
                    "SELECT * FROM t WHERE d = :d");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("d"));
            assertEquals(1, values.size());
            assertInstanceOf(Date.class, values.get(0));
            assertEquals(Date.valueOf(LocalDate.of(2024, 1, 15)), values.get(0));
        }

        @Test
        @DisplayName("datetime string '2024-01-15T10:30:00' becomes java.sql.Timestamp")
        void datetimeStringBecomesSqlTimestamp() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("dt", "datetime", "2024-01-15T10:30:00"))),
                    "SELECT * FROM t WHERE dt = :dt");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("dt"));
            assertEquals(1, values.size());
            assertInstanceOf(Timestamp.class, values.get(0));
            assertEquals(Timestamp.valueOf(LocalDateTime.of(2024, 1, 15, 10, 30, 0)), values.get(0));
        }

        @Test
        @DisplayName("null defaultValue remains null")
        void nullDefaultRemainsNull() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(paramEntry("s", "string", null))),
                    "SELECT * FROM t WHERE x = :s");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("s"));
            assertEquals(1, values.size());
            assertNull(values.get(0));
        }

        @Test
        @DisplayName("multiple typed params all normalized correctly")
        void multipleTypedParamsAllNormalized() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(
                            paramEntry("s", "string", "hello"),
                            paramEntry("n", "number", "42"),
                            paramEntry("b", "boolean", "true"),
                            paramEntry("d", "date", "2024-06-01"),
                            paramEntry("dt", "datetime", "2024-06-01T12:00:00")
                    )),
                    "SELECT * FROM t WHERE a = :s AND b = :n AND c = :b AND d = :d AND e = :dt");
            SqlParameterMetadata meta = SqlParameterMetadata.from(def);
            List<Object> values = meta.buildValueList(List.of("s", "n", "b", "d", "dt"));
            assertEquals(5, values.size());
            assertInstanceOf(String.class, values.get(0));
            assertInstanceOf(Integer.class, values.get(1));
            assertInstanceOf(Boolean.class, values.get(2));
            assertInstanceOf(Date.class, values.get(3));
            assertInstanceOf(Timestamp.class, values.get(4));
        }
    }

    // ========== P0-A: currentUser path/type compatibility validation ==========

    @Nested
    @DisplayName("currentUser path/type compatibility validation")
    class CurrentUserPathTypeValidation {

        @Test
        @DisplayName("currentUser.id + number → valid")
        void currentUserIdWithNumberTypeValid() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(currentUserParam("userId", "number", "id"))),
                    "SELECT * FROM t WHERE owner_id = :userId");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("currentUser.email + string → valid")
        void currentUserEmailWithStringTypeValid() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(currentUserParam("userEmail", "string", "email"))),
                    "SELECT * FROM t WHERE email = :userEmail");
            assertDoesNotThrow(() -> SqlParameterMetadata.from(def));
        }

        @Test
        @DisplayName("currentUser.id + string → invalid")
        void currentUserIdWithStringTypeInvalid() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(currentUserParam("userId", "string", "id"))),
                    "SELECT * FROM t WHERE owner_id = :userId");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("userId"));
            assertTrue(ex.getMessage().contains("'number'"));
            assertTrue(ex.getMessage().contains("'string'"));
        }

        @Test
        @DisplayName("currentUser.email + number → invalid")
        void currentUserEmailWithNumberTypeInvalid() {
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(currentUserParam("userEmail", "number", "email"))),
                    "SELECT * FROM t WHERE email = :userEmail");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("userEmail"));
            assertTrue(ex.getMessage().contains("'string'"));
            assertTrue(ex.getMessage().contains("'number'"));
        }

        @Test
        @DisplayName("currentUser + defaultValue → invalid")
        void currentUserWithDefaultValueInvalid() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "userId");
            entry.put("type", "number");
            entry.put("source", "currentUser");
            entry.put("path", "id");
            entry.put("defaultValue", 99);
            entry.put("required", true);
            CollectionDefinition def = buildDef("test",
                    optionsWithParams(List.of(entry)),
                    "SELECT * FROM t WHERE owner_id = :userId");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlParameterMetadata.from(def));
            assertTrue(ex.getMessage().contains("userId"));
            assertTrue(ex.getMessage().contains("defaultValue"));
        }
    }

    // ========== P1-F: SqlNamedParameterParser result immutability ==========

    @Nested
    @DisplayName("SqlNamedParameterParser result immutability")
    class ParameterParserResultImmutability {

        @Test
        @DisplayName("getParameterNames returns unmodifiable list")
        void parameterNamesIsUnmodifiable() {
            SqlNamedParameterParser.Result result =
                    SqlNamedParameterParser.parse("SELECT * FROM t WHERE x = :a AND y = :b");
            List<String> names = result.getParameterNames();
            assertThrows(UnsupportedOperationException.class, () -> names.add("c"));
        }
    }
}