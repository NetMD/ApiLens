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
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Short-lived stash mapping a {@link PreparedStatement} to a wrapped {@link ResultSet}
 * produced by the agent during {@code PreparedStatement.execute()} interception.
 *
 * <p>Background: MyBatis (and any direct JDBC user that uses
 * {@code PreparedStatement.execute()}) invokes {@code execute()} followed by
 * {@code getResultSet()} — the {@code ResultSet} is not the {@code execute()}
 * return value. To capture rows in that pattern, the {@code execute()} advice
 * eagerly retrieves the {@code ResultSet}, wraps it via
 * {@link CapturedResultSet#capture}, and stashes the wrapper here so the
 * subsequent {@code getResultSet()} call (intercepted separately) can return
 * the wrapper to the caller.
 *
 * <p>{@link WeakHashMap} keyed by identity — closed/GC'd statements leak nothing.
 * Stash is consumed at most once per put via {@link #poll}; cache wins beat
 * stale entries from earlier executes on the same statement.
 */
public final class JdbcResultSetCache {

    private static final Map<PreparedStatement, ResultSet> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private JdbcResultSetCache() {
    }

    public static void put(PreparedStatement ps, ResultSet wrapper) {
        if (ps == null || wrapper == null) {
            return;
        }
        CACHE.put(ps, wrapper);
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][JDBC] rs.cache.put cls=" + ps.getClass().getName()
                    + " size=" + CACHE.size());
        }
    }

    /** Consume and return the wrapper for {@code ps}; subsequent calls return {@code null}. */
    public static ResultSet poll(PreparedStatement ps) {
        if (ps == null) {
            return null;
        }
        ResultSet rs = CACHE.remove(ps);
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][JDBC] rs.cache.poll cls=" + ps.getClass().getName()
                    + " hit=" + (rs != null));
        }
        return rs;
    }
}
