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

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded buffer between agent instrumentation hooks (producer) and the
 * background sender (consumer).
 *
 * <p>Producer side uses non-blocking {@link #offer(Span)} — when capacity is
 * exceeded the span is silently dropped to protect the host application from
 * back-pressure. The agent's job is best-effort observability, not correctness.
 *
 * <p>Consumer side uses {@link #poll(long, TimeUnit)} + {@link #drainTo(Collection, int)}
 * to assemble batches with both a max size and a max wait.
 */
public final class SpanQueue {

    private final LinkedBlockingQueue<Span> queue;
    private final int capacity;

    public SpanQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** @return true when the span was queued, false when the buffer was full and the span was dropped. */
    public boolean offer(Span span) {
        if (span == null) {
            return false;
        }
        return queue.offer(span);
    }

    public Span poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int drainTo(Collection<? super Span> sink, int maxElements) {
        return queue.drainTo(sink, maxElements);
    }

    public int drainTo(Collection<? super Span> sink) {
        return queue.drainTo(sink);
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }
}
