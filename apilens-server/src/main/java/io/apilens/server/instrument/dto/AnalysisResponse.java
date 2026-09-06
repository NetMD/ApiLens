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

import io.apilens.server.instrument.ExcludeReasonCode;
import io.apilens.server.instrument.ExcludeStatus;

import java.util.List;

/**
 * Response of {@code POST /v1/instrument/analysis} — the three-axis ranking of a service.
 *
 * <p>// [Phase R19] AC-02-2/AC-02-3/AC-02-6 — 순위는 언제나 <b>전체 클래스 집합 기준</b>이다.
 * // 잘린 목록 안 순위가 아니다 — 그래야 "이 목록 안 1위" 와 "전체 1위" 가 안 섞인다.
 *
 * @param window       구간·조회 시각
 * @param summary      구간 총계 — <b>바꾸기 전</b> 값. 시뮬레이션의 {@code impact} 와 짝을 이룬다
 * @param totalClasses 구간 안 전체 클래스 수(집계 상한 안에서 센 값)
 * @param truncated    목록이 잘렸는가
 * @param items        세 축 각각 상위 N 의 합집합 + 고정 합계 행. 서버가 준 순서를 그대로 쓴다
 */
public record AnalysisResponse(
        Window window,
        Summary summary,
        int totalClasses,
        boolean truncated,
        List<ClassStat> items
) {

    /**
     * Totals for the window, computed <b>before</b> any exclusion.
     *
     * <p>비율은 0.0~1.0 실수 도메인이다. 100 을 곱한 값을 담지 않는다 — 어느 자리는 실수로,
     * 어느 자리는 백분율로 다루면 100배 틀리고 경고가 아예 안 뜬다.
     *
     * <p>// [Phase R25] AC-25-05-1/AC-25-05-2 — 필드 하나가 <b>뒤에</b> 늘었다(추가만 — 기존 네 필드의
     * // 이름·타입은 안 건드린다). 순위 표의 {@code payloadBytes} 는 <b>참조당 그대로</b>이고(UD-4),
     * // 같은 본문을 한 번만 센 값은 이 요약 자리에만 나온다.
     *
     * @param totalSpans           구간 안 span 수
     * @param totalTraces          구간 안 trace 수
     * @param avgSpansPerTrace     trace 당 평균 span 수 (분모 0이면 0.0)
     * @param singleSpanTraceRatio span 이 하나뿐인 trace 의 비율 (0.0~1.0, 분모 0이면 0.0)
     * @param uniquePayloadBytes   구간 안에서 <b>같은 본문을 한 번만</b> 센 바이트 합.
     *                             옛 형태 행은 행마다 별개 본문으로 세므로 올린 뒤 약 이틀간 실제보다 크게 나온다
     *                             (틀리는 방향은 절감이 덜 되어 보이는 안전한 쪽 —
     *                             {@code InstrumentAnalysisRepository.aggregateUniquePayloadBytes} javadoc 참조)
     */
    public record Summary(
            long totalSpans,
            long totalTraces,
            double avgSpansPerTrace,
            double singleSpanTraceRatio,
            long uniquePayloadBytes
    ) {
    }

    /**
     * One class row of the ranking.
     *
     * <p>순위 3종은 고정 합계 행({@code className == ""})에서 {@code null} 이다 — 합계 행은
     * 순위 경쟁 대상이 아니라서 화면이 {@code #N} 을 붙이지 않는다.
     *
     * @param className         span 이름의 클래스 부분. {@code ""} 이면 클래스 이름이 없는 span 들의 고정 합계 행
     * @param spanCount         span 수
     * @param payloadCount      payload 건수
     * @param payloadBytes      payload 크기 합(바이트)
     * @param spanRank          span 수 기준 순위(1부터). 합계 행은 {@code null}
     * @param payloadCountRank  payload 건수 기준 순위. 합계 행은 {@code null}
     * @param payloadBytesRank  payload 크기 기준 순위. 합계 행은 {@code null}
     * @param rootRatio         이 클래스의 span 중 시작점(root)인 것의 비율 (0.0~1.0)
     * @param backgroundWorker  {@code rootRatio} 가 임계 이상이면 true (백그라운드 작업 성격)
     * @param excludeStatus     제외 가능성 3분류
     * @param excludeReasonCode 불가·불확실 사유 코드({@code EXCLUDABLE} 이면 {@code null})
     * @param excludeTarget     옵션에 넣을 값({@code EXCLUDABLE} 일 때만 값이 있다)
     */
    public record ClassStat(
            String className,
            long spanCount,
            long payloadCount,
            long payloadBytes,
            Integer spanRank,
            Integer payloadCountRank,
            Integer payloadBytesRank,
            double rootRatio,
            boolean backgroundWorker,
            ExcludeStatus excludeStatus,
            ExcludeReasonCode excludeReasonCode,
            String excludeTarget
    ) {
    }
}
