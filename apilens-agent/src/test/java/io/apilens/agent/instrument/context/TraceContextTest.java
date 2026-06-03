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
package io.apilens.agent.instrument.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceContextTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void firstPushCreatesTraceWithNoParent() {
        TraceContext.Frame f = TraceContext.push("op", "SERVER");

        assertNotNull(f);
        assertNotNull(f.traceId);
        assertNotNull(f.spanId);
        assertNull(f.parentSpanId);
        assertEquals("op", f.operationName);
        assertEquals("SERVER", f.spanKind);
        assertEquals(1, TraceContext.depth());
    }

    @Test
    void childInheritsTraceIdAndPointsAtParentSpan() {
        TraceContext.Frame parent = TraceContext.push("root", "SERVER");
        TraceContext.Frame child = TraceContext.push("inner", "INTERNAL");

        assertEquals(parent.traceId, child.traceId);
        assertEquals(parent.spanId, child.parentSpanId);
        assertNotEquals(parent.spanId, child.spanId);
        assertEquals(2, TraceContext.depth());
    }

    @Test
    void popReturnsFramesInLifoOrder() {
        TraceContext.Frame a = TraceContext.push("a", "SERVER");
        TraceContext.Frame b = TraceContext.push("b", "INTERNAL");

        assertEquals(b.spanId, TraceContext.pop().spanId);
        assertEquals(a.spanId, TraceContext.pop().spanId);
        assertNull(TraceContext.pop());
        assertEquals(0, TraceContext.depth());
    }

    @Test
    void perThreadIsolation() throws Exception {
        TraceContext.push("main-root", "SERVER");
        AtomicReference<TraceContext.Frame> otherFrame = new AtomicReference<>();
        AtomicReference<Integer> otherDepth = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                otherDepth.set(TraceContext.depth()); // should see 0 — independent thread
                otherFrame.set(TraceContext.push("other", "SERVER"));
                TraceContext.clear();
            } finally {
                done.countDown();
            }
        }).start();
        done.await();

        assertEquals(0, otherDepth.get(), "another thread must not see this thread's frames");
        assertNotNull(otherFrame.get());
        assertEquals(1, TraceContext.depth(), "main thread frame still present");
    }

    @Test
    void clearReleasesThreadLocal() {
        TraceContext.push("a", "SERVER");
        TraceContext.push("b", "INTERNAL");

        TraceContext.clear();

        assertEquals(0, TraceContext.depth());
        assertNull(TraceContext.peek());
    }
}
