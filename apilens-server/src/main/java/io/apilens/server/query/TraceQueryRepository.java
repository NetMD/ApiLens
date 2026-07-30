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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.query.dto.PayloadDto;
import io.apilens.server.query.dto.ServiceInfo;
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.query.dto.TraceSummary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JdbcTemplate-backed read repository for the query endpoints. Reads are not
 * transactional — SQLite snapshot semantics are sufficient here (a read sees a
 * consistent snapshot of the database as of the moment the statement started).
 */
@Repository
public class TraceQueryRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TraceQueryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * List traces with optional filters and keyset pagination.
     * Caller passes {@code fetchLimit = limit + 1} to detect whether a next page exists.
     *
     * <p>// [Phase R12] AC-C2-1/AC-C2-2 — FR-C2: {@code q} = root_operation 풀 FQCN 부분 일치
     * // (BL-09 — shortenOperation 은 FE 표시 전용, BE 검색 경로와 무접점).
     * // W-C2 (Design §8.4): JdbcTemplate 파라미터 바인딩 강제 — 검색어의 SQL 문자열 연결 금지.
     * // 인덱스 없는 LIKE 수용 근거 (AC-C2-2): A4 인덱스(service_name, start_time) prefix 가
     * // 윈도우를 먼저 좁힌 뒤 LIKE 는 잔여 행 필터 — FTS 는 작업 외 (이연).
     */
    public List<TraceSummary> findTraces(
            String service,
            Long since,
            Long until,
            SpanStatus status,
            String q,
            int fetchLimit,
            CursorCodec.Cursor cursor
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT trace_id, root_operation, service_name, start_time, duration_ms,
                       status, span_count, has_error
                FROM traces
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (service != null && !service.isBlank()) {
            sql.append(" AND service_name = ?");
            params.add(service);
        }
        if (since != null) {
            sql.append(" AND start_time >= ?");
            params.add(since);
        }
        if (until != null) {
            sql.append(" AND start_time < ?");
            params.add(until);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (q != null && !q.isBlank()) {
            // E-07: 검색어의 %/_/\ 는 리터럴 매칭 — escapeLike 단일 메서드 (백슬래시 최우선)
            sql.append(" AND root_operation LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(q.trim()) + "%");
        }
        if (cursor != null) {
            // (start_time, trace_id) DESC pagination: fetch rows strictly "below" the cursor row
            sql.append(" AND (start_time < ? OR (start_time = ? AND trace_id < ?))");
            params.add(cursor.startTime());
            params.add(cursor.startTime());
            params.add(cursor.traceId());
        }
        sql.append(" ORDER BY start_time DESC, trace_id DESC LIMIT ?");
        params.add(fetchLimit);
        return jdbc.query(sql.toString(), TRACE_SUMMARY_MAPPER, params.toArray());
    }

    /**
     * LIKE 패턴 이스케이프 단일 거주지 (Design §3.1.7) — E-07: 검색어를 리터럴로만 매칭.
     * 순서 의무: 백슬래시를 가장 먼저 치환 (이후 치환이 만든 {@code \%}/{@code \_} 를 재치환하지 않도록).
     */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public Optional<TraceSummary> findTraceSummary(String traceId) {
        try {
            TraceSummary t = jdbc.queryForObject(
                    """
                            SELECT trace_id, root_operation, service_name, start_time, duration_ms,
                                   status, span_count, has_error
                            FROM traces
                            WHERE trace_id = ?
                            """,
                    TRACE_SUMMARY_MAPPER,
                    traceId
            );
            return Optional.ofNullable(t);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<SpanDto> findSpans(String traceId) {
        return jdbc.query(
                """
                        SELECT span_id, parent_span_id, service_name, operation_name,
                               span_kind, start_time, end_time, status, attributes_json
                        FROM spans
                        WHERE trace_id = ?
                        ORDER BY start_time ASC, span_id ASC
                        """,
                spanRowMapper(),
                traceId
        );
    }

    public boolean spanExists(String traceId, String spanId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id = ? AND span_id = ?",
                Integer.class,
                traceId, spanId
        );
        return count != null && count > 0;
    }

    public List<PayloadDto> findPayloads(String spanId) {
        return jdbc.query(
                """
                        SELECT direction, content_type, body, size_bytes, truncated
                        FROM payloads
                        WHERE span_id = ?
                        ORDER BY payload_id ASC
                        """,
                (rs, rowNum) -> new PayloadDto(
                        rs.getString("direction"),
                        rs.getString("content_type"),
                        rs.getString("body"),
                        rs.getLong("size_bytes"),
                        rs.getInt("truncated") == 1
                ),
                spanId
        );
    }

    // [Phase H] AC-06-3 — Q-03 / Q-04 / D-03 healthStatus 임계. 사용자 명시 비협상 결정.
    // CLAUDE.md '아키텍처 핵심 원칙' 인용.
    // 5분 / 30분 도메인 임계 (성능 NFR 아님 — EXT-004 트리거 미일치).
    private static final long ACTIVE_THRESHOLD_MS = 300_000L;    // 5 minutes
    private static final long STALE_THRESHOLD_MS = 1_800_000L;   // 30 minutes

    /**
     * 24h 윈도우 카운트 기준 — {@code start_time} 사용 사유: A4 복합 인덱스
     * {@code idx_traces_service_start(service_name, start_time DESC, trace_id DESC)} 가
     * JOIN 동등 조건 + range + COUNT(trace_id) 를 covering (Design §2-A3).
     */
    static final long SERVICE_TRACE_COUNT_WINDOW_MS = 86_400_000L; // 24h

    /**
     * [Phase H] AC-06-3 — W-01 / Q-04. List services with 응답 시점 단일 LEFT JOIN
     * GROUP BY 단일 쿼리 — services 컬럼 추가 0 (R12 회귀 가드).
     *
     * <p>now 는 호출자(TraceQueryService)에서 1회 계산 후 전달 — healthStatus 계산은
     * 모든 row 동일 now 기준.
     *
     * <p>// [Phase R12] AC-A3-1/AC-A3-3 — FR-A3: traceCount 의미 변경 "누적 전수" →
     * // "최근 24시간 trace 수" (필드명·응답 구조 무변경). WHERE 없는 전수 COUNT 패턴 자체가
     * // 소멸 — 매 호출 O(전체 N) → O(24h 윈도우 내 행), A4 인덱스 covering.
     * // 윈도우 경계: start_time >= now − 24h (경계값 포함 — Design §7.1).
     *
     * <p>// [Phase R19] AC-01-6 — agent_version 1컬럼 추가 노출. 집계 함수가 아닌 컬럼이라 GROUP BY 에도
     * // 함께 넣는다. ServiceInfo 조립점은 이 메서드 1곳뿐이라(저장소 전체 유일) 동형 노출이 구조로 보장된다.
     */
    public List<ServiceInfo> findServicesWithHealth(long now) {
        return jdbc.query(
                """
                        SELECT
                            s.service_name                      AS service_name,
                            s.registered_at                     AS registered_at,
                            s.last_seen_at                      AS last_seen_at,
                            s.source                            AS source,
                            s.agent_version                     AS agent_version,
                            COALESCE(COUNT(t.trace_id), 0)      AS trace_count
                        FROM services s
                        LEFT JOIN traces t ON s.service_name = t.service_name
                                          AND t.start_time >= ?
                        GROUP BY s.service_name, s.registered_at, s.last_seen_at, s.source, s.agent_version
                        ORDER BY s.service_name ASC
                        """,
                (rs, rowNum) -> {
                    String name = rs.getString("service_name");
                    long registeredAt = rs.getLong("registered_at");
                    // [Phase R19] NULL 은 그대로 null 로 두되, 캐스트 대신 숫자 읽기로 받는다.
                    //   sqlite-jdbc 는 작은 정수를 Integer 로 돌려줄 수 있어 (Long) 캐스트가
                    //   ClassCastException 이 된다(실측). epoch millis 는 늘 커서 운영에서는 안 터지지만,
                    //   터지면 서비스 목록 전체가 500 이 되는 자리라 방어해 둔다. 의미·null 판정 불변.
                    Long lastSeenAt = rs.getObject("last_seen_at") == null ? null : rs.getLong("last_seen_at");
                    String source = rs.getString("source");
                    long traceCount = rs.getLong("trace_count");
                    String health = computeHealthStatus(lastSeenAt, now);
                    // agent 를 아직 재시작하지 않았으면 NULL — 화면이 '—' 로 표시한다(값 없음의 유일한 뜻).
                    String agentVersion = rs.getString("agent_version");
                    return new ServiceInfo(name, registeredAt, lastSeenAt, source, traceCount, health, agentVersion);
                },
                now - SERVICE_TRACE_COUNT_WINDOW_MS
        );
    }

    /**
     * [Phase H] AC-06-3 — D-03 / Q-03 / EXT-002 경계값 5분기. 사용자 명시 비협상 결정.
     *
     * <p>분기:
     * <ul>
     *   <li>lastSeenAt == null → "never"</li>
     *   <li>now - lastSeenAt &lt; 0 (clock skew defensive) → "active"</li>
     *   <li>now - lastSeenAt &lt;= 5분 → "active"</li>
     *   <li>now - lastSeenAt &lt;= 30분 → "stale"</li>
     *   <li>else → "inactive"</li>
     * </ul>
     */
    static String computeHealthStatus(Long lastSeenAt, long now) {
        if (lastSeenAt == null) {
            return "never";
        }
        long ago = now - lastSeenAt;
        if (ago < 0) {
            // clock skew: agent 시계가 server 보다 빠를 때 "방금 받은 service 가 끊김"이 되는
            // 모순 회피. defensive 분기 — UI 운영자 인지 부담 회피.
            return "active";
        }
        if (ago <= ACTIVE_THRESHOLD_MS) {
            return "active";
        }
        if (ago <= STALE_THRESHOLD_MS) {
            return "stale";
        }
        return "inactive";
    }

    private static final RowMapper<TraceSummary> TRACE_SUMMARY_MAPPER = (rs, rowNum) -> new TraceSummary(
            rs.getString("trace_id"),
            rs.getString("root_operation"),
            rs.getString("service_name"),
            rs.getLong("start_time"),
            rs.getLong("duration_ms"),
            SpanStatus.valueOf(rs.getString("status")),
            rs.getInt("span_count"),
            rs.getInt("has_error") == 1
    );

    private RowMapper<SpanDto> spanRowMapper() {
        return (rs, rowNum) -> new SpanDto(
                rs.getString("span_id"),
                rs.getString("parent_span_id"),
                rs.getString("service_name"),
                rs.getString("operation_name"),
                SpanKind.valueOf(rs.getString("span_kind")),
                rs.getLong("start_time"),
                rs.getLong("end_time"),
                SpanStatus.valueOf(rs.getString("status")),
                parseAttributes(rs.getString("attributes_json"))
        );
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, ATTRIBUTES_TYPE);
        } catch (JsonProcessingException e) {
            // Defensive: surface raw JSON instead of failing the request
            return Map.of("_raw", json);
        }
    }
}
