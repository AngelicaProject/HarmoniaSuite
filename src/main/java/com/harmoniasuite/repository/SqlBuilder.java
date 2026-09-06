package com.harmoniasuite.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class SqlBuilder {

    private final StringBuilder sql = new StringBuilder();
    private final List<Object> params = new ArrayList<>();
    private boolean hasWhere;

    private SqlBuilder(String base) {
        sql.append(base);
    }

    public static SqlBuilder select(String columns, String table) {
        return new SqlBuilder("SELECT " + columns + " FROM " + table);
    }

    public static SqlBuilder deleteFrom(String table) {
        return new SqlBuilder("DELETE FROM " + table);
    }

    public static SqlBuilder where(String condition, Object... params) {
        SqlBuilder builder = new SqlBuilder("");
        return builder.and(condition, params);
    }

    public SqlBuilder and(String condition, Object... params) {
        sql.append(hasWhere ? " AND " : " WHERE ").append(condition);
        hasWhere = true;
        Collections.addAll(this.params, params);
        return this;
    }

    public SqlBuilder andIn(String column, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return and("1 = 0");
        }
        StringBuilder group = new StringBuilder(column).append(" IN (");
        group.append("?, ".repeat(values.size()));
        group.setLength(group.length() - 2);
        group.append(')');
        return and(group.toString(), values.toArray());
    }

    public SqlBuilder orderBy(String columns) {
        sql.append(" ORDER BY ").append(columns);
        return this;
    }

    public SqlBuilder limitOffset(int limit, int offset) {
        sql.append(" LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return this;
    }

    public String text() {
        return sql.toString();
    }

    public Object[] params() {
        return params.toArray();
    }
}
