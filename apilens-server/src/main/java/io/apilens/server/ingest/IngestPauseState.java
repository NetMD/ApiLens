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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * In-memory receive-pause state for maintenance mode.
 *
 * <p>// [Phase R15] AC-A1-1/AC-A1-3/AC-A1-4 — 수신 일시정지 상태 보유 싱글톤. 사용자 명시 비협상 결정(D03/D08).
 * // CLAUDE.md '데이터 모델' (스키마 변경 0, V4 마이그레이션 금지 — in-memory only) 인용.
 *
 * <p>cap 경과 자가 재개를 {@link #isPaused()} 호출 시점의 lazy 판정으로 수행한다(별도 스케줄러 0).
 * 모든 cap 판정·시각 읽기는 이 클래스 안에서만 한다 — IngestController/MaintenanceController 가
 * {@code pausedAtEpochMs} 를 직접 비교하거나 {@code now-pausedAt} 을 계산하지 않는다(진입점 단일화).
 */
@Component
public class IngestPauseState {

    private static final Logger log = LoggerFactory.getLogger(IngestPauseState.class);

    /**
     * // [Phase R15] AC-A1-6 — max-pause cap = 운영자가 일시정지를 켜둔 채 잊었을 때 강제 종료(자가 재개)하는
     * // 안전장치. 매직넘버 금지(static final 봉인). 사용자 명시 비협상 결정(D08 — D05 수동 재개와 별개 트리거).
     * // CLAUDE.md '아키텍처 핵심 원칙' (운영자가 결재 없이 직접 깔고 싶은 작은 도구) 인용.
     */
    static final long MAX_PAUSE_MS = 30L * 60L * 1000L; // 30분

    // [Phase R15] AC-A1-3 — D03 비협상: in-memory, 기본 false, 재시작 false 복귀. V4 마이그레이션 금지.
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile long pausedAtEpochMs = 0L;           // [D08] cap 경과 판정용 시작 시각
    private final LongSupplier nowMs;                     // [NFR-06] 시간 소스 주입(결정적 테스트)

    /** Production 생성자 — 시간 소스 = System.currentTimeMillis (Spring 이 이 생성자로 bean 생성). */
    public IngestPauseState() {
        this(System::currentTimeMillis);
    }

    /**
     * 테스트 전용 — 시간 소스 주입. production 호출 0(운영 동작 불변).
     * RegexComplexityGuard 주입 오버로드 동형(NFR-06 — Thread.sleep 0, CI flaky 회피).
     */
    IngestPauseState(LongSupplier nowMs) {
        this.nowMs = nowMs;
    }

    /**
     * 일시정지 + 시작 시각 기록. 멱등 — 이미 paused 면 {@code pausedAtEpochMs} 최초 시각을 유지한다(BL-05).
     */
    public void pause() {
        // false→true 일 때만 시각 기록(멱등: pause×2 라도 최초 시각 유지).
        if (paused.compareAndSet(false, true)) {
            pausedAtEpochMs = nowMs.getAsLong();
        }
    }

    /** 수신 재개. 멱등(resume 2회도 false 유지). */
    public void resume() {
        paused.set(false);
        pausedAtEpochMs = 0L;
    }

    /**
     * 현재 일시정지 여부. cap 경과면 자가 resume + WARN 후 false 반환(lazy 판정).
     *
     * <p>// [Phase R15] AC-A1-4 — D08 비협상: ingest 요청마다 호출되어 cap 경과 시 자가 재개. 별도 스케줄러 0.
     * // 사용자 명시 비협상 결정(D08). 경계는 {@code >} 비교 — cap 정확값(==)에서는 아직 일시정지 유지.
     */
    public boolean isPaused() {
        if (!paused.get()) {
            return false;
        }
        if (nowMs.getAsLong() - pausedAtEpochMs > MAX_PAUSE_MS) {
            log.warn("maintenance pause exceeded MAX_PAUSE_MS ({} ms), auto-resuming ingest", MAX_PAUSE_MS);
            resume();
            return false;
        }
        return true;
    }

    /**
     * status 응답 echo 용 — paused=false 면 null 반환(echo 일관성).
     *
     * <p>// [Phase R15] AC-A3-2 — 사용자 명시 비협상 결정(D03). cap 판정은 안 함(조회는 부작용 없음).
     */
    public Long pausedAt() {
        return paused.get() ? pausedAtEpochMs : null;
    }
}
