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
 * transactional — SQLite snapshot semantics are sufficient for v0.1.
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
     */
    public List<TraceSummary> findTraces(
            String service,
            Long since,
            Long until,
            SpanStatus status,
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
     * [Phase H] AC-06-3 — W-01 / Q-04. List services with 응답 시점 단일 LEFT JOIN
     * GROUP BY 단일 쿼리 — services 컬럼 추가 0 (R12 회귀 가드).
     *
     * <p>now 는 호출자(TraceQueryService)에서 1회 계산 후 전달 — healthStatus 계산은
     * 모든 row 동일 now 기준.
     */
    public List<ServiceInfo> findServicesWithHealth(long now) {
        return jdbc.query(
                """
                        SELECT
                            s.service_name                      AS service_name,
                            s.registered_at                     AS registered_at,
                            s.last_seen_at                      AS last_seen_at,
                            s.source                            AS source,
                            COALESCE(COUNT(t.trace_id), 0)      AS trace_count
                        FROM services s
                        LEFT JOIN traces t ON s.service_name = t.service_name
                        GROUP BY s.service_name, s.registered_at, s.last_seen_at, s.source
                        ORDER BY s.service_name ASC
                        """,
                (rs, rowNum) -> {
                    String name = rs.getString("service_name");
                    long registeredAt = rs.getLong("registered_at");
                    Long lastSeenAt = (Long) rs.getObject("last_seen_at");
                    String source = rs.getString("source");
                    long traceCount = rs.getLong("trace_count");
                    String health = computeHealthStatus(lastSeenAt, now);
                    return new ServiceInfo(name, registeredAt, lastSeenAt, source, traceCount, health);
                }
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
