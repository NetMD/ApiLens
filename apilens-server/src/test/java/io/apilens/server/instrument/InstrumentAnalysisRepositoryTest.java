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

    // ─── [Phase R22] ② 절감 계산 기준 = 실제 저장 바이트 (R22/AC-02-5·02-6) ──

    /**
     * [Phase R22] R22/AC-02-1/R22/AC-02-5/R22/AC-02-6 — R22/AC-02-5 verbatim: "절단 행
     * ({@code truncated=1}, 선언 크기가 본문 길이보다 훨씬 큰 행) 케이스 테스트가 <b>두 픽스처 파일
     * 양쪽</b>에 있다". 사용자 명시 결정(D-1). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★ R22/AC-02-6 verbatim: "그 케이스는 <b>교체 전 코드에서 실패한다.</b> (교체가 무엇을 고쳤는지
     * 증명하는 유일한 회귀 가드다.)" — 교체 전(선언 크기 합산)이면 1,000,050 이 나온다.
     */
    @Test
    void countsTheStoredBodyLengthNotTheDeclaredSizeForTruncatedPayloads() {
        // 선언 1,000,000 · 실제 저장 본문 100 · truncated=1 — 운영에서 절단이 일어난 행의 모양.
        insertPayload("s2", 1_000_000L, 100L, 1);

        Map<String, InstrumentAnalysisRepository.PayloadRow> payloads = payloadRowsByClass();

        // s2 는 원래 50바이트짜리 payload 1건 + 이번 절단 행 1건 = 150 (= 50 + 100).
        assertEquals(2L, payloads.get(SERVICE_CLASS).payloadCount());
        assertEquals(150L, payloads.get(SERVICE_CLASS).payloadBytes(),
                "절감 예측은 디스크에 실제로 있는 본문 길이를 센다 (선언 크기 1,000,000 이 아니다)");
    }

    /**
     * [Phase R22] R22/AC-02-1/R22/AC-02-5/R22/AC-02-6 — Q3b(직접 귀속 절감)도 같은 기준이어야 한다.
     * 두 쿼리가 어긋나면 같은 화면의 표 숫자와 "빼면 이렇게 돼요" 숫자가 다른 기준으로 계산된다.
     */
    @Test
    void countsTheStoredBodyLengthForTruncatedPayloadsInDirectSavingsToo() {
        insertPayload("s2", 1_000_000L, 100L, 1);

        InstrumentAnalysisRepository.SavingsRow savings =
                repository.directSavings(SERVICE, FROM_MS, TO_MS, List.of(SERVICE_CLASS));

        assertEquals(2L, savings.payloadCountDelta());
        assertEquals(150L, savings.payloadBytesDelta(),
                "Q3b 도 실제 저장 바이트 기준 (교체 전이면 1,000,050)");
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

    // ─── [Phase R25] Q1d — 구간 안 「고유 본문 합」 (AC-25-05-1·AC-25-05-2·AC-25-05-5) ──

    /**
     * TS-R25-30 — AC-25-05-1 원문: "분석 요약에 <b>같은 본문을 한 번만 센 바이트 합</b>을 한 줄 더한다."
     *
     * <p>새 형태 행 둘이 같은 본문을 가리키면 저장소에 실제로 남는 것은 <b>한 벌</b>이다. 참조당 합
     * (순위표)은 둘을 다 더하고, 이 값은 한 번만 더한다 — 두 숫자가 <b>달라지는 것이 이 기능의 목적</b>이다.
     *
     * <p>기준선 픽스처의 payload 세 건(100 · 200 · 50)은 서로 다른 본문이라 옛 형태로 350 을 그대로 낸다.
     */
    @Test
    void countsASharedBodyOnceInTheUniqueBytesSum() {
        String shared = "z".repeat(80);
        insertNewFormPayload("s1", shared);
        insertNewFormPayload("s2", shared);

        // 전제: 두 행이 실제로 같은 지문을 가리킨다(빈 DB 에서 0 == 0 으로 통과하는 것을 막는다).
        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"),
                "전제: 같은 본문이라 본문 표에는 한 벌만 있어야 한다");
        assertEquals(2, count("SELECT COUNT(*) FROM payloads WHERE body_hash IS NOT NULL"),
                "전제: 그 한 벌을 가리키는 행은 실제로 둘이어야 한다");

        long unique = repository.aggregateUniquePayloadBytes(SERVICE, FROM_MS, TO_MS);
        long perReference = repository.aggregatePayloadsByClass(SERVICE, FROM_MS, TO_MS, CAP).stream()
                .mapToLong(InstrumentAnalysisRepository.PayloadRow::payloadBytes).sum();

        assertEquals(350L + 80L, unique, "공유 본문은 한 번만 센다 (350 + 80)");
        assertEquals(350L + 160L, perReference, "참조당 합은 둘 다 센다 (350 + 80 + 80)");
    }

    /**
     * TS-R25-31 — 옛 형태 행은 <b>행마다 별개 본문</b>으로 센다. 질의 javadoc 이 적은 한계 그대로다.
     *
     * <p>★올린 뒤 약 이틀간 이 값이 <b>실제보다 크게</b> 나온다. 틀리는 방향은 "절감이 덜 되어 보이는
     * 안전한 쪽" 이고, 옛 행이 사라지면 저절로 정확해진다. 이 시험은 그 사실을 <b>숫자로</b> 고정한다 —
     * 다음 라운드가 열쇠를 바꾸면 여기가 빨개져서 문서와 코드가 같이 움직인다.
     */
    @Test
    void countsEachOldFormRowSeparatelyEvenWhenTheBodiesAreIdentical() {
        String same = "w".repeat(70);
        insertPayload("s1", 70L, 70L, 0);   // 옛 형태 — 본문이 행 안에 있고 지문이 없다
        insertPayload("s2", 70L, 70L, 0);   // 같은 내용이지만 지문이 없어 묶이지 않는다
        assertEquals(70, same.length(), "픽스처 길이 확인 — ASCII 라 글자 수 = 바이트 수");

        // 전제: 이 시험이 보는 행이 실제로 전부 옛 형태다(지문 없음 + 본문 있음).
        //   기준선 픽스처는 창 밖·다른 서비스 몫까지 5건을 심는다 — 그래서 전체 수가 아니라
        //   "새 형태가 하나도 없다" 로 전제를 세운다. 여기에 지문이 하나라도 있으면 아래 대조가 뜻을 잃는다.
        assertEquals(0, count("SELECT COUNT(*) FROM payloads WHERE body_hash IS NOT NULL"),
                "전제: 새 형태 행이 하나도 없어야 이 갈래를 밟는다");
        assertEquals(2, count("SELECT COUNT(*) FROM payloads WHERE body IS NOT NULL"
                        + " AND length(CAST(body AS BLOB)) = 70"),
                "전제: 같은 내용의 옛 형태 행이 실제로 둘 심어져야 한다");

        long unique = repository.aggregateUniquePayloadBytes(SERVICE, FROM_MS, TO_MS);

        assertEquals(350L + 140L, unique,
                "옛 행은 같은 내용이어도 행마다 별개로 센다 (350 + 70 + 70) — 420 이 아니다");
    }

    /**
     * TS-R25-32 — 순위표의 {@code payloadBytes} 는 <b>참조당 그대로</b>다(UD-4 — 사용자 확정).
     *
     * <p>새 값은 요약 자리에만 나온다. 순위표까지 고유 기준으로 바꾸면 "이 클래스를 빼면 얼마나 주나"
     * 라는 표의 뜻이 달라진다 — 그 표는 <b>참조를 지우는 것</b>의 효과를 재는 자리이기 때문이다.
     */
    @Test
    void keepsTheRankingBytesPerReferenceWhenBodiesAreShared() {
        String shared = "z".repeat(80);
        insertNewFormPayload("s1", shared);
        insertNewFormPayload("s1", shared);

        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"),
                "전제: 본문 표에는 한 벌만 있다");

        Map<String, InstrumentAnalysisRepository.PayloadRow> payloads = payloadRowsByClass();

        assertEquals(4L, payloads.get(CONTROLLER).payloadCount(), "기준선 2건 + 이번 2건");
        assertEquals(300L + 160L, payloads.get(CONTROLLER).payloadBytes(),
                "UD-4: 순위표는 참조당 합을 그대로 낸다 (공유해도 두 번 센다)");
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

    /**
     * 절단 없는 payload — 선언 크기({@code size_bytes})와 본문 길이가 <b>같다</b>.
     *
     * <p>// [Phase R22] R22/AC-02-7 verbatim: "기존 단언은 약화되지 않는다. 픽스처 헬퍼가 넘겨받은
     * // sizeBytes 길이만큼의 본문을 만들게 고쳐, 호출 지점과 단언을 손대지 않고 기존 단언이 그대로
     * // 성립하게 한다". 호출 지점(:106-108·:117·:122)과 기존 단언(:168·:171·:237·:258) 무변경.
     */
    private void insertPayload(String spanId, long sizeBytes) {
        insertPayload(spanId, sizeBytes, sizeBytes, 0);
    }

    /**
     * 선언 크기와 본문 길이를 따로 주는 픽스처 — 절단 행({@code truncated=1}) 재현용.
     *
     * <p>★ 본문은 <b>ASCII 로만</b> 만든다. 한 글자 = 1바이트여야
     * {@code length(CAST(body AS BLOB)) == bodyBytes} 가 성립한다 (Design §3.4 인코딩 축).
     */
    private void insertPayload(String spanId, long declaredBytes, long bodyBytes, int truncated) {
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'out', 'application/json', ?, ?, ?)
                        """,
                spanId, "x".repeat((int) bodyBytes), declaredBytes, truncated);
    }

    /**
     * [Phase R25] 새 형태 payload 픽스처 — 본문은 {@code payload_bodies} 에 한 벌, 행에는 지문만.
     *
     * <p>기존 {@link #insertPayload(String, long)} 계열은 <b>옛 형태</b>(본문이 행 안)를 만든다.
     * 그 헬퍼를 안 고치는 것이 이 라운드의 방침이다 — 그 통과가 곧 폴백의 증거이기 때문이다.
     *
     * <p>본문은 ASCII 로만 준다(글자 수 = 바이트 수).
     */
    private void insertNewFormPayload(String spanId, String body) {
        int bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        jdbc.update("INSERT OR IGNORE INTO payload_bodies (body_hash, body, body_bytes, first_seen_at)"
                + " VALUES (?, ?, ?, ?)", sha256Hex(body), body, bytes, 1_000L);
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes,
                                              truncated, body_hash)
                        VALUES (?, 'out', 'application/json', NULL, ?, 0, ?)
                        """,
                spanId, bytes, sha256Hex(body));
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
