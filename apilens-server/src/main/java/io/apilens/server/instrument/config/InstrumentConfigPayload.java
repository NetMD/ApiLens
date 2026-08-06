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
package io.apilens.server.instrument.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Per-service desired instrument config — shared by the 202 ingest piggyback and the
 * GET/PUT config API (single DTO — 동형 노출).
 *
 * <p>[Phase R20] R20/AC-03-1 — 어휘 폐쇄(Q-U4 4종만, 사용자 명시 비협상 결정)의 수단 = 스키마 제한:
 * 이 record 의 4필드가 바인딩 표면의 전부다(Jackson 이 미지의 필드를 무시하고, 저장 컬럼도 4개뿐 —
 * 어휘 확장은 스키마 변경 = 사용자 봉인 재개방 사안, BL-R20-06).
 *
 * <p>[Phase R20] R20/AC-04-2 — <b>부재 허용형(옵셔널)</b>: 전 필드 nullable(부재/null = 그 축 지시
 * 없음 = agent 기동 {@code -D} 값 유지). {@code @JsonInclude(NON_NULL)} 로 null 필드는 JSON 키
 * 자체가 생략된다 — 구 agent(2xx body 무파싱)·FE 테스트 factory 영향 0.
 *
 * @param captureParams    null=지시 없음 / false=끄기(줄임) / true=기동값 복귀(Q-U5)
 * @param captureResultSet null=지시 없음 / false=끄기(줄임) / true=기동값 복귀(Q-U5)
 * @param requireEntryRoot null=지시 없음 / true=억제 켜기(줄이는 방향) / false=기동값 복귀
 * @param gateExcludes     런타임 게이트 exclude FQN 목록(정확 일치) — null=목록 지시 없음
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "서비스별 원격 계측 설정 — 모든 필드가 선택(없으면 그 축은 지시 없음). "
        + "agent 는 줄이는 방향만 적용한다(기준점 = JVM 기동 -D 값)")
public record InstrumentConfigPayload(
        Boolean captureParams,
        Boolean captureResultSet,
        Boolean requireEntryRoot,
        List<String> gateExcludes
) {
}
