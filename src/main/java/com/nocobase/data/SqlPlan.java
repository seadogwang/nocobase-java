package com.nocobase.data;

import java.util.List;

/**
 * Represents a generated SQL execution plan (SQL string + bound parameters).
 * Does not execute SQL — it is a pure data object consumed by JdbcTemplate.
 */
public class SqlPlan {

    private final String sql;
    private final List<Object> parameters;
    private final String countSql;
    private final List<Object> countParameters;
    private final String operation;
    private final String collectionName;

    public SqlPlan(String sql, List<Object> parameters, String countSql, List<Object> countParameters,
                   String operation, String collectionName) {
        this.sql = sql;
        this.parameters = parameters;
        this.countSql = countSql;
        this.countParameters = countParameters;
        this.operation = operation;
        this.collectionName = collectionName;
    }

    public String getSql() {
        return sql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public Object[] getParametersArray() {
        return parameters.toArray();
    }

    public String getCountSql() {
        return countSql;
    }

    public List<Object> getCountParameters() {
        return countParameters;
    }

    public Object[] getCountParametersArray() {
        return countParameters != null ? countParameters.toArray() : null;
    }

    public String getOperation() {
        return operation;
    }

    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public String toString() {
        return "SqlPlan{operation='" + operation + "', collection='" + collectionName + "', "
                + "parameterCount=" + (parameters != null ? parameters.size() : 0) + ", "
                + "hasCountSql=" + (countSql != null) + "}";
    }
}