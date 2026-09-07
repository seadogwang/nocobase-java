package com.nocobase.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IndexDefinition parsing and validation.
 */
@DisplayName("IndexDefinition")
class IndexDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ========================================================================
    // Builder tests
    // ========================================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds single-column non-unique index")
        void buildsSingleColumnNonUniqueIndex() {
            IndexDefinition def = IndexDefinition.builder()
                    .name("idx_users_name")
                    .tableName("users")
                    .addColumnName("name")
                    .unique(false)
                    .collectionName("users")
                    .build();

            assertEquals("idx_users_name", def.getName());
            assertEquals("users", def.getTableName());
            assertEquals(List.of("name"), def.getColumnNames());
            assertFalse(def.isUnique());
            assertEquals("users", def.getCollectionName());
        }

        @Test
        @DisplayName("builds multi-column unique index")
        void buildsMultiColumnUniqueIndex() {
            IndexDefinition def = IndexDefinition.builder()
                    .name("udx_orders_user_product")
                    .tableName("orders")
                    .columnNames(List.of("user_id", "product_id"))
                    .unique(true)
                    .collectionName("orders")
                    .build();

            assertEquals("udx_orders_user_product", def.getName());
            assertEquals("orders", def.getTableName());
            assertEquals(List.of("user_id", "product_id"), def.getColumnNames());
            assertTrue(def.isUnique());
        }

        @Test
        @DisplayName("throws on null name")
        void throwsOnNullName() {
            assertThrows(NullPointerException.class, () ->
                    IndexDefinition.builder()
                            .tableName("t")
                            .addColumnName("c")
                            .collectionName("c")
                            .build());
        }

        @Test
        @DisplayName("throws on null collectionName")
        void throwsOnNullCollectionName() {
            assertThrows(NullPointerException.class, () ->
                    IndexDefinition.builder()
                            .name("idx_t")
                            .tableName("t")
                            .addColumnName("c")
                            .build());
        }

        @Test
        @DisplayName("throws on invalid index name")
        void throwsOnInvalidIndexName() {
            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.builder()
                            .name("idx; DROP TABLE")
                            .tableName("t")
                            .addColumnName("c")
                            .collectionName("c")
                            .build());
        }

        @Test
        @DisplayName("throws on invalid column name")
        void throwsOnInvalidColumnName() {
            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.builder()
                            .name("idx_t")
                            .tableName("t")
                            .addColumnName("c; DROP TABLE")
                            .collectionName("c")
                            .build());
        }

        @Test
        @DisplayName("throws on invalid table name")
        void throwsOnInvalidTableName() {
            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.builder()
                            .name("idx_t")
                            .tableName("t; DROP TABLE")
                            .addColumnName("c")
                            .collectionName("c")
                            .build());
        }
    }

    // ========================================================================
    // Parse: field-level index/unique options
    // ========================================================================

    @Nested
    @DisplayName("Parse from field options")
    class FieldOptionsTests {

        @Test
        @DisplayName("parses field with index=true")
        void parsesFieldIndex() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "email", "string");
            field.setOptions("{\"index\": true}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            assertEquals("idx_users_email", def.getName());
            assertEquals("users", def.getTableName());
            assertEquals(List.of("email"), def.getColumnNames());
            assertFalse(def.isUnique());
        }

        @Test
        @DisplayName("parses field with unique=true")
        void parsesFieldUnique() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "username", "string");
            field.setOptions("{\"unique\": true}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            assertEquals("udx_users_username", def.getName());
            assertTrue(def.isUnique());
        }

        @Test
        @DisplayName("parses field with both index and unique (both added)")
        void parsesFieldBothIndexAndUnique() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "email", "string");
            field.setOptions("{\"index\": true, \"unique\": true}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);

            assertEquals(2, indexes.size());
            assertTrue(indexes.stream().anyMatch(d -> d.getName().equals("idx_users_email") && !d.isUnique()));
            assertTrue(indexes.stream().anyMatch(d -> d.getName().equals("udx_users_email") && d.isUnique()));
        }

        @Test
        @DisplayName("skips field with null options")
        void skipsFieldWithNullOptions() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "name", "string");
            // options is null

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);
            assertTrue(indexes.isEmpty());
        }

        @Test
        @DisplayName("throws on field with invalid JSON options")
        void throwsOnFieldWithInvalidJsonOptions() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "name", "string");
            field.setOptions("{not valid json}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws on field with invalid column name")
        void throwsOnFieldWithInvalidColumnName() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "bad;column", "string");
            field.setOptions("{\"index\": true}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("skips field with index=false")
        void skipsFieldWithIndexFalse() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");

            FieldEntity field = new FieldEntity("users", "name", "string");
            field.setOptions("{\"index\": false}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);
            assertTrue(indexes.isEmpty());
        }

        @Test
        @DisplayName("uses effectiveTableName for index name generation")
        void usesEffectiveTableName() {
            CollectionEntity coll = new CollectionEntity("my_collection", "My Collection", "physical");
            coll.setTableName("custom_table");

            FieldEntity field = new FieldEntity("my_collection", "code", "string");
            field.setOptions("{\"index\": true}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);

            assertEquals(1, indexes.size());
            assertEquals("idx_custom_table_code", indexes.get(0).getName());
            assertEquals("custom_table", indexes.get(0).getTableName());
        }
    }

    // ========================================================================
    // Parse: collection-level indexes
    // ========================================================================

    @Nested
    @DisplayName("Parse from collection options")
    class CollectionOptionsTests {

        @Test
        @DisplayName("parses collection-level multi-column index")
        void parsesCollectionLevelMultiColumnIndex() {
            CollectionEntity coll = new CollectionEntity("orders", "Orders", "physical");
            coll.setTableName("orders");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_orders_user_product\", \"fields\": [\"user_id\", \"product_id\"], \"unique\": false}]}");

            FieldEntity f1 = new FieldEntity("orders", "user_id", "bigInt");
            FieldEntity f2 = new FieldEntity("orders", "product_id", "bigInt");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(f1, f2), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            assertEquals("idx_orders_user_product", def.getName());
            assertEquals(List.of("user_id", "product_id"), def.getColumnNames());
            assertFalse(def.isUnique());
        }

        @Test
        @DisplayName("parses collection-level unique index")
        void parsesCollectionLevelUniqueIndex() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"udx_users_email\", \"fields\": [\"email\"], \"unique\": true}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(field), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            assertEquals("udx_users_email", def.getName());
            assertTrue(def.isUnique());
        }

        @Test
        @DisplayName("throws on collection-level index with empty name")
        void throwsOnCollectionLevelIndexWithEmptyName() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"\", \"fields\": [\"email\"], \"unique\": false}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws on collection-level index with empty fields")
        void throwsOnCollectionLevelIndexWithEmptyFields() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [], \"unique\": false}]}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("parses both field-level and collection-level indexes")
        void parsesBothFieldAndCollectionLevelIndexes() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_users_name_email\", \"fields\": [\"name\", \"email\"], \"unique\": true}]}");

            FieldEntity f1 = new FieldEntity("users", "name", "string");
            FieldEntity f2 = new FieldEntity("users", "email", "string");
            f2.setOptions("{\"index\": true}");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(f1, f2), coll, objectMapper);

            // 1 from field options (index on email) + 1 from collection options
            assertEquals(2, indexes.size());
        }

        @Test
        @DisplayName("throws on invalid collection options JSON")
        void throwsOnInvalidCollectionOptionsJson() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{not valid}");

            FieldEntity field = new FieldEntity("users", "name", "string");
            field.setOptions("{\"index\": true}");

            // Invalid collection options JSON should cause parse to fail-fast
            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }
    }

    // ========================================================================
    // Parse: collection-level index validation failures (P0-B)
    // ========================================================================

    @Nested
    @DisplayName("Collection-level index validation failures")
    class CollectionIndexValidationTests {

        @Test
        @DisplayName("throws when indexes is not an array")
        void throwsWhenIndexesIsNotArray() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": \"not_an_array\"}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when indexes is a number")
        void throwsWhenIndexesIsNumber() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": 42}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when index item is not an object")
        void throwsWhenIndexItemIsNotObject() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [\"not_an_object\"]}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when index item is a number")
        void throwsWhenIndexItemIsNumber() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [123]}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when index name is missing (null)")
        void throwsWhenIndexNameIsNull() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"fields\": [\"email\"]}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when index fields is missing (null)")
        void throwsWhenIndexFieldsIsNull() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\"}]}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when fields is not an array")
        void throwsWhenFieldsIsNotArray() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": \"not_an_array\"}]}");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when fields contains non-string elements")
        void throwsWhenFieldsContainsNonString() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [\"email\", 123]}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when fields contains blank strings")
        void throwsWhenFieldsContainsBlank() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [\"email\", \"  \"]}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when field referenced in index does not exist")
        void throwsWhenFieldDoesNotExist() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [\"nonexistent_field\"]}]}");

            FieldEntity field = new FieldEntity("users", "email", "string");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(field), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when field referenced in index is a relation field")
        void throwsWhenFieldIsRelation() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [\"profile\"]}]}");

            FieldEntity nameField = new FieldEntity("users", "name", "string");
            FieldEntity relField = new FieldEntity("users", "profile", "hasOne");
            relField.setTarget("profiles");
            relField.setForeignKey("user_id");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(nameField, relField), coll, objectMapper));
        }

        @Test
        @DisplayName("throws when field referenced in index is a virtual field (hasMany)")
        void throwsWhenFieldIsVirtualHasMany() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_test\", \"fields\": [\"posts\"]}]}");

            FieldEntity nameField = new FieldEntity("users", "name", "string");
            FieldEntity relField = new FieldEntity("users", "posts", "hasMany");
            relField.setTarget("posts");
            relField.setForeignKey("user_id");

            assertThrows(IllegalArgumentException.class, () ->
                    IndexDefinition.parse(List.of(nameField, relField), coll, objectMapper));
        }
    }

    // ========================================================================
    // Parse: effective column name resolution (P0-B)
    // ========================================================================

    @Nested
    @DisplayName("Effective column name resolution for collection-level indexes")
    class EffectiveColumnNameResolutionTests {

        @Test
        @DisplayName("resolves belongsTo field to its foreignKey column name")
        void resolvesBelongsToFieldToForeignKey() {
            CollectionEntity coll = new CollectionEntity("orders", "Orders", "physical");
            coll.setTableName("orders");
            // Index on the belongsTo field "user" — should resolve to FK column "user_id"
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_orders_user\", \"fields\": [\"user\"], \"unique\": false}]}");

            FieldEntity nameField = new FieldEntity("orders", "name", "string");
            FieldEntity userField = new FieldEntity("orders", "user", "belongsTo");
            userField.setTarget("users");
            userField.setForeignKey("user_id");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(nameField, userField), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            assertEquals("idx_orders_user", def.getName());
            // Should resolve to the effective column name (user_id), not the field name (user)
            assertEquals(List.of("user_id"), def.getColumnNames());
        }

        @Test
        @DisplayName("resolves multi-column index with belongsTo and regular fields")
        void resolvesMultiColumnIndexWithBelongsTo() {
            CollectionEntity coll = new CollectionEntity("orders", "Orders", "physical");
            coll.setTableName("orders");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_orders_user_status\", \"fields\": [\"user\", \"status\"], \"unique\": false}]}");

            FieldEntity statusField = new FieldEntity("orders", "status", "string");
            FieldEntity userField = new FieldEntity("orders", "user", "belongsTo");
            userField.setTarget("users");
            userField.setForeignKey("user_id");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(statusField, userField), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            // user → user_id, status → status
            assertEquals(List.of("user_id", "status"), def.getColumnNames());
        }

        @Test
        @DisplayName("uses field name as effective column name for regular fields")
        void usesFieldNameForRegularFields() {
            CollectionEntity coll = new CollectionEntity("users", "Users", "physical");
            coll.setTableName("users");
            coll.setOptions("{\"indexes\": [{\"name\": \"idx_users_name_email\", \"fields\": [\"name\", \"email\"], \"unique\": true}]}");

            FieldEntity nameField = new FieldEntity("users", "name", "string");
            FieldEntity emailField = new FieldEntity("users", "email", "string");

            List<IndexDefinition> indexes = IndexDefinition.parse(List.of(nameField, emailField), coll, objectMapper);

            assertEquals(1, indexes.size());
            IndexDefinition def = indexes.get(0);
            // Regular fields use their own name as effective column name
            assertEquals(List.of("name", "email"), def.getColumnNames());
        }
    }

    // ========================================================================
    // Equals / hashCode
    // ========================================================================

    @Test
    @DisplayName("equals returns true for identical definitions")
    void equalsReturnsTrueForIdentical() {
        IndexDefinition a = IndexDefinition.builder()
                .name("idx_t_c").tableName("t").addColumnName("c").collectionName("coll").build();
        IndexDefinition b = IndexDefinition.builder()
                .name("idx_t_c").tableName("t").addColumnName("c").collectionName("coll").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals returns false for different definitions")
    void equalsReturnsFalseForDifferent() {
        IndexDefinition a = IndexDefinition.builder()
                .name("idx_t_c1").tableName("t").addColumnName("c1").collectionName("coll").build();
        IndexDefinition b = IndexDefinition.builder()
                .name("idx_t_c2").tableName("t").addColumnName("c2").collectionName("coll").build();

        assertNotEquals(a, b);
    }
}