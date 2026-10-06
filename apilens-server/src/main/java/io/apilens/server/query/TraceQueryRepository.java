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
import io.apilens.server.ingest.IngestService;
import io.apilens.server.query.dto.PayloadDto;
import io.apilens.server.query.dto.ServiceInfo;
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.query.dto.TraceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JdbcTemplate-backed read repository for the query endpoints. Reads are not
 * transactional — SQLite snapshot semantics are sufficient here (a read sees a
 * consistent snapshot of the database as of the moment the statement started).
 */
@Repository
public class TraceQueryRepository {

    private static final Logger log = LoggerFactory.getLogger(TraceQueryRepository.class);

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE =
            new TypeReference<>() {
            };

    // [Phase R26] R26/AC-R26-33 — ★상수·도우미의 **두 벌을 없앴다**. 「IN (?, …) 목록을 몇 개씩 나누나」와
    //   「로그에 싣는 외부 문자열 위생 처리」는 둘 다 쓰는 쪽(IngestService)이 단일 거주지이고, 읽는 쪽인
    //   이 파일은 그것을 **부른다**. 같은 값을 여기 따로 들면 한쪽만 고치는 순간 조용히 갈린다 —
    //   STMT_REF_ATTRIBUTE·DB_STATEMENT_ATTRIBUTE 가 이미 같은 이유로 공유되는 전례다.
    //   (R25 는 "그쪽이 private 이라" 복사본을 뒀고, 이번에 그 정당화를 지웠다.)

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

    // ══ [Phase R28] 대표 점 조회 (신규 패턴 — 앵커 밀도 2배) ═══════════════════════════════════════
    //
    //  [Phase R28] AC-28-01-1/AC-28-01-2/AC-28-01-3/AC-28-01-6/AC-28-01-7/AC-28-01-11 — 사용자 명시 결정
    //  (UD-2 「오류 아닌 것 중 가장 느린 1 + 오류 전부」 · 확정 ③ 「오류 상한 · 칸마다 오류 하나 이상 · 칸별 오류
    //  건수를 늘」). CLAUDE.md '데이터 모델 (변경 신중히)' 인용 — 스키마 변경 0 · 기존 두 인덱스만 쓴다.
    //  되풀이 차단을 위해 아래 다섯을 코드 옆에 남긴다. 다음 조사자가 여기부터 읽는다.
    //
    //   (ㄱ) **한 문장 = 한 스냅숏.** 칸 · 점 · 총계를 UNION ALL 한 문장으로 뽑는다. 따로 질의하면 사이에 들어온
    //        수신이 「범례 총계 ≠ 칸 합」을 만든다(함정 9). 그래서 총계는 SQL 이 아니라 칸 합으로 만든다.
    //   (ㄴ) **합성 키 = 걸린 시간 19자리 0 채움 + trace_id.** 한 인자 MAX 가 TEXT 를 바이트 순서로 견주므로
    //        「더 느린 쪽, 같으면 trace_id 가 큰 쪽」이 된다. 걸린 시간을 누르지 않는다(long 전체 = 19자리).
    //        음수는 **키에서만** 0 으로 올린다 — 응답에는 원래 값이 나간다(DD-28-07).
    //   (ㄷ) **맨 컬럼에 기대지 않는다.** 키에서 trace_id 를 꺼내 기본 키로 되읽는다 — GROUP BY 의 맨 컬럼은
    //        SQLite 가 어느 행 값을 줄지 정하지 않는다(함정 5).
    //   (ㄹ) **창 안에서만.** 대표 · 오류 · 건수 모두 since ≤ start_time < until. 칸 경계는 묶음 키에만 쓴다 —
    //        그래서 가장자리 칸의 건수는 창 안 몫만이다(RC-10 · DD-28-34).
    //   (ㅁ) **문장과 인자를 한 곳에서 함께 붙인다.** 검색어는 바인딩 + {@link #escapeLike} 한 곳(목록 경로와 같은
    //        규칙)이고 문자열 연결로 SQL 에 들어가는 사용자 값은 0 이다. 시험용 문장 꺼내기도 같은 조립을 탄다.
    //
    //  ★이 저장소 메서드는 **서비스의 동시 실행 상한을 거쳐서만** 부른다(생산 호출처 = TraceQueryService 한 곳).
    //   직접 부르면 창 · 칸 검증과 허가가 둘 다 빠진다.

    /**
     * One row of the representative-point statement: either a bucket row ({@code bucketRow == true} — bucket index,
     * count, error count) or a point row (the trace columns of one representative).
     */
    public record PointRow(
            boolean bucketRow,
            long bucketIndex,
            long count,
            long errorCount,
            String traceId,
            String rootOperation,
            long startTime,
            long durationMs,
            SpanStatus status,
            boolean hasError
    ) {
    }

    /** 문장과 인자 한 벌 — 같은 조립에서 함께 나온다(위 (ㅁ)). */
    private record PointsStatement(String sql, Object[] args) {
    }

    /**
     * Representative points of one window — buckets plus points in a single statement. Callers must go through
     * {@code TraceQueryService} (window validation + the concurrency permit live there).
     *
     * @param bucketMs  bucket size chosen by the service (bound as a Java long — integer division)
     * @param errorsMax error safety cap; above it errors are reduced to the slowest one per bucket
     */
    public List<PointRow> findTracePoints(String service, long since, long until, SpanStatus status, String q,
                                          long bucketMs, int errorsMax) {
        // 검색어: 목록 경로와 같은 규칙 — 앞뒤 공백을 뺀 값이 비면 걸지 않는다 · 이스케이프는 escapeLike 한 곳.
        String likePattern = (q == null || q.isBlank()) ? null : "%" + escapeLike(q.trim()) + "%";
        PointsStatement st = pointsStatement(service, since, until, status, likePattern, bucketMs, errorsMax);
        return jdbc.query(st.sql(), POINT_ROW_MAPPER, st.args());
    }

    /**
     * 시험 · 측정 틀 전용 — 걸 조건(status · q)의 있고 없음에 따른 문장 원문. 실제 조회와 <b>같은 조립</b>을 타므로
     * 측정 틀이 복사한 문장이 운영 문장과 어긋나지 않는다(설계 §2.3 · GATE-Q1).
     */
    static String pointsSql(boolean withStatus, boolean withQ) {
        return pointsStatement("", 0L, 1L, withStatus ? SpanStatus.ERROR : null, withQ ? "%%" : null, 1L, 0).sql();
    }

    private static PointsStatement pointsStatement(String service, long since, long until, SpanStatus status,
                                                   String likePattern, long bucketMs, int errorsMax) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        // ① 칸 크기 · ②③④ 서비스 · 창 — 칸 묶음은 창 안 행을 인덱스 범위로 한 번 훑는다.
        sql.append(POINTS_AGG_HEAD);
        args.add(bucketMs);
        args.add(service);
        args.add(since);
        args.add(until);
        appendPointsFilters(sql, args, "", status, likePattern);                  // ⑤⑥ 있을 때만
        sql.append(POINTS_AGG_TAIL_AND_REPRESENTATIVES);
        args.add(errorsMax);                                                       // ⑦ 넘침 갈래 문턱
        args.add(service);
        args.add(since);
        args.add(until);                                                           // ⑧⑨⑩ 평소 갈래 창
        appendPointsFilters(sql, args, "t.", status, likePattern);                 // ⑪⑫ 있을 때만
        sql.append(POINTS_EVERY_ERROR_TAIL);
        args.add(errorsMax);                                                       // ⑬ 평소 갈래 문턱
        return new PointsStatement(sql.toString(), args.toArray());
    }

    private static void appendPointsFilters(StringBuilder sql, List<Object> args, String alias,
                                            SpanStatus status, String likePattern) {
        if (status != null) {
            sql.append("                   AND ").append(alias).append("status = ?\n");
            args.add(status.name());
        }
        if (likePattern != null) {
            sql.append("                   AND ").append(alias).append("root_operation LIKE ? ESCAPE '\\'\n");
            args.add(likePattern);
        }
    }

    /** 칸 묶음 머리 — 창 안 행에서 칸마다 건수 · 오류 건수 · 정상 키 · 오류 키를 만든다. */
    private static final String POINTS_AGG_HEAD = """
            WITH agg AS (
                SELECT start_time / ?                                             AS b
                     , COUNT(*)                                                   AS cnt
                     , SUM(err)                                                   AS err_cnt
                     , MAX(CASE WHEN err = 0 THEN printf('%019d', d) || trace_id END) AS ok_key
                     , MAX(CASE WHEN err = 1 THEN printf('%019d', d) || trace_id END) AS err_key
                  FROM (
                    SELECT trace_id
                         , start_time
                         , CASE WHEN duration_ms < 0 THEN 0 ELSE duration_ms END     AS d
                         , CASE WHEN status = 'ERROR' OR has_error = 1 THEN 1 ELSE 0 END AS err
                      FROM traces
                     WHERE service_name = ?
                       AND start_time >= ?
                       AND start_time < ?
            """;

    /** 칸 묶음 꼬리 + 칸 행 + 칸마다 정상 대표 + 넘침 갈래(칸마다 가장 느린 오류) + 평소 갈래 머리. */
    private static final String POINTS_AGG_TAIL_AND_REPRESENTATIVES = """
                  )
                 GROUP BY b
            ),
            tot AS (
                SELECT COALESCE(SUM(err_cnt), 0) AS errs
                  FROM agg
            )
            SELECT 'B'       AS kind
                 , a.b       AS b
                 , a.cnt     AS cnt
                 , a.err_cnt AS err_cnt
                 , NULL      AS trace_id
                 , NULL      AS root_operation
                 , NULL      AS start_time
                 , NULL      AS duration_ms
                 , NULL      AS status
                 , NULL      AS has_error
              FROM agg a
            UNION ALL
            SELECT 'P', a.b, NULL, NULL
                 , t.trace_id, t.root_operation, t.start_time, t.duration_ms, t.status, t.has_error
              FROM agg a
              JOIN traces t ON t.trace_id = substr(a.ok_key, 20)
             WHERE a.ok_key IS NOT NULL
            UNION ALL
            SELECT 'P', a.b, NULL, NULL
                 , t.trace_id, t.root_operation, t.start_time, t.duration_ms, t.status, t.has_error
              FROM agg a
              JOIN traces t ON t.trace_id = substr(a.err_key, 20)
             WHERE a.err_key IS NOT NULL
               AND (SELECT errs FROM tot) > ?
            UNION ALL
            SELECT 'P', NULL, NULL, NULL
                 , t.trace_id, t.root_operation, t.start_time, t.duration_ms, t.status, t.has_error
              FROM traces t
             WHERE t.service_name = ?
               AND t.start_time >= ?
               AND t.start_time < ?
            """;

    /** 평소 갈래 꼬리 — 창 안 오류가 1 이상 · 상한 이하일 때만 오류 전부(오류 0 이면 이 갈래는 행을 안 낸다). */
    private static final String POINTS_EVERY_ERROR_TAIL = """
               AND (t.status = 'ERROR' OR t.has_error = 1)
               AND (SELECT errs FROM tot) BETWEEN 1 AND ?
            """;

    private static final RowMapper<PointRow> POINT_ROW_MAPPER = (rs, rowNum) -> {
        if ("B".equals(rs.getString("kind"))) {
            return new PointRow(true, rs.getLong("b"), rs.getLong("cnt"), rs.getLong("err_cnt"),
                    null, null, 0L, 0L, null, false);
        }
        String status = rs.getString("status");
        return new PointRow(false, 0L, 0L, 0L,
                rs.getString("trace_id"),
                rs.getString("root_operation"),
                rs.getLong("start_time"),
                rs.getLong("duration_ms"),
                status == null ? null : SpanStatus.valueOf(status),
                rs.getInt("has_error") == 1);
    };

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

    /**
     * [Phase R25] AC-25-02-4 — <b>밖으로 나가는 응답이 지금과 같은 모양이다.</b> span 속성의
     * {@code db.statement} 는 여전히 SQL 문자열이고, 화면·문서·계측기는 한 줄도 안 바뀐다.
     *
     * <p>되돌리기는 <b>행 매퍼가 아니라 이 반환 지점</b>에서 한다. {@code parseAttributes} 는
     * 행 하나를 받는 함수라 그 안에서는 지문을 모을 수 없다 — 모아서 {@code IN} 한 문장으로 가져와야
     * span 마다 조회하는 일이 안 생긴다.
     *
     * <p>// ★옛 형태 읽기 폴백 — 지우지 말 것.
     * //   올린 뒤 약 이틀(retention.days=1 + 04:00 정리)간 예약 키가 없고 SQL 원문을 속성에 그대로
     * //   담은 옛 span 행이 남는다. 그 행은 아래 후처리를 <b>그냥 지나가야</b> 한다.
     * //   이 갈래를 지우면 그 사이의 SQL 이 <b>에러 없이 빈 값</b>으로 나온다(예외가 안 뜨므로 아무도 모른다).
     * //   정본 시험: SqlStatementInterningTest.readsOldFormSpanRowsThatStillCarryTheStatementInline
     * //   ★이 자리의 정본 시험은 payload 쪽(LegacyRowFallbackTest)이 <b>아니다</b> — 여기서 되돌리는 것은
     * //   본문이 아니라 SQL 원문이라, payload 시험은 이 갈래를 지워도 초록으로 남는다.
     */
    public List<SpanDto> findSpans(String traceId) {
        List<SpanDto> spans = jdbc.query(
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
        return resolveStatementRefs(spans);
    }

    /**
     * [Phase R25] AC-25-02-4/AC-25-02-6 — 예약 키에 든 지문을 SQL 원문으로 되돌린다.
     *
     * <p>지문이 <b>하나도 없으면 여기서 끝낸다</b> — 질의도 새 객체 생성도 없어 옛 형태만 있는 응답은
     * 오늘과 바이트가 같다.
     *
     * <p>행마다의 규칙 셋:
     * <ul>
     *   <li>{@code db.statement} 가 이미 있으면 <b>그대로 통과</b>(원문 우선 — 밖에서 온 값을 안 덮는다).</li>
     *   <li>지문이 풀리면 <b>새 맵</b>을 만들어 예약 키를 빼고 원문을 넣는다. {@code parseAttributes} 가
     *       돌려준 맵을 고치지 않는다({@code Map.of()} 는 불변이다).</li>
     *   <li>안 풀리면 <b>키를 그대로 두고</b> 경고 한 줄. 자리표 문자열로 채우지 않는다 —
     *       밖에서 넣은 값일 수 있어 무손실이 우선이다.</li>
     * </ul>
     */
    private List<SpanDto> resolveStatementRefs(List<SpanDto> spans) {
        Set<String> refs = new LinkedHashSet<>();
        for (SpanDto span : spans) {
            Object ref = span.attributes().get(IngestService.STMT_REF_ATTRIBUTE);
            if (ref instanceof String s && !s.isBlank()) {
                refs.add(s);
            }
        }
        if (refs.isEmpty()) {
            return spans;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> list = new ArrayList<>(refs);
        for (int start = 0; start < list.size(); start += IngestService.HASH_IN_CHUNK_SIZE) {
            List<String> slice = list.subList(start, Math.min(start + IngestService.HASH_IN_CHUNK_SIZE, list.size()));
            jdbc.query(
                    "SELECT stmt_hash, statement FROM sql_statements WHERE stmt_hash IN ("
                            + String.join(", ", Collections.nCopies(slice.size(), "?")) + ")",
                    (RowCallbackHandler) rs -> resolved.put(rs.getString("stmt_hash"), rs.getString("statement")),
                    slice.toArray());
        }
        List<SpanDto> out = new ArrayList<>(spans.size());
        for (SpanDto span : spans) {
            Object ref = span.attributes().get(IngestService.STMT_REF_ATTRIBUTE);
            if (!(ref instanceof String hash) || hash.isBlank()) {
                out.add(span);
                continue;
            }
            if (span.attributes().containsKey(IngestService.DB_STATEMENT_ATTRIBUTE)) {
                out.add(span);   // 원문 우선 — 양쪽 키가 다 있으면 원문을 쓴다.
                continue;
            }
            String statement = resolved.get(hash);
            if (statement == null) {
                // 못 푼 참조 — 키를 지우지 않고 그대로 통과시킨다(밖에서 넣은 값일 수 있다).
                log.warn("unresolved statement ref: spanId={} ref={}",
                        IngestService.sanitizeForLog(span.spanId()), IngestService.sanitizeForLog(hash));
                out.add(span);
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<>(span.attributes());
            merged.remove(IngestService.STMT_REF_ATTRIBUTE);
            merged.put(IngestService.DB_STATEMENT_ATTRIBUTE, statement);
            out.add(new SpanDto(span.spanId(), span.parentSpanId(), span.serviceName(),
                    span.operationName(), span.spanKind(), span.startTime(), span.endTime(),
                    span.status(), merged));
        }
        return out;
    }

    public boolean spanExists(String traceId, String spanId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id = ? AND span_id = ?",
                Integer.class,
                traceId, spanId
        );
        return count != null && count > 0;
    }

    /**
     * [Phase R25] AC-25-01-1/AC-25-03-2 — 본문을 본문 표에서 되돌린다. 행 매퍼는 <b>안 바뀐다</b>
     * (별칭 {@code body} 유지) — 새 행은 {@code pb.body}, 옛 행은 {@code p.body},
     * 본문이 없는 행은 둘 다 NULL 이라 <b>오늘과 같은 값</b>이다.
     *
     * <p>// ★옛 형태 읽기 폴백 — 지우지 말 것.
     * //   올린 뒤 약 이틀(retention.days=1 + 04:00 정리)간 body_hash 가 없는 옛 행이 남는다.
     * //   이 갈래를 지우면 그 사이의 본문·SQL 이 <b>에러 없이 빈 값</b>으로 나온다(예외가 안 뜨므로 아무도 모른다).
     * //   정본 시험: LegacyRowFallbackTest.oldFormPayloadRowReadsTheSameAsNewForm
     */
    public List<PayloadDto> findPayloads(String spanId) {
        return jdbc.query(
                """
                        SELECT p.direction, p.content_type,
                               COALESCE(pb.body, p.body) AS body,
                               p.size_bytes, p.truncated
                        FROM payloads p
                        LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash
                        WHERE p.span_id = ?
                        ORDER BY p.payload_id ASC
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
