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
package io.apilens.server.retention;

/**
 * Echo response shared by GET/POST {@code /v1/maintenance/{status,pause,resume}}.
 *
 * <p>// [Phase R15] AC-A3-1/AC-A3-2 — 수신 일시정지 상태 echo DTO. 사용자 명시 비협상 결정(D03).
 * // CLAUDE.md '데이터 모델' (스키마 변경 0, in-memory 상태) 인용.
 *
 * <p>{@link MaintenanceResult}(cleanup/purge/optimize)와 별도 타입 — 이종 반환 회피(Design §4.1).
 * {@code pausedAt} 은 {@code Long}(박싱)이라 paused=false 시 null 직렬화 가능
 * ({@code {"paused":false,"pausedAt":null}}).
 *
 * <p>// [Phase R20] R20/AC-10-1/AC-10-2 — SQLITE_BUSY 카운터 2필드 additive 확장(기존 두 필드
 * // paused·pausedAt 불변). R17 확정 설계 불변(사용자 명시 비협상 결정): 카운터 이름 그대로 ·
 * // 인메모리(DB 저장 금지·스키마 무변경) · 재시작 시 0 복귀 정상 · 기준선은 logs/apilens.log 누적
 * // 비교. 인증은 기존 status 표면의 기존 상태 그대로(/v1/** default-deny — 표면 신설 0).
 * // CLAUDE.md '데이터 모델' (in-memory, 스키마 변경 0) 인용.
 *
 * @param paused                 현재 수신 일시정지 여부.
 * @param pausedAt               일시정지 시작 epoch millis. paused=false 면 null(echo 일관성, AC-A3-2).
 * @param sqliteBusyEncountered  적재 경합으로 SQLITE_BUSY 예외를 만난 누적 횟수(재시작 시 0).
 * @param sqliteBusyDropped      SQLITE_BUSY 로 유실된 누적 청크 수(청크 ≈ 500 span, 재시작 시 0).
 */
public record MaintenanceStatusResponse(boolean paused, Long pausedAt,
                                        long sqliteBusyEncountered, long sqliteBusyDropped) {
}
