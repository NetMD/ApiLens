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

import io.apilens.agent.instrument.InstrumentationInstaller;

import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Maps a {@link PreparedStatement} instance back to the SQL it was prepared with.
 *
 * <p>{@link PreparedStatement} doesn't expose the SQL via its public API — we
 * stash it at {@code Connection.prepareStatement(sql)} time and look it up at
 * {@code execute*} time. {@link WeakHashMap} keys by identity so closed/GC'd
 * statements don't leak.
 */
public final class JdbcSqlCache {

    private static final java.util.Map<PreparedStatement, String> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private JdbcSqlCache() {
    }

    public static void put(PreparedStatement ps, String sql) {
        if (ps == null || sql == null) {
            return;
        }
        CACHE.put(ps, sql);
        if (InstrumentationInstaller.DEBUG) {
            String s = sql.length() > 80 ? sql.substring(0, 80) + "…" : sql;
            System.err.println("[ApiLens][JDBC] cache.put cls=" + ps.getClass().getName()
                    + " sql=" + s + " size=" + CACHE.size());
        }
    }

    public static String get(PreparedStatement ps) {
        if (ps == null) {
            return null;
        }
        String sql = CACHE.get(ps);
        if (InstrumentationInstaller.DEBUG) {
            String s;
            if (sql == null) {
                s = "<MISS>";
            } else if (sql.length() > 80) {
                s = sql.substring(0, 80) + "…";
            } else {
                s = sql;
            }
            System.err.println("[ApiLens][JDBC] cache.get cls=" + ps.getClass().getName()
                    + " sql=" + s);
        }
        return sql;
    }
}
