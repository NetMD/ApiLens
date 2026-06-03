/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apilens.agent.instrument.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Snapshot of {@link ResultSetMetaData} taken before the underlying ResultSet
 * is closed by {@link CapturedResultSet#capture}. Backed by a {@link Proxy} so
 * we don't reproduce the entire metadata interface; only the methods commonly
 * exercised by ORM mapping code paths are honoured.
 */
public final class SimpleResultSetMetaData implements InvocationHandler {

    private final int columnCount;
    private final String[] labels;
    private final String[] names;
    private final int[] types;
    private final String[] typeNames;
    private final String[] classNames;
    private final int[] nullable;

    private SimpleResultSetMetaData(int columnCount,
                                    String[] labels,
                                    String[] names,
                                    int[] types,
                                    String[] typeNames,
                                    String[] classNames,
                                    int[] nullable) {
        this.columnCount = columnCount;
        this.labels = labels;
        this.names = names;
        this.types = types;
        this.typeNames = typeNames;
        this.classNames = classNames;
        this.nullable = nullable;
    }

    public static SimpleResultSetMetaData from(ResultSetMetaData md) throws SQLException {
        int columnCount = md.getColumnCount();
        String[] labels = new String[columnCount];
        String[] names = new String[columnCount];
        int[] types = new int[columnCount];
        String[] typeNames = new String[columnCount];
        String[] classNames = new String[columnCount];
        int[] nullable = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            int oneBased = i + 1;
            labels[i] = safeGetLabel(md, oneBased);
            names[i] = safeGetName(md, oneBased);
            types[i] = safeGetType(md, oneBased);
            typeNames[i] = safeGetTypeName(md, oneBased);
            classNames[i] = safeGetClassName(md, oneBased);
            nullable[i] = safeIsNullable(md, oneBased);
        }
        SimpleResultSetMetaData snapshot = new SimpleResultSetMetaData(
                columnCount, labels, names, types, typeNames, classNames, nullable);
        return snapshot;
    }

    /** Wraps this snapshot in a {@link ResultSetMetaData} {@link Proxy}. */
    public ResultSetMetaData asResultSetMetaData() {
        return (ResultSetMetaData) Proxy.newProxyInstance(
                SimpleResultSetMetaData.class.getClassLoader(),
                new Class<?>[] { ResultSetMetaData.class },
                this);
    }

    public int columnCount() { return columnCount; }
    public String label(int oneBased) { return labels[oneBased - 1]; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getColumnCount":   return columnCount;
            case "getColumnLabel":   return labels[((Integer) args[0]) - 1];
            case "getColumnName":    return names[((Integer) args[0]) - 1];
            case "getColumnType":    return types[((Integer) args[0]) - 1];
            case "getColumnTypeName":return typeNames[((Integer) args[0]) - 1];
            case "getColumnClassName": return classNames[((Integer) args[0]) - 1];
            case "isNullable":       return nullable[((Integer) args[0]) - 1];
            case "isAutoIncrement":
            case "isCaseSensitive":
            case "isSearchable":
            case "isCurrency":
            case "isSigned":
            case "isReadOnly":
            case "isWritable":
            case "isDefinitelyWritable":
                return false;
            case "getColumnDisplaySize":
            case "getPrecision":
            case "getScale":
                return 0;
            case "getSchemaName":
            case "getCatalogName":
            case "getTableName":
                return "";
            case "toString":
                return "SimpleResultSetMetaData[" + columnCount + " columns]";
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(this);
            case "unwrap":
                throw new SQLFeatureNotSupportedException(
                        "SimpleResultSetMetaData does not support unwrap");
            case "isWrapperFor":
                return false;
            default:
                throw new SQLFeatureNotSupportedException(
                        "SimpleResultSetMetaData does not support: " + name);
        }
    }

    // Per-attribute helpers — drivers occasionally throw on individual calls;
    // we keep going rather than failing the whole snapshot.
    private static String safeGetLabel(ResultSetMetaData md, int i) {
        try { return md.getColumnLabel(i); } catch (SQLException ignore) { return "col" + i; }
    }
    private static String safeGetName(ResultSetMetaData md, int i) {
        try { return md.getColumnName(i); } catch (SQLException ignore) { return "col" + i; }
    }
    private static int safeGetType(ResultSetMetaData md, int i) {
        try { return md.getColumnType(i); } catch (SQLException ignore) { return java.sql.Types.OTHER; }
    }
    private static String safeGetTypeName(ResultSetMetaData md, int i) {
        try { return md.getColumnTypeName(i); } catch (SQLException ignore) { return "UNKNOWN"; }
    }
    private static String safeGetClassName(ResultSetMetaData md, int i) {
        try { return md.getColumnClassName(i); } catch (SQLException ignore) { return Object.class.getName(); }
    }
    private static int safeIsNullable(ResultSetMetaData md, int i) {
        try { return md.isNullable(i); } catch (SQLException ignore) { return ResultSetMetaData.columnNullableUnknown; }
    }
}
