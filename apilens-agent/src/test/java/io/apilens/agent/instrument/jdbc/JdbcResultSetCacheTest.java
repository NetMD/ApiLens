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
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Contract checks for {@link JdbcResultSetCache} — stash is identity-keyed,
 * single-use ({@code poll} removes), and null-safe.
 *
 * <p>{@link Mockito} produces hashable identity-based stubs adequate for the
 * {@link java.util.WeakHashMap} backing store.
 */
class JdbcResultSetCacheTest {

    @Test
    void putThenPollReturnsTheSameWrapper() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        JdbcResultSetCache.put(ps, rs);

        assertSame(rs, JdbcResultSetCache.poll(ps));
    }

    @Test
    void pollIsSingleUse() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        JdbcResultSetCache.put(ps, rs);

        JdbcResultSetCache.poll(ps);
        assertNull(JdbcResultSetCache.poll(ps), "second poll should miss");
    }

    @Test
    void pollOnUncachedStatementReturnsNull() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        assertNull(JdbcResultSetCache.poll(ps));
    }

    @Test
    void putIsNullSafe() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        JdbcResultSetCache.put(null, Mockito.mock(ResultSet.class));
        JdbcResultSetCache.put(ps, null);
        // No exception, no entry stored.
        assertNull(JdbcResultSetCache.poll(ps));
    }
}
