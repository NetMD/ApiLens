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
import io.swagger.v3.oas.annotations.Operation;
import io.apilens.server.query.dto.PayloadListResponse;
import io.apilens.server.query.dto.ServiceListResponse;
import io.apilens.server.query.dto.TraceDetailResponse;
import io.apilens.server.query.dto.TraceListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read endpoints consumed by the dashboard and trace detail view.
 *
 * <p>Error handling:
 * <ul>
 *   <li>{@link IllegalArgumentException} (incl. {@link CursorCodec.InvalidCursorException}) → 400</li>
 *   <li>{@link TraceNotFoundException}, {@link SpanNotFoundException} → 404 with stable body shape</li>
 * </ul>
 */
@RestController
public class TraceQueryController {

    private final TraceQueryService service;

    public TraceQueryController(TraceQueryService service) {
        this.service = service;
    }

    // [Phase R12] AC-C2-1 — FR-C2: `q` param 신설 (root_operation 풀 FQCN 부분 일치).
    // D-03 비협상: 필터는 status + operation 검색만 — duration 필터 param 추가 금지.
    // [Phase R16] FR-04 — 상위 핵심 @Operation(§4.4 T-08). 응답 스키마는 반환 record 자동 도출(§4.3).
    @Operation(summary = "Trace 목록 조회 (대시보드 산점도·리스트)")
    @GetMapping("/v1/traces")
    public TraceListResponse listTraces(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) SpanStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        return this.service.listTraces(service, since, until, status, q, limit, cursor);
    }

    // [Phase R16] FR-04 — 상위 핵심 @Operation(§4.4 T-09).
    @Operation(summary = "Trace 상세 조회 (노드 그래프용 span 목록)")
    @GetMapping("/v1/traces/{traceId}")
    public TraceDetailResponse getTrace(@PathVariable String traceId) {
        return service.getTrace(traceId);
    }

    // [Phase R16] FR-04 — 상위 핵심 @Operation. §4.1 P1-② 범위(#4)이나 §4.4 확정 문구 미명세 →
    //   dev 가 D3 상위 핵심 취지 하에 문구 보강(설계와 다르게 구현한 부분 §에 자진 신고).
    @Operation(summary = "Payload 조회 (노드 클릭 시 lazy load)")
    @GetMapping("/v1/traces/{traceId}/spans/{spanId}/payloads")
    public PayloadListResponse getPayloads(
            @PathVariable String traceId,
            @PathVariable String spanId
    ) {
        return service.getPayloads(traceId, spanId);
    }

    @GetMapping("/v1/services")
    public ServiceListResponse listServices() {
        return service.listServices();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(TraceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleTraceNotFound(TraceNotFoundException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "trace not found");
        body.put("traceId", e.getTraceId());
        return body;
    }

    @ExceptionHandler(SpanNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleSpanNotFound(SpanNotFoundException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "span not found");
        body.put("traceId", e.getTraceId());
        body.put("spanId", e.getSpanId());
        return body;
    }
}
