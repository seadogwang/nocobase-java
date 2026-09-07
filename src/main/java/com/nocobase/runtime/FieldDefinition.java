package com.nocobase.runtime;

import java.util.*;

/**
 * Runtime field definition, loaded from field metadata.
 */
public class FieldDefinition {
    private final String name;
    private final String type;
    private final String interfaceType;
    private final boolean hidden;
    private final boolean system;
    private final boolean physical;
    private final String effectiveColumnName;
    private final Map<String, Object> options;

    private FieldDefinition(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.interfaceType = builder.interfaceType;
        this.hidden = builder.hidden;
        this.system = builder.system;
        this.physical = builder.physical;
        this.effectiveColumnName = builder.effectiveColumnName != null ? builder.effectiveColumnName : builder.name;
        this.options = Collections.unmodifiableMap(new HashMap<>(builder.options));
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getInterfaceType() { return interfaceType; }
    public boolean isHidden() { return hidden; }
    public boolean isSystem() { return system; }
    public boolean isPhysical() { return physical; }
    public String getEffectiveColumnName() { return effectiveColumnName; }
    public Map<String, Object> getOptions() { return options; }

    public boolean isRelation() {
        return "belongsTo".equals(type) || "hasOne".equals(type)
            || "hasMany".equals(type) || "belongsToMany".equals(type);
    }

    public static Builder builder(String name, String type) {
        return new Builder(name, type);
    }

    public static class Builder {
        private final String name;
        private final String type;
        private String interfaceType;
        private boolean hidden;
        private boolean system;
        private boolean physical = true;
        private String effectiveColumnName;
        private Map<String, Object> options = new HashMap<>();

        public Builder(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public Builder interfaceType(String interfaceType) { this.interfaceType = interfaceType; return this; }
        public Builder hidden(boolean hidden) { this.hidden = hidden; return this; }
        public Builder system(boolean system) { this.system = system; return this; }
        public Builder physical(boolean physical) { this.physical = physical; return this; }
        public Builder effectiveColumnName(String name) { this.effectiveColumnName = name; return this; }
        public Builder options(Map<String, Object> options) { this.options.putAll(options); return this; }

        public FieldDefinition build() {
            return new FieldDefinition(this);
        }
    }
}