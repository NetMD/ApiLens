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
package io.apilens.server.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase H] BT-6 — healthStatus 경계값 5분기 단위 테스트.
 *
 * <p>D-03 / Q-03 / EXT-002 적용 (경계값 테스트 케이스 명시).
 * 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>경계값 5축:
 * <ul>
 *   <li>lastSeenAt == null → "never"</li>
 *   <li>정확 5분 (300_000ms) → "active" (경계 포함)</li>
 *   <li>5분 +1ms (300_001ms) → "stale"</li>
 *   <li>정확 30분 (1_800_000ms) → "stale" (경계 포함)</li>
 *   <li>30분 +1ms (1_800_001ms) → "inactive"</li>
 *   <li>clock skew (lastSeenAt &gt; now) → "active" (defensive)</li>
 * </ul>
 */
class HealthStatusTest {

    private static final long NOW = 1_716_400_000_000L;  // 고정 epoch millis

    @Test
    void nullLastSeenAtReturnsNever() {
        assertEquals("never", TraceQueryRepository.computeHealthStatus(null, NOW));
    }

    @Test
    void exactlyZeroAgoReturnsActive() {
        // ago = 0 ms — boundary low
        assertEquals("active", TraceQueryRepository.computeHealthStatus(NOW, NOW));
    }

    @Test
    void exactlyFiveMinutesAgoReturnsActive() {
        // ago = 300_000 ms (boundary, included)
        long lastSeen = NOW - 300_000L;
        assertEquals("active", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }

    @Test
    void fiveMinutesPlusOneMsReturnsStale() {
        // ago = 300_001 ms
        long lastSeen = NOW - 300_001L;
        assertEquals("stale", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }

    @Test
    void exactlyThirtyMinutesAgoReturnsStale() {
        // ago = 1_800_000 ms (boundary, included)
        long lastSeen = NOW - 1_800_000L;
        assertEquals("stale", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }

    @Test
    void thirtyMinutesPlusOneMsReturnsInactive() {
        // ago = 1_800_001 ms
        long lastSeen = NOW - 1_800_001L;
        assertEquals("inactive", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }

    @Test
    void clockSkewLastSeenAtAfterNowReturnsActive() {
        // ago = -1000 ms (agent clock 이 server 보다 1초 빠름)
        // defensive: "방금 받은 service 가 끊김" 모순 회피
        long lastSeen = NOW + 1000L;
        assertEquals("active", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }

    @Test
    void veryOldLastSeenAtReturnsInactive() {
        // ago = 1 hour (3_600_000 ms) > 30 분
        long lastSeen = NOW - 3_600_000L;
        assertEquals("inactive", TraceQueryRepository.computeHealthStatus(lastSeen, NOW));
    }
}
