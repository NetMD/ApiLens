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
package io.apilens.server.query.dto;

/**
 * Single service entry returned by {@code GET /v1/services}.
 *
 * <p>[Phase H] AC-06-3 — W-01 breaking change. 사용자 명시 비협상 결정.
 * CLAUDE.md '아키텍처 핵심 원칙' 인용. v0.1.0 미공개 release 이라 deprecation period 0.
 *
 * <p>변경 사항:
 * <ul>
 *   <li>{@code lastSeen} (long, 항상 값 있음) → {@code lastSeenAt} (Long, NULL 허용)</li>
 *   <li>{@code registeredAt} / {@code source} / {@code healthStatus} 3 필드 신규</li>
 * </ul>
 *
 * @param name          service name (matches {@code traces.service_name} / {@code services.service_name})
 * @param registeredAt  epoch millis of first registration (wizard 또는 첫 trace 도착 시점)
 * @param lastSeenAt    epoch millis of most recent trace (wizard 등록 후 trace 미수신 시 null)
 * @param source        등록 경로 — "wizard" 또는 "auto"
 * @param traceCount    최근 24시간 trace 수 (start_time 기준 윈도우 — [Phase R12] AC-A3-3
 *                      의미 변경: "누적 전수" → "최근 24h". 필드명·타입 무변경, FR-A3)
 * @param healthStatus  서버 응답 시점 분기 — "active" / "stale" / "inactive" / "never"
 */
public record ServiceInfo(
        String name,
        long registeredAt,
        Long lastSeenAt,
        String source,
        long traceCount,
        String healthStatus
) {
}
