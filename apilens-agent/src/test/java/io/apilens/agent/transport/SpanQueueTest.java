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
package io.apilens.agent.transport;

import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanQueueTest {

    @Test
    void offerStoresSpan() {
        SpanQueue queue = new SpanQueue(10);
        assertTrue(queue.offer(span("s1")));
        assertEquals(1, queue.size());
    }

    @Test
    void capacityExceededDropsSilently() {
        SpanQueue queue = new SpanQueue(2);
        assertTrue(queue.offer(span("a")));
        assertTrue(queue.offer(span("b")));
        // capacity reached; further offers must return false (drop), never throw
        assertFalse(queue.offer(span("c")));
        assertEquals(2, queue.size());
    }

    @Test
    void drainToWithLimitAssemblesBatch() throws InterruptedException {
        SpanQueue queue = new SpanQueue(10);
        for (int i = 0; i < 5; i++) {
            queue.offer(span("s" + i));
        }
        List<Span> batch = new ArrayList<>();
        Span first = queue.poll(10, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        batch.add(first);
        int more = queue.drainTo(batch, 2); // pull 2 more (3 total)
        assertEquals(2, more);
        assertEquals(3, batch.size());
        assertEquals(2, queue.size()); // 5 - 3 left
    }

    @Test
    void drainToFullDrainsEverything() {
        SpanQueue queue = new SpanQueue(10);
        for (int i = 0; i < 3; i++) {
            queue.offer(span("s" + i));
        }
        List<Span> all = new ArrayList<>();
        int drained = queue.drainTo(all);
        assertEquals(3, drained);
        assertEquals(0, queue.size());
    }

    @Test
    void pollTimesOutWhenEmpty() throws InterruptedException {
        SpanQueue queue = new SpanQueue(10);
        long start = System.nanoTime();
        Span polled = queue.poll(50, TimeUnit.MILLISECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertNull(polled);
        assertTrue(elapsedMs >= 30, "poll should wait close to the timeout, got " + elapsedMs);
    }

    @Test
    void offerNullReturnsFalse() {
        SpanQueue queue = new SpanQueue(10);
        assertFalse(queue.offer(null));
        assertEquals(0, queue.size());
    }

    @Test
    void zeroCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpanQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new SpanQueue(-1));
    }

    private static Span span(String id) {
        long now = System.currentTimeMillis();
        return new Span(id, "trace-x", null, "svc", "op", SpanKind.INTERNAL,
                now, now, SpanStatus.OK, null, List.of());
    }
}
