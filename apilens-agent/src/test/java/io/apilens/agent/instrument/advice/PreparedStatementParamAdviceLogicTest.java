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

import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.agent.instrument.jdbc.JdbcParamCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase E3 — behavioural tests for {@link PreparedStatementParamAdvice} and
 * {@link PreparedStatementAddBatchAdvice}.
 *
 * <p>{@code @Advice} methods inline into the target class at instrumentation time,
 * but at unit-test time we call them as plain static methods. The annotations are
 * no-ops when invoked directly, so the body executes normally and we observe its
 * effect via {@link JdbcParamCache}.
 *
 * <p>AC mapping: AC-01-3, AC-01-4, AC-01-5, AC-01-6, AC-04-3, AC-05-1, AC-05-2.
 */
class PreparedStatementParamAdviceLogicTest {

    private boolean previousCaptureParams;

    @BeforeEach
    void enableCaptureParams() {
        previousCaptureParams = InstrumentationInstaller.CAPTURE_PARAMS;
        InstrumentationInstaller.CAPTURE_PARAMS = true;
    }

    @AfterEach
    void restoreCaptureParams() {
        InstrumentationInstaller.CAPTURE_PARAMS = previousCaptureParams;
    }

    /** UT-ADV-01: setter advice writes value into the cache when CAPTURE_PARAMS=true. */
    @Test
    void onSetterPutsValueIntoCache() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1, "hello"}, "fakeType");

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots, "advice must populate the cache when CAPTURE_PARAMS=true");
        assertEquals(1, slots.size());
        assertEquals("hello", slots.get(0).get(1));

        JdbcParamCache.clear(ps);
    }

    /** UT-ADV-02: when CAPTURE_PARAMS=false, advice is a silent no-op. */
    @Test
    void onSetterSilentWhenCaptureDisabled() {
        InstrumentationInstaller.CAPTURE_PARAMS = false;
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1, "hello"}, "fakeType");

        assertNull(JdbcParamCache.get(ps),
                "kill switch must short-circuit before any cache write");
    }

    /** UT-ADV-03: self != PreparedStatement → silent no-op. */
    @Test
    void onSetterSilentOnNonPreparedStatementSelf() {
        Object plain = new Object();

        // No throw, no observable side-effect.
        PreparedStatementParamAdvice.onSetter(plain, new Object[]{1, "hello"}, "fakeType");
    }

    /** UT-ADV-04: args[0] is not an Integer (e.g. someone calls the advice with the wrong shape) → silent. */
    @Test
    void onSetterSilentOnNonIntegerFirstArg() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, new Object[]{"not-int", "value"}, "fakeType");

        assertNull(JdbcParamCache.get(ps),
                "instanceof Integer guard must reject non-int first arg");
    }

    /** UT-ADV-05: args.length < 2 → silent (e.g. a degenerate setter signature). */
    @Test
    void onSetterSilentOnEmptyArgs() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, new Object[]{}, "fakeType");
        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1}, "fakeType");

        assertNull(JdbcParamCache.get(ps),
                "args.length < 2 guard must short-circuit before cache write");
    }

    /** UT-ADV-06: null args → silent, host throw 0. */
    @Test
    void onSetterSilentOnNullArgs() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, null, "fakeType");

        assertNull(JdbcParamCache.get(ps));
    }

    /**
     * UT-ADV-07: NullPointerException-friendly path — call advice with null self.
     * Body must short-circuit on the {@code instanceof PreparedStatement} guard and
     * never throw, satisfying D-05 (host throw 0).
     */
    @Test
    void onSetterTolerantOfNullSelf() {
        // No assertion needed beyond "this call does not throw".
        PreparedStatementParamAdvice.onSetter(null, new Object[]{1, "v"}, "fakeType");
    }

    /** UT-ADV-08: addBatch advice opens a new slot, with prior setter values preserved. */
    @Test
    void onAddBatchCommitsSlot() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1, "a"}, "fakeType");
        PreparedStatementAddBatchAdvice.onAddBatch(ps, "fakeType");
        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1, "b"}, "fakeType");

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(2, slots.size(), "addBatch must open a new slot");
        assertEquals("a", slots.get(0).get(1));
        assertEquals("b", slots.get(1).get(1));

        JdbcParamCache.clear(ps);
    }

    /** UT-ADV-09: addBatch advice is a silent no-op when CAPTURE_PARAMS=false. */
    @Test
    void onAddBatchSilentWhenCaptureDisabled() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        // First seed a value while capture is on so we have a cache entry to observe.
        PreparedStatementParamAdvice.onSetter(ps, new Object[]{1, "a"}, "fakeType");
        // Now disable capture and call addBatch — must NOT open a new slot.
        InstrumentationInstaller.CAPTURE_PARAMS = false;
        PreparedStatementAddBatchAdvice.onAddBatch(ps, "fakeType");

        // Re-enable capture and inspect the cache. The slot count must still be 1.
        InstrumentationInstaller.CAPTURE_PARAMS = true;
        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(1, slots.size(),
                "addBatch must not commit a slot when CAPTURE_PARAMS=false");

        JdbcParamCache.clear(ps);
    }
}
