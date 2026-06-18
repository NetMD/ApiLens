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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.Span;
import io.apilens.server.masking.MaskingEngineHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persists ingested spans + payloads, then derives and upserts the trace summary.
 *
 * <p>v0.1 simplification: the trace summary is computed from the spans in <em>this
 * batch</em>. Agents are expected to flush all spans of a finished trace at once.
 * Multi-batch traces (e.g. very long-running root) will have their summary
 * overwritten by the latest batch — acceptable for v0.1; v0.2 will recompute
 * from {@code spans} table on each ingest.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final JdbcTemplate jdbc;
    // [Phase R12] AC-B2-3 — MaskingEngine 직접 주입 → MaskingEngineHolder 전환 (핫 리로드).
    // 매 mask 호출 시점에 current() 로 최신 엔진을 읽는다 — 룰 변경은 이후 ingest 분부터 반영 (BL-06).
    private final MaskingEngineHolder maskingHolder;
    private final ObjectMapper mapper;
    // [Phase R13] AC-A2-1 — payload 저장 직전 크기 가드 한도(개별 payload body byte).
    // @ConfigurationPropertiesScan(ApiLensApplication.java:27)으로 bean 자동 등록 → 생성자 주입.
    private final IngestProperties ingestProperties;

    public IngestService(JdbcTemplate jdbc, MaskingEngineHolder maskingHolder, ObjectMapper mapper,
                         IngestProperties ingestProperties) {
        this.jdbc = jdbc;
        this.maskingHolder = maskingHolder;
        this.mapper = mapper;
        this.ingestProperties = ingestProperties;
    }

    @Transactional
    public IngestResponse ingest(IngestRequest request) {
        validate(request);
        long receivedAt = System.currentTimeMillis();

        Map<String, List<Span>> byTrace = request.spans().stream()
                .collect(Collectors.groupingBy(Span::traceId));

        for (Map.Entry<String, List<Span>> entry : byTrace.entrySet()) {
            persistTrace(entry.getKey(), entry.getValue(), receivedAt);
        }

        return new IngestResponse(request.spans().size(), byTrace.size());
    }

    private void persistTrace(String traceId, List<Span> spans, long receivedAt) {
        // [Phase R12] AC-A5-1 — FR-A5: 단건 INSERT 루프 → batchUpdate 전환 (writer 점유 축소).
        // A5 비협상 (Design §0-3): SQL 문자열·컬럼·REPLACE 시맨틱(G-12) 무변경 — 호출 형태만 배치.
        // upsertTraceSummary 의 spans SQL 재집계 구조는 절대 불변 (diff 0).
        insertSpans(spans);
        insertPayloads(spans);
        upsertTraceSummary(traceId, spans, receivedAt);
        // [Phase H] AC-06-3 — D-02 경로 B (자동 등록). 사용자 명시 비협상 결정.
        // CLAUDE.md '아키텍처 핵심 원칙' (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
        // R6 회귀 가드: try-catch(Throwable) 외곽 — 호스트 throw 0 비협상.
        upsertServiceRegistration(spans, receivedAt);
    }

    /**
     * Auto-register services seen in this batch (D-02 path B).
     *
     * <p>[Phase H] AC-06-3 — D-02 / R6 / R12. 사용자 명시 비협상 결정.
     * CLAUDE.md '아키텍처 핵심 원칙' (호스트 throw 0) 인용.
     *
     * <p>R6 비협상: 어떤 이유로 실패해도 host throw 0. trace 수신 흐름
     * (spans/payloads/traces INSERT) 은 이미 INSERT 된 상태, services UPSERT 만
     * 실패해도 전체 트랜잭션 rollback 0 — Spring 의 DataAccessException 은
     * RuntimeException 이므로 정상이라면 rollback 마킹하지만, 본 분기는
     * try-catch(Throwable) 외곽으로 잡아 silent log + skip 한다. 트랜잭션은
     * 이미 INSERT/UPDATE 가 완료된 상태로 정상 commit.
     *
     * <p>R12 회귀 가드: 단일 UPSERT 1회 per distinct service_name. spans 전체
     * SQL 재집계 패턴 추가 도입 0.
     *
     * <p>D-02 멱등성: ON CONFLICT(service_name) DO UPDATE SET last_seen_at =
     * excluded.last_seen_at → source 와 registered_at 은 처음 INSERT 시점 값 유지.
     * wizard 로 먼저 등록된 service (source='wizard') 의 trace 가 도착해도
     * source='wizard' 유지.
     */
    private void upsertServiceRegistration(List<Span> batchSpans, long receivedAt) {
        try {
            Set<String> distinctServices = batchSpans.stream()
                    .map(Span::serviceName)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());
            for (String name : distinctServices) {
                jdbc.update(
                        """
                                INSERT INTO services (service_name, registered_at, last_seen_at, source)
                                VALUES (?, ?, ?, 'auto')
                                ON CONFLICT(service_name) DO UPDATE SET last_seen_at = excluded.last_seen_at
                                """,
                        name, receivedAt, receivedAt
                );
            }
        } catch (Throwable t) {
            // D-02 비협상 + R6 회귀 차단: services UPSERT 실패는 silent log + skip.
            // 호스트 throw 0 / 트랜잭션 전체 rollback 0.
            log.warn("services UPSERT skipped due to {}: {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /**
     * [Phase R12] AC-A5-1 — spans batchUpdate 1회. SQL 문자열·컬럼·INSERT OR REPLACE
     * 시맨틱은 v0.1 단건 버전과 동일 (G-12 — REPLACE 시맨틱 무변경).
     */
    private void insertSpans(List<Span> spans) {
        List<Object[]> rows = spans.stream()
                .map(span -> new Object[]{
                        span.spanId(),
                        span.traceId(),
                        span.parentSpanId(),
                        span.serviceName(),
                        span.operationName(),
                        span.spanKind().name(),
                        span.startTime(),
                        span.endTime(),
                        span.status().name(),
                        serializeAttributes(span.attributes())
                })
                .toList();
        jdbc.batchUpdate(
                """
                        INSERT OR REPLACE INTO spans (
                            span_id, trace_id, parent_span_id, service_name, operation_name,
                            span_kind, start_time, end_time, status, attributes_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                rows
        );
    }

    /**
     * [Phase R12] AC-A5-1 — payloads 마스킹 적용 후 batchUpdate 1회.
     * 마스킹은 저장 전 1회 적용 구조 그대로 (BL-06 — 기존 payload 재마스킹 경로 없음).
     */
    private void insertPayloads(List<Span> spans) {
        List<Object[]> rows = new ArrayList<>();
        for (Span span : spans) {
            if (span.payloads() == null) {
                continue;
            }
            for (Payload payload : span.payloads()) {
                // NFR-06 비협상: mask → guard 순서 (마스킹 회피 차단). mask 결과를 측정·절단한다.
                String maskedBody = maskingHolder.current().mask(payload.body(), payload.contentType());
                // [Phase R13] AC-A1-1/AC-A1-2/AC-A1-5 — D-03 server-side truncate 가드.
                // 한도 초과 시 잘라 저장 + truncated=1. agent 가 정상 흐름에서 먼저 자르므로 보통 idle(안전망).
                PayloadGuard.Result guarded = PayloadGuard.guard(maskedBody, ingestProperties.maxPayloadBytes());
                rows.add(new Object[]{
                        span.spanId(),
                        payload.direction().name().toLowerCase(Locale.ROOT),
                        payload.contentType(),
                        // 한도 초과면 절단 본문, 아니면 mask 결과 그대로 (무손실).
                        guarded.body(),
                        // size_bytes: server 가 절단했을 때만 mask 결과의 원본 byte 로 재계산해 덮어씀.
                        // 미발동 시 agent 가 보낸 sizeBytes 신뢰 — "자르기 전 원본 크기" 의미 보존 (AC-A1-5, D-A3).
                        guarded.truncated() ? guarded.sizeBytes() : payload.sizeBytes(),
                        // truncated: server 가 잘랐거나(신규) agent 가 이미 잘랐으면(기존) 1 — OR 보존 (AC-A1-5).
                        (guarded.truncated() || payload.truncated()) ? 1 : 0
                });
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                rows
        );
    }

    private void upsertTraceSummary(String traceId, List<Span> batchSpans, long receivedAt) {
        // 같은 trace의 spans가 여러 batch로 나눠 들어와도 traces 요약은 항상 전체 상태를
        // 반영하도록 spans 테이블을 다시 집계. (이번 batch도 이미 INSERT OR REPLACE 됨.)
        // sample-app 검증에서 SpanSender의 poll-arrival 즉시 drain 패턴이 매 advice exit마다
        // 별도 batch를 만들어 옛 정책(batch 단위 덮어쓰기)이 마지막 batch로 traces.span_count를
        // 1로 덮어쓰는 부작용 발견 — Phase E1 후속 fix.

        Map<String, Object> aggregate = jdbc.queryForMap(
                """
                        SELECT
                            MIN(start_time)                                            AS min_start,
                            MAX(end_time)                                              AS max_end,
                            COUNT(*)                                                   AS span_count,
                            COUNT(DISTINCT service_name)                               AS service_count,
                            SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END)          AS error_count
                        FROM spans
                        WHERE trace_id = ?
                        """,
                traceId
        );

        Map<String, Object> rootInfo = jdbc.queryForMap(
                """
                        SELECT operation_name, service_name FROM spans
                        WHERE trace_id = ?
                        ORDER BY (CASE WHEN parent_span_id IS NULL THEN 0 ELSE 1 END) ASC,
                                 start_time ASC
                        LIMIT 1
                        """,
                traceId
        );

        long startTime = ((Number) aggregate.get("min_start")).longValue();
        long endTime = ((Number) aggregate.get("max_end")).longValue();
        int spanCount = ((Number) aggregate.get("span_count")).intValue();
        int serviceCount = ((Number) aggregate.get("service_count")).intValue();
        long errorCount = aggregate.get("error_count") == null
                ? 0L
                : ((Number) aggregate.get("error_count")).longValue();
        boolean hasError = errorCount > 0;

        jdbc.update(
                """
                        INSERT OR REPLACE INTO traces (
                            trace_id, root_operation, service_name, start_time, duration_ms,
                            status, span_count, service_count, has_error, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                traceId,
                rootInfo.get("operation_name"),
                rootInfo.get("service_name"),
                startTime,
                endTime - startTime,
                hasError ? "ERROR" : "OK",
                spanCount,
                serviceCount,
                hasError ? 1 : 0,
                receivedAt
        );
    }

    private String serializeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize span attributes", e);
        }
    }

    private static void validate(IngestRequest request) {
        if (request == null || request.spans() == null || request.spans().isEmpty()) {
            throw new IllegalArgumentException("spans is required and must be non-empty");
        }
        for (Span s : request.spans()) {
            if (s.spanId() == null || s.spanId().isBlank()) {
                throw new IllegalArgumentException("each span must have spanId");
            }
            if (s.traceId() == null || s.traceId().isBlank()) {
                throw new IllegalArgumentException("each span must have traceId");
            }
            if (s.spanKind() == null) {
                throw new IllegalArgumentException("each span must have spanKind");
            }
            if (s.status() == null) {
                throw new IllegalArgumentException("each span must have status");
            }
            if (s.operationName() == null || s.operationName().isBlank()) {
                throw new IllegalArgumentException("each span must have operationName");
            }
            if (s.serviceName() == null || s.serviceName().isBlank()) {
                throw new IllegalArgumentException("each span must have serviceName");
            }
            if (s.endTime() < s.startTime()) {
                throw new IllegalArgumentException("span endTime must be >= startTime");
            }
        }
    }
}
