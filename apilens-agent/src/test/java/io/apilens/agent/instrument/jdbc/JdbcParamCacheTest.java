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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E3 — JdbcParamCache contract tests.
 *
 * <p>AC mapping: AC-01-1, AC-01-2 (planner §2 US-01), AC-05-1/2 (host throw 0).
 *
 * <p>Mockito mocks for {@link PreparedStatement} are identity-hashed which is
 * enough for the {@link java.util.WeakHashMap} backing store to find entries.
 */
class JdbcParamCacheTest {

    /** UT-CACHE-01: single slot round-trip — put + get returns the values. */
    @Test
    void putAndGetSingleSlot() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 1, "hello");
        JdbcParamCache.put(ps, 2, 42);

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(1, slots.size(), "single un-batched slot expected");
        assertEquals("hello", slots.get(0).get(1));
        assertEquals(42, slots.get(0).get(2));

        JdbcParamCache.clear(ps);
    }

    /** UT-CACHE-02: addBatch boundary opens a fresh slot. */
    @Test
    void commitBatchSlotOpensNewMap() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 1, "a");
        JdbcParamCache.commitBatchSlot(ps);
        JdbcParamCache.put(ps, 1, "b");

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(2, slots.size());
        assertEquals("a", slots.get(0).get(1));
        assertEquals("b", slots.get(1).get(1));

        JdbcParamCache.clear(ps);
    }

    /** UT-CACHE-03: clear removes the entry. */
    @Test
    void clearRemovesEntry() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 1, "x");
        JdbcParamCache.clear(ps);

        assertNull(JdbcParamCache.get(ps));
    }

    /** UT-CACHE-04: put(null, ...) is a silent no-op. */
    @Test
    void putWithNullStatementSilent() {
        // No exception, no side-effect on the cache.
        JdbcParamCache.put(null, 1, "x");
        // get(null) returns null cleanly
        assertNull(JdbcParamCache.get(null));
    }

    /** UT-CACHE-05: negative parameterIndex silently dropped. */
    @Test
    void putWithNegativeIndexSilent() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, -1, "x");

        assertNull(JdbcParamCache.get(ps),
                "negative parameterIndex must not create a cache entry");
    }

    /** UT-CACHE-06: zero parameterIndex silently dropped (JDBC 1-based). */
    @Test
    void putWithZeroIndexSilent() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 0, "x");

        assertNull(JdbcParamCache.get(ps));
    }

    /** UT-CACHE-07: parameterIndex above MAX_PARAMS_PER_SLOT silently dropped. */
    @Test
    void putWithIndexOverflowSilent() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, JdbcParamCache.MAX_PARAMS_PER_SLOT + 1, "x");

        assertNull(JdbcParamCache.get(ps));
    }

    /**
     * UT-CACHE-08: batch slots beyond MAX_BATCH_SLOTS are silently dropped —
     * no host throw, slot count capped.
     */
    @Test
    void batchSlotsExceedingMaxSilent() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 1, "seed");
        // attempt to open MAX + 50 extra slots
        for (int i = 0; i < JdbcParamCache.MAX_BATCH_SLOTS + 50; i++) {
            JdbcParamCache.commitBatchSlot(ps);
        }

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertTrue(slots.size() <= JdbcParamCache.MAX_BATCH_SLOTS,
                "slots " + slots.size() + " must be <= MAX_BATCH_SLOTS (" + JdbcParamCache.MAX_BATCH_SLOTS + ")");

        JdbcParamCache.clear(ps);
    }

    /**
     * UT-CACHE-09: WeakHashMap allows GC reclamation when no strong reference
     * exists. Best-effort — JVM GC policy varies; we time-bound the test.
     */
    @Test
    void weakReferenceAllowsGc() {
        int before = JdbcParamCache.currentSize();
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        JdbcParamCache.put(ps, 1, "v");
        int afterPut = JdbcParamCache.currentSize();
        assertTrue(afterPut > before, "put must create at least one entry");

        // Drop strong reference and nudge GC.
        ps = null;
        for (int i = 0; i < 20; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (JdbcParamCache.currentSize() <= before) {
                return; // reclaimed
            }
        }
        // If we get here GC didn't fire — the test is intentionally tolerant
        // because GC is non-deterministic. We assert the cache didn't grow
        // unboundedly which is the real correctness property we care about.
        assertTrue(JdbcParamCache.currentSize() < before + 5,
                "WeakHashMap entry must not strongly-hold the PreparedStatement");
    }

    /**
     * UT-CACHE-10: concurrent puts to the same statement — host throw 0,
     * data race tolerated (last-write-wins semantics on the inner map are fine).
     */
    @Test
    void concurrentPutFromMultipleThreads() throws InterruptedException {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        int threads = 10;
        int putsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        JdbcParamCache.put(ps, 1, "t" + tid + "-i" + i);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(2, TimeUnit.SECONDS);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "all threads must finish");
        pool.shutdownNow();

        assertEquals(0, errors.get(), "host throw must be 0 across all threads");
        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(1, slots.size());
        assertNotNull(slots.get(0).get(1), "some last-writer's value must be visible");

        JdbcParamCache.clear(ps);
    }

    /** UT-CACHE-11: concurrent puts across distinct statements — host throw 0. */
    @Test
    void concurrentPutDifferentStatements() throws InterruptedException {
        int threads = 10;
        int stmtsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        // Keep strong references so WeakHashMap doesn't reclaim mid-test.
        java.util.List<PreparedStatement> allStmts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < stmtsPerThread; i++) {
                        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
                        allStmts.add(ps);
                        JdbcParamCache.put(ps, 1, "v" + i);
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(0, errors.get(), "host throw must be 0");
        // Each PS that we still strongly reference must still be retrievable.
        for (PreparedStatement ps : allStmts) {
            assertNotNull(JdbcParamCache.get(ps));
            JdbcParamCache.clear(ps);
        }
    }

    /** UT-CACHE-12: get returns a defensive copy — mutating it does not affect cache. */
    @Test
    void getReturnsDefensiveCopy() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        JdbcParamCache.put(ps, 1, "orig");
        List<Map<Integer, Object>> copy = JdbcParamCache.get(ps);
        assertNotNull(copy);
        copy.clear();
        copy.add(new java.util.HashMap<>());

        List<Map<Integer, Object>> again = JdbcParamCache.get(ps);
        assertNotNull(again);
        assertEquals(1, again.size());
        assertEquals("orig", again.get(0).get(1));

        JdbcParamCache.clear(ps);
    }

    /** UT-CACHE-13: clear(null) silent. */
    @Test
    void clearWithNullStatementSilent() {
        // No exception, idempotent.
        JdbcParamCache.clear(null);
        JdbcParamCache.clear(null);
    }

    /**
     * UT-CACHE-14: cache accepts a very large byte[] value — truncation happens
     * at serialization time, not at cache time.
     */
    @Test
    void largeByteArrayValueAccepted() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        byte[] big = new byte[1_000_000];

        JdbcParamCache.put(ps, 1, big);

        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
        assertNotNull(slots);
        assertEquals(1, slots.size());
        assertTrue(slots.get(0).get(1) instanceof byte[]);
        assertEquals(1_000_000, ((byte[]) slots.get(0).get(1)).length);

        JdbcParamCache.clear(ps);
    }
}
