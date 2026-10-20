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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.apilens.common.SpanStatus;
import io.apilens.server.query.dto.TracePointsResponse;
import io.apilens.server.query.dto.TracePointsResponse.TraceBucket;
import io.apilens.server.query.dto.TracePointsResponse.TracePoint;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R28] AC-28-01-1~AC-28-01-11 · AC-28-03-5 — 대표 점 조회의 그물(설계 부록 D-1 · 정정 파일 §3-8).
 *
 * <p>AC 원문(기획 보충 §S4) 요지: 「칸마다 <b>오류 아닌 것 중 가장 느린 1</b> + 창 안 <b>오류 전부</b>(상한 500 ·
 * 넘치면 오류가 있던 모든 칸에 가장 느린 오류 1 · {@code buckets} 의 칸별 오류 건수는 늘) · 대표 · 오류 · 건수 모두
 * {@code since ≤ start_time < until} · 창 · 서비스 필수 · 칸은 서버가 고름 · 동시 실행 상한 1 · 넘치면 2초 기다린 뒤
 * 503」. 사용자 명시 결정(UD-2 · 확정 ③). CLAUDE.md '데이터 모델 (변경 신중히)' 인용 — 실 SQLite 임시 파일 + Flyway.
 *
 * <p>★각 시험은 <b>「전제」 단언</b>(고정값이 실제로 그 갈래를 만들었는가)을 대조 단언보다 먼저 둔다 — 전제가 없으면
 * 갈래를 안 밟고도 초록이 되는 빈 그물이 된다. 개수만 보는 시험은 두지 않는다 — 대표 {@code traceId} 를 단언한다.
 *
 * <p>★실행 행이 둘 이상인 행은 {@code @ParameterizedTest} 로 짰다 — 한 시험 안에 판을 단언 여럿으로 짜면 판별식이
 * 1 로 센다. 기다림은 걸쇠 · Awaitility 폴링으로만 한다(실시간 잠자기 0 — 부록 E G-R28-19).
 */
class TracePointsQueryTest {

    private static final String SVC = "svc-a";
    private static final String OTHER_SVC = "svc-b";
    /** 1분 창 — 칸 1초. */
    private static final long ONE_MINUTE = 60_000L;
    /** 1시간 창 — 칸 5초. */
    private static final long ONE_HOUR = 3_600_000L;
    /** epoch 정렬된 기준 시각(1초 · 5초 칸 경계 위). */
    private static final long T0 = 1_730_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private TraceQueryRepository repo;
    private TraceQueryService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-points-test-", ".db");
        Files.deleteIfExists(dbFile);
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repo = new TraceQueryRepository(jdbc, mapper);
        service = new TraceQueryService(repo);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── P · 대표 규칙 (AC-28-01-1 · 01-2 · 01-3 · 01-6 · 01-7) ───────────────────────

    /** P01 — 같은 칸의 9 ms 와 10 ms: 걸린 시간을 글자로 견주면 「9」가 이긴다. 0 채움 키라 10 ms 가 이긴다. */
    @Test
    void picksTheSlowerOfNineAndTenMsInTheSameBucket() {
        insert("z-nine", SVC, T0 + 1, 9, false);       // 글자 순서로도 id 순서로도 이 행이 「크다」
        insert("a-ten", SVC, T0 + 999, 10, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1_000L, r.bucketMs(), "전제: 1분 창의 칸은 1초다");
        assertEquals(1, r.buckets().size(), "전제: 두 행이 한 칸에 든다");
        assertEquals(List.of("a-ten"), ids(r.points()), "더 느린 10 ms 쪽이 대표다");
    }

    /** P02 — 걸린 시간이 같으면 trace id 가 큰 쪽. 큰 id 를 칸 앞 · 뒤 어느 시각에 두어도 같다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void breaksTiesByTheLargerTraceIdInEitherInsertOrder(boolean largerIdFirst) {
        insert("t-b", SVC, largerIdFirst ? T0 + 10 : T0 + 900, 50, false);
        insert("t-a", SVC, largerIdFirst ? T0 + 900 : T0 + 10, 50, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1, r.buckets().size(), "전제: 동률 두 행이 한 칸에 든다");
        assertEquals(2L, r.buckets().get(0).count(), "전제: 칸 건수 2");
        assertEquals(List.of("t-b"), ids(r.points()), "동률이면 trace id 가 큰 쪽이 대표다");
    }

    /** P03 — 같은 초의 1,000 ms 오류와 900 ms 정상 → 두 점 모두(RA-R27-02 · CXD-4 를 결과로 닫음). */
    @Test
    void returnsTheSlowErrorAndTheSlowestOkInTheSameSecond() {
        insert("err-1000", SVC, T0 + 100, 1_000, true);
        insert("ok-900", SVC, T0 + 200, 900, false);
        insert("ok-100", SVC, T0 + 300, 100, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1, r.buckets().size(), "전제: 세 행이 한 칸에 든다");
        assertEquals(1L, r.errorCount(), "전제: 오류 하나");
        assertEquals(List.of("ok-900", "err-1000"), ids(r.points()), "칸의 정상 대표 + 오류 전부");
    }

    /** P04 — 같은 입력 두 번 → 응답이 같다(동률 칸 포함). */
    @Test
    void returnsTheSameOutputForTheSameInput() {
        insert("d-1", SVC, T0 + 10, 70, false);
        insert("d-2", SVC, T0 + 20, 70, false);
        insert("d-3", SVC, T0 + 1_500, 30, true);
        insert("d-4", SVC, T0 + 2_500, 80, false);

        TracePointsResponse first = points(T0, T0 + ONE_MINUTE);
        TracePointsResponse second = points(T0, T0 + ONE_MINUTE);

        assertEquals(3, first.buckets().size(), "전제: 칸 셋");
        assertEquals(first, second, "같은 입력 → 같은 응답");
    }

    /** P05 — 칸 셋에 걸친 고정값: 칸 수 = 식 값 · 칸 시작 = epoch 정렬(칸 크기를 long 으로 묶은 정수 나눗셈). */
    @Test
    void countsBucketsForAFixtureSpanningSeveralBuckets() {
        long since = T0 + 2_000;                       // 칸 5초의 한가운데에서 시작
        long[] starts = {since + 1_000, since + 4_100, since + 17_999};
        for (int i = 0; i < starts.length; i++) {
            insert("c-" + i, SVC, starts[i], 10 + i, false);
        }

        TracePointsResponse r = points(since, since + ONE_HOUR);

        assertEquals(5_000L, r.bucketMs(), "전제: 1시간 창의 칸은 5초다");
        Set<Long> expectedStarts = new HashSet<>();
        for (long s : starts) {
            expectedStarts.add(Math.floorDiv(s, 5_000L) * 5_000L);
        }
        assertEquals(3, expectedStarts.size(), "전제: 고정값이 실제로 칸 셋에 걸친다");
        assertEquals(expectedStarts.size(), r.buckets().size(), "칸 수 = 식 값");
        for (TraceBucket b : r.buckets()) {
            assertEquals(0L, b.start() % 5_000L, "칸 시작은 epoch 정렬이다 — " + b);
            assertTrue(expectedStarts.contains(b.start()), "칸 시작 = floor(시각 ÷ 칸) × 칸 — " + b);
        }
    }

    /** P06 — 15자리와 16자리 걸린 시간: 진짜로 더 느린 16자리 쪽. */
    @Test
    void keepsTheTrulySlowerOfFifteenAndSixteenDigitDurations() {
        insert("z-15", SVC, T0 + 1, 999_999_999_999_999L, false);
        insert("a-16", SVC, T0 + 2, 1_000_000_000_000_000L, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1, r.buckets().size(), "전제: 두 행이 한 칸에 든다");
        assertEquals(List.of("a-16"), ids(r.points()));
        assertEquals(1_000_000_000_000_000L, r.points().get(0).durationMs());
    }

    /** P07 — 18자리와 19자리 걸린 시간: 19자리 쪽(서버 응답 객체로 단언 — 화면 JSON 정밀도와 무관). */
    @Test
    void keepsTheTrulySlowerOfEighteenAndNineteenDigitDurations() {
        insert("z-18", SVC, T0 + 1, 999_999_999_999_999_999L, false);
        insert("a-19", SVC, T0 + 2, 1_000_000_000_000_000_000L, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1, r.buckets().size(), "전제: 두 행이 한 칸에 든다");
        assertEquals(List.of("a-19"), ids(r.points()));
        assertEquals(1_000_000_000_000_000_000L, r.points().get(0).durationMs(), "응답 값은 누르지 않은 원래 값");
    }

    /** P08 — 칸의 유일한 행이 음수 걸린 시간이어도 그 칸 대표로 남는다(키에서만 0 · 응답은 원래 값). */
    @ParameterizedTest
    @ValueSource(longs = {-5L, -9_223_372_036_854_775_807L})
    void keepsALoneNegativeDurationRowAsItsBucketRepresentative(long negativeDuration) {
        insert("neg", SVC, T0 + 500, negativeDuration, false);
        insert("other", SVC, T0 + 5_500, 40, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(2, r.buckets().size(), "전제: 칸 둘 · 음수 행은 자기 칸에 홀로 있다");
        TracePoint neg = r.points().stream().filter(p -> "neg".equals(p.traceId())).findFirst().orElseThrow();
        assertEquals(negativeDuration, neg.durationMs(), "응답에는 원래 값이 나간다");
        assertEquals(List.of("neg", "other"), ids(r.points()));
    }

    /** P09 — 창 시작이 칸 한가운데 · 창 밖에 더 느린 정상과 오류가 있어도 대표 · 오류는 창 안에서만. */
    @Test
    void picksTheInWindowTraceWhenASlowerOneSitsOutsideTheWindow() {
        long since = T0 + 500;                          // 1초 칸의 한가운데
        insert("in-ok", SVC, since + 10, 100, false);
        insert("out-slow-ok", SVC, since - 10, 5_000, false);
        insert("out-err", SVC, since - 20, 50, true);

        TracePointsResponse r = points(since, since + ONE_MINUTE);

        assertEquals(1, r.buckets().size(), "전제: 창 안 행이 든 칸 하나");
        assertTrue(r.buckets().get(0).start() < since, "전제: 그 칸의 시작은 창 시작보다 앞이다(가장자리 칸)");
        assertEquals(List.of("in-ok"), ids(r.points()), "창 밖 행은 대표도 오류 점도 못 된다");
        assertEquals(0L, r.errorCount(), "창 밖 오류는 오류 건수에 안 든다");
        assertEquals(1L, r.buckets().get(0).count(), "가장자리 칸의 건수는 창 안 몫만이다");
    }

    /** P10 — {@code start_time = since} 는 들어가고 {@code = until} 은 빠진다. */
    @Test
    void includesSinceAndExcludesUntil() {
        long since = T0;
        long until = T0 + ONE_MINUTE;
        insert("at-since", SVC, since, 10, false);
        insert("before-until", SVC, until - 1, 10, false);
        insert("at-until", SVC, until, 999, true);
        insert("before-since", SVC, since - 1, 999, true);

        TracePointsResponse r = points(since, until);

        assertEquals(2L, r.totalCount(), "손으로 센 창 안 행 = 2");
        assertEquals(Set.of("at-since", "before-until"), Set.copyOf(ids(r.points())));
        assertEquals(0L, r.errorCount());
    }

    /** P11 — 총계를 칸 합과만 견주면 늘 참이다 — 독립 COUNT 질의 둘과 견준다. */
    @Test
    void sumsBucketCountsAndErrorCountsToTheTotals() {
        long since = T0 + 300;
        long until = since + ONE_MINUTE;
        for (int i = 0; i < 40; i++) {
            insert("w-" + i, SVC, since + i * 1_400L, 10 + i % 7, i % 9 == 0);
        }
        insert("outside", SVC, until + 5, 10, true);
        insert("other-svc", OTHER_SVC, since + 50, 10, true);

        TracePointsResponse r = points(since, until);

        long independentTotal = count("SELECT COUNT(*) FROM traces WHERE service_name = ? AND start_time >= ? AND start_time < ?",
                SVC, since, until);
        long independentErrors = count("SELECT COUNT(*) FROM traces WHERE service_name = ? AND start_time >= ? AND start_time < ?"
                + " AND (status = 'ERROR' OR has_error = 1)", SVC, since, until);
        assertTrue(independentErrors > 0, "전제: 창 안 오류가 있다");
        assertEquals(independentTotal, r.totalCount(), "totalCount = 독립 COUNT");
        assertEquals(independentErrors, r.errorCount(), "errorCount = 독립 오류 COUNT");
        assertEquals(independentTotal, r.buckets().stream().mapToLong(TraceBucket::count).sum(), "Σ 칸 건수 = 독립 COUNT");
        assertEquals(independentErrors, r.buckets().stream().mapToLong(TraceBucket::errorCount).sum());
        assertEquals(independentErrors, errorPoints(r).size(), "평소 갈래 오류 점 수 = 독립 오류 COUNT");
    }

    /** P12 — 오류가 상한(500) 이하면 오류 전부 · 「줄여 보냄」 거짓. 경계 499 · 500. */
    @ParameterizedTest
    @ValueSource(ints = {499, 500})
    void returnsEveryErrorUpToTheCap(int errors) {
        long since = T0;
        long until = since + 600_000L;                  // 10분 창 · 1초 칸
        Set<String> errorIds = new HashSet<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < errors; i++) {
            String id = String.format("e-%04d", i);
            errorIds.add(id);
            rows.add(row(id, SVC, since + i * 1_000L + 7, 100 + i % 13, true));
        }
        for (int i = 0; i < 30; i++) {
            rows.add(row(String.format("o-%04d", i), SVC, since + i * 1_000L + 3, 5, false));
        }
        insertAll(rows);

        TracePointsResponse r = points(since, until);

        assertEquals(errors, count("SELECT COUNT(*) FROM traces WHERE service_name = ? AND start_time >= ? AND start_time < ?"
                + " AND (status = 'ERROR' OR has_error = 1)", SVC, since, until), "전제: 창 안 오류 실제 수");
        assertFalse(r.errorsReduced(), "상한 이하면 줄여 보내지 않는다");
        assertEquals(errorIds, Set.copyOf(ids(errorPoints(r))), "오류 전부");
        assertEquals(errors, r.errorCount());
        assertEquals(r.errorCount(), r.buckets().stream().mapToLong(TraceBucket::errorCount).sum(), "Σ 칸 오류 = errorCount");
    }

    /** P13 — 오류가 상한을 넘으면 오류가 있던 <b>모든</b> 칸에 그 칸의 가장 느린 오류 하나(최신 N건 자르기 0). */
    @ParameterizedTest
    @ValueSource(ints = {501, 1_200})
    void reducesErrorsToTheSlowestPerBucketAboveTheCap(int errors) {
        long since = T0;
        long until = since + ONE_HOUR;                  // 칸 5초
        long[] bucketStarts = {since, since + 1_800_000L, since + ONE_HOUR - 5_000L};   // 왼 · 가운데 · 오른
        Map<Long, String> slowestPerBucket = new HashMap<>();
        Map<Long, Long> slowestDuration = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < errors; i++) {
            long bucket = bucketStarts[i % 3];
            long duration = 10L + (i * 37L) % 997L + i;   // 칸 안에서 서로 다른 값
            String id = String.format("e-%05d", i);
            rows.add(row(id, SVC, bucket + (i % 5_000), duration, true));
            if (duration > slowestDuration.getOrDefault(bucket, -1L)) {
                slowestDuration.put(bucket, duration);
                slowestPerBucket.put(bucket, id);
            }
        }
        rows.add(row("ok-left", SVC, since + 1, 3, false));
        insertAll(rows);

        TracePointsResponse r = points(since, until);

        assertEquals(5_000L, r.bucketMs(), "전제: 칸 5초");
        assertEquals(errors, r.errorCount(), "전제: 창 안 오류 실제 수가 상한을 넘는다");
        assertTrue(r.errorCount() > TraceQueryService.POINTS_ERRORS_MAX, "전제: 상한 위");
        assertTrue(r.errorsReduced(), "줄여 보냈다 표시");
        long bucketsWithErrors = r.buckets().stream().filter(b -> b.errorCount() > 0).count();
        assertEquals(3L, bucketsWithErrors, "전제: 오류가 왼 · 가운데 · 오른 칸에 있다");
        Map<Long, String> actual = errorPoints(r).stream()
                .collect(Collectors.toMap(p -> Math.floorDiv(p.startTime(), r.bucketMs()) * r.bucketMs(), TracePoint::traceId));
        assertEquals(slowestPerBucket, actual, "오류가 있던 모든 칸에 그 칸의 가장 느린 오류 하나");
        assertEquals(List.of("ok-left"), ids(okPoints(r)), "정상 대표는 그대로");
    }

    // ─── P · 필터 · HTTP 경로 (AC-28-03-5) ────────────────────────────────────────

    /**
     * P14 — HTTP 경로(standalone MockMvc + 실 서비스 + 실 저장소 + 임시 SQLite). 서비스 · 상태 · 검색어를 하나씩 걸어
     * 대표 점 응답과 같은 조건 목록 응답을 견준다 — 컨트롤러가 {@code q} · {@code status} 를 서비스로 안 넘기면 빨강.
     * 인증 필터는 안 거친다(인증 = 통합 시험).
     */
    @Test
    void appliesServiceStatusAndQToPointsCountsBucketsAndErrors() throws Exception {
        long since = T0;
        long until = T0 + ONE_MINUTE;
        insertOp("m-ok", SVC, T0 + 100, 50, false, "GET /orders/{id}");
        insertOp("n-ok-slow", SVC, T0 + 200, 900, false, "GET /payments");          // 같은 칸 · 더 느림 · 검색어 안 맞음
        insertOp("m-err", SVC, T0 + 300, 70, true, "POST /orders");
        insertOp("n-err", SVC, T0 + 400, 80, true, "POST /payments");
        insertOp("m-ok-2", SVC, T0 + 3_100, 20, false, "GET /orders");
        insertOp("x-other", OTHER_SVC, T0 + 150, 5_000, true, "GET /orders/{id}");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(service)).build();

        String[] filters = {"", "&status=ERROR", "&status=OK", "&q=orders", "&q=orders&status=ERROR", "&q=%20orders%20"};
        int compared = 0;
        for (String f : filters) {
            JsonNode p = json(mvc, "/v1/trace-points?service=" + SVC + "&since=" + since + "&until=" + until + f);
            JsonNode list = json(mvc, "/v1/traces?service=" + SVC + "&since=" + since + "&until=" + until + "&limit=500" + f);
            assertTrue(list.path("traces").size() > 0, "전제: 조건 " + f + " 에 맞는 행이 있다(빈 대 빈 비교 0)");
            assertExpectedFromList(p, list.path("traces"), f);
            compared++;
        }
        assertEquals(filters.length, compared, "전제: 조건 여섯을 모두 견줬다");
        JsonNode qOnly = json(mvc, "/v1/trace-points?service=" + SVC + "&since=" + since + "&until=" + until + "&q=orders");
        assertTrue(textValues(qOnly.path("points"), "traceId").contains("m-ok"),
                "전제: 검색어가 걸리면 더 느린 비일치 행 대신 일치 행이 대표다(넘기지 않으면 n-ok-slow)");
    }

    /** P15 — 검색어의 % · _ 는 글자 그대로 맞춘다(이스케이프 = 목록 경로와 같은 한 곳). */
    @ParameterizedTest
    @CsvSource({"%25,pct", "_,und"})
    void matchesPercentAndUnderscoreLiterallyInQ(String encodedQ, String expectedId) throws Exception {
        insertOp("pct", SVC, T0 + 100, 10, false, "GET /a%b");
        insertOp("und", SVC, T0 + 2_100, 10, false, "GET /a_b");
        insertOp("plain", SVC, T0 + 4_100, 10, false, "GET /axb");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(service)).build();

        JsonNode all = json(mvc, "/v1/trace-points?service=" + SVC + "&since=" + T0 + "&until=" + (T0 + ONE_MINUTE));
        assertEquals(3, all.path("totalCount").asInt(), "전제: 세 행이 다 창 안에 있다");
        JsonNode p = json(mvc, "/v1/trace-points?service=" + SVC + "&since=" + T0 + "&until=" + (T0 + ONE_MINUTE) + "&q=" + encodedQ);

        assertEquals(1, p.path("totalCount").asInt(), "글자 그대로 한 행만 맞는다");
        assertEquals(List.of(expectedId), textValues(p.path("points"), "traceId"));
    }

    // ─── P · 기준 질의 · 다시 쓰임 · 실행 계획 (AC-28-01-10 · 01-11 · RC-6) ──────────────────

    /** P16 — 동률이 많은 고정값에서 창 함수 기준 질의와 칸마다 같은 대표(정상 · 오류). 걸린 시간은 0 이상만. */
    @Test
    void picksTheSameTraceAsTheWindowFunctionReferenceOnTiedData() {
        long since = T0;
        long until = T0 + ONE_MINUTE;
        Random random = new Random(28L);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            String id = Long.toHexString(random.nextLong());
            rows.add(row("t" + id, SVC, since + random.nextInt((int) ONE_MINUTE), random.nextInt(4), random.nextInt(10) == 0));
        }
        insertAll(rows);
        long bucketMs = TraceQueryService.validateAndPickBucket(SVC, since, until);
        long tiedBuckets = count("""
                SELECT COUNT(*) FROM (
                    SELECT start_time / ? AS b, MAX(duration_ms) AS m FROM traces
                     WHERE service_name = ? AND start_time >= ? AND start_time < ?
                       AND NOT (status = 'ERROR' OR has_error = 1)
                     GROUP BY b) g
                 WHERE (SELECT COUNT(*) FROM traces t
                         WHERE t.service_name = ? AND t.start_time >= ? AND t.start_time < ?
                           AND NOT (t.status = 'ERROR' OR t.has_error = 1)
                           AND t.start_time / ? = g.b AND t.duration_ms = g.m) >= 2
                """, bucketMs, SVC, since, until, SVC, since, until, bucketMs);
        assertTrue(tiedBuckets > 0, "전제: 정상 대표가 동률인 칸이 있다 — 실제 " + tiedBuckets);

        // 오류 대표까지 칸마다 견주려고 오류 상한 0 으로 넘침 갈래를 늘 켠다(저장소 직접 · 문장 동일성 시험).
        List<TraceQueryRepository.PointRow> rowsOut = repo.findTracePoints(SVC, since, until, null, null, bucketMs, 0);
        Map<String, String> actual = new TreeMap<>();
        for (TraceQueryRepository.PointRow row : rowsOut) {
            if (!row.bucketRow()) {
                boolean err = row.status() == SpanStatus.ERROR || row.hasError();
                actual.put(Math.floorDiv(row.startTime(), bucketMs) + (err ? "/err" : "/ok"), row.traceId());
            }
        }
        Map<String, String> reference = new TreeMap<>();
        jdbc.query("""
                SELECT b, err, trace_id FROM (
                    SELECT start_time / ? AS b
                         , CASE WHEN status = 'ERROR' OR has_error = 1 THEN 1 ELSE 0 END AS err
                         , trace_id
                         , ROW_NUMBER() OVER (PARTITION BY start_time / ?, CASE WHEN status = 'ERROR' OR has_error = 1 THEN 1 ELSE 0 END
                                              ORDER BY duration_ms DESC, trace_id DESC) AS rn
                      FROM traces
                     WHERE service_name = ? AND start_time >= ? AND start_time < ?)
                 WHERE rn = 1
                """, rs -> {
            reference.put(rs.getLong("b") + (rs.getInt("err") == 1 ? "/err" : "/ok"), rs.getString("trace_id"));
        }, bucketMs, bucketMs, SVC, since, until);
        assertTrue(reference.size() > 60, "전제: 칸 × 정상/오류 갈래가 넉넉하다 — " + reference.size());
        assertEquals(reference, actual, "칸마다 같은 대표(정상 · 오류)");
    }

    /** P17 — 점 순서: 정상 먼저 · 오류 나중 · 그 안에서 시작 시각 · trace id 오름차순. */
    @Test
    void ordersPointsOkFirstThenErrorsByStartTimeAndTraceId() {
        insert("ok-b", SVC, T0 + 3_000, 10, false);
        insert("ok-a", SVC, T0 + 1_000, 10, false);
        insert("err-z", SVC, T0 + 500, 10, true);
        insert("err-b", SVC, T0 + 2_000, 10, true);
        insert("err-a", SVC, T0 + 2_000, 10, true);
        insert("ok-c", SVC, T0 + 5_000, 10, false);

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(6, r.points().size(), "전제: 정상 대표 셋(칸 셋) + 오류 셋");
        assertEquals(List.of("ok-a", "ok-b", "ok-c", "err-z", "err-a", "err-b"), ids(r.points()));
    }

    /** P18 — 요약 행이 다시 쓰이면 다음 호출에 그대로 반영된다(닫힌 칸을 기억하지 않는다). */
    @Test
    void reflectsARewrittenSummaryRowOnTheNextCall() {
        insert("x", SVC, T0 + 100, 100, false);
        insert("y", SVC, T0 + 200, 50, false);
        TracePointsResponse before = points(T0, T0 + ONE_MINUTE);
        assertEquals(List.of("x"), ids(before.points()), "전제: 처음에는 x 가 정상 대표다");

        jdbc.update("""
                INSERT OR REPLACE INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                               status, span_count, service_count, has_error, received_at)
                VALUES ('x', 'GET /op', ?, ?, 5000, 'ERROR', 3, 1, 1, ?)
                """, SVC, T0 + 100, T0 + 100);

        TracePointsResponse after = points(T0, T0 + ONE_MINUTE);
        assertEquals(List.of("y", "x"), ids(after.points()), "정상 대표는 y · x 는 오류 점");
        assertEquals(5_000L, errorPoints(after).get(0).durationMs(), "다시 쓴 걸린 시간이 그대로");
        assertEquals(1L, after.errorCount());
    }

    /**
     * P19 — {@code ANALYZE} 뒤 실행 계획에 서비스 · 시각 인덱스가 있고 {@code spans} 는 안 읽는다. 작은 고정값에서는
     * 계획이 표 훑기로 바뀌므로 서비스 10개를 고르게 · 창은 전체 행의 10% 이하 · 조건 없는 갈래만 본다(갈래 전부는
     * 30일 규모 측정 틀 몫).
     */
    @Test
    void usesTheServiceStartIndexAndNeverReadsSpans() {
        List<Object[]> rows = new ArrayList<>();
        for (int s = 0; s < 10; s++) {
            for (int i = 0; i < 600; i++) {
                rows.add(row("p" + s + "-" + i, "svc-" + s, T0 + i * 1_000L, i % 50, i % 40 == 0));
            }
        }
        insertAll(rows);
        jdbc.execute("ANALYZE");
        long since = T0 + 120_000L;
        long until = since + ONE_MINUTE;
        long windowRows = count("SELECT COUNT(*) FROM traces WHERE service_name = 'svc-3' AND start_time >= ? AND start_time < ?",
                since, until);
        long allRows = count("SELECT COUNT(*) FROM traces");
        assertTrue(windowRows * 10 <= allRows, "전제: 창이 전체 행의 10% 이하다 — " + windowRows + "/" + allRows);
        assertEquals(10L, count("SELECT COUNT(DISTINCT service_name) FROM traces"), "전제: 서비스 10개");

        long bucketMs = TraceQueryService.validateAndPickBucket("svc-3", since, until);
        int cap = TraceQueryService.POINTS_ERRORS_MAX;
        List<String> plan = jdbc.query("EXPLAIN QUERY PLAN " + TraceQueryRepository.pointsSql(false, false),
                (rs, n) -> rs.getString("detail"), bucketMs, "svc-3", since, until, cap, "svc-3", since, until, cap);

        assertTrue(plan.stream().anyMatch(d -> d.contains("idx_traces_service_start")), "서비스 · 시각 인덱스 — " + plan);
        assertTrue(plan.stream().anyMatch(d -> d.contains("sqlite_autoindex_traces_1")), "기본 키 되읽기 — " + plan);
        assertTrue(plan.stream().noneMatch(d -> d.contains("spans")), "spans 0 — " + plan);
    }

    /** P20 — 창을 밀어도 겹치는 안쪽 칸의 대표는 같다(옛 화면 시험 X-04 를 서버로 옮김). */
    @Test
    void keepsInteriorBucketRepresentativesStableAcrossShiftedWindows() {
        for (int i = 0; i < 40; i++) {
            insert("s-" + i, SVC, T0 + i * 700L, (i * 13) % 17, false);
            insert("s2-" + i, SVC, T0 + i * 700L + 300, (i * 7) % 17, false);
        }
        TracePointsResponse a = points(T0, T0 + ONE_MINUTE);
        TracePointsResponse b = points(T0 + 3_300, T0 + 3_300 + ONE_MINUTE);

        Map<Long, String> repsA = okRepsByBucket(a);
        Map<Long, String> repsB = okRepsByBucket(b);
        Set<Long> interior = new HashSet<>(repsA.keySet());
        interior.retainAll(repsB.keySet());
        interior.removeIf(start -> start < T0 + 4_000 || start + 1_000 > T0 + ONE_MINUTE);  // 두 창 모두에 온전히 든 칸
        assertTrue(interior.size() >= 1, "전제: 겹치는 안쪽 칸이 있다 — " + interior.size());
        for (long start : interior) {
            assertEquals(repsA.get(start), repsB.get(start), "안쪽 칸 " + start + " 의 대표가 같다");
        }
    }

    /** P21 — 빈 창 → 점 0 · 칸 0 · 총계 0(정답 자리가 0 · 옛 X-05 를 서버로 옮김). */
    @Test
    void returnsNoPointsAndZeroTotalsForAnEmptyWindow() {
        insert("before", SVC, T0 - 1, 10, true);
        insert("after", SVC, T0 + ONE_MINUTE, 10, true);
        insert("other", OTHER_SVC, T0 + 10, 10, true);
        assertEquals(3L, count("SELECT COUNT(*) FROM traces"), "전제: 창 밖 · 다른 서비스에는 행이 있다");

        TracePointsResponse r = points(T0, T0 + ONE_MINUTE);

        assertEquals(1_000L, r.bucketMs());
        assertEquals(0L, r.totalCount());
        assertEquals(0L, r.errorCount());
        assertFalse(r.errorsReduced());
        assertTrue(r.points().isEmpty());
        assertTrue(r.buckets().isEmpty());
    }

    // ─── V · 요청 검증과 칸 크기 (AC-28-01-5 · RC-3) ─────────────────────────────────

    /** V01 — 창 끝이 빠지면 400 갈래. */
    @ParameterizedTest
    @CsvSource(value = {"null,1000", "0,null"}, nullValues = "null")
    void rejectsMissingSinceOrUntil(Long since, Long until) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listTracePoints(SVC, since, until, null, null));
        assertEquals("since and until are required", e.getMessage());
    }

    /** V02 — 역전 · 빈 창은 400 갈래 · 1 ms 창은 받는다. */
    @ParameterizedTest
    @CsvSource({"5,4", "5,5"})
    void rejectsReversedOrEmptyWindowsAndAcceptsOneMs(long since, long until) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listTracePoints(SVC, since, until, null, null));
        assertEquals("since must be >= 0 and less than until", e.getMessage());
        assertEquals(1_000L, service.listTracePoints(SVC, since, since + 1, null, null).bucketMs(), "1 ms 창은 받는다");
    }

    /** V03 — 음수 since · long 양 끝은 창 길이를 셈하기 전에 400 갈래. */
    @ParameterizedTest
    @CsvSource({"-1,1000", "-9223372036854775808,9223372036854775807"})
    void rejectsNegativeSinceIncludingTheLongExtremes(long since, long until) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listTracePoints(SVC, since, until, null, null));
        assertEquals("since must be >= 0 and less than until", e.getMessage());
    }

    /** V04 — 창 상한 = 7일 + 10초 받음 · 1 ms 더 → 400 갈래. */
    @ParameterizedTest
    @CsvSource({"604810000,true", "604810001,false"})
    void acceptsSevenDaysPlusSlackAndRejectsOneMsMore(long window, boolean accepted) {
        long since = T0;
        assertEquals(604_810_000L, TraceQueryService.POINTS_MAX_WINDOW_MS, "전제: 창 상한 상수");
        if (accepted) {
            assertEquals(900_000L, service.listTracePoints(SVC, since, since + window, null, null).bucketMs());
        } else {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.listTracePoints(SVC, since, since + window, null, null));
            assertEquals("window must be at most 7 days", e.getMessage());
        }
    }

    /** V05 — 서비스가 없거나 공백이면 400 갈래. */
    @ParameterizedTest
    @CsvSource(value = {"null", "'   '"}, nullValues = "null")
    void rejectsMissingOrBlankService(String svc) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.listTracePoints(svc, T0, T0 + ONE_MINUTE, null, null));
        assertEquals("service is required", e.getMessage());
    }

    /** V06 — 창 길이별 칸 크기(사다리 · 목표 720 · 여유 10초) · 1시간 · 24시간 칸 단 끝 경계(B-19 · B-20). */
    @ParameterizedTest
    @CsvSource({
            "68000,1000", "308000,1000", "608000,1000", "730000,1000", "730001,2000",
            "3600000,5000", "3608000,5000", "3610000,5000", "3610001,10000",
            "86400000,120000", "86408000,120000", "86410000,120000", "86410001,300000",
            "604800000,900000", "604808000,900000"})
    void picksBucketSizesPerWindow(long window, long expectedBucketMs) {
        assertEquals(expectedBucketMs, TraceQueryService.validateAndPickBucket(SVC, T0, T0 + window));
    }

    /** V07 — 731 칸을 다 채운 최악 고정값(칸마다 정상 · 오류 · 오류 > 500) → 칸 ≤ 731 · 점 ≤ 1,462(구조 상한). */
    @Test
    void boundsBucketsAndPointsByTheStructuralCaps() {
        long since = T0 + 500;                          // 칸 한가운데 → 양 끝 칸이 걸친다
        long until = since + 730_000L;                  // 1초 칸의 마지막 창
        List<Object[]> rows = new ArrayList<>();
        long firstBucket = Math.floorDiv(since, 1_000L);
        long lastBucket = Math.floorDiv(until - 1, 1_000L);
        for (long b = firstBucket; b <= lastBucket; b++) {
            long at = Math.max(since, b * 1_000L);
            rows.add(row("ok-" + b, SVC, at, 10, false));
            rows.add(row("er-" + b, SVC, at, 20, true));
        }
        insertAll(rows);

        TracePointsResponse r = points(since, until);

        assertEquals(1_000L, r.bucketMs(), "전제: 1초 칸");
        assertEquals(TraceQueryService.POINTS_BUCKETS_MAX, r.buckets().size(), "전제: 칸 상한까지 다 채웠다");
        assertTrue(r.errorsReduced(), "전제: 오류가 상한을 넘었다");
        assertTrue(r.buckets().size() <= 731, "칸 ≤ 731");
        assertTrue(r.points().size() <= 1_462, "점 ≤ 1,462");
        assertEquals(TraceQueryService.POINTS_MAX, r.points().size(), "최악 고정값은 구조 상한에 정확히 닿는다");
    }

    /** V08 — 검증은 허가 <b>전</b>. 허가를 쥔 상태에서 since 없는 호출 → 곧바로 400 갈래(바쁨이 아님). */
    @Test
    void validatesBeforeTakingThePermit() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesInFlight() == 1);
            assertEquals(1, svc.pointsQueriesInFlight(), "전제: 허가를 쥔 질의가 하나 돈다");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> svc.listTracePoints(SVC, null, T0, null, null, 2_000L, System::nanoTime));
            assertEquals("since and until are required", e.getMessage(), "바쁨이 아니라 검증 거절");
            assertEquals(0, svc.pointsQueriesWaiting(), "허가 앞에서 기다린 호출 0");
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    // ─── C · 동시 실행 상한 (AC-28-01-8 · 01-9 · RC-4) ────────────────────────────

    /**
     * C01 — 서버 전체에서 대표 점 질의는 동시에 하나만 돈다. 전제 = 도는 수 1 그리고 기다리는 수 1.
     *
     * <p>// [Phase R29] R29/AC-R29-31 — 기다리는 호출을 둘 → 하나로 줄였다(설계 결정 DD-R29-14). 「기다리는 둘이 다 돈다」는
     * // R29 계약 변경(기다리는 요청 중 가장 새것만)으로 사라졌고 그 축은 {@code supersedesAnOlderWaitingPointsRequestWithTheNewestOne}
     * // 이 잰다. 이 시험은 「동시 최대 1 · 둘 다 돎」만 잰다.
     */
    @Test
    void neverRunsMoreThanOnePointsQueryAtOnce() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<TracePointsResponse>> calls = new ArrayList<>();
            calls.add(pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime)));
            await().atMost(Duration.ofSeconds(10)).until(() -> blocking.running.get() == 1);
            calls.add(pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_HOUR, null, null, 30_000L, System::nanoTime)));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesWaiting() == 1);
            assertEquals(1, blocking.running.get(), "전제: 도는 수 = 1");
            assertEquals(1, svc.pointsQueriesWaiting(), "전제: 기다리는 수 = 1");
            assertEquals(1, svc.pointsQueriesInFlight(), "전제: 허가를 쥔 질의 1");

            blocking.open();
            for (Future<TracePointsResponse> f : calls) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertEquals(2, blocking.calls.get(), "두 호출이 다 돌았다");
            assertEquals(1, blocking.maxRunning.get(), "동시 최대 1");
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    /** C02 — 허가를 쥔 채 기다림 0 호출 → 바쁨 + INFO 한 줄(값 = 설정 상한). */
    @Test
    void returnsBusyWhenThePermitIsHeldPastTheWait() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesInFlight() == 1);

            List<ILoggingEvent> events = captureServiceLog(() -> assertThrows(TraceQueryService.PointsBusyException.class,
                    () -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime)));

            List<String> busy = lines(events, Level.INFO).stream().filter(l -> l.startsWith("trace points busy:")).toList();
            assertEquals(List.of("trace points busy: waitLimitMs=0"), busy);
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    /** C03 — 저장소가 두 번 던진 뒤에도 허가가 돌아와 새 호출이 돈다(finally 반납). */
    @Test
    void givesThePermitBackAfterRepositoryExceptions() {
        AtomicInteger failuresLeft = new AtomicInteger(2);
        TraceQueryRepository failing = new TraceQueryRepository(jdbc, mapper) {
            @Override
            public List<PointRow> findTracePoints(String s, long since, long until, SpanStatus status, String q,
                                                  long bucketMs, int errorsMax) {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new IllegalStateException("repository failure for the test");
                }
                return super.findTracePoints(s, since, until, status, q, bucketMs, errorsMax);
            }
        };
        TraceQueryService svc = new TraceQueryService(failing);
        insert("after", SVC, T0 + 1, 10, false);

        assertThrows(IllegalStateException.class, () -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime));
        assertThrows(IllegalStateException.class, () -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime));
        assertEquals(0, svc.pointsQueriesInFlight(), "전제: 던진 두 호출이 허가를 쥐고 있지 않다");

        TracePointsResponse r = svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime);
        assertEquals(List.of("after"), ids(r.points()), "기다림 0 으로도 새 호출이 돈다");
    }

    /** C04 — 기다림 안에 풀리면 허가를 얻는다(실시간 대기 0 · 걸쇠만). 전제 = 기다리는 수 1. */
    @Test
    void acquiresThePermitWhenItIsReleasedWithinTheWait() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TracePointsResponse> first =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> blocking.running.get() == 1);
            Future<TracePointsResponse> second =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesWaiting() == 1);
            assertEquals(1, svc.pointsQueriesWaiting(), "전제: 뒤따른 호출이 허가 앞에서 기다리는 중");

            blocking.open();
            first.get(10, TimeUnit.SECONDS);
            TracePointsResponse r = second.get(10, TimeUnit.SECONDS);
            assertEquals(1_000L, r.bucketMs(), "기다리던 호출이 바쁨 없이 돌았다");
            assertEquals(2, blocking.calls.get());
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    /**
     * C05 — 연결 산수(시험 전용 Hikari 풀 · 최대 4 · 연결 대기 250 ms). 판 ① 대표 점 1 + 뺏긴 분석 2 = 연결 3 을 쥔 채
     * 두 스레드가 연결 얻기 + 짧은 INSERT + 반납 → 둘 다 1초 안. 판 ② 판정력 — 4 를 다 쥐면 연결 얻기가 실패한다.
     * 실제 수신 보호 판정은 배포 전 측정(GATE-Q1)이다 — 이 시험은 산수를 잴 뿐 질의 시간과 무관하다.
     */
    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void ingestGetsAConnectionWhilePointsAndAnalysisHoldTheirs(int held) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?journal_mode=WAL&busy_timeout=10000");
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(250L);
        config.setPoolName("points-c05");
        List<Connection> holding = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (HikariDataSource hikari = new HikariDataSource(config)) {
            for (int i = 0; i < held; i++) {
                holding.add(hikari.getConnection());
            }
            assertEquals(held, hikari.getHikariPoolMXBean().getActiveConnections(), "전제: 풀 활성 연결 = " + held);

            if (held == 4) {
                assertThrows(SQLTransientConnectionException.class, hikari::getConnection,
                        "넷을 다 쥐면 수신이 연결을 못 얻는다(막을 장치 없음 · 설계 R-14 의 기록)");
                return;
            }
            List<Future<Long>> ingests = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                String id = "ingest-" + t;
                ingests.add(pool.submit(() -> {
                    long started = System.nanoTime();
                    try (Connection c = hikari.getConnection(); Statement st = c.createStatement()) {
                        st.executeUpdate("INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,"
                                + " status, span_count, service_count, has_error, received_at)"
                                + " VALUES ('" + id + "', 'GET /x', 'svc', 1, 1, 'OK', 1, 1, 0, 1)");
                    }
                    return (System.nanoTime() - started) / 1_000_000L;
                }));
            }
            for (Future<Long> f : ingests) {
                long ms = f.get(10, TimeUnit.SECONDS);
                assertTrue(ms < 1_000L, "수신 흉내 한 건 < 1,000 ms — 실제 " + ms);
            }
            assertEquals(2L, count("SELECT COUNT(*) FROM traces WHERE trace_id LIKE 'ingest-%'"), "두 건 다 들어갔다");
        } finally {
            for (Connection c : holding) {
                c.close();
            }
            shutdown(pool);
        }
    }

    /** C06 — 느린 줄은 1,000 ms 이상일 때만 · INFO. 999 → 0줄 · 1,000 → 1줄. */
    @ParameterizedTest
    @CsvSource({"999,0", "1000,1"})
    void logsTheSlowLineOnlyAtOrAboveOneSecond(long elapsedMs, int expectedLines) {
        insert("one", SVC, T0 + 1, 10, false);
        Iterator<Long> ticks = List.of(0L, elapsedMs * 1_000_000L).iterator();
        LongSupplier clock = ticks::next;

        List<ILoggingEvent> events = captureServiceLog(
                () -> service.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, clock));

        List<ILoggingEvent> slow = events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("trace points slow:")).toList();
        assertEquals(expectedLines, slow.size());
        if (expectedLines == 1) {
            assertEquals(Level.INFO, slow.get(0).getLevel());
            assertEquals("trace points slow: elapsedMs=1000 windowMs=60000 bucketMs=1000 totalCount=1",
                    slow.get(0).getFormattedMessage());
        }
    }

    /** C07 — 바쁨으로 떠난 호출은 허가를 반납하지 않는다(누수 0 · 같은 서비스에서 상한 1 유지). */
    @Test
    void neverLeaksAPermitOnBusyRejections() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<TracePointsResponse> holder =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesInFlight() == 1);
            assertEquals(1, svc.pointsQueriesInFlight(), "전제: 허가를 쥔 질의가 하나 돈다");
            for (int i = 0; i < 3; i++) {
                assertThrows(TraceQueryService.PointsBusyException.class,
                        () -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime));
            }
            blocking.open();
            holder.get(10, TimeUnit.SECONDS);
            assertEquals(0, svc.pointsQueriesInFlight(), "바쁨 셋 뒤에도 허가 수는 원래대로(음수 = 얻지 못한 허가를 반납한 것)");
            assertEquals(1, blocking.calls.get(), "전제: 저장소까지 간 호출은 쥐고 있던 하나뿐");

            // 같은 서비스에서 다시 쥔 채 기다림 0 → 여전히 바쁨(새 허가가 생겼다면 이 호출이 통과한다).
            blocking.rearm();
            Future<TracePointsResponse> holderAgain =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesInFlight() == 1);
            assertThrows(TraceQueryService.PointsBusyException.class,
                    () -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 0L, System::nanoTime));
            blocking.open();
            holderAgain.get(10, TimeUnit.SECONDS);
            assertEquals(1, blocking.maxRunning.get(), "동시 최대 1");
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    /**
     * [Phase R29] R29/AC-R29-31 verbatim: "범위를 연달아 바꿨을 때 마지막 요청이 앞 대기 때문에 503 을 받지 않는다".
     * 설계 결정 DD-R29-11 · DD-R29-12(기다리는 요청 중 가장 새것만 · 앞 대기는 503 · 저장소에 안 감).
     *
     * <p>순서 고정: A 쥠 → B 제출 → 기다리는 수 1 → C 제출 → 기다리는 수 2 → 연다. 걸쇠 · 폴링만(실시간 잠자기 0).
     * 끝에 허가 수 0 을 재 이중 반납 · 누수를 함께 본다. 기다림 0 호출은 섞지 않는다(차례표 미세 창 · 설계 한계 ⓓ).
     */
    @Test
    void supersedesAnOlderWaitingPointsRequestWithTheNewestOne() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService svc = new TraceQueryService(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<TracePointsResponse> a =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> blocking.running.get() == 1);
            Future<TracePointsResponse> b =
                    pool.submit(() -> svc.listTracePoints(SVC, T0, T0 + ONE_HOUR, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesWaiting() == 1);
            assertEquals(1, svc.pointsQueriesWaiting(), "전제: B(1시간 창)가 허가 앞에서 기다린다");
            Future<TracePointsResponse> c =
                    pool.submit(() -> svc.listTracePoints(SVC, T0 + 5, T0 + 600_000L, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> svc.pointsQueriesWaiting() == 2);
            assertEquals(2, svc.pointsQueriesWaiting(), "전제: B · C(10분 창) 둘이 기다린다");

            List<ILoggingEvent> events = captureServiceLog(() -> {
                blocking.open();
                try {
                    a.get(10, TimeUnit.SECONDS);
                    ExecutionException bOutcome = assertThrows(ExecutionException.class, () -> b.get(10, TimeUnit.SECONDS));
                    assertTrue(bOutcome.getCause() instanceof TraceQueryService.PointsBusyException,
                            "앞선 대기 B 는 바쁨(503) — 실제: " + bOutcome.getCause());
                    TracePointsResponse cr = c.get(10, TimeUnit.SECONDS);
                    assertEquals(1_000L, cr.bucketMs(), "가장 새 요청 C 가 자기 창(10분 · 칸 1초)으로 돌았다");
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });

            assertEquals(2, blocking.calls.get(), "저장소 호출 = A · C 둘(대체된 B 는 저장소에 안 간다)");
            assertEquals(1, blocking.maxRunning.get(), "동시 최대 1");
            List<String> info = lines(events, Level.INFO);
            assertEquals(1, info.stream().filter(l -> l.equals("trace points superseded: newer request waiting")).count(),
                    "대체 줄 한 줄 — 실제: " + info);
            assertEquals(0, info.stream().filter(l -> l.startsWith("trace points busy:")).count(),
                    "대체는 바쁨 줄을 안 찍는다 — 실제: " + info);
            assertEquals(0, svc.pointsQueriesInFlight(), "허가 수 원래대로(이중 반납 0 · 누수 0)");
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    // ─── H · HTTP 계약 (AC-28-01-5 · 01-8 · §2.1) ────────────────────────────────

    /**
     * [Phase R29] R29/AC-R29-33 verbatim: "형 변환 실패가 고정 영어 문장 400 {@code {error}} 다. 입력값({@code abc} ·
     * {@code FOO})과 패키지 이름({@code io.apilens})이 안 실린다". 대표 점 2 + 목록 3 = 실행 5. SEC-R28-01.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/trace-points?service=svc-a&since=abc&until=1000",
            "/v1/trace-points?service=svc-a&since=0&until=1000&status=FOO",
            "/v1/traces?since=abc",
            "/v1/traces?status=FOO",
            "/v1/traces?limit=abc"})
    void returnsAFixedSentenceForUnconvertibleParameters(String url) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(service)).build();

        MvcResult res = mvc.perform(getRaw(url)).andExpect(status().isBadRequest()).andReturn();

        String raw = res.getResponse().getContentAsString();
        JsonNode body = mapper.readTree(raw);
        assertEquals(1, body.size(), "본문은 error 한 키 — " + raw);
        assertEquals("invalid parameter value", body.path("error").asText(), "고정 문장 — " + raw);
        assertFalse(raw.contains("abc"), "입력값이 안 실린다 — " + raw);
        assertFalse(raw.contains("FOO"), "입력값이 안 실린다 — " + raw);
        assertFalse(raw.contains("io.apilens"), "패키지 이름이 안 실린다 — " + raw);
    }

    /** H01 — 거절 입력 아홉 → 각 400 · 본문 {@code {"error": 영어 고정 문장 넷 중 하나}} · 요청 값이 문장에 없다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "service=svc-a&until=1000",
            "service=svc-a&since=0",
            "service=svc-a&since=5&until=4",
            "service=svc-a&since=5&until=5",
            "service=svc-a&since=-1&until=1000",
            "service=svc-a&since=-9223372036854775808&until=9223372036854775807",
            "service=svc-a&since=0&until=604810001",
            "since=0&until=1000",
            "service=%20%20&since=0&until=1000"})
    void returns400WithAnErrorBodyForEachRejectedInput(String query) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(service)).build();

        MvcResult res = mvc.perform(getRaw("/v1/trace-points?" + query)).andExpect(status().isBadRequest()).andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        String error = body.path("error").asText();
        assertTrue(Set.of("since and until are required", "since must be >= 0 and less than until",
                "window must be at most 7 days", "service is required").contains(error), "고정 문장 넷 중 하나 — " + error);
        assertEquals(1, body.size(), "본문은 error 한 키");
        assertFalse(error.matches(".*\\d{2,}.*"), "요청 값(숫자)이 문장에 없다 — " + error);
    }

    /** H02 — 바쁨 → 503 · 본문 {@code {"error": …}}. 시험에서는 기다림 0 갈래를 거쳐 실시간 대기 없이 잰다. */
    @Test
    void returns503WithAnErrorBodyWhenBusy() throws Exception {
        BlockingRepository blocking = new BlockingRepository(jdbc, mapper);
        TraceQueryService busyAfterNoWait = new TraceQueryService(blocking) {
            @Override
            public TracePointsResponse listTracePoints(String s, Long since, Long until, SpanStatus st, String q) {
                return listTracePoints(s, since, until, st, q, 0L, System::nanoTime);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> busyAfterNoWait.listTracePoints(SVC, T0, T0 + ONE_MINUTE, null, null, 30_000L, System::nanoTime));
            await().atMost(Duration.ofSeconds(10)).until(() -> busyAfterNoWait.pointsQueriesInFlight() == 1);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(busyAfterNoWait)).build();

            MvcResult res = mvc.perform(getRaw("/v1/trace-points?service=" + SVC + "&since=" + T0 + "&until=" + (T0 + ONE_MINUTE)))
                    .andExpect(status().isServiceUnavailable()).andReturn();

            JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
            assertEquals("trace points query is busy — retry shortly", body.path("error").asText());
            assertEquals(1, body.size(), "본문은 error 한 키");
        } finally {
            blocking.open();
            shutdown(pool);
        }
    }

    /** H03 — 응답 필드 이름 · 순서 = 설계 §2.1 계약(최상위 · 점 · 칸). */
    @Test
    void serializesTheResponseFieldsInContractOrder() throws Exception {
        insert("ok", SVC, T0 + 10, 120, false);
        insert("err", SVC, T0 + 2_010, 1_840, true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceQueryController(service)).build();

        JsonNode body = json(mvc, "/v1/trace-points?service=" + SVC + "&since=" + T0 + "&until=" + (T0 + ONE_MINUTE));

        assertEquals(List.of("bucketMs", "totalCount", "errorCount", "errorsReduced", "points", "buckets"), fieldNames(body));
        assertEquals(2, body.path("points").size(), "전제: 점 둘");
        assertEquals(List.of("traceId", "rootOperation", "startTime", "durationMs", "status", "hasError"),
                fieldNames(body.path("points").get(0)));
        assertEquals(List.of("start", "count", "errorCount"), fieldNames(body.path("buckets").get(0)));
        assertEquals("ERROR", body.path("points").get(1).path("status").asText());
        assertTrue(body.path("points").get(1).path("hasError").asBoolean());
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private TracePointsResponse points(long since, long until) {
        return service.listTracePoints(SVC, since, until, null, null);
    }

    private void insert(String id, String svc, long start, long duration, boolean error) {
        insertOp(id, svc, start, duration, error, "GET /op");
    }

    private void insertOp(String id, String svc, long start, long duration, boolean error, String rootOperation) {
        jdbc.update("""
                INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                    status, span_count, service_count, has_error, received_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                """, id, rootOperation, svc, start, duration, error ? "ERROR" : "OK", error ? 1 : 0, start);
    }

    private static Object[] row(String id, String svc, long start, long duration, boolean error) {
        return new Object[]{id, "GET /op", svc, start, duration, error ? "ERROR" : "OK", error ? 1 : 0, start};
    }

    /** 많은 행은 한 트랜잭션으로 넣는다(행마다 커밋하면 느리다). */
    private void insertAll(List<Object[]> rows) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(s ->
                jdbc.batchUpdate("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
                        """, rows));
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    private static List<String> ids(List<TracePoint> points) {
        return points.stream().map(TracePoint::traceId).toList();
    }

    private static boolean isError(TracePoint p) {
        return p.status() == SpanStatus.ERROR || p.hasError();
    }

    private static List<TracePoint> errorPoints(TracePointsResponse r) {
        return r.points().stream().filter(TracePointsQueryTest::isError).toList();
    }

    private static List<TracePoint> okPoints(TracePointsResponse r) {
        return r.points().stream().filter(p -> !isError(p)).toList();
    }

    private static Map<Long, String> okRepsByBucket(TracePointsResponse r) {
        Map<Long, String> out = new HashMap<>();
        for (TracePoint p : okPoints(r)) {
            out.put(Math.floorDiv(p.startTime(), r.bucketMs()) * r.bucketMs(), p.traceId());
        }
        return out;
    }

    /** 주소 문자열을 그대로 보낸다 — 문자열 판 {@code get} 은 주소 틀로 읽어 {@code %20} · {@code %25} 를 한 번 더 인코딩한다. */
    private static org.springframework.test.web.servlet.RequestBuilder getRaw(String url) {
        return get(URI.create(url));
    }

    private JsonNode json(MockMvc mvc, String url) throws Exception {
        MvcResult res = mvc.perform(getRaw(url)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    private static List<String> textValues(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /**
     * 같은 조건 목록 응답(전수 · limit 500)에서 대표 점 응답이 가져야 할 값을 계산해 견준다 — 칸마다 가장 느린 정상
     * (동률이면 큰 id) + 오류 전부 · 총계 · 칸 건수 · 칸 오류 건수.
     */
    private static void assertExpectedFromList(JsonNode points, JsonNode traces, String filter) {
        long bucketMs = points.path("bucketMs").asLong();
        Map<Long, long[]> counts = new TreeMap<>();
        Map<Long, JsonNode> okRep = new HashMap<>();
        Set<String> expectedIds = new HashSet<>();
        long errors = 0;
        for (JsonNode t : traces) {
            long bucket = Math.floorDiv(t.path("startTime").asLong(), bucketMs) * bucketMs;
            boolean err = "ERROR".equals(t.path("status").asText()) || t.path("hasError").asBoolean();
            long[] c = counts.computeIfAbsent(bucket, k -> new long[2]);
            c[0]++;
            if (err) {
                c[1]++;
                errors++;
                expectedIds.add(t.path("traceId").asText());
            } else {
                JsonNode cur = okRep.get(bucket);
                if (cur == null || slowerOrLargerId(t, cur)) {
                    okRep.put(bucket, t);
                }
            }
        }
        okRep.values().forEach(t -> expectedIds.add(t.path("traceId").asText()));
        assertEquals(traces.size(), points.path("totalCount").asLong(), "totalCount = 목록 건수 · 조건 " + filter);
        assertEquals(errors, points.path("errorCount").asLong(), "errorCount = 목록 오류 건수 · 조건 " + filter);
        assertEquals(expectedIds, Set.copyOf(textValues(points.path("points"), "traceId")), "점 집합 · 조건 " + filter);
        Map<Long, long[]> actualCounts = new TreeMap<>();
        points.path("buckets").forEach(b ->
                actualCounts.put(b.path("start").asLong(), new long[]{b.path("count").asLong(), b.path("errorCount").asLong()}));
        assertEquals(counts.keySet(), actualCounts.keySet(), "칸 집합 · 조건 " + filter);
        counts.forEach((k, v) -> {
            assertEquals(v[0], actualCounts.get(k)[0], "칸 " + k + " 건수 · 조건 " + filter);
            assertEquals(v[1], actualCounts.get(k)[1], "칸 " + k + " 오류 건수 · 조건 " + filter);
        });
    }

    private static boolean slowerOrLargerId(JsonNode candidate, JsonNode current) {
        long a = candidate.path("durationMs").asLong();
        long b = current.path("durationMs").asLong();
        if (a != b) {
            return a > b;
        }
        return candidate.path("traceId").asText().compareTo(current.path("traceId").asText()) > 0;
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "시험 스레드가 끝났다");
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TraceQueryService.class);
    }

    /** INFO 를 켠 채 action 을 돌리고 모든 이벤트를 돌려준다. 레벨은 원래대로 되돌린다. */
    private static List<ILoggingEvent> captureServiceLog(Runnable action) {
        ch.qos.logback.classic.Logger logger = serviceLogger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static List<String> lines(List<ILoggingEvent> events, Level level) {
        return events.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * 걸쇠가 열릴 때까지 대표 점 질의를 붙잡는 저장소 — 허가를 쥔 「도는 질의」 흉내. 도는 수 · 최대 동시 수를 센다.
     * 걸쇠는 다시 걸 수 있다({@link #rearm()} — 같은 서비스에서 허가를 다시 쥐는 판).
     */
    private static final class BlockingRepository extends TraceQueryRepository {
        private final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        BlockingRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
            super(jdbc, mapper);
        }

        @Override
        public List<PointRow> findTracePoints(String s, long since, long until, SpanStatus status, String q,
                                              long bucketMs, int errorsMax) {
            calls.incrementAndGet();
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                if (!gate.get().await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch was not released");
                }
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }

        void open() {
            gate.get().countDown();
        }

        void rearm() {
            gate.set(new CountDownLatch(1));
        }
    }
}
