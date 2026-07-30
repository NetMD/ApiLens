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

import io.apilens.server.instrument.dto.AnalysisResponse;
import io.apilens.server.instrument.dto.SimulationResponse;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R19] InstrumentAnalysisService — 순위 조립 규약.
 *
 * <p>핵심 단언 둘:
 * <ul>
 *   <li><b>순위는 합집합을 뽑기 <u>전</u> 전체 집합 기준이다.</b> 세 축 상위 N 만 골라 담은 뒤에
 *       순위를 매기면 "받은 목록 안 순위" 가 되어 화면이 뜻하는 값과 달라진다. 순위가 목록 크기를
 *       넘어서는 값으로 나오는지로 기계 판정한다.</li>
 *   <li><b>고정 합계 행은 순위 경쟁 대상이 아니다.</b> 세 순위가 전부 비어 있고 목록 맨 앞에 온다.</li>
 * </ul>
 */
class InstrumentAnalysisServiceTest {

    private static final String SERVICE = "svc";

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private InstrumentAnalysisService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-instrument-service-test-", ".db");
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
        this.service = new InstrumentAnalysisService(
                new InstrumentAnalysisRepository(jdbc), new InstrumentAnalysisGate());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 고정 합계 행 · 판별 · 워커 배지 ─────────────────────────────────────

    @Test
    void putsTheNamelessTotalRowFirstWithoutAnyRank() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        insertSpan("s2", "t1", "s1", "jdbc.execute", "DB", now - 59_000L);
        insertSpan("s3", "t1", "s1", "jdbc.execute", "DB", now - 58_000L);

        AnalysisResponse response = service.analyze(SERVICE, 1);

        AnalysisResponse.ClassStat first = response.items().get(0);
        assertEquals("", first.className(), "the nameless total row leads the list");
        assertNull(first.spanRank());
        assertNull(first.payloadCountRank());
        assertNull(first.payloadBytesRank());
        assertEquals(2L, first.spanCount());
        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, first.excludeStatus());
        assertEquals(ExcludeReasonCode.NO_CLASS_NAME, first.excludeReasonCode());
        assertNull(first.excludeTarget());
    }

    @Test
    void carriesTheExcludeVerdictOnEveryNamedRow() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.service.OrderService#find", "INTERNAL", now - 60_000L);
        insertSpan("s2", "t1", "s1", "com.acme.mapper.OrderMapper#select", "INTERNAL", now - 59_000L);

        AnalysisResponse response = service.analyze(SERVICE, 1);

        AnalysisResponse.ClassStat svc = item(response, "com.acme.service.OrderService");
        assertEquals(ExcludeStatus.EXCLUDABLE, svc.excludeStatus());
        assertEquals("com.acme.service.OrderService", svc.excludeTarget());

        AnalysisResponse.ClassStat mapper = item(response, "com.acme.mapper.OrderMapper");
        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, mapper.excludeStatus());
        assertEquals(ExcludeReasonCode.PROXY_INSTRUMENTED, mapper.excludeReasonCode());
        assertNull(mapper.excludeTarget(), "a non-excludable row must not hand out an option value");
    }

    @Test
    void marksAClassAsBackgroundWorkerExactlyAtTheRootRatioThreshold() {
        long now = System.currentTimeMillis();
        // worker: 5 span 중 4개가 시작점 → rootRatio 0.80 (경계 — 임계값과 같으면 워커다)
        insertTrace("t-worker", now - 60_000L);
        for (int i = 0; i < 4; i++) {
            insertSpan("w" + i, "t-worker", null, "com.acme.batch.SyncJob#run", "INTERNAL", now - 60_000L);
        }
        insertSpan("w4", "t-worker", "w0", "com.acme.batch.SyncJob#run", "INTERNAL", now - 59_000L);
        // non-worker: 4 span 중 3개가 시작점 → rootRatio 0.75
        insertTrace("t-web", now - 60_000L);
        for (int i = 0; i < 3; i++) {
            insertSpan("v" + i, "t-web", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        }
        insertSpan("v3", "t-web", "v0", "com.acme.web.OrderController#list", "SERVER", now - 59_000L);

        AnalysisResponse response = service.analyze(SERVICE, 1);

        AnalysisResponse.ClassStat worker = item(response, "com.acme.batch.SyncJob");
        assertEquals(0.80d, worker.rootRatio(), 1e-9);
        assertTrue(worker.backgroundWorker(), "a root ratio equal to the threshold already counts as a worker");

        AnalysisResponse.ClassStat web = item(response, "com.acme.web.OrderController");
        assertEquals(0.75d, web.rootRatio(), 1e-9);
        assertFalse(web.backgroundWorker());
    }

    // ─── ★ 순위는 전체 집합 기준(합집합 추출보다 먼저) ───────────────────────

    @Test
    void ranksAcrossEveryClassNotJustTheReturnedOnes() {
        long now = System.currentTimeMillis();
        insertTrace("many", now - 60_000L);
        // 60개 클래스, 각각 span 1개. 동점은 클래스 이름 오름차순으로 갈리므로 C00=1위 … C59=60위.
        insertSpan("m0", "many", null, "com.acme.C00#run", "INTERNAL", now - 60_000L);
        for (int i = 1; i < 60; i++) {
            insertSpan("m" + i, "many", "m0", "com.acme.C" + pad(i) + "#run", "INTERNAL", now - 60_000L + i);
        }
        // 꼴찌 클래스에만 아주 큰 payload 를 달아 payload 축 1위로 만든다.
        insertPayload("m59", 1_000_000L);

        AnalysisResponse response = service.analyze(SERVICE, 1);

        assertEquals(60, response.totalClasses());
        assertTrue(response.truncated(), "60 classes cannot all fit into the three top-50 unions");
        assertTrue(response.items().size() < response.totalClasses());

        AnalysisResponse.ClassStat last = item(response, "com.acme.C59");
        assertEquals(1, last.payloadBytesRank(), "it is first on the payload-size axis");
        assertEquals(1, last.payloadCountRank());
        // ★ 합집합을 먼저 뽑고 순위를 매겼다면 이 값은 목록 크기(약 51) 이하로 나온다.
        assertEquals(60, last.spanRank(),
                "span rank must be the rank within every class, not within the returned list");
        assertTrue(last.spanRank() > response.items().size(),
                "a rank larger than the returned list proves ranking happened before selection");
    }

    @Test
    void reportsNotTruncatedWhenEveryClassFits() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        insertSpan("s2", "t1", "s1", "com.acme.service.OrderService#find", "INTERNAL", now - 59_000L);

        AnalysisResponse response = service.analyze(SERVICE, 1);

        assertEquals(2, response.totalClasses());
        assertEquals(2, response.items().size());
        assertFalse(response.truncated());
    }

    // ─── 창 계산 · 빈 자료 ──────────────────────────────────────────────────

    @Test
    void derivesTheWindowFromTheRequestedHours() {
        AnalysisResponse response = service.analyze(SERVICE, 6);

        assertEquals(6 * 3_600_000L, response.window().toMs() - response.window().fromMs());
        assertEquals(response.window().toMs(), response.window().queriedAtMs());
    }

    @Test
    void returnsEmptyResultWithZeroTotalsWhenNothingWasCollected() {
        AnalysisResponse response = service.analyze(SERVICE, 1);

        assertEquals(0, response.totalClasses());
        assertTrue(response.items().isEmpty());
        assertFalse(response.truncated());
        assertEquals(0.0d, response.summary().avgSpansPerTrace(), 1e-9);
        assertEquals(0.0d, response.summary().singleSpanTraceRatio(), 1e-9);
    }

    // ─── 시뮬레이션 — 절감과 부작용이 함께, 그리고 서로 다른 계산식으로 ──────

    @Test
    void returnsSavingsAndImpactTogetherFromDifferentCalculations() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        insertSpan("s2", "t1", "s1", "com.acme.service.OrderService#find", "INTERNAL", now - 59_000L);
        insertSpan("s3", "t1", "s2", "jdbc.execute", "DB", now - 58_000L);
        insertPayload("s2", 400L);

        AnalysisResponse ranking = service.analyze(SERVICE, 1);
        SimulationResponse simulation = service.simulate(SERVICE,
                ranking.window().fromMs(), ranking.window().toMs(),
                List.of("com.acme.service.OrderService"));

        assertNotNull(simulation.savings());
        assertNotNull(simulation.impact());
        // 절감: 직접 귀속만 (자식 jdbc.execute 는 남는다)
        assertEquals(1L, simulation.savings().spanDelta());
        assertEquals(1L, simulation.savings().payloadCountDelta());
        assertEquals(400L, simulation.savings().payloadBytesDelta());
        // 부작용: 트리 재계산 — span 은 3→2, trace 수는 그대로 1
        assertEquals(2L, simulation.impact().remainingSpans());
        assertEquals(1L, simulation.impact().resultTraces());
        assertEquals(2.0d, simulation.impact().avgSpansPerTrace(), 1e-9);
        assertEquals(0.0d, simulation.impact().singleSpanTraceRatio(), 1e-9);
        assertFalse(simulation.depthCapped());
    }

    @Test
    void showsTraceCountGrowingWhenAncestorsAreExcluded() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        insertSpan("s2", "t1", "s1", "com.acme.service.OrderService#find", "INTERNAL", now - 59_000L);
        insertSpan("s3", "t1", "s1", "com.acme.service.ReportService#find", "INTERNAL", now - 58_000L);

        AnalysisResponse ranking = service.analyze(SERVICE, 1);
        SimulationResponse simulation = service.simulate(SERVICE,
                ranking.window().fromMs(), ranking.window().toMs(),
                List.of("com.acme.web.OrderController"));

        // 시작점을 빼면 두 자식이 각자 새 시작점이 된다 — trace 수가 1 → 2 로 늘어난다(오류 아님).
        assertEquals(1L, ranking.summary().totalTraces());
        assertEquals(2L, simulation.impact().resultTraces());
        assertEquals(1.0d, simulation.impact().singleSpanTraceRatio(), 1e-9);
    }

    @Test
    void returnsUntouchedTotalsWhenTheSimulationSelectsNothing() {
        long now = System.currentTimeMillis();
        insertTrace("t1", now - 60_000L);
        insertSpan("s1", "t1", null, "com.acme.web.OrderController#list", "SERVER", now - 60_000L);
        insertSpan("s2", "t1", "s1", "com.acme.service.OrderService#find", "INTERNAL", now - 59_000L);

        AnalysisResponse ranking = service.analyze(SERVICE, 1);
        SimulationResponse simulation = service.simulate(SERVICE,
                ranking.window().fromMs(), ranking.window().toMs(), List.of());

        assertEquals(ranking.summary().totalSpans(), simulation.impact().remainingSpans());
        assertEquals(ranking.summary().totalTraces(), simulation.impact().resultTraces());
        assertEquals(ranking.summary().singleSpanTraceRatio(),
                simulation.impact().singleSpanTraceRatio(), 1e-9);
        assertEquals(0L, simulation.savings().spanDelta());
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private static String pad(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }

    private static AnalysisResponse.ClassStat item(AnalysisResponse response, String className) {
        Optional<AnalysisResponse.ClassStat> found = response.items().stream()
                .filter(it -> className.equals(it.className()))
                .findFirst();
        assertTrue(found.isPresent(), "expected class in the ranking: " + className);
        return found.get();
    }

    private void insertTrace(String traceId, long startTime) {
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'root', ?, ?, 10, 'OK', 1, 1, 0, ?)
                        """,
                traceId, SERVICE, startTime, startTime);
    }

    private void insertSpan(String spanId, String traceId, String parentSpanId,
                            String operationName, String spanKind, long startTime) {
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OK', NULL)
                        """,
                spanId, traceId, parentSpanId, SERVICE, operationName, spanKind, startTime, startTime + 1L);
    }

    private void insertPayload(String spanId, long sizeBytes) {
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'out', 'application/json', '{}', ?, 0)
                        """,
                spanId, sizeBytes);
    }
}
