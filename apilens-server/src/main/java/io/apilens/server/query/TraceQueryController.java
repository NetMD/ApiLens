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
import io.apilens.server.query.dto.TracePointsResponse;
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
 *   <li>{@link TraceQueryService.PointsBusyException} → 503 (representative-point permit not obtained)</li>
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
    // [Phase R28] AC-28-01-12 — 요약 글만 고쳤다(차트는 이제 대표 점 경로가 그린다 · 응답 모양 · 인자 불변).
    @Operation(summary = "Trace 목록 조회 (대시보드 리스트)")
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

    /**
     * [Phase R28] AC-28-01-5/AC-28-01-12 — 대시보드 산점도의 대표 점. 사용자 명시 결정(UD-2 「오류 아닌 것 중 가장
     * 느린 1 + 오류 전부」). 새 경로는 {@code /v1/setup/} 로 시작하지 않아 키를 설정한 설치에서 기본 차단이다
     * (인증 면제 목록 무접촉).
     *
     * <p>★인자는 전부 {@code required = false} 다 — 필수 판정은 서비스 검증이 한다(목록 경로 전례). 기본값
     * ({@code required = true})을 쓰면 빠진 인자가 Spring 기본 400(다른 본문)으로 가서 {@code {"error": …}} 모양이
     * 깨진다. 칸 크기 인자는 받지 않는다 — 서버가 창 길이로 고른다(모르는 인자는 무시).
     */
    @Operation(summary = "대표 점 조회 (대시보드 산점도 — 칸마다 가장 느린 정상 trace 하나와 오류)")
    @GetMapping("/v1/trace-points")
    public TracePointsResponse listTracePoints(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) SpanStatus status,
            @RequestParam(required = false) String q
    ) {
        return this.service.listTracePoints(service, since, until, status, q);
    }

    // [Phase R16] FR-04 — 상위 핵심 @Operation(§4.4 T-09).
    @Operation(summary = "Trace 상세 조회 (노드 그래프용 span 목록)")
    @GetMapping("/v1/traces/{traceId}")
    public TraceDetailResponse getTrace(@PathVariable String traceId) {
        return service.getTrace(traceId);
    }

    // [Phase R16] FR-04 — 상위 핵심 @Operation. §4.1 P1-② 범위(#4)이나 §4.4 확정 문구 미명세 →
    //   dev 가 D3 상위 핵심 취지 하에 문구 보강(설계와 다르게 구현한 부분 §에 자진 신고).
    // [Phase R17] FR-07 (T-06) — 문구 보강(Δ-1 마스킹 뉘앙스 추가, docs/api.md '마스킹은 서버에서 적용됩니다' 근거).
    @Operation(summary = "Payload 조회 (노드 클릭 시 lazy load) — body 는 이미 마스킹 적용된 결과(저장 시 1회 적용, 재마스킹 없음)")
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

    /**
     * [Phase R28] AC-28-01-8 — 대표 점 허가를 기다림 안에 못 얻음 → <b>503</b> {@code {"error": …}}(영어 고정 문장).
     * 화면은 본문이 아니라 상태 코드로만 「서버가 바빠」를 고른다. {@code Retry-After} 는 싣지 않는다.
     *
     * <p>★한계: 처리기가 이 컨트롤러 안에 있어 springdoc 이 이 컨트롤러의 다른 경로 명세에도 503 을 단다(설계 §5.2 ⓓ ·
     * 지금 404 가 같은 식으로 번져 있음). 별 컨트롤러로 떼면 파일이 늘어 고르지 않았다 — 공개 문서에 한 줄로 적는다.
     */
    @ExceptionHandler(TraceQueryService.PointsBusyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handlePointsBusy(TraceQueryService.PointsBusyException e) {
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
