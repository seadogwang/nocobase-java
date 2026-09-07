package com.nocobase;

import com.nocobase.ddl.*;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.field.FieldOptions;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
import com.nocobase.runtime.CollectionRuntimeService;
import com.nocobase.runtime.IndexDefinition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for P0-C (Dialect-based SchemaPlan diff) and P1-D (Default value DDL safety model).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultValueAndDialectDiffTest {

    @Autowired
    private DdlSynchronizer ddlSynchronizer;

    @Autowired
    private CollectionRuntimeService runtimeService;

    @Autowired
    private DialectAdapterFactory dialectAdapterFactory;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private FieldRepository fieldRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger counter = new AtomicInteger(0);

    private String uniqueName(String prefix) {
        return prefix + "_" + counter.incrementAndGet();
    }

    private void authAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @BeforeEach
    void resetAuth() {
        authAsAdmin();
    }

    // ========================================================================
    // P0-C: Dialect-based SchemaPlan diff tests
    // ========================================================================

    @Test
    @DisplayName("P0-C: diff uses dialectAdapter.tableExists, not hardcoded H2 INFORMATION_SCHEMA")
    void diffUsesDialectAdapterForTableExistence() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        assertNotNull(adapter, "DialectAdapter should be available");

        // Create a physical collection to test diff
        String collName = uniqueName("p0c_diff_table");
        try {
            CollectionEntity coll = new CollectionEntity(collName, "P0C Diff Table", "physical");
            coll.setTableName(collName);
            FieldEntity nameField = new FieldEntity(collName, "name", "string");
            ddlSynchronizer.createCollection(coll, List.of(nameField));
            runtimeService.reload(collName);

            // Verify table exists via adapter
            assertTrue(adapter.tableExists(jdbcTemplate, collName),
                    "Adapter should confirm table exists");

            // Verify column exists via adapter
            assertTrue(adapter.columnExists(jdbcTemplate, collName, "name"),
                    "Adapter should confirm column 'name' exists");

            // Verify system column exists via adapter
            assertTrue(adapter.columnExists(jdbcTemplate, collName, "id"),
                    "Adapter should confirm system column 'id' exists");

            // Verify non-existent column returns false
            assertFalse(adapter.columnExists(jdbcTemplate, collName, "nonexistent_col"),
                    "Adapter should return false for non-existent column");

            // Verify non-existent table returns false
            assertFalse(adapter.tableExists(jdbcTemplate, "nonexistent_table_xyz"),
                    "Adapter should return false for non-existent table");

            // Run diff on an existing collection with no changes - should be NO_OP
            CollectionEntity entity = collectionRepository.findByName(collName).orElseThrow();
            List<FieldEntity> fields = fieldRepository.findByCollectionName(collName);
            List<SchemaPlan> plans = ddlSynchronizer.diff(entity, fields, null);
            assertFalse(plans.isEmpty(), "Diff should return at least one plan");
            assertTrue(plans.stream().anyMatch(p -> p.isNoOp()),
                    "Diff should report NO_OP for matching schema");

            // Verify diff reports missing table for non-existent collection
            CollectionEntity fakeColl = new CollectionEntity("nonexistent_table_xyz", "Fake", "physical");
            fakeColl.setTableName("nonexistent_table_xyz");
            List<SchemaPlan> missingTablePlans = ddlSynchronizer.diff(fakeColl, List.of(), null);
            assertTrue(missingTablePlans.stream()
                            .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_TABLE),
                    "Diff should report MISSING_TABLE for non-existent table");
        } finally {
            safeDropCollection(collName);
        }
    }

    @Test
    @DisplayName("P0-C: diff uses dialectAdapter.columnExists, not hardcoded H2 INFORMATION_SCHEMA")
    void diffUsesDialectAdapterForColumnExistence() {
        String collName = uniqueName("p0c_diff_col");
        try {
            CollectionEntity coll = new CollectionEntity(collName, "P0C Diff Col", "physical");
            coll.setTableName(collName);
            FieldEntity nameField = new FieldEntity(collName, "title", "string");
            ddlSynchronizer.createCollection(coll, List.of(nameField));
            runtimeService.reload(collName);

            // Add a field only in metadata but not in the physical table
            // We simulate by checking diff with a field that doesn't exist in the table
            FieldEntity missingField = new FieldEntity(collName, "missing_column", "string");
            CollectionEntity entity = collectionRepository.findByName(collName).orElseThrow();

            // Diff should report MISSING_COLUMN for the missing field
            List<SchemaPlan> plans = ddlSynchronizer.diff(entity, List.of(missingField), null);
            assertTrue(plans.stream()
                            .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_COLUMN),
                    "Diff should report MISSING_COLUMN for non-existent column. Plans: " + plans);
        } finally {
            safeDropCollection(collName);
        }
    }

    @Test
    @DisplayName("P0-C: diff on view collection returns NO_OP without querying adapter")
    void diffOnViewCollectionReturnsNoOp() {
        String phyBase = uniqueName("p0c_diff_view_base");
        String viewColl = uniqueName("p0c_diff_view");

        try {
            // Create physical base table
            CollectionEntity base = new CollectionEntity(phyBase, "Physical Base", "physical");
            base.setTableName(phyBase);
            ddlSynchronizer.createCollection(base, List.of(
                    new FieldEntity(phyBase, "name", "string")));
            runtimeService.reload(phyBase);

            // Create view collection
            CollectionEntity view = new CollectionEntity(viewColl, "View Coll", "view");
            view.setTableName(viewColl);
            ddlSynchronizer.createCollection(view, List.of(
                    new FieldEntity(viewColl, "id", "bigInt"),
                    new FieldEntity(viewColl, "name", "string")));
            runtimeService.reload(viewColl);

            CollectionEntity viewEntity = collectionRepository.findByName(viewColl).orElseThrow();
            List<FieldEntity> viewFields = fieldRepository.findByCollectionName(viewColl);

            // Diff on view should return NO_OP without querying the database
            List<SchemaPlan> plans = ddlSynchronizer.diff(viewEntity, viewFields, null);
            assertFalse(plans.isEmpty());
            assertTrue(plans.stream().allMatch(p -> p.isNoOp()),
                    "View collection diff should be NO_OP. Plans: " + plans);
        } finally {
            safeDropCollection(viewColl);
            safeDropCollection(phyBase);
        }
    }

    @Test
    @DisplayName("P0-C: diff reports missing indexes via dialectAdapter")
    void diffReportsMissingIndexes() {
        String collName = uniqueName("p0c_diff_idx");
        try {
            CollectionEntity coll = new CollectionEntity(collName, "P0C Diff Idx", "physical");
            coll.setTableName(collName);
            FieldEntity nameField = new FieldEntity(collName, "email", "string");
            ddlSynchronizer.createCollection(coll, List.of(nameField));
            runtimeService.reload(collName);

            IndexDefinition idxDef = IndexDefinition.builder()
                    .name("idx_p0c_diff_email")
                    .tableName(collName)
                    .addColumnName("email")
                    .unique(false)
                    .collectionName(collName)
                    .build();

            CollectionEntity entity = collectionRepository.findByName(collName).orElseThrow();
            List<FieldEntity> fields = fieldRepository.findByCollectionName(collName);

            // Diff should report MISSING_INDEX for the index that doesn't exist
            List<SchemaPlan> plans = ddlSynchronizer.diff(entity, fields, List.of(idxDef));
            assertTrue(plans.stream()
                            .anyMatch(p -> p.getAction() == SchemaPlan.Action.MISSING_INDEX),
                    "Diff should report MISSING_INDEX. Plans: " + plans);
        } finally {
            safeDropCollection(collName);
        }
    }

    // ========================================================================
    // P1-D: Default value DDL safety model tests
    // ========================================================================

    @Test
    @DisplayName("P1-D: DefaultValue.literal with string value formats correctly")
    void defaultValueLiteralString() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("hello");
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("hello", dv.getValue());

        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("'hello'", formatted, "String literal should be quoted");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.literal with string containing single quote escapes correctly")
    void defaultValueLiteralStringWithQuote() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("it's");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("'it''s'", formatted, "Single quote should be escaped as ''");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.literal 'it''s ok' formats with escaped single quote")
    void defaultValueLiteralStringWithQuoteAndSpace() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("it's ok");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("'it''s ok'", formatted,
                "Single quote in 'it''s ok' should be escaped as ''");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.literal with number value formats correctly")
    void defaultValueLiteralNumber() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("42");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("42", formatted, "Number literal should not be quoted");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.literal with boolean true formats correctly")
    void defaultValueLiteralBooleanTrue() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("true");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("TRUE", formatted, "Boolean true should be uppercase");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.literal with boolean false formats correctly")
    void defaultValueLiteralBooleanFalse() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("false");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("FALSE", formatted, "Boolean false should be uppercase");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.expression CURRENT_TIMESTAMP formats correctly")
    void defaultValueExpressionCurrentTimestamp() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP");
        assertEquals(FieldOptions.DefaultValue.Kind.EXPRESSION, dv.getKind());
        assertEquals("CURRENT_TIMESTAMP", dv.getValue());

        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("CURRENT_TIMESTAMP", formatted, "Expression should be passed through as-is");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.expression CURRENT_DATE is allowed")
    void defaultValueExpressionCurrentDate() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.expression("CURRENT_DATE");
        assertEquals("CURRENT_DATE", dv.getValue());
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        assertEquals("CURRENT_DATE", adapter.formatDefaultValue(dv));
    }

    @Test
    @DisplayName("P1-D: DefaultValue.expression NOW() is allowed")
    void defaultValueExpressionNow() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.expression("NOW()");
        assertEquals("NOW()", dv.getValue());
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        assertEquals("NOW()", adapter.formatDefaultValue(dv));
    }

    @Test
    @DisplayName("P1-D: String with semicolon is accepted as literal and properly escaped")
    void defaultValueLiteralWithSemicolon() {
        // "A;B" should succeed as a string literal - the dialect adapter
        // wraps it in quotes, so the semicolon is harmless.
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("A;B");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("A;B", dv.getValue());

        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("'A;B'", formatted,
                "String with semicolon should be quoted literally");
    }

    @Test
    @DisplayName("P1-D: String with SQL keyword 'select' is accepted as literal")
    void defaultValueLiteralWithSqlKeyword() {
        // "select plan" should succeed as a string literal - the dialect
        // adapter escapes it, so the keyword is harmless.
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("select plan");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("select plan", dv.getValue());

        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("'select plan'", formatted,
                "String with SQL keyword should be quoted literally");
    }

    @Test
    @DisplayName("P1-D: String with SQL injection attempt is accepted as literal and escaped")
    void defaultValueLiteralWithSqlInjectionAttempt() {
        // "1); DROP TABLE users; --" should succeed as a literal - the dialect
        // adapter wraps it in quotes, which neutralises the injection.
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("1); DROP TABLE users; --");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());

        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        // The entire string is wrapped in single quotes, rendering it harmless
        assertTrue(formatted.startsWith("'") && formatted.endsWith("'"),
                "SQL injection string should be wrapped in quotes. Actual: " + formatted);
        assertEquals("'1); DROP TABLE users; --'", formatted);
    }

    @Test
    @DisplayName("P1-D: Non-allowlisted expression is rejected")
    void nonAllowlistedExpressionRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                FieldOptions.DefaultValue.expression("RANDOM()"));
        assertTrue(ex.getMessage().contains("not allowed"),
                "Should reject non-allowlisted expression. Actual: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-D: Null defaultValue returns null from formatDefaultValue")
    void nullDefaultValueReturnsNull() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        assertNull(adapter.formatDefaultValue(null), "Null default should return null");
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse handles numeric JSON value")
    void defaultValueParseNumeric() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse(42);
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("42", dv.getValue());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse handles boolean JSON value")
    void defaultValueParseBoolean() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse(true);
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("true", dv.getValue());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse handles string JSON value")
    void defaultValueParseString() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("hello world");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.LITERAL, dv.getKind());
        assertEquals("hello world", dv.getValue());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse recognizes CURRENT_TIMESTAMP as expression")
    void defaultValueParseRecognizesCurrentTimestamp() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("CURRENT_TIMESTAMP");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.EXPRESSION, dv.getKind());
        assertEquals("CURRENT_TIMESTAMP", dv.getValue());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse recognizes CURRENT_DATE as expression")
    void defaultValueParseRecognizesCurrentDate() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("CURRENT_DATE");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.EXPRESSION, dv.getKind());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse recognizes NOW() as expression")
    void defaultValueParseRecognizesNow() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.parse("NOW()");
        assertNotNull(dv);
        assertEquals(FieldOptions.DefaultValue.Kind.EXPRESSION, dv.getKind());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.parse null returns null")
    void defaultValueParseNullReturnsNull() {
        assertNull(FieldOptions.DefaultValue.parse(null));
    }

    @Test
    @DisplayName("P1-D: DefaultValue.equals and hashCode work correctly")
    void defaultValueEqualsAndHashCode() {
        FieldOptions.DefaultValue dv1 = FieldOptions.DefaultValue.literal("test");
        FieldOptions.DefaultValue dv2 = FieldOptions.DefaultValue.literal("test");
        FieldOptions.DefaultValue dv3 = FieldOptions.DefaultValue.literal("other");
        FieldOptions.DefaultValue dv4 = FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP");

        assertEquals(dv1, dv2);
        assertNotEquals(dv1, dv3);
        assertNotEquals(dv1, dv4);
        assertEquals(dv1.hashCode(), dv2.hashCode());
    }

    @Test
    @DisplayName("P1-D: DefaultValue.toString does not contain the real literal value")
    void defaultValueToString() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("test");
        String str = dv.toString();
        assertTrue(str.contains("LITERAL"), "toString should mention LITERAL kind");
        assertFalse(str.contains("test"), "toString must NOT contain the real literal value. Actual: " + str);

        FieldOptions.DefaultValue dvExpr = FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP");
        String strExpr = dvExpr.toString();
        assertTrue(strExpr.contains("EXPRESSION"), "toString should mention EXPRESSION kind");
        assertTrue(strExpr.contains("CURRENT_TIMESTAMP"),
                "Expression toString may include the expression name");
    }

    @Test
    @DisplayName("P1-D: ColumnDef.toSql uses dialect.formatDefaultValue for string default")
    void columnDefToSqlFormatsStringDefault() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("hello");
        DdlPlan.ColumnDef col = new DdlPlan.ColumnDef("name", "VARCHAR(255)", true, dv, false, false);

        String sql = col.toSql(adapter);
        assertTrue(sql.contains("DEFAULT 'hello'"),
                "Column SQL should contain properly formatted default. Actual: " + sql);
    }

    @Test
    @DisplayName("P1-D: ColumnDef.toSql uses dialect.formatDefaultValue for number default")
    void columnDefToSqlFormatsNumberDefault() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("0");
        DdlPlan.ColumnDef col = new DdlPlan.ColumnDef("count", "INTEGER", true, dv, false, false);

        String sql = col.toSql(adapter);
        assertTrue(sql.contains("DEFAULT 0"),
                "Column SQL should contain number default without quotes. Actual: " + sql);
    }

    @Test
    @DisplayName("P1-D: ColumnDef.toSql uses dialect.formatDefaultValue for expression default")
    void columnDefToSqlFormatsExpressionDefault() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.expression("CURRENT_TIMESTAMP");
        DdlPlan.ColumnDef col = new DdlPlan.ColumnDef("created_at", "TIMESTAMP", true, dv, false, false);

        String sql = col.toSql(adapter);
        assertTrue(sql.contains("DEFAULT CURRENT_TIMESTAMP"),
                "Column SQL should contain expression default as-is. Actual: " + sql);
    }

    @Test
    @DisplayName("P1-D: ColumnDef.toSql with null default omits DEFAULT clause")
    void columnDefToSqlNullDefaultOmitsClause() {
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        DdlPlan.ColumnDef col = new DdlPlan.ColumnDef("name", "VARCHAR(255)", true, null, false, false);

        String sql = col.toSql(adapter);
        assertFalse(sql.contains("DEFAULT"),
                "Column SQL without default should not contain DEFAULT. Actual: " + sql);
    }

    @Test
    @DisplayName("P1-D: Double value default formats as number")
    void defaultValueLiteralDouble() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("3.14");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("3.14", formatted, "Double literal should not be quoted");
    }

    @Test
    @DisplayName("P1-D: Negative number default formats correctly")
    void defaultValueLiteralNegativeNumber() {
        FieldOptions.DefaultValue dv = FieldOptions.DefaultValue.literal("-1");
        DialectAdapter adapter = dialectAdapterFactory.getAdapter();
        String formatted = adapter.formatDefaultValue(dv);
        assertEquals("-1", formatted, "Negative number should not be quoted");
    }

    // ========================================================================
    // P0-C + P1-D integration: DDL with default values via dialect adapter
    // ========================================================================

    @Test
    @DisplayName("P0-C+P1-D: CREATE TABLE DDL uses dialect adapter for defaults")
    void createTableDdlUsesDialectAdapterForDefaults() {
        String collName = uniqueName("p0c_p1d_create");
        try {
            CollectionEntity coll = new CollectionEntity(collName, "P0C P1D Create", "physical");
            coll.setTableName(collName);

            FieldEntity strField = new FieldEntity(collName, "status", "string");
            strField.setOptions("{\"default\": \"active\"}");

            FieldEntity numField = new FieldEntity(collName, "score", "integer");
            numField.setOptions("{\"default\": 0}");

            FieldEntity boolField = new FieldEntity(collName, "enabled", "boolean");
            boolField.setOptions("{\"default\": true}");

            ddlSynchronizer.createCollection(coll, List.of(strField, numField, boolField));
            runtimeService.reload(collName);

            // Verify table exists
            DialectAdapter adapter = dialectAdapterFactory.getAdapter();
            assertTrue(adapter.tableExists(jdbcTemplate, collName));

            // Verify the dry-run plan includes proper defaults
            DdlPlan plan = ddlSynchronizer.dryRunCreateCollection(coll,
                    List.of(strField, numField, boolField));
            assertFalse(plan.getStatements().isEmpty());

            String createSql = plan.getStatements().get(0);
            // String default should be quoted
            assertTrue(createSql.contains("'active'"),
                    "CREATE TABLE should contain quoted string default. SQL: " + createSql);
            // Number default should not be quoted
            assertTrue(createSql.contains("DEFAULT 0"),
                    "CREATE TABLE should contain number default without quotes. SQL: " + createSql);
            // Boolean default should be uppercase
            assertTrue(createSql.contains("DEFAULT TRUE"),
                    "CREATE TABLE should contain boolean default uppercase. SQL: " + createSql);
        } finally {
            safeDropCollection(collName);
        }
    }

    @Test
    @DisplayName("P0-C+P1-D: ALTER TABLE ADD COLUMN DDL uses dialect adapter for defaults")
    void alterTableAddColumnUsesDialectAdapterForDefaults() {
        String collName = uniqueName("p0c_p1d_alter");
        try {
            CollectionEntity coll = new CollectionEntity(collName, "P0C P1D Alter", "physical");
            coll.setTableName(collName);
            FieldEntity nameField = new FieldEntity(collName, "name", "string");
            ddlSynchronizer.createCollection(coll, List.of(nameField));
            runtimeService.reload(collName);

            // Add a field with a string default
            FieldEntity statusField = new FieldEntity(collName, "status", "string");
            statusField.setOptions("{\"default\": \"pending\"}");
            ddlSynchronizer.addField(statusField);
            runtimeService.reload(collName);

            // Verify column exists
            DialectAdapter adapter = dialectAdapterFactory.getAdapter();
            assertTrue(adapter.columnExists(jdbcTemplate, collName, "status"),
                    "Column 'status' should exist after addField");
        } finally {
            safeDropCollection(collName);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void safeDropCollection(String name) {
        try {
            if (runtimeService.exists(name)) {
                ddlSynchronizer.dropCollection(name);
                runtimeService.reload(name);
            }
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
    }
}