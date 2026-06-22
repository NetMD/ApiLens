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
package io.apilens.server.ingest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IngestPauseState 단위 테스트 — LongSupplier 시간 소스 주입으로 cap 경계를 결정적으로 검증.
 *
 * <p>[Phase R15] NFR-06 — Thread.sleep/실시간 deadline 단언 0(R14 hotfix 93b015a flaky 교훈).
 * 시각은 {@link AtomicLong} mutable supplier 로 제어한다.
 *
 * <p>MAX_PAUSE_MS = 30분 = 1_800_000 ms. cap 경계는 {@code >} 비교 — 정확값(==)에서는 일시정지 유지.
 */
class IngestPauseStateTest {

    /** MAX_PAUSE_MS verbatim — production static final 과 동일값(30분). 테스트 가독성용 상수. */
    private static final long MAX_PAUSE_MS = 30L * 60L * 1000L; // 1_800_000

    /**
     * [Phase R15] AC-A1-3 verbatim: "재시작 시 in-memory 상태가 false 로 복귀한다" — 새 인스턴스는 수신 중.
     * 정방향: returns false on fresh instance.
     */
    @Test
    void returnsNotPausedOnFreshInstance() {
        IngestPauseState state = new IngestPauseState(() -> 0L);
        assertFalse(state.isPaused(), "초기(재시작) 상태는 일시정지 아님");
        assertNull(state.pausedAt(), "수신 중이면 pausedAt = null (echo 일관성)");
    }

    /**
     * [Phase R15] AC-A3-2 verbatim: "pausedAt 은 paused=false 면 null, true 면 시작 epoch millis" — pause 후 true.
     * 정방향: pauses and records start time.
     */
    @Test
    void pausesAndRecordsStartTime() {
        AtomicLong clock = new AtomicLong(1000L);
        IngestPauseState state = new IngestPauseState(clock::get);

        state.pause();

        assertTrue(state.isPaused(), "pause 후 일시정지 상태");
        assertEquals(1000L, state.pausedAt(), "pausedAt = pause 시점 시각");
    }

    /**
     * [Phase R15] AC-A1-4 — cap 경계 정확 임계(== MAX_PAUSE_MS). {@code >} 비교라 경계 미초과 → 일시정지 유지.
     * 정방향: stays paused exactly at cap boundary.
     */
    @Test
    void staysPausedExactlyAtCapBoundary() {
        AtomicLong clock = new AtomicLong(0L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause(); // pausedAt = 0

        clock.set(MAX_PAUSE_MS); // now - pausedAt == MAX_PAUSE_MS (정확 임계)

        assertTrue(state.isPaused(), "now-pausedAt == MAX_PAUSE_MS 는 경계 미초과(> 비교) → 일시정지 유지");
    }

    /**
     * [Phase R15] AC-A1-4 — cap 경계 미만(MAX_PAUSE_MS - 1). 자가 재개 안 함.
     * 정방향: stays paused just below cap.
     */
    @Test
    void staysPausedJustBelowCap() {
        AtomicLong clock = new AtomicLong(0L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause();

        clock.set(MAX_PAUSE_MS - 1); // 1_799_999

        assertTrue(state.isPaused(), "cap 미만은 자가 재개 안 함");
    }

    /**
     * [Phase R15] AC-A1-4 verbatim: "max-pause cap 경과 시 자가 재개 + WARN" — cap 초과(MAX_PAUSE_MS + 1).
     * 정방향: auto-resumes just above cap.
     */
    @Test
    void autoResumesJustAboveCap() {
        AtomicLong clock = new AtomicLong(0L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause();

        clock.set(MAX_PAUSE_MS + 1); // 1_800_001

        assertFalse(state.isPaused(), "cap 초과 시 자가 재개 → false");
        // 내부 상태도 false 로 수렴 — 시각을 cap 미만으로 되돌려도 다시 일시정지로 보이지 않음.
        clock.set(MAX_PAUSE_MS - 1);
        assertFalse(state.isPaused(), "자가 재개 후 내부 paused=false 확정");
        assertNull(state.pausedAt(), "자가 재개 후 pausedAt = null");
    }

    /**
     * [Phase R15] AC-A1-5 — cap 자가 재개와 D05 수동 resume 은 별개 트리거. 둘 다 false 수렴하나 분리 검증.
     * 정방향: resumes manually independent of cap.
     */
    @Test
    void resumesManuallyIndependentOfCap() {
        AtomicLong clock = new AtomicLong(5000L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause();
        assertTrue(state.isPaused());

        // cap 미경과 시점에 수동 resume(D05) — cap 트리거와 무관하게 즉시 false.
        clock.set(5000L + 1000L); // cap 한참 미만
        state.resume();

        assertFalse(state.isPaused(), "수동 resume 은 cap 과 별개로 즉시 false");
        assertNull(state.pausedAt());
    }

    /**
     * [Phase R15] BL-05 — 멱등 pause×2 시 pausedAt 최초 시각 유지.
     * 정방향: keeps first start time on repeated pause.
     */
    @Test
    void keepsFirstStartTimeOnRepeatedPause() {
        AtomicLong clock = new AtomicLong(1000L);
        IngestPauseState state = new IngestPauseState(clock::get);

        state.pause();          // pausedAt = 1000
        clock.set(2000L);
        state.pause();          // 이미 paused → 시각 갱신 안 함

        assertTrue(state.isPaused());
        assertEquals(1000L, state.pausedAt(), "pause×2 라도 최초 시각(1000) 유지");
    }

    /**
     * [Phase R15] 멱등 resume×2 — false 유지, pausedAt null.
     * 정방향: stays resumed on repeated resume.
     */
    @Test
    void staysResumedOnRepeatedResume() {
        AtomicLong clock = new AtomicLong(1000L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause();

        state.resume();
        state.resume();

        assertFalse(state.isPaused());
        assertNull(state.pausedAt());
    }

    /**
     * [Phase R15] 자가 재개 후 다시 pause 하면 새 시작 시각으로 일시정지(cap 재계산).
     * 정방향: re-pauses with fresh start time after auto-resume.
     */
    @Test
    void rePausesWithFreshStartTimeAfterAutoResume() {
        AtomicLong clock = new AtomicLong(0L);
        IngestPauseState state = new IngestPauseState(clock::get);
        state.pause();              // pausedAt = 0
        clock.set(MAX_PAUSE_MS + 1);
        assertFalse(state.isPaused()); // 자가 재개

        clock.set(MAX_PAUSE_MS + 100);
        state.pause();              // 새 시작 시각 = MAX_PAUSE_MS + 100

        assertTrue(state.isPaused());
        assertEquals(MAX_PAUSE_MS + 100, state.pausedAt(), "재 pause 는 새 시작 시각");
    }

    /** [Phase R15] LongSupplier 주입이 production 동작과 동치임을 명시(NFR-06 — Thread.sleep 0 보장). */
    @Test
    void usesInjectedTimeSourceForDeterminism() {
        LongSupplier deterministicClock = () -> 12345L;
        IngestPauseState state = new IngestPauseState(deterministicClock);
        state.pause();
        assertEquals(12345L, state.pausedAt(), "주입한 시각 소스가 그대로 사용됨(결정적)");
    }
}
