package com.nocobase.ddl;

import com.nocobase.field.FieldOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * A plan describing DDL operations to be executed.
 * Allows building up a list of changes before executing them atomically.
 */
public class DdlPlan {

    private final String collectionName;
    private final List<String> statements = new ArrayList<>();
    private final List<String> rollbackStatements = new ArrayList<>();

    public DdlPlan(String collectionName) {
        this.collectionName = collectionName;
    }

    public String getCollectionName() { return collectionName; }

    public void addStatement(String sql, String rollbackSql) {
        statements.add(sql);
        if (rollbackSql != null) {
            rollbackStatements.add(rollbackSql);
        }
    }

    public void addStatement(String sql) {
        addStatement(sql, null);
    }

    public List<String> getStatements() {
        return List.copyOf(statements);
    }

    public List<String> getRollbackStatements() {
        return List.copyOf(rollbackStatements);
    }

    public boolean isEmpty() {
        return statements.isEmpty();
    }

    /**
     * Build a CREATE TABLE plan.
     */
    public static DdlPlan createTable(String collectionName, String tableName,
                                       List<ColumnDef> columns, DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        StringBuilder sql = new StringBuilder("CREATE TABLE ");
        sql.append(dialect.quoteIdentifier(tableName));
        sql.append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columns.get(i).toSql(dialect));
        }
        sql.append(")");

        plan.addStatement(sql.toString(),
                "DROP TABLE IF EXISTS " + dialect.quoteIdentifier(tableName));
        return plan;
    }

    /**
     * Build an ADD COLUMN plan.
     */
    public static DdlPlan addColumn(String collectionName, String tableName,
                                     ColumnDef column, DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        String sql = "ALTER TABLE " + dialect.quoteIdentifier(tableName)
                + " ADD COLUMN " + column.toSql(dialect);
        plan.addStatement(sql,
                "ALTER TABLE " + dialect.quoteIdentifier(tableName)
                + " DROP COLUMN " + dialect.quoteIdentifier(column.getName()));
        return plan;
    }

    /**
     * Build a DROP COLUMN plan.
     */
    public static DdlPlan dropColumn(String collectionName, String tableName,
                                      String columnName, DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        plan.addStatement("ALTER TABLE " + dialect.quoteIdentifier(tableName)
                + " DROP COLUMN " + dialect.quoteIdentifier(columnName));
        return plan;
    }

    /**
     * Build a DROP TABLE plan.
     */
    public static DdlPlan dropTable(String collectionName, String tableName, DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        plan.addStatement("DROP TABLE IF EXISTS " + dialect.quoteIdentifier(tableName));
        return plan;
    }

    /**
     * Build a CREATE INDEX plan (supports multi-column indexes).
     */
    public static DdlPlan createIndex(String collectionName, String tableName,
                                       String indexName, List<String> columnNames, boolean unique,
                                       DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        String sql = dialect.buildCreateIndex(tableName, indexName, columnNames, unique);
        plan.addStatement(sql,
                "DROP INDEX IF EXISTS " + dialect.quoteIdentifier(indexName));
        return plan;
    }

    /**
     * Build a DROP INDEX plan.
     */
    public static DdlPlan dropIndex(String collectionName, String indexName, DialectAdapter dialect) {
        DdlPlan plan = new DdlPlan(collectionName);
        plan.addStatement("DROP INDEX IF EXISTS " + dialect.quoteIdentifier(indexName));
        return plan;
    }

    /**
     * Column definition for DDL.
     */
    public static class ColumnDef {
        private final String name;
        private final String sqlType;
        private final boolean nullable;
        private final FieldOptions.DefaultValue defaultValue;
        private final boolean primaryKey;
        private final boolean autoIncrement;

        public ColumnDef(String name, String sqlType) {
            this(name, sqlType, true, null, false, false);
        }

        public ColumnDef(String name, String sqlType, boolean nullable, FieldOptions.DefaultValue defaultValue,
                         boolean primaryKey, boolean autoIncrement) {
            this.name = name;
            this.sqlType = sqlType;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.primaryKey = primaryKey;
            this.autoIncrement = autoIncrement;
        }

        public String getName() { return name; }
        public String getSqlType() { return sqlType; }

        public String toSql(DialectAdapter dialect) {
            StringBuilder sb = new StringBuilder();
            sb.append(dialect.quoteIdentifier(name)).append(" ").append(sqlType);
            if (autoIncrement) {
                sb.append(" ").append(dialect.autoIncrementClause());
            }
            if (primaryKey) {
                sb.append(" PRIMARY KEY");
            }
            if (!nullable) {
                sb.append(" NOT NULL");
            }
            if (defaultValue != null) {
                String formatted = dialect.formatDefaultValue(defaultValue);
                if (formatted != null) {
                    sb.append(" DEFAULT ").append(formatted);
                }
            }
            return sb.toString();
        }
    }
}