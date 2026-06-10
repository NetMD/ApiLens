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
package io.apilens.server.masking.dto;

import java.util.List;

/**
 * Request body of {@code POST /v1/masking-rules/preview} (Design §5.4).
 *
 * <p>// [Phase R12] AC-B3-1 — D-02 비협상: "라이브 프리뷰 — 결재용 신뢰 도구".
 * // 요청에 화면의 현재 토글 상태 전체 스냅샷({@code ruleStates})을 동봉 — 저장 전 상태 반영.
 * // 서버 DB persisted 상태 의존 0 (race 원천 차단). 사용자 명시 비협상 결정.
 *
 * @param sample      null/생략 = 서버 내장 기본 샘플 (AC-B3-2). 명시 시 blank 400 / 64KB 초과 400
 * @param contentType 생략 시 application/json
 * @param ruleStates  화면 토글 상태 스냅샷. 생략 = DB 저장 상태 그대로. 미존재 ruleId 는 무시
 *                    (stale 화면 관용 — Design §3.1.5)
 */
public record PreviewRequest(
        String sample,
        String contentType,
        List<RuleState> ruleStates
) {

    /** 화면의 룰 1건 토글 상태. */
    public record RuleState(long ruleId, boolean enabled) {
    }
}
