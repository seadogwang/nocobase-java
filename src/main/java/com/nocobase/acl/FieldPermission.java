package com.nocobase.acl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents field-level permission for a collection.
 * Replaces the ambiguous null-return from getReadableFields/getWritableFields.
 * <p>
 * Semantics:
 * <ul>
 * <li>{@code all()} -- all fields are allowed (admin/root or no field restriction configured)</li>
 * <li>{@code none()} -- no fields are allowed (user has no permission at all)</li>
 * <li>{@code only(Set)} -- only the specified fields are allowed</li>
 * </ul>
 */
public class FieldPermission {

    private static final FieldPermission ALL = new FieldPermission(null, true);
    private static final FieldPermission NONE = new FieldPermission(Collections.emptySet(), false);

    private final Set<String> fields; // null = all fields
    private final boolean allowAll;

    private FieldPermission(Set<String> fields, boolean allowAll) {
        this.fields = fields != null ? Collections.unmodifiableSet(new LinkedHashSet<>(fields)) : null;
        this.allowAll = allowAll;
    }

    /** All fields allowed. */
    public static FieldPermission all() { return ALL; }

    /** No fields allowed. */
    public static FieldPermission none() { return NONE; }

    /** Only the specified fields allowed. */
    public static FieldPermission only(Set<String> fields) {
        if (fields == null || fields.isEmpty()) return NONE;
        return new FieldPermission(fields, false);
    }

    /** Returns true if all fields are allowed. */
    public boolean isAll() { return allowAll; }

    /** Returns true if no fields are allowed. */
    public boolean isNone() { return !allowAll && (fields == null || fields.isEmpty()); }

    /** Returns the set of allowed fields, or null if all are allowed. */
    public Set<String> getAllowedFields() { return fields; }

    /** Returns true if the given field is allowed. */
    public boolean allows(String fieldName) {
        if (allowAll) return true;
        return fields != null && fields.contains(fieldName);
    }

    @Override
    public String toString() {
        return allowAll ? "FieldPermission(all)" : "FieldPermission(" + fields + ")";
    }
}