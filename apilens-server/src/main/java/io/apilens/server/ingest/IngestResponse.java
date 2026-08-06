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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.apilens.server.instrument.config.InstrumentConfigPayload;

/**
 * Result of {@code POST /v1/spans}.
 *
 * <p>[Phase R20] R20/AC-04-1 — GT-3 재정의(Q-U3 사용자 승인 개방, 사용자 명시 비협상 결정):
 * <b>"additive only — 기존 두 필드({@code accepted}·{@code traces}) 형식 불변, 새 필드 추가만
 * 허용"</b>. 기존 두 필드의 이름·타입·의미 변경 0 (int primitive 라 NON_NULL 영향도 0).
 * {@code instrumentConfig} 는 <b>부재 허용형</b> — null 이면 {@code @JsonInclude(NON_NULL)} 로
 * JSON 키 자체가 생략된다(config 미설정 서비스·구 agent(2xx body 무파싱) 영향 0, AC-04-2·3).
 *
 * @param accepted         number of spans persisted
 * @param traces           distinct trace_ids touched in this batch
 * @param instrumentConfig 해당 서비스의 원격 계측 설정 piggyback — 설정 없으면 null(키 생략)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestResponse(int accepted, int traces, InstrumentConfigPayload instrumentConfig) {

    /** [Phase R20] R20/AC-04-1 — 기존 2-인자 호출 보존(IngestService.ingest diff 0). */
    public IngestResponse(int accepted, int traces) {
        this(accepted, traces, null);
    }
}
