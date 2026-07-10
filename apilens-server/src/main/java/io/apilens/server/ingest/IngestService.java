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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Persists ingested spans + payloads, then derives and upserts the trace summary.
 *
 * <p>The trace summary is always recomputed from the {@code spans} table (not from
 * the current batch), so a trace whose spans arrive across multiple batches keeps a
 * correct summary. See {@code upsertTraceSummary}.
 *
 * <p>[Phase R17] FR-01 — 거대 trace 적재는 span 을 {@code SPAN_CHUNK_SIZE} 단위 청크로 나눠
 * 청크마다 짧은 프로그래매틱 트랜잭션으로 커밋한다(write lock 보유 시간 단축). 청크 중간이
 * SQLITE_BUSY 로 실패하면 앞 청크만 남는 부분 적재를 허용하고(모니터링 도구 — 통째 유실보다 나음),
 * 호스트에는 예외를 던지지 않는다(host-throw-0). 요약은 청크 루프 밖에서 1회만 재집계한다.
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

    // [Phase R17] FR-01 — 청크 단위 프로그래매틱 트랜잭션. 생성자 시그니처 불변(V-02):
    //   이미 주입된 jdbc 의 DataSource 로 트랜잭션 관리자를 본문에서 구성한다(새 인자 0).
    private final TransactionTemplate chunkTx;

    // [Phase R17] FR-03 — SQLITE_BUSY 발생/유실 카운터(인메모리, host-throw-0). 생성자 인자 아님.
    //   재시작 시 0 복귀는 정상(스키마 무변경 — DB 저장 안 함). 기준선은 로그 파일 누적으로 비교.
    //   encountered = SQLITE_BUSY 예외를 catch 한 횟수(경합 이벤트), dropped = 유실된 청크 수(청크 ≈ 500 span).
    private final AtomicLong sqliteBusyEncountered = new AtomicLong();
    private final AtomicLong sqliteBusyDropped = new AtomicLong();

    // [Phase R17] FR-01 — 청크 크기 상수(EXT-003 매직넘버 상수화). OQ-C 확정값(span 개수).
    private static final int SPAN_CHUNK_SIZE = 500;

    // [Phase R17] EXT-003 anchor — V-02 생성자 4-인자 불변(사용자 명시 비협상 결정).
    //   협력자(트랜잭션 관리자·카운터)는 생성자 인자가 아니라 내부 필드/본문으로 얻는다.
    //   R13 287a7e7 회귀 진원지(생성자 변경이 agent 통합테스트 컴파일 파손) 재발 차단.
    //   CLAUDE.md 'Build 설정 lessons §1'(shadow jar relocate 함정) 인용.
    public IngestService(JdbcTemplate jdbc, MaskingEngineHolder maskingHolder, ObjectMapper mapper,
                         IngestProperties ingestProperties) {
        this.jdbc = jdbc;
        this.maskingHolder = maskingHolder;
        this.mapper = mapper;
        this.ingestProperties = ingestProperties;
        // [Phase R17] FR-01 — jdbc.getDataSource() 로 트랜잭션 관리자 구성(생성자 인자 미추가 = V-02 봉인).
        //   ingest 는 순수 JdbcTemplate(JPA 엔티티 미사용)이라 DataSourceTransactionManager 로 정합(GT-9).
        this.chunkTx = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    // [Phase R17] FR-01 — @Transactional 제거: 상위 통짜 트랜잭션 대신 청크별 프로그래매틱 트랜잭션.
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

    // [Phase R17] FR-01 — 청크 단위 프로그래매틱 트랜잭션. 한 trace 의 span 을 SPAN_CHUNK_SIZE 씩 나눠
    //   각 청크(span INSERT OR REPLACE + payload delete-then-insert)를 짧은 트랜잭션으로 커밋한다.
    //   청크 경계마다 write lock 이 풀려 UI 조회·다른 writer 가 끼어들 여지가 생긴다(거대 trace 완화 근본 레버).
    private void persistTrace(String traceId, List<Span> spans, long receivedAt) {
        boolean committedAny = false;
        int total = spans.size();
        for (int start = 0, idx = 0; start < total; start += SPAN_CHUNK_SIZE, idx++) {
            List<Span> chunk = spans.subList(start, Math.min(start + SPAN_CHUNK_SIZE, total));
            try {
                chunkTx.executeWithoutResult(status -> {
                    // [Phase R17] V-03/G-09 — insertSpans 의 INSERT OR REPLACE spans SQL 문자열·컬럼 diff 0.
                    insertSpans(chunk);           // A5 비협상: SQL·REPLACE 시맨틱 무변경, 호출 인자만 청크로.
                    deletePayloadsForChunk(chunk); // [Phase R17] OQ-A — payload 멱등 가드(재적재 중복 0).
                    insertPayloads(chunk);        // [Phase R17] V-03/G-09 — INSERT INTO payloads SQL 문자열 diff 0.
                });
                committedAny = true;
            } catch (DataAccessException e) {
                // [Phase R17] FR-03/V-04 — 청크 write 실패는 host 로 던지지 않는다(host-throw-0).
                boolean busy = isSqliteBusy(e);
                if (busy) {
                    sqliteBusyEncountered.incrementAndGet();
                }
                // 이 청크 + 남은 청크를 유실로 집계(지연 상한 위해 break — 지속 경합 시 남은 청크도 어차피 실패).
                int remaining = (int) Math.ceil((double) (total - start) / SPAN_CHUNK_SIZE);
                sqliteBusyDropped.addAndGet(remaining);
                log.warn("SQLITE_BUSY drop: traceId={} chunkIdx={} droppedChunks={} busy={} cause={} encounteredTotal={} droppedTotal={}",
                        traceId, idx, remaining, busy, e.getClass().getSimpleName(),
                        sqliteBusyEncountered.get(), sqliteBusyDropped.get());
                break; // 요청 지연을 ~1 busy_timeout 으로 제한(남은 청크 시도 안 함).
            }
        }
        if (committedAny) {
            try {
                // [Phase R17] FR-01 — 요약은 청크 루프 밖 1회. upsertTraceSummary 는 spans 테이블을 재집계하므로
                //   부분 적재라도 커밋된 span 만 반영한다(GT-10, G-10 SQL diff 0). 순서 역전 시 O(N²)+lock 점유로 목적 붕괴.
                chunkTx.executeWithoutResult(status -> upsertTraceSummary(traceId, spans, receivedAt));
            } catch (DataAccessException e) {
                if (isSqliteBusy(e)) {
                    sqliteBusyEncountered.incrementAndGet();
                }
                // 요약 실패는 span 유실 아님(이미 커밋) → dropped 증가 안 함. 다음 ingest 가 재집계로 자가치유.
                log.warn("trace summary deferred (self-heal next ingest): traceId={} cause={}",
                        traceId, e.getClass().getSimpleName());
            }
        }
        // [Phase H] AC-06-3 — D-02 경로 B (자동 등록). 사용자 명시 비협상 결정.
        // CLAUDE.md '아키텍처 핵심 원칙' (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
        // R6 회귀 가드: try-catch(Throwable) 외곽 — 호스트 throw 0 비협상.
        // [Phase R17] V-04 — 이제 auto-commit 문맥(상위 @Transactional 제거)에서 실행. catch(Throwable) 그대로 유지.
        upsertServiceRegistration(spans, receivedAt);
    }

    // [Phase R17] OQ-A — 청크 멱등 가드. 청크의 span_id 별 기존 payload 제거 후 재삽입 → 재적재 시 중복 0.
    //   idx_payloads_span_id(GT-7)로 각 삭제가 인덱스 조회라 저렴. foreign_keys=OFF 라 cascade 부작용 없음.
    //   IN(...) 이 아니라 batchUpdate(WHERE span_id = ?)라 SQLite 변수 한도(999)에 무의존(OQ-C).
    private void deletePayloadsForChunk(List<Span> chunk) {
        List<Object[]> ids = chunk.stream().map(s -> new Object[]{ s.spanId() }).toList();
        jdbc.batchUpdate("DELETE FROM payloads WHERE span_id = ?", ids);
    }

    // [Phase R17] FR-03 — SQLITE_BUSY 감지. org.sqlite 타입 미사용(GT-1 runtimeOnly — import 하면 컴파일 파손).
    //   java.sql.SQLException.getErrorCode(): SQLITE_BUSY=5, SQLITE_LOCKED=6 (dev STEP 0 실측 확인 errorCode==5).
    //   코드가 0으로 오는 엣지에는 message 매칭으로 폴백 → 감지 무공백.
    private static boolean isSqliteBusy(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException se && (se.getErrorCode() == 5 || se.getErrorCode() == 6)) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                String u = m.toUpperCase(Locale.ROOT);
                if (u.contains("SQLITE_BUSY") || u.contains("DATABASE IS LOCKED") || u.contains("SQLITE_LOCKED")) {
                    return true;
                }
            }
        }
        return false;
    }

    // 테스트 관측용 package-private getter (생성자 인자 아님 — V-02 무관).
    long sqliteBusyEncounteredCount() {
        return sqliteBusyEncountered.get();
    }

    long sqliteBusyDroppedCount() {
        return sqliteBusyDropped.get();
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
