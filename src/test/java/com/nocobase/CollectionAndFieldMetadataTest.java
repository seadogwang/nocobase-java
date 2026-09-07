package com.nocobase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.entity.CollectionEntity;
import com.nocobase.entity.FieldEntity;
import com.nocobase.repository.CollectionRepository;
import com.nocobase.repository.FieldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for B1: Collection metadata model and B2: Field metadata model.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CollectionAndFieldMetadataTest {

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private FieldRepository fieldRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Clean up any existing test data (order matters due to foreign keys)
        fieldRepository.deleteAll();
        collectionRepository.deleteAll();
    }

    private CollectionEntity createTestCollection(String name) {
        CollectionEntity collection = new CollectionEntity(name, name, "physical");
        collection.setTableName(name);
        return collectionRepository.save(collection);
    }

    // --- B1: Collection Metadata Model Tests ---

    @Test
    @DisplayName("B1: Should save and read physical table collection")
    void savePhysicalTableCollection() {
        CollectionEntity collection = new CollectionEntity("test_table", "Test Table", "physical");
        collection.setTableName("test_table");
        collection.setSystem(false);

        CollectionEntity saved = collectionRepository.save(collection);
        assertNotNull(saved.getId());

        Optional<CollectionEntity> found = collectionRepository.findByName("test_table");
        assertTrue(found.isPresent());
        assertEquals("physical", found.get().getType());
        assertEquals("test_table", found.get().getEffectiveTableName());
    }

    @Test
    @DisplayName("B1: Should save and read view collection")
    void saveViewCollection() {
        CollectionEntity collection = new CollectionEntity("test_view", "Test View", "view");
        collection.setView(true);

        CollectionEntity saved = collectionRepository.save(collection);
        assertNotNull(saved.getId());
        assertTrue(saved.isView());
        assertFalse(saved.isPhysical());
    }

    @Test
    @DisplayName("B1: Should save and read SQL collection")
    void saveSqlCollection() {
        CollectionEntity collection = new CollectionEntity("test_sql", "Test SQL", "sql");
        collection.setSql("SELECT * FROM users WHERE active = true");

        CollectionEntity saved = collectionRepository.save(collection);
        assertNotNull(saved.getId());
        assertTrue(saved.isSql());
        assertEquals("SELECT * FROM users WHERE active = true", saved.getSql());
    }

    @Test
    @DisplayName("B1: Should mark system collection")
    void systemCollection() {
        CollectionEntity collection = new CollectionEntity("sys_col", "System", "physical");
        collection.setSystem(true);

        CollectionEntity saved = collectionRepository.save(collection);
        assertTrue(saved.getSystem());
    }

    @Test
    @DisplayName("B1: Should store JSON options and inherits")
    void jsonFields() {
        CollectionEntity collection = new CollectionEntity("json_test", "JSON Test", "physical");
        collection.setOptions("{\"timestamps\":true,\"paranoid\":false}");
        collection.setInherits("[\"base_collection\"]");

        CollectionEntity saved = collectionRepository.save(collection);
        assertEquals("{\"timestamps\":true,\"paranoid\":false}", saved.getOptions());
        assertEquals("[\"base_collection\"]", saved.getInherits());
    }

    @Test
    @DisplayName("B1: Should not break /api/collections:list compatibility")
    void listCollectionsCompatible() {
        // Create a collection that should appear in list
        CollectionEntity collection = new CollectionEntity("compat_test", "Compat Test", "physical");
        collection.setTableName("compat_test");
        collectionRepository.save(collection);

        List<CollectionEntity> all = collectionRepository.findAll();
        assertFalse(all.isEmpty());
        assertTrue(all.stream().anyMatch(c -> "compat_test".equals(c.getName())));
    }

    // --- B2: Field Metadata Model Tests ---

    @Test
    @DisplayName("B2: Should save and read string field")
    void saveStringField() {
        createTestCollection("test_collection");
        FieldEntity field = new FieldEntity("test_collection", "title", "string");
        field.setInterfaceType("input");

        FieldEntity saved = fieldRepository.save(field);
        assertNotNull(saved.getId());
        assertEquals("title", saved.getName());
        assertEquals("string", saved.getType());
        assertFalse(saved.isRelation());
    }

    @Test
    @DisplayName("B2: Should save integer field")
    void saveIntegerField() {
        createTestCollection("test_collection");
        FieldEntity field = new FieldEntity("test_collection", "age", "integer");
        field.setInterfaceType("number");

        FieldEntity saved = fieldRepository.save(field);
        assertEquals("integer", saved.getType());
        assertTrue(saved.generatesPhysicalColumn());
    }

    @Test
    @DisplayName("B2: Should save boolean field")
    void saveBooleanField() {
        createTestCollection("test_collection");
        FieldEntity field = new FieldEntity("test_collection", "active", "boolean");
        FieldEntity saved = fieldRepository.save(field);
        assertEquals("boolean", saved.getType());
    }

    @Test
    @DisplayName("B2: Should save datetime field")
    void saveDatetimeField() {
        createTestCollection("test_collection");
        FieldEntity field = new FieldEntity("test_collection", "published_at", "datetime");
        FieldEntity saved = fieldRepository.save(field);
        assertEquals("datetime", saved.getType());
    }

    @Test
    @DisplayName("B2: Should save JSON field")
    void saveJsonField() {
        createTestCollection("test_collection");
        FieldEntity field = new FieldEntity("test_collection", "metadata", "json");
        FieldEntity saved = fieldRepository.save(field);
        assertEquals("json", saved.getType());
    }

    @Test
    @DisplayName("B2: Should save belongsTo relationship field")
    void saveBelongsToField() {
        createTestCollection("test_articles");
        createTestCollection("test_users");
        FieldEntity field = new FieldEntity("test_articles", "author", "belongsTo");
        field.setTarget("test_users");
        field.setForeignKey("author_id");
        field.setTargetKey("id");

        FieldEntity saved = fieldRepository.save(field);
        assertTrue(saved.isRelation());
        assertEquals("belongsTo", saved.getType());
        assertEquals("test_users", saved.getTarget());
        assertEquals("author_id", saved.getForeignKey());
        // belongsTo with foreignKey generates a physical column
        assertTrue(saved.generatesPhysicalColumn());
        assertEquals("author_id", saved.getEffectiveColumnName());
    }

    @Test
    @DisplayName("B2: Should save hasMany relationship field")
    void saveHasManyField() {
        createTestCollection("test_users");
        createTestCollection("test_articles");
        FieldEntity field = new FieldEntity("test_users", "test_articles", "hasMany");
        field.setTarget("test_articles");
        field.setForeignKey("author_id");
        field.setSourceKey("id");

        FieldEntity saved = fieldRepository.save(field);
        assertTrue(saved.isRelation());
        assertEquals("hasMany", saved.getType());
        // hasMany does NOT generate a physical column on users table
        assertFalse(saved.generatesPhysicalColumn());
    }

    @Test
    @DisplayName("B2: Should save belongsToMany relationship field")
    void saveBelongsToManyField() {
        createTestCollection("test_articles2");
        createTestCollection("test_tags");
        createTestCollection("test_article_tags");
        FieldEntity field = new FieldEntity("test_articles2", "test_tags", "belongsToMany");
        field.setTarget("test_tags");
        field.setThrough("test_article_tags");
        field.setForeignKey("article_id");
        field.setOtherKey("tag_id");
        field.setSourceKey("id");
        field.setTargetKey("id");

        FieldEntity saved = fieldRepository.save(field);
        assertTrue(saved.isRelation());
        assertEquals("belongsToMany", saved.getType());
        assertEquals("test_article_tags", saved.getThrough());
        // belongsToMany does NOT generate a physical column
        assertFalse(saved.generatesPhysicalColumn());
    }

    @Test
    @DisplayName("B2: Should reject illegal field names")
    void rejectIllegalFieldNames() {
        // Test SQL injection
        FieldEntity field1 = new FieldEntity("test", "name;DROP TABLE users;--", "string");
        assertThrows(IllegalArgumentException.class, field1::validateFieldName);

        // Test empty name
        FieldEntity field2 = new FieldEntity("test", "", "string");
        assertThrows(IllegalArgumentException.class, field2::validateFieldName);

        // Test SQL comment injection
        FieldEntity field3 = new FieldEntity("test", "name/*comment*/", "string");
        assertThrows(IllegalArgumentException.class, field3::validateFieldName);
    }

    @Test
    @DisplayName("B2: Should accept valid field names")
    void acceptValidFieldNames() {
        // Valid names should not throw
        assertDoesNotThrow(() -> {
            FieldEntity field = new FieldEntity("test", "valid_name_123", "string");
            field.validateFieldName();
        });

        assertDoesNotThrow(() -> {
            FieldEntity field = new FieldEntity("test", "_private", "string");
            field.validateFieldName();
        });
    }

    @Test
    @DisplayName("B2: Relationship fields should not be marked as generating physical columns")
    void relationshipFieldsNoPhysicalColumn() {
        String[] relTypes = {"hasOne", "hasMany", "belongsToMany"};
        for (String type : relTypes) {
            FieldEntity field = new FieldEntity("test", "rel_" + type, type);
            field.setTarget("other");
            assertFalse(field.generatesPhysicalColumn(),
                type + " should not generate physical column");
        }
    }
}