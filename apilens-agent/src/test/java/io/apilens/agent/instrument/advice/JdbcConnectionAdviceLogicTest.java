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
package io.apilens.agent.instrument.advice;

import io.apilens.agent.instrument.AdviceSupport;
import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.agent.instrument.jdbc.JdbcSqlCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UT-24, UT-25: behavioural tests for {@link JdbcConnectionAdvice}.
 *
 * <p>{@code @Advice} methods inline into the target class at instrumentation time,
 * but we can still call them as plain static methods in a unit test — the
 * annotations are no-ops at JVM call time, so the body executes normally.
 *
 * <ul>
 *   <li>UT-24: when {@code returned} is a real {@link PreparedStatement}, the SQL
 *       is stored in {@link JdbcSqlCache}.</li>
 *   <li>UT-25: when DEBUG=true, {@link AdviceSupport#logPrepareReturn} prints a
 *       {@code prepareStatement returned cls=…} diagnostic line. When DEBUG=false,
 *       no diagnostic is printed.</li>
 * </ul>
 */
class JdbcConnectionAdviceLogicTest {

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

    /** UT-24: PreparedStatement return path → JdbcSqlCache.put. */
    @Test
    void afterPrepareCachesSqlForReturnedPreparedStatement() {
        // DEBUG=false to keep the test focused on caching behaviour, no log noise
        InstrumentationInstaller.DEBUG = false;

        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        String sql = "SELECT 1";

        JdbcConnectionAdvice.afterPrepare(sql, ps);

        assertEquals(sql, JdbcSqlCache.get(ps),
                "afterPrepare must cache the SQL on PreparedStatement return");

        // Non-PreparedStatement return — must NOT cache and must NOT throw
        Object notPs = new Object();
        // (we just call it; no exception is the assertion)
        JdbcConnectionAdvice.afterPrepare("DROP TABLE x", notPs);

        // Null return — must NOT throw
        JdbcConnectionAdvice.afterPrepare("SELECT null", null);
        // and JdbcSqlCache.get(null) returns null cleanly
        assertNull(JdbcSqlCache.get(null));
    }

    /** UT-25: diagnostic stderr line emitted only when DEBUG=true. */
    @Test
    void afterPrepareEmitsDiagnosticLineWhenDebugEnabled() {
        InstrumentationInstaller.DEBUG = true;

        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        String sql = "INSERT INTO orders (id) VALUES (?)";

        JdbcConnectionAdvice.afterPrepare(sql, ps);

        String out = errBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("[ApiLens][JDBC] prepareStatement returned"),
                "expected diagnostic line missing; was:\n" + out);
        assertTrue(out.contains("INSERT INTO orders"),
                "diagnostic line must include the SQL (truncated); was:\n" + out);

        // Reset and verify silent path
        errBuffer.reset();
        InstrumentationInstaller.DEBUG = false;
        JdbcConnectionAdvice.afterPrepare(sql, ps);
        assertFalse(errBuffer.toString(StandardCharsets.UTF_8)
                        .contains("[ApiLens][JDBC] prepareStatement returned"),
                "DEBUG=false must suppress the diagnostic line");
    }
}
