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

import io.apilens.agent.util.AgentLogger;
import io.apilens.common.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that drains {@link SpanQueue} into batches and hands them to
 * {@link HttpTransport}. Designed to never let any failure escape — the loop
 * keeps running until {@link #shutdown()} flips {@code running = false}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link AgentLifecycle#start} creates a daemon thread executing this Runnable.</li>
 *   <li>On shutdown hook, {@link #shutdown()} is called and the thread is joined
 *       with a 2-second budget for a final drain attempt.</li>
 * </ol>
 */
public final class SpanSender implements Runnable {

    private final SpanQueue queue;
    private final HttpTransport transport;
    private final AgentLogger logger;
    private final int batchMaxSize;
    private final long flushIntervalMs;
    private volatile boolean running = true;

    public SpanSender(SpanQueue queue, HttpTransport transport, AgentLogger logger,
                      int batchMaxSize, long flushIntervalMs) {
        this.queue = queue;
        this.transport = transport;
        this.logger = logger;
        this.batchMaxSize = batchMaxSize;
        this.flushIntervalMs = flushIntervalMs;
    }

    @Override
    public void run() {
        while (running) {
            try {
                pollAndSend();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // never let an exception kill the sender — host app must keep running
                logger.error("sender loop error (continuing)", t);
            }
        }
        // shutdown drain: best-effort flush of whatever is left in the queue
        try {
            drainAndSend();
        } catch (Throwable t) {
            logger.error("shutdown drain failed", t);
        }
    }

    private void pollAndSend() throws InterruptedException {
        Span first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return;
        }
        List<Span> batch = new ArrayList<>(batchMaxSize);
        batch.add(first);
        queue.drainTo(batch, batchMaxSize - 1);
        transport.send(batch);
    }

    private void drainAndSend() {
        List<Span> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (remaining.isEmpty()) {
            return;
        }
        for (int i = 0; i < remaining.size(); i += batchMaxSize) {
            int end = Math.min(i + batchMaxSize, remaining.size());
            transport.send(remaining.subList(i, end));
        }
    }

    public void shutdown() {
        running = false;
    }
}
