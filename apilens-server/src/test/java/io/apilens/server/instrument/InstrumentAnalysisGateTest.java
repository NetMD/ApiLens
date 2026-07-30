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
package io.apilens.server.instrument;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R19] InstrumentAnalysisGate — 동시 실행 1건 + 점유 상한 경계.
 *
 * <p>시간 소스를 주입해 경계(미만 / 정확 / 초과)를 <b>{@code Thread.sleep} 0</b> 으로 단언한다
 * (CI flaky 회피 — {@code IngestPauseStateTest} 와 동형 패턴).
 *
 * <p>경계 규약: 점유 시각으로부터 경과가 상한을 <b>초과</b>했을 때만 다음 요청이 뺏는다.
 * 정확히 상한값이면 아직 점유가 유효하다({@code >} 비교).
 */
class InstrumentAnalysisGateTest {

    /** 주입 가능한 가짜 시계 — 테스트가 시각을 직접 옮긴다. */
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private InstrumentAnalysisGate gate() {
        return new InstrumentAnalysisGate(clock::get);
    }

    @Test
    void runsWorkAndReturnsItsResult() {
        InstrumentAnalysisGate gate = gate();

        String result = gate.runExclusive(() -> "done");

        assertEquals("done", result);
    }

    @Test
    void releasesGateAfterWorkCompletes() {
        InstrumentAnalysisGate gate = gate();

        assertEquals("first", gate.runExclusive(() -> "first"));
        // 해제가 됐으므로 곧바로 다음 실행이 가능하다 — 해제 누락이면 여기서부터 영구 409 가 된다.
        assertEquals("second", gate.runExclusive(() -> "second"));
    }

    @Test
    void releasesGateEvenWhenWorkFails() {
        InstrumentAnalysisGate gate = gate();

        assertThrows(IllegalStateException.class, () -> gate.runExclusive(() -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals("after failure", gate.runExclusive(() -> "after failure"));
    }

    @Test
    void signalsBusyWhileAnotherRunHoldsTheGate() {
        InstrumentAnalysisGate gate = gate();

        // 바깥 작업이 도는 도중에 안쪽에서 다시 진입을 시도한다.
        RuntimeException inner = gate.runExclusive(() ->
                assertThrows(RuntimeException.class, () -> gate.runExclusive(() -> "nested")));

        assertInstanceOf(InstrumentAnalysisGate.BusyException.class, inner);
    }

    @Test
    void keepsHoldingBelowTheOccupancyCap() {
        InstrumentAnalysisGate gate = gate();
        long startedAt = clock.get();

        gate.runExclusive(() -> {
            clock.set(startedAt + InstrumentAnalysisGate.OCCUPANCY_CAP_MS - 1);
            assertThrows(InstrumentAnalysisGate.BusyException.class, () -> gate.runExclusive(() -> "nested"));
            return null;
        });
    }

    @Test
    void keepsHoldingAtExactlyTheOccupancyCap() {
        InstrumentAnalysisGate gate = gate();
        long startedAt = clock.get();

        gate.runExclusive(() -> {
            clock.set(startedAt + InstrumentAnalysisGate.OCCUPANCY_CAP_MS);
            // 경계는 초과부터 — 정확히 상한값에서는 아직 점유가 유효하다.
            assertThrows(InstrumentAnalysisGate.BusyException.class, () -> gate.runExclusive(() -> "nested"));
            return null;
        });
    }

    @Test
    void allowsTakeoverAboveTheOccupancyCap() {
        InstrumentAnalysisGate gate = gate();
        long startedAt = clock.get();

        String nested = gate.runExclusive(() -> {
            clock.set(startedAt + InstrumentAnalysisGate.OCCUPANCY_CAP_MS + 1);
            return gate.runExclusive(() -> "taken over");
        });

        assertEquals("taken over", nested);
    }

    // ─── 실행 시간 상한(504 경로) ────────────────────────────────────────────

    @Test
    void signalsDeadlineExceededWhenWorkOutrunsItsBudget() throws Exception {
        // 데드라인을 짧게 주입해 대기 없이 504 경로를 재현한다(Thread.sleep 0 — 래치로 붙잡는다).
        InstrumentAnalysisGate gate = new InstrumentAnalysisGate(clock::get, 50L);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertThrows(InstrumentAnalysisGate.DeadlineExceededException.class,
                    () -> gate.runExclusive(() -> {
                        awaitQuietly(release);
                        return "too late";
                    }));

            // 버려진 작업이 아직 도는 동안에는 점유가 풀리지 않는다 — 무거운 읽기 둘이 겹치면 안 된다.
            assertThrows(InstrumentAnalysisGate.BusyException.class, () -> gate.runExclusive(() -> "next"));
        } finally {
            release.countDown();
        }

        // 버려진 작업이 끝나면 점유가 스스로 풀려 다음 요청이 정상 통과한다.
        assertTrue(waitUntilFree(gate), "the gate must free itself once the abandoned work finishes");
    }

    @Test
    void keepsWorkRunningWithinTheDeadline() {
        InstrumentAnalysisGate gate = new InstrumentAnalysisGate(clock::get, 10_000L);

        assertEquals("in time", gate.runExclusive(() -> "in time"));
    }

    @Test
    void keepsTheTakeoverAliveWhenTheEvictedRunFinishes() {
        InstrumentAnalysisGate gate = gate();
        long startedAt = clock.get();

        // 상한을 넘겨 점유를 뺏긴 원 실행이 뒤늦게 끝나도, 남의 점유를 풀어 버리면 안 된다.
        gate.runExclusive(() -> {
            clock.set(startedAt + InstrumentAnalysisGate.OCCUPANCY_CAP_MS + 1);
            // 안쪽에서 점유를 인수한 뒤, 인수한 실행은 계속 도는 중이라고 가정하지 않고
            // 곧바로 끝내 인수분을 해제한다. 그 뒤 바깥이 끝나면서 자기 것이 아닌 점유를 풀지 않는지 본다.
            gate.runExclusive(() -> "taken over");
            return null;
        });

        // 바깥 실행이 끝난 뒤에도 게이트는 정상적으로 비어 있어야 한다.
        assertEquals("next", gate.runExclusive(() -> "next"));
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 버려진 작업이 점유를 놓을 때까지 짧게 재시도한다(스레드 종료 순서에만 의존하지 않게). */
    private static boolean waitUntilFree(InstrumentAnalysisGate gate) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                gate.runExclusive(() -> "free");
                return true;
            } catch (InstrumentAnalysisGate.BusyException e) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return false;
    }
}
