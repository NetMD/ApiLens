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

import io.apilens.common.SpanStatus;
import io.apilens.server.query.dto.PayloadDto;
import io.apilens.server.query.dto.PayloadListResponse;
import io.apilens.server.query.dto.ServiceListResponse;
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.query.dto.TraceDetailResponse;
import io.apilens.server.query.dto.TraceListResponse;
import io.apilens.server.query.dto.TraceSummary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-side service. Wraps {@link TraceQueryRepository} with pagination,
 * limit normalisation, and 404 propagation.
 */
@Service
public class TraceQueryService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final TraceQueryRepository repo;

    public TraceQueryService(TraceQueryRepository repo) {
        this.repo = repo;
    }

    public TraceListResponse listTraces(
            String service,
            Long since,
            Long until,
            SpanStatus status,
            Integer requestedLimit,
            String cursorParam
    ) {
        int limit = normaliseLimit(requestedLimit);
        CursorCodec.Cursor cursor = (cursorParam == null || cursorParam.isEmpty())
                ? null
                : CursorCodec.decode(cursorParam);

        // fetch limit+1 so we know whether more pages exist
        List<TraceSummary> rows = repo.findTraces(service, since, until, status, limit + 1, cursor);

        String nextCursor = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            TraceSummary last = rows.get(rows.size() - 1);
            nextCursor = CursorCodec.encode(last.startTime(), last.traceId());
        }
        return new TraceListResponse(rows, nextCursor);
    }

    public TraceDetailResponse getTrace(String traceId) {
        TraceSummary summary = repo.findTraceSummary(traceId)
                .orElseThrow(() -> new TraceNotFoundException(traceId));
        List<SpanDto> spans = repo.findSpans(traceId);
        // 편의 필드 — 스팬 스캐너 client(UI)가 root를 찾기 위해 다시 순회하지 않게.
        // null parentSpanId를 가진 스팬이 여러 개면 가장 먼저 발견되는 것을 우선 (start_time ASC 정렬됨).
        String rootSpanId = spans.stream()
                .filter(s -> s.parentSpanId() == null)
                .map(SpanDto::spanId)
                .findFirst()
                .orElse(null);
        return new TraceDetailResponse(summary, rootSpanId, spans);
    }

    public PayloadListResponse getPayloads(String traceId, String spanId) {
        // 404 when the (traceId, spanId) pair does not exist; empty payloads list is a valid 200
        if (!repo.spanExists(traceId, spanId)) {
            throw new SpanNotFoundException(traceId, spanId);
        }
        List<PayloadDto> payloads = repo.findPayloads(spanId);
        return new PayloadListResponse(payloads);
    }

    /**
     * [Phase H] AC-06-3 — D-03 / Q-03. 사용자 명시 비협상 결정.
     * CLAUDE.md '아키텍처 핵심 원칙' 인용.
     *
     * <p>server-side 단일 출처: now 는 호출 시점 1회 계산 → 모든 row 동일 now 기준
     * healthStatus 분기. client drift 0.
     */
    public ServiceListResponse listServices() {
        long now = System.currentTimeMillis();
        return new ServiceListResponse(repo.findServicesWithHealth(now));
    }

    private static int normaliseLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
