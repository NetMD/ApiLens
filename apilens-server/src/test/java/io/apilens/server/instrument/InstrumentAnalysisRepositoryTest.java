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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R19] InstrumentAnalysisRepository — 실제 SQLite 로 집계 정확성을 잰다.
 *
 * <p>★ <b>이 클래스의 최우선 단언은 {@link #returnsSummaryEquivalentImpactWhenNothingIsExcluded()}</b>
 * 다. 아무것도 빼지 않은 시뮬레이션 결과는 같은 구간 총계와 <b>정확히 같아야</b> 한다 — 재귀 CTE
 * 정확성의 결정적 자가 증명이며, 이 라운드에서 가장 조용히 틀릴 수 있는 계산의 회귀 가드다.
 *
 * <p>테스트 자료 모양(서비스 {@code svc-a}, 구간 {@code [1000, 2000)}):
 * <pre>
 *   trace-1 (start 1000)         trace-2 (start 1500)
 *     s1 OrderController#list      s4 SyncJob#run      ← span 하나뿐인 trace
 *       s2 OrderService#find
 *         s3 jdbc.execute          ← '#' 없음 = 고정 합계 행
 * </pre>
 * 구간 밖 trace 와 다른 서비스 trace 를 각각 하나씩 섞어 두어 창·서비스 필터도 함께 잰다.
 */
class InstrumentAnalysisRepositoryTest {

    private static final String SERVICE = "svc-a";
    private static final long FROM_MS = 1_000L;
    private static final long TO_MS = 2_000L;
    private static final int MAX_DEPTH = InstrumentAnalysisService.MAX_CLIMB_DEPTH;
    private static final int CAP = InstrumentAnalysisService.ANALYZE_GROUP_CAP;

    private static final String CONTROLLER = "com.acme.web.OrderController";
    private static final String SERVICE_CLASS = "com.acme.service.OrderService";
    private static final String BATCH = "com.acme.batch.SyncJob";

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private InstrumentAnalysisRepository repository;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-instrument-repo-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.repository = new InstrumentAnalysisRepository(jdbc);

        seedBaseline();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private void seedBaseline() {
        // 구간 안 — 3단 트리
        insertTrace("trace-1", SERVICE, 1_000L, 99);   // span_count 를 일부러 틀린 값(99)으로 둔다
        insertSpan("s1", "trace-1", null, SERVICE, CONTROLLER + "#list", "SERVER", 1_000L);
        insertSpan("s2", "trace-1", "s1", SERVICE, SERVICE_CLASS + "#find", "INTERNAL", 1_010L);
        insertSpan("s3", "trace-1", "s2", SERVICE, "jdbc.execute", "DB", 1_020L);
        insertPayload("s1", 100L);
        insertPayload("s1", 200L);
        insertPayload("s2", 50L);

        // 구간 안 — span 하나뿐인 trace
        insertTrace("trace-2", SERVICE, 1_500L, 1);
        insertSpan("s4", "trace-2", null, SERVICE, BATCH + "#run", "INTERNAL", 1_500L);

        // 구간 밖 (start_time 이 창 뒤)
        insertTrace("trace-3", SERVICE, 3_000L, 1);
        insertSpan("s5", "trace-3", null, SERVICE, SERVICE_CLASS + "#find", "INTERNAL", 3_000L);
        insertPayload("s5", 9_999L);

        // 다른 서비스 (같은 창)
        insertTrace("trace-4", "svc-b", 1_100L, 1);
        insertSpan("s6", "trace-4", null, "svc-b", CONTROLLER + "#list", "SERVER", 1_100L);
        insertPayload("s6", 8_888L);
    }

    // ─── Q1c — 구간 총계 ─────────────────────────────────────────────────────

    @Test
    void countsSummaryFromActualSpanRowsWithinWindowAndService() {
        InstrumentAnalysisRepository.SummaryRow summary = repository.aggregateSummary(SERVICE, FROM_MS, TO_MS);

        assertEquals(2L, summary.totalTraces(), "only the two in-window traces of svc-a count");
        // traces.span_count 요약 컬럼(99)이 아니라 실제 spans 행을 센다.
        assertEquals(4L, summary.totalSpans());
        assertEquals(1L, summary.singleSpanTraces());
    }

    // ─── Q1a — 클래스별 span·root ────────────────────────────────────────────

    @Test
    void countsSpansAndRootsPerClassAndGroupsNamelessSpansTogether() {
        Map<String, InstrumentAnalysisRepository.SpanRow> byClass = spanRowsByClass();

        assertEquals(4, byClass.size());
        assertEquals(1L, byClass.get(CONTROLLER).spanCount());
        assertEquals(1L, byClass.get(CONTROLLER).rootCount());
        assertTrue(byClass.get(CONTROLLER).hasServer());

        assertEquals(1L, byClass.get(SERVICE_CLASS).spanCount());
        assertEquals(0L, byClass.get(SERVICE_CLASS).rootCount());

        // '#' 이 없는 span 은 빈 문자열 키로 묶인다 = 고정 합계 행.
        assertEquals(1L, byClass.get("").spanCount());
        assertFalse(byClass.get("").hasServer());

        assertEquals(1L, byClass.get(BATCH).rootCount());
    }

    // ─── Q1a/Q1b 분리 — payload 가 span 수를 부풀리지 않는다 ─────────────────

    @Test
    void keepsSpanCountUnaffectedByTheNumberOfPayloads() {
        Map<String, InstrumentAnalysisRepository.SpanRow> spans = spanRowsByClass();
        Map<String, InstrumentAnalysisRepository.PayloadRow> payloads = payloadRowsByClass();

        // s1 은 payload 2건짜리 span 1개다. 한 쿼리로 합쳤다면 span_count 가 2로 부풀었을 자리.
        assertEquals(1L, spans.get(CONTROLLER).spanCount());
        assertEquals(2L, payloads.get(CONTROLLER).payloadCount());
        assertEquals(300L, payloads.get(CONTROLLER).payloadBytes());

        assertEquals(1L, payloads.get(SERVICE_CLASS).payloadCount());
        assertEquals(50L, payloads.get(SERVICE_CLASS).payloadBytes());
        // payload 가 없는 클래스는 payload 집계에 아예 나타나지 않는다(합칠 때 0으로 채운다).
        assertFalse(payloads.containsKey(BATCH));
    }

    // ─── ★ V-01 — 아무것도 빼지 않으면 시뮬레이션 = 구간 총계 ────────────────

    @Test
    void returnsSummaryEquivalentImpactWhenNothingIsExcluded() {
        InstrumentAnalysisRepository.SummaryRow summary = repository.aggregateSummary(SERVICE, FROM_MS, TO_MS);
        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans(SERVICE, FROM_MS, TO_MS, List.of(), MAX_DEPTH);

        assertEquals(summary.totalSpans(), orphan.remainingSpans(),
                "an empty exclusion must leave every span in place");
        assertEquals(summary.totalTraces(), orphan.resultTraces(),
                "an empty exclusion must reproduce the original trace count exactly");
        assertEquals(summary.singleSpanTraces(), orphan.singleSpanTraces(),
                "an empty exclusion must reproduce the original single-span trace count exactly");
        assertEquals(0L, orphan.cappedCount());
    }

    // ─── Q2 — 조상을 빼면 말단이 새 시작점이 된다 ────────────────────────────

    @Test
    void reattachesSurvivingSpansToTheHighestSurvivingAncestor() {
        // 가운데(OrderService)를 빼면 jdbc.execute 는 사라지지 않고 Controller 밑으로 붙는다.
        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans(SERVICE, FROM_MS, TO_MS, List.of(SERVICE_CLASS), MAX_DEPTH);

        assertEquals(3L, orphan.remainingSpans());
        assertEquals(2L, orphan.resultTraces(), "the tree keeps one root — trace count is unchanged");
        assertEquals(1L, orphan.singleSpanTraces());
    }

    @Test
    void makesOrphansIntoNewRootsWhenEveryAncestorIsExcluded() {
        // 조상 둘을 다 빼면 jdbc.execute 가 혼자 남아 새 시작점이 된다 = 조각난 trace.
        InstrumentAnalysisRepository.OrphanRow orphan = repository.simulateOrphans(
                SERVICE, FROM_MS, TO_MS, List.of(CONTROLLER, SERVICE_CLASS), MAX_DEPTH);

        assertEquals(2L, orphan.remainingSpans());
        assertEquals(2L, orphan.resultTraces());
        assertEquals(2L, orphan.singleSpanTraces(), "both remaining spans stand alone");
    }

    @Test
    void keepsRootsOfOtherTracesWhenOnlyTheRootClassIsExcluded() {
        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans(SERVICE, FROM_MS, TO_MS, List.of(CONTROLLER), MAX_DEPTH);

        assertEquals(3L, orphan.remainingSpans());
        assertEquals(2L, orphan.resultTraces());
        assertEquals(1L, orphan.singleSpanTraces());
    }

    // ─── Q3a/Q3b — 직접 귀속만 센다 ─────────────────────────────────────────

    @Test
    void countsOnlyDirectlyAttributedSpansAndPayloadsAsSavings() {
        InstrumentAnalysisRepository.SavingsRow savings =
                repository.directSavings(SERVICE, FROM_MS, TO_MS, List.of(SERVICE_CLASS));

        // 자식(jdbc.execute)은 별도 계측이라 부모를 빼도 남는다 → 합산하지 않는다.
        assertEquals(1L, savings.spanDelta());
        assertEquals(1L, savings.payloadCountDelta());
        assertEquals(50L, savings.payloadBytesDelta());
    }

    @Test
    void returnsZeroSavingsWhenNothingIsSelected() {
        InstrumentAnalysisRepository.SavingsRow savings =
                repository.directSavings(SERVICE, FROM_MS, TO_MS, List.of());

        assertEquals(0L, savings.spanDelta());
        assertEquals(0L, savings.payloadCountDelta());
        assertEquals(0L, savings.payloadBytesDelta());
    }

    @Test
    void countsSavingsOnlyInsideTheRequestedWindowAndService() {
        // 구간 밖 trace-3 과 다른 서비스 trace-4 가 같은 클래스 이름을 갖고 있어도 섞이지 않는다.
        InstrumentAnalysisRepository.SavingsRow savings =
                repository.directSavings(SERVICE, FROM_MS, TO_MS, List.of(CONTROLLER));

        assertEquals(1L, savings.spanDelta());
        assertEquals(2L, savings.payloadCountDelta());
        assertEquals(300L, savings.payloadBytesDelta());
    }

    // ─── 재귀 깊이 상한 경계 (B-30 / B-31 / B-32) ────────────────────────────

    @Test
    void reportsNoDepthCapForATreeJustBelowTheLimit() {
        seedChain("deep-19", 19);

        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans("deep-19", FROM_MS, TO_MS, List.of(), MAX_DEPTH);

        assertEquals(0L, orphan.cappedCount());
        assertEquals(19L, orphan.remainingSpans());
        assertEquals(1L, orphan.resultTraces());
    }

    @Test
    void reportsNoDepthCapForATreeExactlyAtTheLimit() {
        seedChain("deep-20", 20);

        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans("deep-20", FROM_MS, TO_MS, List.of(), MAX_DEPTH);

        assertEquals(0L, orphan.cappedCount(), "a depth equal to the limit is still counted in full");
        assertEquals(20L, orphan.remainingSpans());
        assertEquals(1L, orphan.resultTraces());
    }

    @Test
    void reportsDepthCapForATreeDeeperThanTheLimit() {
        seedChain("deep-21", 21);

        InstrumentAnalysisRepository.OrphanRow orphan =
                repository.simulateOrphans("deep-21", FROM_MS, TO_MS, List.of(), MAX_DEPTH);

        assertTrue(orphan.cappedCount() > 0, "a deeper tree must be reported as capped, not silently wrong");
    }

    // ─── 집계 상한(LIMIT) ───────────────────────────────────────────────────

    @Test
    void appliesTheGroupCapToTheNumberOfReturnedClasses() {
        List<InstrumentAnalysisRepository.SpanRow> capped =
                repository.aggregateSpansByClass(SERVICE, FROM_MS, TO_MS, 2);

        assertEquals(2, capped.size(), "the LIMIT bounds how many classes come back");
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private Map<String, InstrumentAnalysisRepository.SpanRow> spanRowsByClass() {
        return repository.aggregateSpansByClass(SERVICE, FROM_MS, TO_MS, CAP).stream()
                .collect(Collectors.toMap(InstrumentAnalysisRepository.SpanRow::className, Function.identity()));
    }

    private Map<String, InstrumentAnalysisRepository.PayloadRow> payloadRowsByClass() {
        return repository.aggregatePayloadsByClass(SERVICE, FROM_MS, TO_MS, CAP).stream()
                .collect(Collectors.toMap(InstrumentAnalysisRepository.PayloadRow::className, Function.identity()));
    }

    /** 깊이 {@code depth} 짜리 한 줄 트리를 자기 서비스·자기 trace 로 심는다. */
    private void seedChain(String service, int depth) {
        String traceId = service + "-trace";
        insertTrace(traceId, service, 1_200L, depth);
        String parent = null;
        for (int i = 1; i <= depth; i++) {
            String spanId = service + "-s" + i;
            insertSpan(spanId, traceId, parent, service, "com.acme.deep.Level" + i + "#run", "INTERNAL", 1_200L + i);
            parent = spanId;
        }
    }

    private void insertTrace(String traceId, String service, long startTime, int spanCount) {
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'root', ?, ?, 10, 'OK', ?, 1, 0, ?)
                        """,
                traceId, service, startTime, spanCount, startTime);
    }

    private void insertSpan(String spanId, String traceId, String parentSpanId, String service,
                            String operationName, String spanKind, long startTime) {
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OK', NULL)
                        """,
                spanId, traceId, parentSpanId, service, operationName, spanKind, startTime, startTime + 1L);
    }

    private void insertPayload(String spanId, long sizeBytes) {
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'out', 'application/json', '{}', ?, 0)
                        """,
                spanId, sizeBytes);
    }
}
