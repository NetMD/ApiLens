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
package io.apilens.server.instrument;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only aggregate queries behind {@code POST /v1/instrument/**}. Writes: none.
 *
 * <p>★ <b>모든 SQL 이 {@code traces} 를 FROM 첫 테이블로 쓴다.</b> 이 파일 안에 FROM 절이
 * {@code spans} 로 시작하는 SQL 이 하나도 없다(NFR-01 — 이 라운드 최우선 규약). 이 형태라야
 * {@code idx_traces_service_start(service_name, start_time DESC, trace_id DESC)}
 * ({@code V3__performance_and_settings.sql:7})를 커버링으로 타고, 그 뒤
 * {@code idx_spans_trace_id} → {@code idx_payloads_span_id} 로 내려간다.
 * 부수 이점 둘이 자동으로 따라온다 — (i) {@code traces} 행이 없는 외톨이 span 이 집계에 안 잡혀
 * 순위 오염이 사라지고, (ii) trace 단위로 들어오므로 트리가 구간 경계에서 잘리지 않는다.
 *
 * <p>// [Phase R19] AC-02-5 — 사용자 명시 비협상 결정(S-1: "집계는 traces 에서 출발한다.
 * // spans 를 FROM 첫 테이블로 쓰지 않는다"). CLAUDE.md '데이터 모델' 인용.
 *
 * <p>🔒 <b>SQL 바인딩 비협상</b>: 사용자 입력({@code serviceName} · {@code fromMs} · {@code toMs} ·
 * {@code targets} 원소)은 전부 {@code ?} 파라미터 바인딩으로 넘긴다. SQL 문자열에 연결하지 않는다.
 * {@code targets} 는 개수만큼 {@code ?} 를 생성해 {@code IN (?, ?, ...)} 로 만들고 값은 바인딩한다.
 * 문자열 결합이 허용되는 유일한 자리는 <b>클래스 추출식 상수</b>와 <b>자리표시자 개수</b>뿐이고
 * 둘 다 사용자 입력이 아니다. LIKE 를 쓰지 않으므로 {@code %}/{@code _} 이스케이프는 대상이 없다.
 *
 * <p>⚠️ <b>실행 시간 상한 없음(정직한 격하)</b>: 설계가 둔 4번 방어선(statement timeout 15초)은
 * 이 드라이버에서 성립하지 않는다 — R19 dev 실행 게이트 GT-18 실측 결과 sqlite-jdbc 3.47.1.0 의
 * {@code Statement.setQueryTimeout(n)} 은 그 값을 <b>busy(잠금 대기) timeout</b> 으로만 쓰고
 * 쿼리 실행을 끊지 않는다(2초로 걸어 둔 쿼리가 51초 동안 완주). 그래서 이 파일은
 * {@code setQueryTimeout} 을 부르지 않는다 — 부르면 이 커넥션의 잠금 대기 시간만 바뀌고
 * (연결 URL 의 {@code busy_timeout=5000}) 얻는 것은 없다. 상한은 나머지 방어선으로 유지한다:
 * 유한 창(1/6/24시간) · 집계 행 상한 · 재귀 깊이 상한 · 동시 실행 1건 · 게이트 점유 상한.
 */
@Repository
public class InstrumentAnalysisRepository {

    /**
     * span 이름에서 클래스 부분을 잘라 내는 식. <b>5개 쿼리가 문자 그대로 공유</b>한다 —
     * 이 식이 5곳에 흩어지면 한 곳만 바뀌어도 숫자가 어긋난다. 사용자 입력이 아니므로 결합해도 안전.
     *
     * <p>{@code #} 이 없는 span(예: {@code jdbc.execute})은 빈 문자열이 되어 <b>고정 합계 행</b>으로 묶인다.
     */
    static final String CLASS_EXPR =
            "CASE WHEN instr(s.operation_name, '#') > 0 "
                    + "THEN substr(s.operation_name, 1, instr(s.operation_name, '#') - 1) "
                    + "ELSE '' END";

    /**
     * 모든 쿼리가 문자 그대로 공유하는 출발점. 총계만 다른 방식으로 세는 자리가 없어야
     * 클래스별 합계와 구간 총계가 어긋나지 않는다.
     */
    private static final String FROM_WINDOW =
            "FROM traces t\n"
                    + "JOIN spans s ON s.trace_id = t.trace_id\n"
                    + "WHERE t.service_name = ?\n"
                    + "  AND t.start_time  >= ?\n"
                    + "  AND t.start_time  <  ?\n";

    private final JdbcTemplate jdbc;

    public InstrumentAnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── 반환 행 ─────────────────────────────────────────────────────────────

    /**
     * Q1c — 구간 총계(바꾸기 전 값).
     *
     * @param totalTraces      구간 안 trace 수
     * @param totalSpans       구간 안 span 수
     * @param singleSpanTraces span 이 하나뿐인 trace 수
     */
    public record SummaryRow(long totalTraces, long totalSpans, long singleSpanTraces) {
    }

    /**
     * Q1a — 클래스별 span·root 집계.
     *
     * @param className  클래스 이름(없으면 빈 문자열)
     * @param spanCount  span 수
     * @param rootCount  parent 가 없는 span 수(= 시작점)
     * @param hasServer  SERVER 종류 span 이 하나라도 있는가. 현재 판별 규칙은 이 값을 소비하지 않는다
     *                  — 설계 Q1a 원문을 보존한 채 두었고, 화면에도 노출하지 않는다
     */
    public record SpanRow(String className, long spanCount, long rootCount, boolean hasServer) {
    }

    /**
     * Q1b — 클래스별 payload 집계.
     *
     * @param className    클래스 이름(없으면 빈 문자열)
     * @param payloadCount payload 건수
     * @param payloadBytes payload 크기 합(바이트)
     */
    public record PayloadRow(String className, long payloadCount, long payloadBytes) {
    }

    /**
     * Q2 — 제외 시뮬레이션(재귀 CTE 정확 계산) 결과.
     *
     * @param remainingSpans   남는 span 수
     * @param resultTraces     남는 trace 수(재계산)
     * @param singleSpanTraces span 이 하나뿐인 trace 수
     * @param cappedCount      깊이 상한에 걸려 더 올라가지 못한 경로 수
     */
    public record OrphanRow(long remainingSpans, long resultTraces, long singleSpanTraces, long cappedCount) {
    }

    /**
     * Q3a + Q3b — 직접 귀속 절감.
     *
     * @param spanDelta         줄어드는 span 수
     * @param payloadCountDelta 줄어드는 payload 건수
     * @param payloadBytesDelta 줄어드는 payload 크기(바이트)
     */
    public record SavingsRow(long spanDelta, long payloadCountDelta, long payloadBytesDelta) {
    }

    // ─── Q1c — 구간 총계 ─────────────────────────────────────────────────────

    /**
     * Totals for the window (before any exclusion).
     *
     * <p>{@code traces.span_count} <b>요약 컬럼을 쓰지 않는다.</b> 그 값은 요약이라 부분 적재
     * 상황에서 실제 행 수와 다를 수 있고, 그러면 클래스별 합계와 어긋난다.
     */
    public SummaryRow aggregateSummary(String serviceName, long fromMs, long toMs) {
        String sql = """
                SELECT
                    COUNT(*)                                          AS total_traces,
                    COALESCE(SUM(c.span_cnt), 0)                      AS total_spans,
                    COALESCE(SUM(CASE WHEN c.span_cnt = 1 THEN 1 ELSE 0 END), 0) AS single_span_traces
                FROM (
                    SELECT t.trace_id AS tid, COUNT(s.span_id) AS span_cnt
                """
                + FROM_WINDOW
                + """
                    GROUP BY t.trace_id
                ) c
                """;
        return jdbc.queryForObject(
                sql,
                (rs, rowNum) -> new SummaryRow(
                        rs.getLong("total_traces"),
                        rs.getLong("total_spans"),
                        rs.getLong("single_span_traces")),
                serviceName, fromMs, toMs
        );
    }

    // ─── Q1a — 클래스별 span·root 집계 ───────────────────────────────────────

    /**
     * Per-class span and root counts.
     *
     * <p>⚠️ Q1b(payload 집계)와 <b>한 쿼리로 합치지 않는다.</b> payload 를 LEFT JOIN 하면 payload 가
     * 2건인 span 이 두 번 세어져 {@code span_count} 가 부풀어 오른다 — 이 설계에서 가장 조용히
     * 틀리는 자리다. 그래서 별도 실행으로 두어 비용·실패도 함께 격리한다.
     */
    public List<SpanRow> aggregateSpansByClass(String serviceName, long fromMs, long toMs, int cap) {
        String sql = "SELECT\n"
                + "    " + CLASS_EXPR + "                                    AS class_name,\n"
                + "    COUNT(*)                                              AS span_count,\n"
                + "    SUM(CASE WHEN s.parent_span_id IS NULL THEN 1 ELSE 0 END) AS root_count,\n"
                + "    MAX(CASE WHEN s.span_kind = 'SERVER' THEN 1 ELSE 0 END)   AS has_server\n"
                + FROM_WINDOW
                + "GROUP BY class_name\n"
                + "ORDER BY span_count DESC\n"
                + "LIMIT ?\n";
        return jdbc.query(
                sql,
                (rs, rowNum) -> new SpanRow(
                        rs.getString("class_name"),
                        rs.getLong("span_count"),
                        rs.getLong("root_count"),
                        rs.getInt("has_server") == 1),
                serviceName, fromMs, toMs, cap
        );
    }

    // ─── Q1b — 클래스별 payload 집계 ─────────────────────────────────────────

    /**
     * Per-class payload count and size. 별도 실행 — 비용·실패 격리(위 Q1a 주석 참조).
     *
     * <p>// [Phase R22] R22/AC-02-1/R22/AC-02-2/R22/AC-02-3 — R22/AC-02-1 verbatim: "payload 바이트
     * // 집계 2곳이 {@code COALESCE(SUM(length(CAST(p.body
     * // AS BLOB))), 0)} 로 바뀐다 (이 메서드의 {@code payload_bytes} 와 {@link #directSavings} 의
     * // {@code payload_bytes_delta} — 좌표는 줄번호가 아니라 두 별칭으로 가리킨다)". 사용자 명시 결정(D-1).
     * // CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용 — 스키마 0 · 마이그레이션 0.
     * // ★ 위 인용의 SQL 식을 줄바꿈해 적은 이유: 완료 판정 grep 이 <b>실제 SQL 2곳</b>만 세도록
     * //   문서 인용이 한 줄에 통째로 걸리지 않게 한 것이다(값을 바꾼 것이 아니라 줄만 접었다).
     *
     * <p><b>왜 {@code size_bytes} 가 아닌가</b>: {@code size_bytes} 는 <b>자르기 전 원본 크기</b>다
     * ({@code PayloadGuard} javadoc 정의 — 무접촉). 절감 예측이 물어야 하는 것은 "이 클래스를 빼면
     * 디스크에서 실제로 얼마가 사라지는가" 이고, 디스크에 있는 것은 <b>잘린 뒤 저장된 본문</b>이다.
     * 절단 행({@code truncated=1})에서 둘이 갈리므로 소비처 해석을 바꾼다 — <b>정의는 안 바꾼다</b>
     * (R22/AC-02-3: 뒤집으면 R13 테스트가 깨진다).
     *
     * <p><b>왜 {@code CAST(... AS BLOB)} 이 필수인가</b> (R22/AC-02-2): {@code payloads.body} 는
     * {@code TEXT} 라 {@code length(TEXT)} 는 <b>문자 수</b>를 센다. 한글이 든 본문에서 또 다른 방향으로
     * 틀린다. {@code CAST(TEXT AS BLOB)} 은 UTF-8 바이트열이므로 {@code length()} 가 바이트 수가 된다.
     * {@code body} 가 NULL 인 행은 {@code SUM} 이 건너뛰고, 전량 NULL 이면 {@code COALESCE} 가 0 을 준다.
     *
     * <p>별칭({@code payload_bytes})·정렬({@code ORDER BY payload_bytes DESC})·{@code COALESCE(...,0)} 는
     * 그대로다 — <b>식만 바뀌고 구조는 안 바뀐다</b>.
     */
    public List<PayloadRow> aggregatePayloadsByClass(String serviceName, long fromMs, long toMs, int cap) {
        String sql = "SELECT\n"
                + "    " + CLASS_EXPR + "               AS class_name,\n"
                + "    COUNT(*)                         AS payload_count,\n"
                // [Phase R22] R22/AC-02-1 — 실제 저장 바이트. size_bytes(자르기 전 원본)가 아니다.
                // [Phase R25] AC-25-03-2 — ★옛 형태 읽기 폴백 — 지우지 말 것.
                //   올린 뒤 약 이틀(retention.days=1 + 04:00 정리)간 body_hash 가 없는 옛 행이 남는다.
                //   이 갈래를 지우면 그 사이의 본문·SQL 이 **에러 없이 빈 값**으로 나온다(예외가 안 뜨므로 아무도 모른다).
                //   정본 시험: LegacyRowFallbackTest.oldFormPayloadRowReadsTheSameAsNewForm
                + "    COALESCE(SUM(COALESCE(pb.body_bytes, length(CAST(p.body AS BLOB)))), 0)   AS payload_bytes\n"
                + "FROM traces t\n"
                + "JOIN spans s    ON s.trace_id = t.trace_id\n"
                + "JOIN payloads p ON p.span_id  = s.span_id\n"
                + "LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash\n"
                + "WHERE t.service_name = ?\n"
                + "  AND t.start_time  >= ?\n"
                + "  AND t.start_time  <  ?\n"
                + "GROUP BY class_name\n"
                + "ORDER BY payload_bytes DESC\n"
                + "LIMIT ?\n";
        return jdbc.query(
                sql,
                (rs, rowNum) -> new PayloadRow(
                        rs.getString("class_name"),
                        rs.getLong("payload_count"),
                        rs.getLong("payload_bytes")),
                serviceName, fromMs, toMs, cap
        );
    }

    // ─── Q1d — 구간 안 고유 본문 합 ──────────────────────────────────────────

    /**
     * Bytes of the distinct payload bodies stored in the window ("고유 본문 합").
     *
     * <p>// [Phase R25] AC-25-05-1/AC-25-05-2/AC-25-05-5 — 순위표의 {@code payloadBytes} 는
     * // <b>참조당 그대로</b> 두고(UD-4), 새 값은 요약 자리에만 나온다. 계산 창은 분석 창(1/6/24시간)과
     * // <b>같다</b> — 한 화면에서 두 숫자를 비교하려면 분모가 같아야 한다.
     *
     * <p>★{@code aggregateSummary} 에 <b>합치지 않는다</b>: 요약 질의는 trace 단위로 묶으므로 거기에
     * payload 를 결합하면 {@code span_cnt} 가 부풀어 오른다(같은 함정이 Q1a javadoc 에 이미 적혀 있다).
     * 질의를 나누는 것이 이 파일의 기존 규약이다.
     *
     * <p><b>방향(in/out)을 열쇠에 안 넣는다</b> — 화면 라벨이 「고유 본문 합」이고 목적이 "저장소에 실제로
     * 남는 바이트" 라, 같은 본문이 양쪽에 있어도 저장은 한 벌이다.
     *
     * <p>★<b>한계 그 자리에</b>: <b>옛 행은 행마다 별개 묶음</b>이라 개별 본문으로 센다
     * ({@code 'row:' || payload_id} 열쇠). 그래서 올린 뒤 약 이틀간 이 값이 <b>실제보다 크게</b> 나온다.
     * 틀리는 방향은 <b>절감이 덜 되어 보이는 안전한 쪽</b>이다. 옛 행이 사라지면 저절로 정확해진다.
     *
     * <p>본문이 없는 행은 값이 NULL 이라 {@code SUM} 이 건너뛴다(0 과 같은 결과).
     */
    public long aggregateUniquePayloadBytes(String serviceName, long fromMs, long toMs) {
        String sql = """
                SELECT COALESCE(SUM(u.b), 0) AS unique_payload_bytes
                FROM (
                    SELECT COALESCE(p.body_hash, 'row:' || p.payload_id)              AS k,
                           MIN(COALESCE(pb.body_bytes, length(CAST(p.body AS BLOB)))) AS b
                    FROM traces t
                    JOIN spans s    ON s.trace_id = t.trace_id
                    JOIN payloads p ON p.span_id  = s.span_id
                    LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash
                    WHERE t.service_name = ?
                      AND t.start_time  >= ?
                      AND t.start_time  <  ?
                    GROUP BY k
                ) u
                """;
        Long value = jdbc.queryForObject(sql, Long.class, serviceName, fromMs, toMs);
        return value == null ? 0L : value;
    }

    // ─── Q2 — 제외 시뮬레이션 (재귀 CTE 정확 계산) ───────────────────────────

    /**
     * Recompute the remaining trees after removing the target classes.
     *
     * <p>3단 자기조인은 새 시작점의 일부를 <b>과소 보고</b>한다(경고가 약해지는 위험한 방향)는 것이
     * 실측으로 확인돼 재귀 CTE 정확 계산을 쓴다.
     *
     * <p><b>계산 원리</b>
     * <ul>
     *   <li>{@code climb} 은 살아남은 span 마다 부모를 따라 위로 올라간다(제외 여부와 무관하게 상한 깊이까지).</li>
     *   <li>{@code anc} 는 그 조상 체인 중 <b>살아남은 것만</b> 남긴다.</li>
     *   <li>각 span 의 소속 새 시작점 = 살아남은 조상 중 <b>가장 위(depth 최대)</b>. 없으면 자기 자신.</li>
     *   <li>따라서 {@code sizes} 의 행 수 = 결과 trace 수, {@code n = 1} 인 행 수 = span 하나뿐인 trace 수.</li>
     * </ul>
     * 이 계산은 "가장 가까운 계측된 조상이 부모가 된다" 는 agent 의 실제 동작과 같은 모양이다.
     *
     * <p>{@code MATERIALIZED} 힌트는 R19 dev 실행 게이트 GT-17 로 인식이 실측 확인됐다
     * (sqlite-jdbc 3.47.1.0 / SQLite 3.47.1).
     */
    public OrphanRow simulateOrphans(String serviceName, long fromMs, long toMs,
                                     List<String> targets, int maxDepth) {
        List<Object> params = new ArrayList<>();
        // 빈 목록이면 아무것도 제외하지 않는다 → 상수 0. (IN () 는 유효한 SQL 이 아니다.)
        String excludedExpr = "0";
        if (!targets.isEmpty()) {
            excludedExpr = "CASE WHEN " + CLASS_EXPR + " IN (" + placeholders(targets.size()) + ") THEN 1 ELSE 0 END";
            params.addAll(targets);
        }
        params.add(serviceName);
        params.add(fromMs);
        params.add(toMs);
        params.add(maxDepth);   // climb 재귀 상한
        params.add(maxDepth);   // capped 판정

        String sql = "WITH RECURSIVE\n"
                + "win AS MATERIALIZED (\n"
                + "    SELECT s.span_id AS span_id, s.parent_span_id AS parent_span_id,\n"
                + "           " + excludedExpr + " AS excluded\n"
                + "    " + FROM_WINDOW.replace("\n", "\n    ")
                + "),\n"
                + "climb(span_id, cur, depth) AS (\n"
                + "    SELECT span_id, parent_span_id, 1 FROM win WHERE excluded = 0\n"
                + "    UNION ALL\n"
                + "    SELECT c.span_id, w.parent_span_id, c.depth + 1\n"
                + "    FROM climb c JOIN win w ON w.span_id = c.cur\n"
                + "    WHERE c.depth < ?\n"
                + "),\n"
                + "anc AS (\n"
                + "    SELECT c.span_id AS span_id, c.cur AS anc_id, c.depth AS depth\n"
                + "    FROM climb c JOIN win w ON w.span_id = c.cur\n"
                + "    WHERE w.excluded = 0\n"
                + "),\n"
                + "deepest AS (\n"
                + "    SELECT span_id, MAX(depth) AS d FROM anc GROUP BY span_id\n"
                + "),\n"
                + "owner AS (\n"
                + "    SELECT w.span_id AS span_id, COALESCE(a.anc_id, w.span_id) AS root_id\n"
                + "    FROM win w\n"
                + "    LEFT JOIN deepest dp ON dp.span_id = w.span_id\n"
                + "    LEFT JOIN anc     a  ON a.span_id  = w.span_id AND a.depth = dp.d\n"
                + "    WHERE w.excluded = 0\n"
                + "),\n"
                + "sizes AS (\n"
                + "    SELECT root_id, COUNT(*) AS n FROM owner GROUP BY root_id\n"
                + "),\n"
                + "capped AS (\n"
                + "    SELECT COUNT(*) AS n FROM climb c\n"
                + "    WHERE c.depth = ? AND c.cur IS NOT NULL\n"
                + "      AND EXISTS (SELECT 1 FROM win w WHERE w.span_id = c.cur)\n"
                + ")\n"
                + "SELECT\n"
                + "    (SELECT COALESCE(SUM(n), 0) FROM sizes)                                 AS remaining_spans,\n"
                + "    (SELECT COUNT(*) FROM sizes)                                            AS result_traces,\n"
                + "    (SELECT COALESCE(SUM(CASE WHEN n = 1 THEN 1 ELSE 0 END), 0) FROM sizes)  AS single_span_traces,\n"
                + "    (SELECT n FROM capped)                                                  AS capped_count\n";

        return jdbc.queryForObject(
                sql,
                (rs, rowNum) -> new OrphanRow(
                        rs.getLong("remaining_spans"),
                        rs.getLong("result_traces"),
                        rs.getLong("single_span_traces"),
                        rs.getLong("capped_count")),
                params.toArray()
        );
    }

    // ─── Q3a / Q3b — 직접 귀속 절감 ──────────────────────────────────────────

    /**
     * Directly attributed savings for the target classes.
     *
     * <p>⚠️ <b>자식 span 을 합산하지 않는다.</b> 제외 대상 아래의 {@code jdbc.execute} 는 별도 계측이라
     * 부모를 빼도 남는다(실측: 1차 재시작 후에도 DB span 유지). 합산하면 절감량을 과대 산정한다.
     *
     * <p>⚠️ 이 계산은 {@link #simulateOrphans} 의 trace 재계산과 <b>다른 계산식</b>이다. 두 값이 같은
     * 변수를 공유하는 자리를 만들지 않는다 — 같은 감산식으로 묶으면 trace 수를 반드시 틀린다.
     */
    public SavingsRow directSavings(String serviceName, long fromMs, long toMs, List<String> targets) {
        if (targets.isEmpty()) {
            // 뺄 대상이 없으면 절감도 없다. IN () 를 만들지 않고 여기서 닫는다(쿼리 0회).
            return new SavingsRow(0L, 0L, 0L);
        }
        String inList = " AND " + CLASS_EXPR + " IN (" + placeholders(targets.size()) + ")\n";

        List<Object> params = new ArrayList<>();
        params.add(serviceName);
        params.add(fromMs);
        params.add(toMs);
        params.addAll(targets);

        // Q3a — 제외 대상 span 수
        String spanSql = "SELECT COUNT(*) AS span_delta\n" + FROM_WINDOW + inList;
        Long spanDelta = jdbc.queryForObject(spanSql, Long.class, params.toArray());

        // Q3b — 제외 대상에 직접 달린 payload
        // [Phase R22] R22/AC-02-1/R22/AC-02-2 — Q1b(:207 부근)와 같은 식으로 교체. 두 곳이 어긋나면
        //   같은 화면의 "표 숫자" 와 "빼면 이렇게 돼요 숫자" 가 다른 기준으로 계산된다. 사용자 명시 결정(D-1).
        // [Phase R25] AC-25-03-2 — ★옛 형태 읽기 폴백 — 지우지 말 것.
        //   올린 뒤 약 이틀(retention.days=1 + 04:00 정리)간 body_hash 가 없는 옛 행이 남는다.
        //   이 갈래를 지우면 그 사이의 본문·SQL 이 **에러 없이 빈 값**으로 나온다(예외가 안 뜨므로 아무도 모른다).
        //   정본 시험: LegacyRowFallbackTest.oldFormPayloadRowReadsTheSameAsNewForm
        //   ★위 aggregatePayloadsByClass 와 **같은 처방**이어야 한다 — 두 곳이 어긋나면 같은 화면의
        //   "표 숫자" 와 "빼면 이렇게 돼요 숫자" 가 다른 기준이 된다(R22/D-1 이 이미 겪은 자리).
        String payloadSql = "SELECT COUNT(*) AS payload_count_delta,\n"
                + "       COALESCE(SUM(COALESCE(pb.body_bytes, length(CAST(p.body AS BLOB)))), 0) AS payload_bytes_delta\n"
                + "FROM traces t\n"
                + "JOIN spans s    ON s.trace_id = t.trace_id\n"
                + "JOIN payloads p ON p.span_id  = s.span_id\n"
                + "LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash\n"
                + "WHERE t.service_name = ?\n"
                + "  AND t.start_time  >= ?\n"
                + "  AND t.start_time  <  ?\n"
                + inList;
        SavingsRow payloadPart = jdbc.queryForObject(
                payloadSql,
                (rs, rowNum) -> new SavingsRow(0L,
                        rs.getLong("payload_count_delta"),
                        rs.getLong("payload_bytes_delta")),
                params.toArray()
        );

        long spans = spanDelta == null ? 0L : spanDelta;
        long payloadCount = payloadPart == null ? 0L : payloadPart.payloadCountDelta();
        long payloadBytes = payloadPart == null ? 0L : payloadPart.payloadBytesDelta();
        return new SavingsRow(spans, payloadCount, payloadBytes);
    }

    /** {@code ?, ?, ...} — 개수만 문자열로 만든다(값은 언제나 바인딩). */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
