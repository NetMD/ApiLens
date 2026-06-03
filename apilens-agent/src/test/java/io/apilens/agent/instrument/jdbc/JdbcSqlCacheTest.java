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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UT-08, UT-09: contract checks for {@link JdbcSqlCache} — null safety and
 * round-trip put/get for a single {@link PreparedStatement} instance.
 *
 * <p>{@link Mockito} produces a hashable, identity-based stub which is enough
 * for the {@link java.util.WeakHashMap} backing store to find the entry.
 */
class JdbcSqlCacheTest {

    @Test
    void putThenGetReturnsTheSameSql() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        JdbcSqlCache.put(ps, "SELECT 1");

        assertEquals("SELECT 1", JdbcSqlCache.get(ps));
    }

    @Test
    void getOnUncachedStatementReturnsNull() {
        PreparedStatement other = Mockito.mock(PreparedStatement.class);

        // distinct mock, never put — must not collide with any prior test entry
        assertNull(JdbcSqlCache.get(other));
    }

    @Test
    void putToleratesNullArguments() {
        // both null: no NPE
        JdbcSqlCache.put(null, "x");
        JdbcSqlCache.put(Mockito.mock(PreparedStatement.class), null);

        // get(null) returns null without NPE
        assertNull(JdbcSqlCache.get(null));
    }
}
