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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UT-18, UT-19: validates the diagnostic stderr path of {@link JdbcSqlCache}.
 *
 * <ul>
 *   <li>DEBUG=true — a {@code [ApiLens][JDBC] cache.put} or {@code cache.get} line per call</li>
 *   <li>DEBUG=false — silent (zero stderr output, must not impact production)</li>
 * </ul>
 */
class JdbcSqlCacheDebugLogTest {

    private PrintStream originalErr;
    private ByteArrayOutputStream errBuffer;

    @BeforeEach
    void redirectStderr() {
        originalErr = System.err;
        errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
        InstrumentationInstaller.DEBUG = false;
    }

    @Test
    void putAndGetEmitDebugLineWhenDebugEnabled() {
        InstrumentationInstaller.DEBUG = true;
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcSqlCache.put(ps, "SELECT * FROM users WHERE id = ?");
        JdbcSqlCache.get(ps);

        String out = errBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("[ApiLens][JDBC] cache.put"),
                "put must log a [ApiLens][JDBC] cache.put line, was:\n" + out);
        assertTrue(out.contains("[ApiLens][JDBC] cache.get"),
                "get must log a [ApiLens][JDBC] cache.get line, was:\n" + out);
        assertTrue(out.contains("SELECT * FROM users WHERE id = ?"),
                "log line must include the SQL (truncated to 80ch)");
    }

    @Test
    void putAndGetSilentWhenDebugDisabled() {
        InstrumentationInstaller.DEBUG = false;
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcSqlCache.put(ps, "INSERT INTO orders VALUES (?)");
        JdbcSqlCache.get(ps);

        String out = errBuffer.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("[ApiLens][JDBC]"),
                "no debug output expected when DEBUG=false; was:\n" + out);
    }
}
