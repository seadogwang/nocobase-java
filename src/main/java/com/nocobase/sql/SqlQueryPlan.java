package com.nocobase.sql;

import java.util.Collections;
import java.util.List;

/**
 * Value object representing a fully built SQL query plan for execution.
 * Separates the SQL building phase from the execution phase for clarity.
 */
public class SqlQueryPlan {

    private final String sql;
    private final List<Object> parameters;
    private final String countSql;
    private final List<Object> countParameters;

    public SqlQueryPlan(String sql, List<Object> parameters, String countSql, List<Object> countParameters) {
        this.sql = sql;
        this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
        this.countSql = countSql;
        this.countParameters = countParameters != null ? Collections.unmodifiableList(countParameters) : Collections.emptyList();
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
        return countParameters.toArray();
    }
}