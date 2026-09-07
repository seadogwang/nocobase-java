package com.nocobase.runtime;

import java.util.Set;

/**
 * Defines what operations are allowed on a collection based on its type.
 */
public class CollectionCapability {

    private final boolean readable;
    private final boolean writable;
    private final boolean schemaMutable;
    private final boolean indexMutable;
    private final boolean relationSupported;

    private CollectionCapability(boolean readable, boolean writable, boolean schemaMutable,
                                  boolean indexMutable, boolean relationSupported) {
        this.readable = readable;
        this.writable = writable;
        this.schemaMutable = schemaMutable;
        this.indexMutable = indexMutable;
        this.relationSupported = relationSupported;
    }

    public boolean isReadable() { return readable; }
    public boolean isWritable() { return writable; }
    public boolean isSchemaMutable() { return schemaMutable; }
    public boolean isIndexMutable() { return indexMutable; }
    public boolean isRelationSupported() { return relationSupported; }

    /**
     * Physical table: full capabilities.
     */
    public static final CollectionCapability PHYSICAL = new CollectionCapability(true, true, true, true, true);

    /**
     * Database view: read-only, supports querying.
     */
    public static final CollectionCapability VIEW = new CollectionCapability(true, false, false, false, false);

    /**
     * SQL collection: read-only, must declare fields explicitly.
     */
    public static final CollectionCapability SQL = new CollectionCapability(true, false, false, false, false);

    /**
     * External collection: capabilities determined by connector (reserved).
     */
    public static final CollectionCapability EXTERNAL = new CollectionCapability(true, false, false, false, false);

    /**
     * Get capability for a collection type.
     */
    public static CollectionCapability forType(String type) {
        if (type == null) return PHYSICAL;
        return switch (type) {
            case "physical" -> PHYSICAL;
            case "view" -> VIEW;
            case "sql" -> SQL;
            case "external" -> EXTERNAL;
            default -> PHYSICAL;
        };
    }
}