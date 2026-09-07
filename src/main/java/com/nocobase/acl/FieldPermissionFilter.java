package com.nocobase.acl;

import com.nocobase.runtime.CollectionDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified field permission filter.
 * Used by DynamicRepository for list/get/readAfterWrite/executeSqlList.
 * Eliminates duplicated field filtering logic and hardcoded "id" references.
 */
public class FieldPermissionFilter {

    /**
     * Filter a row to only include fields allowed by the given FieldPermission.
     * Always preserves the primary key field if present in the row.
     */
    public static Map<String, Object> filter(FieldPermission permission, CollectionDefinition def,
                                              Map<String, Object> row) {
        if (permission.isAll()) {
            return row;
        }

        if (permission.isNone()) {
            if (def.hasPrimaryKey()) {
                String pkField = def.getPrimaryKeyFieldName();
                Map<String, Object> minimal = new LinkedHashMap<>();
                if (row.containsKey(pkField) && row.get(pkField) != null) {
                    minimal.put(pkField, row.get(pkField));
                }
                return minimal;
            }
            return new LinkedHashMap<>();
        }

        // Partial: only allowed fields + primary key
        Map<String, Object> filtered = new LinkedHashMap<>();
        String pkField = def.getPrimaryKeyFieldName();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (permission.allows(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        if (!filtered.containsKey(pkField) && row.containsKey(pkField) && row.get(pkField) != null) {
            filtered.put(pkField, row.get(pkField));
        }
        return filtered;
    }
}