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
package io.apilens.agent.instrument;

import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.common.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * UT-01 ~ UT-07: validates the {@link AdviceSupport#enterDbSpan} /
 * {@link AdviceSupport#markDbSpanExited} re-entrancy guard. The point of these
 * tests is to lock the contract that JDBC pool wrappers stacking 3 deep produce
 * exactly one DB span on the queue and leave {@link TraceContext}'s stack and the
 * IN_DB_SPAN ThreadLocal in their original state.
 */
class AdviceSupportJdbcSentinelTest {

    private SpanQueue queue;

    @BeforeEach
    void setup() {
        // Fresh per-test queue; capacity is generous to avoid offer() failures.
        queue = new SpanQueue(64);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.PAYLOAD_MAX_BYTES = 65_536;
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        // Reset IN_DB_SPAN by exhausting any leftover outer state from a failing test.
        AdviceSupport.markDbSpanExited(true);
        InstrumentationInstaller.QUEUE = null;
    }

    private List<Span> drainAll() {
        List<Span> out = new ArrayList<>();
        queue.drainTo(out);
        return out;
    }

    // ─── UT-01: SKIPPED is a static singleton ─────────────────────────────

    @Test
    void skippedFrameIsStaticSingleton() {
        assertSame(TraceContext.Frame.SKIPPED, TraceContext.Frame.SKIPPED,
                "SKIPPED must be a singleton — sentinel comparison relies on referential equality");
        assertEquals("__SKIPPED__", TraceContext.Frame.SKIPPED.traceId);
        assertEquals("__SKIPPED__", TraceContext.Frame.SKIPPED.spanId);
        assertNull(TraceContext.Frame.SKIPPED.parentSpanId);
        assertEquals(0L, TraceContext.Frame.SKIPPED.startMillis);
    }

    // ─── UT-02: outer enter pushes a real frame ──────────────────────────

    @Test
    void outerEnterPushesRealFrame() {
        TraceContext.Frame f = AdviceSupport.enterDbSpan("OuterPS", "jdbc.execute");

        assertNotNull(f);
        assertNotSame(TraceContext.Frame.SKIPPED, f);
        assertEquals(1, TraceContext.depth());
        assertEquals("jdbc.execute", f.operationName);
        assertEquals("DB", f.spanKind);

        // cleanup so this test doesn't leak ThreadLocal state to the next
        AdviceSupport.exit(f, null, null, null);
        AdviceSupport.markDbSpanExited(true);
    }

    // ─── UT-03: inner enter returns SKIPPED ──────────────────────────────

    @Test
    void innerEnterReturnsSkippedSentinel() {
        TraceContext.Frame outer = AdviceSupport.enterDbSpan("OuterPS", "jdbc.execute");
        TraceContext.Frame inner = AdviceSupport.enterDbSpan("InnerPS", "jdbc.execute");
        TraceContext.Frame innermost = AdviceSupport.enterDbSpan("InnermostPS", "jdbc.execute");

        assertNotNull(outer);
        assertNotSame(TraceContext.Frame.SKIPPED, outer);
        assertSame(TraceContext.Frame.SKIPPED, inner);
        assertSame(TraceContext.Frame.SKIPPED, innermost);
        // Stack only carries the outer
        assertEquals(1, TraceContext.depth());

        // cleanup: only outer drops the TL
        AdviceSupport.exit(innermost, null, null, null); // NO_OP
        AdviceSupport.exit(inner, null, null, null);     // NO_OP
        AdviceSupport.exit(outer, null, null, null);     // pops + queues
        AdviceSupport.markDbSpanExited(true);
    }

    // ─── UT-04: exactly ONE span on the queue for a 3-wrapper call ───────

    @Test
    void threeWrapperCallProducesExactlyOneSpan() {
        TraceContext.Frame outer = AdviceSupport.enterDbSpan("HikariPS", "jdbc.execute");
        TraceContext.Frame inner = AdviceSupport.enterDbSpan("DriverPS", "jdbc.execute");
        TraceContext.Frame innermost = AdviceSupport.enterDbSpan("UnderlyingPS", "jdbc.execute");

        // exit unwinds in LIFO order (innermost first, outer last)
        AdviceSupport.exit(innermost, null, null, null);
        AdviceSupport.exit(inner, null, null, null);
        AdviceSupport.exit(outer, null, null, null);
        AdviceSupport.markDbSpanExited(true);

        // queue must contain exactly one span
        List<Span> drained = drainAll();
        assertEquals(1, drained.size(), "exactly one DB span must be queued for a 3-wrapper call");
        assertEquals("jdbc.execute", drained.get(0).operationName());
    }

    // ─── UT-05: SKIPPED exit must NOT pop the live stack ─────────────────

    @Test
    void skippedExitDoesNotPopLiveStack() {
        // simulate an outer non-DB frame already on the stack
        TraceContext.Frame outerNonDb = TraceContext.push("controller.handle", "SERVER");
        int beforeDepth = TraceContext.depth(); // 1

        // hand out a SKIPPED frame and call exit on it — must be NO_OP w.r.t. the stack
        AdviceSupport.exit(TraceContext.Frame.SKIPPED, null, null, null);

        assertEquals(beforeDepth, TraceContext.depth(),
                "SKIPPED exit must never pop the live stack — would orphan the controller frame");
        // queue must still be empty
        assertEquals(0, drainAll().size(), "SKIPPED exit must not enqueue a span");

        // cleanup
        AdviceSupport.exit(outerNonDb, null, null, null);
    }

    // ─── UT-06: outer exit cleans the TL so the NEXT call starts fresh ───

    @Test
    void outerExitClearsThreadLocalAndNextCallStartsFresh() {
        TraceContext.Frame outer1 = AdviceSupport.enterDbSpan("ps", "jdbc.execute");
        AdviceSupport.exit(outer1, null, null, null);
        AdviceSupport.markDbSpanExited(true);

        // The next call on the same thread must again be treated as outer (not SKIPPED)
        TraceContext.Frame outer2 = AdviceSupport.enterDbSpan("ps", "jdbc.execute");
        assertNotSame(TraceContext.Frame.SKIPPED, outer2,
                "ThreadLocal must be cleared so the next outer call gets a real frame");
        assertNotNull(outer2);

        // both DB spans must end up on the queue
        AdviceSupport.exit(outer2, null, null, null);
        AdviceSupport.markDbSpanExited(true);

        List<Span> drained = drainAll();
        assertEquals(2, drained.size(), "two separate DB spans expected");
        assertNotEquals(drained.get(0).spanId(), drained.get(1).spanId());
    }

    // ─── UT-07: per-thread isolation — IN_DB_SPAN doesn't bleed across ───

    @Test
    void inDbSpanThreadLocalIsPerThread() throws Exception {
        TraceContext.Frame mainOuter = AdviceSupport.enterDbSpan("ps-main", "jdbc.execute");
        assertNotSame(TraceContext.Frame.SKIPPED, mainOuter,
                "main thread's own first call is outer");

        // background thread should also see itself as outer (TL is per-thread)
        final boolean[] otherSawSkipped = {false};
        Thread t = new Thread(() -> {
            TraceContext.Frame other = AdviceSupport.enterDbSpan("ps-other", "jdbc.execute");
            otherSawSkipped[0] = (other == TraceContext.Frame.SKIPPED);
            // cleanup on background thread
            AdviceSupport.exit(other, null, null, null);
            AdviceSupport.markDbSpanExited(true);
            TraceContext.clear();
        });
        t.start();
        t.join();

        // cleanup main thread
        AdviceSupport.exit(mainOuter, null, null, null);
        AdviceSupport.markDbSpanExited(true);

        // background thread must NOT have been treated as SKIPPED
        assertFalse(otherSawSkipped[0], "another thread must not see this thread's IN_DB_SPAN");
    }
}
