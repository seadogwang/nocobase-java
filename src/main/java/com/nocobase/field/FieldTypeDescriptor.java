package com.nocobase.field;

/**
 * Descriptor for a NocoBase field type, defining its SQL mapping and runtime behavior.
 */
public class FieldTypeDescriptor {
    private final String type;
    private final String sqlType;
    private final boolean scalar;
    private final boolean virtual;
    private final boolean relation;

    public FieldTypeDescriptor(String type, String sqlType, boolean scalar, boolean virtual, boolean relation) {
        this.type = type;
        this.sqlType = sqlType;
        this.scalar = scalar;
        this.virtual = virtual;
        this.relation = relation;
    }

    public String getType() { return type; }
    public String getSqlType() { return sqlType; }
    public boolean isScalar() { return scalar; }
    public boolean isVirtual() { return virtual; }
    public boolean isRelation() { return relation; }

    /** Unknown/unsupported type. */
    public static final FieldTypeDescriptor UNKNOWN = new FieldTypeDescriptor("unknown", null, false, false, false);
}