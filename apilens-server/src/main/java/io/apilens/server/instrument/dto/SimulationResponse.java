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
package io.apilens.server.instrument.dto;

/**
 * Response of {@code POST /v1/instrument/simulation}.
 *
 * <p>★ <b>{@code savings} 와 {@code impact} 는 언제나 한 응답에 함께 온다.</b> 절감만 담긴 응답을
 * 만드는 코드 경로가 서버에 아예 없다 — "절감 숫자를 보여주는 화면은 반드시 부작용을 함께 보여준다"
 * 는 비협상 결정을 문장이 아니라 <b>계약</b>으로 못 박은 것이다. 둘은 같은 record 의 두 필드이고
 * 둘 다 필수다. 화면 코드가 아무리 바뀌어도 "절감만 받아서 절감만 그리는" 상태가 만들어지지 않는다.
 *
 * <p>// [Phase R19] AC-05-1 — 사용자 명시 비협상 결정(S-3). CLAUDE.md 'UI 디자인 철학'
 * // (마스킹 라이브 프리뷰 = 결재용 신뢰 도구 — 숫자를 보여줄 때 대가도 함께 보여준다) 인용.
 *
 * @param window      구간·조회 시각 (요청이 들고 온 구간 그대로 + 이 결과를 만든 시각)
 * @param savings     절감 축 — 제외 대상에 <b>직접 귀속</b>되는 감소분
 * @param impact      부작용 축 — 조상을 빼면 말단이 새 시작점이 되므로 trace 는 <b>재계산</b>한 값
 * @param depthCapped 깊이 상한 때문에 못 센 흐름이 있는가
 */
public record SimulationResponse(
        Window window,
        Savings savings,
        Impact impact,
        boolean depthCapped
) {

    /**
     * Directly attributed reduction (span/payload actually attached to the excluded classes).
     *
     * <p>⚠️ 자식 span 을 합산하지 않는다 — 그 아래 {@code jdbc.execute} 는 별도 계측이라 부모를 빼도
     * 남는다(실측 확인). 합산하면 절감량을 과대 산정한다.
     *
     * @param spanDelta         줄어드는 span 수
     * @param payloadCountDelta 줄어드는 payload 건수
     * @param payloadBytesDelta 줄어드는 payload 크기(바이트)
     */
    public record Savings(long spanDelta, long payloadCountDelta, long payloadBytesDelta) {
    }

    /**
     * Recomputed shape of the remaining trees.
     *
     * <p>⚠️ 절감과 <b>다른 계산식</b>이다. 같은 감산식으로 묶으면 trace 수를 반드시 틀린다 —
     * 조상을 빼면 말단이 각자 새 시작점이 되어 trace 수가 <b>늘어날 수도</b> 있다.
     * {@code resultTraces} 가 순위 응답의 {@code summary.totalTraces} 보다 커도 오류가 아니다.
     *
     * @param remainingSpans       남는 span 수
     * @param resultTraces         남는 trace 수(재계산 — 늘어날 수 있다)
     * @param avgSpansPerTrace     trace 당 평균 span 수 (분모 0이면 0.0)
     * @param singleSpanTraceRatio span 이 하나뿐인 trace 의 비율 (0.0~1.0, 분모 0이면 0.0)
     */
    public record Impact(
            long remainingSpans,
            long resultTraces,
            double avgSpansPerTrace,
            double singleSpanTraceRatio
    ) {
    }
}
