package com.nocobase.field;

import java.util.Map;

/**
 * Maps NocoBase field types to database column types and runtime behavior.
 * Central registry for field type definitions.
 */
public class FieldTypeMapper {

    private static final Map<String, FieldTypeDescriptor> REGISTRY = Map.ofEntries(
            // Scalar types
            Map.entry("string", new FieldTypeDescriptor("string", "VARCHAR(255)", true, false, false)),
            Map.entry("text", new FieldTypeDescriptor("text", "TEXT", true, false, false)),
            Map.entry("integer", new FieldTypeDescriptor("integer", "INTEGER", true, false, false)),
            Map.entry("bigint", new FieldTypeDescriptor("bigInt", "BIGINT", true, false, false)),
            Map.entry("float", new FieldTypeDescriptor("float", "FLOAT", true, false, false)),
            Map.entry("double", new FieldTypeDescriptor("double", "DOUBLE", true, false, false)),
            Map.entry("boolean", new FieldTypeDescriptor("boolean", "BOOLEAN", true, false, false)),
            Map.entry("date", new FieldTypeDescriptor("date", "DATE", true, false, false)),
            Map.entry("datetime", new FieldTypeDescriptor("datetime", "TIMESTAMP", true, false, false)),
            Map.entry("json", new FieldTypeDescriptor("json", "TEXT", true, false, false)),
            Map.entry("uuid", new FieldTypeDescriptor("uuid", "VARCHAR(36)", true, false, false)),
            Map.entry("password", new FieldTypeDescriptor("password", "VARCHAR(255)", true, false, false)),

            // Relationship types
            Map.entry("belongsto", new FieldTypeDescriptor("belongsTo", null, false, false, true)),
            Map.entry("hasone", new FieldTypeDescriptor("hasOne", null, false, false, true)),
            Map.entry("hasmany", new FieldTypeDescriptor("hasMany", null, false, false, true)),
            Map.entry("belongstomany", new FieldTypeDescriptor("belongsToMany", null, false, false, true))
    );

    /**
     * Get the descriptor for a field type.
     */
    public static FieldTypeDescriptor get(String type) {
        if (type == null) return FieldTypeDescriptor.UNKNOWN;
        return REGISTRY.getOrDefault(type.toLowerCase(), FieldTypeDescriptor.UNKNOWN);
    }

    /**
     * Returns true if the type is a known scalar type.
     */
    public static boolean isScalar(String type) {
        FieldTypeDescriptor desc = get(type);
        return desc.isScalar();
    }

    /**
     * Returns true if the type is a relationship type.
     */
    public static boolean isRelation(String type) {
        FieldTypeDescriptor desc = get(type);
        return desc.isRelation();
    }

    /**
     * Returns the SQL column type for a field type.
     */
    public static String getSqlType(String type) {
        return get(type).getSqlType();
    }
}