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

import io.apilens.common.SpanStatus;

import java.util.List;

/**
 * Body of {@code GET /v1/trace-points} — the representative points the dashboard scatter plot draws.
 *
 * <p>// [Phase R28] AC-28-01-1/AC-28-01-3/AC-28-01-6/AC-28-01-7 — 사용자 명시 결정(UD-2 「오류 아닌 것 중
 * // 가장 느린 1 + 오류 전부」 · 확정 ③ 「칸별 오류 건수와 줄여 보냈다 표시를 늘 싣는다」).
 * // CLAUDE.md 'UI 디자인 철학'(흐름과 끊긴 지점 — 화면 표본 대신 서버가 고른다) 인용.
 * // 응답 record 하나에 중첩 record 둘(DD-28-02) — 계약이 한 자리에 있다. 필드 순서 = JSON 순서(설계 §2.1).
 *
 * <p>★<b>대표 점은 전부가 아니다.</b> 시간 칸마다 가장 느린 정상 trace 하나와 창 안 오류 전부(오류가
 * 상한을 넘으면 칸마다 가장 느린 오류 하나)만 싣는다. 건수의 정본은 {@code buckets} 와 두 총계다.
 *
 * <p>★<b>가장자리 칸</b>: 왼쪽 끝 칸의 {@code start} 는 요청 창 시작보다 앞일 수 있고, 그 칸의 두 건수는
 * <b>창 안 몫만</b>이다(오른쪽 끝 칸도 같다). 서버는 창 밖 행을 읽지 않는다.
 *
 * @param bucketMs      size of one time bucket chosen by the server from the window length (milliseconds)
 * @param totalCount    traces in the window (sum of {@code buckets[].count})
 * @param errorCount    error traces in the window (sum of {@code buckets[].errorCount})
 * @param errorsReduced true when errors exceeded the safety cap and were reduced to the slowest one per bucket
 * @param points        representative points — normal first, then errors; each by startTime, then traceId
 * @param buckets       buckets holding at least one trace, ordered by start
 */
public record TracePointsResponse(
        long bucketMs,
        long totalCount,
        long errorCount,
        boolean errorsReduced,
        List<TracePoint> points,
        List<TraceBucket> buckets
) {

    /**
     * One representative trace. A subset of {@link TraceSummary} — what the scatter plot needs.
     *
     * @param traceId       W3C trace id
     * @param rootOperation operation name of the root span
     * @param startTime     epoch millis at trace start
     * @param durationMs    end-to-end latency as stored (negative values are kept as they are)
     * @param status        OK or ERROR
     * @param hasError      convenience flag from the summary row
     */
    public record TracePoint(
            String traceId,
            String rootOperation,
            long startTime,
            long durationMs,
            SpanStatus status,
            boolean hasError
    ) {
    }

    /**
     * Per-bucket counts. Edge buckets carry only the in-window share.
     *
     * @param start      bucket start, epoch millis (multiple of {@code bucketMs})
     * @param count      traces of this bucket inside the window
     * @param errorCount error traces of this bucket inside the window
     */
    public record TraceBucket(
            long start,
            long count,
            long errorCount
    ) {
    }
}
