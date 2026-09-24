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
package io.apilens.server.retention;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [2026-09-24] RA-R26-01 · 02 · 03 · 04 · 15 · SEC-R26-05 — 참조 없는 SQL 원문 회수의 <b>갈래별 그물</b>.
 *
 * <p>회수 본체 시험({@code io.apilens.server.ingest.SqlStatementInterningTest})은 밤 정리 진입점을
 * 통째로 돌려 "지워진다 / 남는다" 를 본다. 이 파일은 회전 크기·예산·시계를 인자로 받는 package-private
 * 갈래({@code gcUnreferencedSqlStatements(int, long, LongSupplier)})를 직접 불러, 그 시험들이 한 번도
 * 안 밟은 갈래(NULL 가드 · 예산 · 재확인 상한 · 표시점 경계 · 회전 중 실패 · 검사-삭제 경쟁)를 하나씩 연다.
 *
 * <p>★생산 코드는 한 글자도 안 바뀐다. 끼워 넣기는 {@code jdbc}·{@code tx} 필드를 리플렉션으로 바꿔
 * 끼우는 시험 전용 주입이다(같은 수법의 전례: {@code SqlStatementInterningTest} 의 {@code StatementGcSpy}).
 *
 * <p>★각 시험은 <b>「전제」 단언</b>(픽스처가 실제로 그 갈래를 만들었는가)을 먼저 둔다 — 전제가 없으면
 * 갈래를 안 밟고도 초록이 되는 빈 그물이 된다.
 */
class SqlStatementGcNetTest {

    /** 로그 줄 파싱용 — 생산 코드의 로그 문면과 같은 모양이다. */
    private static final Pattern SCAN_LINE =
            Pattern.compile("^sql statement gc scan: scanMs=-?\\d+ reclaimable=(\\d+) watermarkRowid=(\\d+)$");
    private static final Pattern ROUND_LINE =
            Pattern.compile("^sql statement gc round: round=(\\d+) deleted=(\\d+) recheckRows=(\\d+) statementMs=-?\\d+$");
    private static final Pattern SUMMARY_LINE =
            Pattern.compile("^sql statement gc: deleted=(\\d+) rounds=(\\d+) elapsedMs=-?\\d+$");

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private RetentionCleanupService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-stmt-gc-net-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(ds);
        this.txManager = new DataSourceTransactionManager(ds);
        this.service = new RetentionCleanupService(jdbc, txManager,
                new SettingsService(jdbc, new SettingsRegistry(), new RetentionProperties(30, "0 0 4 * * *")));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── RA-R26-01 ───────────────────────────────────────────────────────

    /**
     * [2026-09-24] RA-R26-01 — 훑기 질의의 {@code IS NOT NULL} 가드. 참조 키가 <b>없는</b> span 이 섞여 있어도
     * 참조 없는 원문은 지워진다.
     *
     * <p>★이 가드가 없으면 {@code NOT IN (… NULL …)} 의미론 때문에 후보가 <b>0</b> 이 된다 — SQL 에서
     * {@code x NOT IN (a, NULL)} 은 참이 아니라 NULL(알 수 없음)이라 모든 행이 걸러진다. 참조 키가 없는
     * span(HTTP span · 속성 NULL)은 운영에 늘 있으므로, 가드가 빠지면 회수는 <b>영원히 조용히 0행</b>이고
     * 로그에는 {@code deleted=0} 만 남아 "지울 것이 없다" 와 구별이 안 된다.
     *
     * <p>픽스처는 참조 키 없는 span 을 <b>가장 큰 rowid</b>에 둔다 — 그래야 표시점 행(재확인 범위)에도 NULL 이
     * 들어가 삭제 문장 쪽 {@code IS NOT NULL} 가드까지 함께 걸린다.
     */
    @Test
    void reclaimsAnUnreferencedStatementEvenWhenSpansWithoutAStatementRefArePresent() {
        String unreferenced = hash('a');
        String referenced = hash('b');
        insertStatement(unreferenced);
        insertStatement(referenced);
        insertSpan("s-db", refJson(referenced));
        insertSpan("s-null", null);                                  // 속성 자체가 NULL
        insertSpan("s-http", "{\"http.method\":\"GET\"}");           // 참조 키가 없는 span · 가장 큰 rowid

        // 전제 ① — 참조 키가 없는(= json_extract 가 NULL 인) span 이 실제로 있다.
        assertEquals(2, count("SELECT COUNT(*) FROM spans"
                        + " WHERE json_extract(attributes_json, '$.\"apilens.stmt.ref\"') IS NULL"),
                "전제: 참조 키 없는 span 이 실제로 있어야 NULL 함정이 생긴다");
        // 전제 ② — 가드를 뺀 질의는 이 픽스처에서 실제로 0행이다(함정이 진짜로 깔렸다는 증거).
        assertEquals(0, jdbc.queryForList("""
                        SELECT stmt_hash FROM sql_statements
                         WHERE stmt_hash NOT IN (
                               SELECT json_extract(attributes_json, '$."apilens.stmt.ref"') FROM spans)
                        """, String.class).size(),
                "전제: IS NOT NULL 이 없으면 NULL 하나에 후보가 0 이 된다 — 이 픽스처가 그 함정을 만든다");
        // 전제 ③ — 표시점 행(가장 큰 rowid)이 참조 키 없는 span 이다.
        assertEquals("s-http", jdbc.queryForObject(
                        "SELECT span_id FROM spans WHERE rowid = (SELECT MAX(rowid) FROM spans)", String.class),
                "전제: 재확인 범위(표시점 이후)에도 NULL 이 들어가야 삭제 문장의 가드까지 잰다");

        List<String> info = captureInfo(() -> service.gcUnreferencedSqlStatements(500, 8_000L, System::nanoTime));

        assertEquals(1, scanReclaimable(info), "후보는 참조 없는 원문 하나다 — 실제: " + info);
        assertEquals(0, countStatement(unreferenced), "참조 없는 원문은 지워진다");
        assertEquals(1, countStatement(referenced), "다른 span 이 가리키는 원문은 남는다");
        assertEquals(List.of(1, 1), summary(info), "요약 줄 deleted=1 rounds=1 — 실제: " + info);
        assertEquals(0, danglingRefs(), "끊긴 참조 0");
    }

    // ─── RA-R26-02 ───────────────────────────────────────────────────────

    /**
     * [2026-09-24] RA-R26-02 ① — 예산이 <b>0</b> 이어도 첫 회전은 반드시 돈다(전진 보장).
     *
     * <p>후보 3 · 회전 크기 1 이라 회전이 셋 필요하다. 예산 0 이면 첫 회전만 돌고 둘째 회전 머리에서
     * 멈춘다 — {@code rounds=1 deleted=1} 이고 후보 둘이 표에 남는다. 예산 판정이 루프 머리로 올라가
     * 첫 회전까지 막으면 {@code rounds=0} 이 되어 회수가 영원히 안 돈다.
     */
    @Test
    void runsTheFirstStatementGcRoundEvenWhenTheBudgetIsZero() {
        seedUnreferencedStatements(3);
        assertEquals(3, count("SELECT COUNT(*) FROM sql_statements"), "전제: 참조 없는 원문이 실제로 셋 있다");

        List<String> info = captureInfo(() -> service.gcUnreferencedSqlStatements(1, 0L, System::nanoTime));

        assertEquals(3, scanReclaimable(info), "전제: 후보 셋이 실제로 잡혀 회전이 여럿 필요해야 한다 — 실제: " + info);
        assertEquals(List.of(1, 1), summary(info),
                "예산 0 에서도 첫 회전은 돈다 — 요약 줄 deleted=1 rounds=1 · 실제: " + info);
        assertEquals(2, count("SELECT COUNT(*) FROM sql_statements"), "남은 후보 둘은 다음 실행 몫이다");
    }

    /**
     * [2026-09-24] RA-R26-02 ② — 첫 회전 뒤 예산이 다하면 둘째 회전을 미루고, 남은 후보는 다음 실행이 잇는다.
     *
     * <p>가짜 시계는 <b>호출 횟수가 아니라 삭제 문장에</b> 묶었다 — 삭제 문장 한 번이 10초 걸린 것처럼
     * 시계를 민다. 그래서 생산 코드가 로그 줄을 하나 더 찍어도(시계 호출이 늘어도) 결과가 안 흔들린다.
     * 예산은 운영 값({@link RetentionCleanupService#SQL_STMT_GC_BUDGET_MS} = 8초)을 그대로 쓴다.
     * 대조군으로 같은 픽스처에서 삭제가 1초씩 걸리면 세 회전이 모두 도는 것을 먼저 본다 — 멈춘 원인이
     * 예산이지 다른 무엇이 아님을 가른다.
     */
    @Test
    void defersTheSecondStatementGcRoundWhenTheBudgetRunsOutAfterTheFirst() throws Exception {
        // 대조군 — 삭제 1회 = 1초 → 경과 1초·2초 < 8초 → 세 회전 모두 돈다.
        seedUnreferencedStatements(3);
        AtomicLong fastNanos = new AtomicLong();
        setJdbc(new ClockAdvancingJdbc(jdbc, fastNanos, 1_000L));
        List<String> control = captureInfo(() ->
                service.gcUnreferencedSqlStatements(1, RetentionCleanupService.SQL_STMT_GC_BUDGET_MS, fastNanos::get));
        assertEquals(List.of(3, 3), summary(control),
                "전제(대조군): 예산이 넉넉하면 회전 셋이 다 돈다 — 실제: " + control);
        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements"), "전제(대조군): 셋 다 지워졌다");

        // 본체 — 삭제 1회 = 10초 → 둘째 회전 머리의 경과 10초 ≥ 8초 → 미룬다.
        seedUnreferencedStatements(3);
        AtomicLong slowNanos = new AtomicLong();
        setJdbc(new ClockAdvancingJdbc(jdbc, slowNanos, 10_000L));
        ch.qos.logback.classic.Logger logger = retentionLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            service.gcUnreferencedSqlStatements(1, RetentionCleanupService.SQL_STMT_GC_BUDGET_MS, slowNanos::get);
        } finally {
            logger.setLevel(previous);
            detach(logger, appender);
        }
        List<String> info = messages(appender, Level.INFO);

        assertEquals(3, scanReclaimable(info), "전제: 후보 셋이 실제로 잡혔다 — 실제: " + info);
        assertEquals(List.of(1, 1), summary(info), "첫 회전만 돌고 멈춘다 — 요약 줄 deleted=1 rounds=1 · 실제: " + info);
        assertTrue(messages(appender, Level.WARN).stream()
                        .anyMatch(m -> m.startsWith("sql statement gc stopped at the time budget:")),
                "예산으로 멈춘 사실이 경고 한 줄로 남는다 — 실제: " + messages(appender, Level.WARN));
        assertEquals(2, count("SELECT COUNT(*) FROM sql_statements"), "남은 후보 둘은 표에 남는다");

        // 다음 실행이 잇는다(훑기 방식이라 밀려도 대상이 안 사라진다).
        setJdbc(jdbc);
        List<String> next = captureInfo(() ->
                service.gcUnreferencedSqlStatements(1, RetentionCleanupService.SQL_STMT_GC_BUDGET_MS, System::nanoTime));
        assertEquals(List.of(2, 2), summary(next), "다음 실행이 남은 둘을 지운다 — 실제: " + next);
        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements"));
    }

    // ─── RA-R26-03 ───────────────────────────────────────────────────────

    /**
     * [2026-09-24] RA-R26-03 — 재확인 상한 갈래. 표시점 이후 span 이 상한을 <b>넘으면</b> 그 회전을 미루고
     * 한 행도 안 지우며, 상한과 <b>같으면</b> 돈다(경계는 {@code >}).
     *
     * <p>★실제로 20만 행을 넣지 않는다(느리다). 상한 검사 문장({@code COUNT(*) FROM spans WHERE rowid >= ?})의
     * <b>돌려주는 값만</b> 바꿔 끼운다 — {@code IngestServiceLogHygieneTest} 의 집계 값 바꿔 끼우기와 같은 수법이다.
     * 그래서 이 시험은 <b>갈래 판정</b>(넘으면 미룬다 · 경계 값은 통과)만 재고, 상한까지 갔을 때의
     * 실제 잠금 시간은 안 잰다(그건 여전히 실측 몫).
     */
    @Test
    void defersTheRoundWhenNewSpansSinceTheScanExceedTheRecheckCap() throws Exception {
        String candidate = hash('c');
        insertStatement(candidate);
        insertSpan("s-other", "{\"http.method\":\"GET\"}");
        assertEquals(1, countStatement(candidate), "전제: 참조 없는 원문이 실제로 하나 있다");

        // 상한 + 1 → 미룬다.
        int cap = RetentionCleanupService.SQL_STMT_GC_RECHECK_MAX_NEW_SPANS;
        setJdbc(new RecheckCountOverridingJdbc(jdbc, cap + 1));
        ch.qos.logback.classic.Logger logger = retentionLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            service.gcUnreferencedSqlStatements(500, 8_000L, System::nanoTime);
        } finally {
            logger.setLevel(previous);
            detach(logger, appender);
        }
        List<String> info = messages(appender, Level.INFO);
        assertEquals(1, scanReclaimable(info), "전제: 후보가 실제로 잡혀 회전 루프에 들어갔다 — 실제: " + info);
        assertTrue(messages(appender, Level.WARN).stream()
                        .anyMatch(m -> m.startsWith("sql statement gc deferred the round: newSpansSinceScan="
                                + (cap + 1) + " cap=" + cap)),
                "상한을 넘으면 미룬 사실이 경고 한 줄로 남는다 — 실제: " + messages(appender, Level.WARN));
        assertEquals(List.of(0, 0), summary(info), "미룬 회전은 rounds 에 안 든다 — 실제: " + info);
        assertEquals(1, countStatement(candidate), "미룬 실행은 원문을 한 행도 안 지운다");

        // 상한과 같으면(경계) 돈다.
        setJdbc(new RecheckCountOverridingJdbc(jdbc, cap));
        List<String> atCap = captureInfo(() -> service.gcUnreferencedSqlStatements(500, 8_000L, System::nanoTime));
        assertEquals(List.of(1, 1), summary(atCap), "상한과 같은 값은 통과한다(경계는 >) — 실제: " + atCap);
        assertEquals(0, countStatement(candidate));
    }

    // ─── RA-R26-04 ───────────────────────────────────────────────────────

    /**
     * [2026-09-24] RA-R26-04 — 표시점 경계가 <b>실제로 일하는</b> 상태에서, 훑기 뒤 · 삭제 전에 들어온 참조를
     * 재확인이 살린다.
     *
     * <p>기존 지정 회귀 시험({@code SqlStatementInterningTest#theStatementGcDeleteStartsWithAWriteAndRechecksOnlyNewSpans})은
     * span 표가 빈 채로 훑기를 해 표시점이 0 이다 — {@code rowid >= 0} 은 표 전체라 경계가 아무 일도 안 한다.
     * 여기서는 기존 span 을 먼저 깔아 표시점을 0 이 아닌 값으로 만들고, 훑기 문장({@code queryForList … FROM
     * sql_statements}) 직후에 후보 원문을 가리키는 span 을 끼워 넣는다(그 rowid 는 표시점보다 크다).
     *
     * <p>★「표시점보다 <b>작은</b> rowid 로 들어온 삽입」 갈래(rowid 되쓰기 · SEC-R26-04)는 <b>이번 범위 밖</b>이다 —
     * 그 갈래는 회전마다 표시점을 다시 보는 가드 코드와 함께 파이프라인이 맡는다.
     */
    @Test
    void theRecheckAboveANonZeroWatermarkKeepsAStatementReferencedAfterTheScan() throws Exception {
        String revived = hash('d');
        String orphan = hash('e');
        String live = hash('f');
        insertStatement(revived);
        insertStatement(orphan);
        insertStatement(live);
        insertSpan("s-live-1", refJson(live));
        insertSpan("s-live-2", refJson(live));
        insertSpan("s-live-3", refJson(live));
        long maxRowidBefore = count("SELECT MAX(rowid) FROM spans");
        assertTrue(maxRowidBefore > 0, "전제: 기존 span 이 있어 표시점이 0 이 아니어야 한다");

        setJdbc(new AfterScanJdbc(jdbc, () -> insertSpan("s-late", refJson(revived))));
        List<String> info = captureInfo(() -> service.gcUnreferencedSqlStatements(500, 8_000L, System::nanoTime));

        // ① 전제 — 표시점이 실제로 0 이 아니고, 훑기 시점의 후보는 둘(revived · orphan)이었다.
        long watermark = scanWatermark(info);
        assertNotEquals(0L, watermark, "전제: watermarkRowid 가 0 이면 경계가 아무 일도 안 한다 — 실제: " + info);
        assertEquals(maxRowidBefore, watermark, "전제: 표시점은 끼워 넣기 전의 최대 rowid 다");
        assertEquals(2, scanReclaimable(info), "전제: 훑기 시점에 revived 도 후보였다 — 실제: " + info);
        long lateRowid = count("SELECT rowid FROM spans WHERE span_id = 's-late'");
        assertTrue(lateRowid > watermark, "전제: 끼워 넣은 span 의 rowid 가 표시점보다 커야 재확인 범위에 든다");

        // ② 재확인이 살렸다.
        assertEquals(1, countStatement(revived), "훑기 뒤에 참조가 생긴 원문은 재확인이 살린다");
        // ③ 재확인이 본 행 = 표시점 행 + 끼워 넣은 행.
        List<List<Integer>> rounds = rounds(info);
        assertEquals(1, rounds.size(), "회전은 하나다 — 실제: " + info);
        assertEquals(2, rounds.get(0).get(2), "recheckRows = 표시점 행 1 + 끼워 넣은 행 1 — 실제: " + info);
        assertTrue(rounds.get(0).get(2) >= 1);
        // ④ 참조 없는 다른 원문은 지워졌다 · 기존 참조 원문은 남는다.
        assertEquals(0, countStatement(orphan), "참조 없는 다른 원문은 지워진다");
        assertEquals(1, countStatement(live), "기존 span 이 가리키는 원문은 후보도 아니다");
        assertEquals(List.of(1, 1), summary(info));
        // ⑤ 끊긴 참조 0.
        assertEquals(0, danglingRefs(), "끊긴 참조 0");
    }

    // ─── RA-R26-15 ───────────────────────────────────────────────────────

    /**
     * [2026-09-24] RA-R26-15 — 첫 회전이 <b>커밋된 뒤</b> 형식이 깨진 행이 들어오면 둘째 회전이 실패하고,
     * 첫 회전이 지운 몫은 <b>그대로 지워진 채</b> 남는다(회전마다 별도 트랜잭션이라 부분 커밋).
     *
     * <p>기존 시험({@code SqlStatementInterningTest#theStatementGcSkipsTheWholeRunWhenAttributesJsonIsMalformed})은
     * 깨진 행을 훑기 <b>앞</b>에 심어 초기 검사(건너뜀) 갈래만 밟는다. 여기서는 {@code tx} 를 감싸 첫
     * {@code execute} 가 <b>돌아온 뒤</b>(= 커밋 뒤) 깨진 행을 넣는다 — 둘째 회전의 삭제 문장이 재확인
     * 서브질의의 {@code json_extract} 에서 오류로 끝난다. 타이밍이 아니라 순서로 만든 결정적 경합이다.
     *
     * <p>★오류 줄은 <b>앞머리 {@code sql statement gc failed}</b> 까지만 단언한다. 지금 문면의 뒷부분
     * "nothing was deleted" 는 이 시험이 보여 주듯 <b>거짓</b>이다(첫 회전 몫은 이미 지워졌다) — 다음 라운드
     * (OBS-R26-01)가 문면을 고친다. 거짓 문면을 시험으로 봉인하면 그 정정이 시험을 깨뜨려야 하므로 안 묶는다.
     */
    @Test
    void keepsTheFirstRoundDeletionsWhenAMalformedRowArrivesAfterTheFirstCommit() throws Exception {
        String first = hash('1');
        String second = hash('2');
        insertStatement(first);
        insertStatement(second);
        insertSpan("s-http", "{\"http.method\":\"GET\"}");
        assertEquals(0, count("SELECT COUNT(*) FROM spans WHERE json_valid(attributes_json) = 0"),
                "전제: 훑기 시점에는 깨진 행이 없어야 초기 검사(건너뜀) 갈래를 피한다");

        AfterFirstCommitTx wrapped = new AfterFirstCommitTx(txManager,
                () -> insertSpan("s-broken", "{not valid json"));
        setTx(wrapped);

        ch.qos.logback.classic.Logger logger = retentionLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            // ④ 예외가 호출자로 새지 않는다.
            assertDoesNotThrow(() -> service.gcUnreferencedSqlStatements(1, 8_000L, System::nanoTime));
        } finally {
            logger.setLevel(previous);
            detach(logger, appender);
        }
        List<String> info = messages(appender, Level.INFO);

        // 전제 — 회전이 둘 필요했고, 깨진 행은 첫 커밋 **뒤**에 실제로 들어갔다.
        assertEquals(2, scanReclaimable(info), "전제: 후보 둘 · 회전 크기 1 이라 회전이 둘 필요하다 — 실제: " + info);
        assertTrue(wrapped.injectedAfterFirstCommit, "전제: 깨진 행이 첫 회전 커밋 뒤에 들어갔다");
        assertEquals(1, count("SELECT COUNT(*) FROM spans WHERE json_valid(attributes_json) = 0"),
                "전제: 깨진 행이 실제로 하나 있다");
        List<List<Integer>> rounds = rounds(info);
        assertEquals(1, rounds.size(), "첫 회전만 완료 줄을 남긴다 — 실제: " + info);
        assertEquals(1, rounds.get(0).get(1), "첫 회전은 한 행을 지웠다 — 실제: " + info);

        // ① 첫 회전 몫은 실제로 표에서 사라졌다(부분 커밋이 남는다). ③ 둘째 후보는 남는다.
        int firstLeft = countStatement(first);
        int secondLeft = countStatement(second);
        assertEquals(1, firstLeft + secondLeft, "후보 둘 중 정확히 하나(첫 회전 몫)만 사라졌다");
        // ② 실패 줄은 정확히 한 줄.
        List<String> failures = messages(appender, Level.ERROR).stream()
                .filter(m -> m.startsWith("sql statement gc failed"))
                .toList();
        assertEquals(1, failures.size(), "둘째 회전 실패가 오류 한 줄로 남는다 — 실제: " + messages(appender, Level.ERROR));
        assertTrue(summary(info).isEmpty(), "실패한 실행은 요약 줄을 안 남긴다 — 실제: " + info);
    }

    // ─── SEC-R26-05 ──────────────────────────────────────────────────────

    /**
     * [2026-09-24] SEC-R26-05 — 재확인 상한 검사와 삭제 <b>사이</b>의 경쟁. 상한은 무르지만 재확인은 옳다.
     *
     * <p>상한 검사 문장({@code COUNT(*) FROM spans WHERE rowid >= ?})이 값을 읽은 <b>직후</b>(그 값은 그대로
     * 돌려준다) 후보 원문을 가리키는 span 여러 건을 끼워 넣는다. 그러면 로그의 {@code recheckRows} 는
     * 삭제 순간의 실제 행 수보다 <b>작다</b>(= 상한이 무르다는 실물). 그래도 삭제 문장은 자기 트랜잭션
     * 안에서 재확인 서브질의를 다시 돌리므로 새 참조를 보고 그 원문을 살린다.
     *
     * <p>상한 값(20만) 자체를 넘기는 삽입은 안 한다(느리다) — 그 갈래는 RA-R26-03 시험이 값을 바꿔 끼워 잰다.
     */
    @Test
    void theRecheckStaysCorrectWhenSpansArriveBetweenTheCapCheckAndTheDelete() throws Exception {
        String raced = hash('7');
        String orphan = hash('8');
        insertStatement(raced);
        insertStatement(orphan);
        insertSpan("s-http", "{\"http.method\":\"GET\"}");
        int racers = 5;

        AfterCapCheckJdbc spy = new AfterCapCheckJdbc(jdbc, () -> {
            for (int i = 0; i < racers; i++) {
                insertSpan("s-race-" + i, refJson(raced));
            }
        });
        setJdbc(spy);
        ch.qos.logback.classic.Logger logger = retentionLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            service.gcUnreferencedSqlStatements(500, 8_000L, System::nanoTime);
        } finally {
            logger.setLevel(previous);
            detach(logger, appender);
        }
        List<String> info = messages(appender, Level.INFO);

        // 전제 — 끼워 넣기가 상한 검사 **뒤** · 삭제 **앞**에 실제로 일어났다.
        assertTrue(spy.injected, "전제: 상한 검사 문장이 실제로 돌았고 그 직후 끼워 넣었다");
        assertEquals(2, scanReclaimable(info), "전제: 훑기 시점에 raced 도 후보였다 — 실제: " + info);
        long watermark = scanWatermark(info);
        List<List<Integer>> rounds = rounds(info);
        assertEquals(1, rounds.size(), "회전 하나가 완주했다 — 실제: " + info);
        int loggedRecheck = rounds.get(0).get(2);
        int actualAfter = count("SELECT COUNT(*) FROM spans WHERE rowid >= " + watermark);
        assertEquals(1, loggedRecheck, "전제: 검사 순간의 값은 표시점 행 하나뿐이었다 — 실제: " + info);
        assertEquals(1 + racers, actualAfter,
                "전제: 삭제 순간의 실제 행 수는 검사 값보다 크다(상한이 무르다는 실물)");

        // ① 완주 — 실패 줄 0 · 요약 줄 정상.
        assertTrue(messages(appender, Level.ERROR).isEmpty(), "경쟁이 있어도 실패하지 않는다 — 실제: "
                + messages(appender, Level.ERROR));
        assertEquals(List.of(1, 1), summary(info), "요약 줄 deleted=1 rounds=1 — 실제: " + info);
        // ② 새로 참조된 원문은 재확인이 살린다 · 참조 없는 원문은 지워진다.
        assertEquals(1, countStatement(raced), "검사 뒤에 참조가 생긴 원문은 재확인이 살린다");
        assertEquals(0, countStatement(orphan), "참조 없는 원문은 지워진다");
        // ③ 끊긴 참조 0.
        assertEquals(0, danglingRefs(), "끊긴 참조 0");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    /** 64자 지문 흉내 — 같은 문자를 64번. 표는 지문 형식을 검사하지 않는다. */
    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static String refJson(String ref) {
        return "{\"apilens.stmt.ref\":\"" + ref + "\"}";
    }

    private void insertStatement(String stmtHash) {
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, 0)",
                stmtHash, "SELECT '" + stmtHash.charAt(0) + "'");
    }

    private void seedUnreferencedStatements(int n) {
        for (int i = 0; i < n; i++) {
            insertStatement(String.format("%064d", i));
        }
    }

    private void insertSpan(String spanId, String attributesJson) {
        jdbc.update("INSERT INTO spans (span_id, trace_id, service_name, operation_name, span_kind, "
                        + "start_time, end_time, status, attributes_json) "
                        + "VALUES (?, 't-net', 'svc', 'op', 'DB', 1000, 1002, 'OK', ?)",
                spanId, attributesJson);
    }

    private int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }

    private int countStatement(String stmtHash) {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM sql_statements WHERE stmt_hash = ?",
                Integer.class, stmtHash);
        return v == null ? 0 : v;
    }

    /**
     * 끊긴 참조 = 참조 키가 있는데 원문 표에 그 지문이 없는 span. 깨진 행은 {@code CASE} 로 먼저 걸러
     * {@code json_extract} 오류를 피한다({@code AND} 는 평가 순서가 보장되지 않는다).
     */
    private int danglingRefs() {
        return count("""
                SELECT COUNT(*) FROM (
                    SELECT CASE WHEN json_valid(attributes_json)
                                THEN json_extract(attributes_json, '$."apilens.stmt.ref"') END AS ref
                      FROM spans)
                 WHERE ref IS NOT NULL
                   AND ref NOT IN (SELECT stmt_hash FROM sql_statements)
                """);
    }

    private void setJdbc(JdbcTemplate replacement) throws Exception {
        Field f = RetentionCleanupService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        f.set(service, replacement);
    }

    private void setTx(TransactionTemplate replacement) throws Exception {
        Field f = RetentionCleanupService.class.getDeclaredField("tx");
        f.setAccessible(true);
        f.set(service, replacement);
    }

    private static int scanReclaimable(List<String> info) {
        return Integer.parseInt(scanMatch(info).group(1));
    }

    private static long scanWatermark(List<String> info) {
        return Long.parseLong(scanMatch(info).group(2));
    }

    private static Matcher scanMatch(List<String> info) {
        for (String line : info) {
            Matcher m = SCAN_LINE.matcher(line);
            if (m.matches()) {
                return m;
            }
        }
        throw new AssertionError("scan line not found: " + info);
    }

    /** 회전 줄마다 [round, deleted, recheckRows]. */
    private static List<List<Integer>> rounds(List<String> info) {
        return info.stream()
                .map(ROUND_LINE::matcher)
                .filter(Matcher::matches)
                .map(m -> List.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))))
                .toList();
    }

    /** 요약 줄의 [deleted, rounds]. 요약 줄이 없으면 빈 목록. 둘 이상이면 실패. */
    private static List<Integer> summary(List<String> info) {
        List<List<Integer>> found = info.stream()
                .map(SUMMARY_LINE::matcher)
                .filter(Matcher::matches)
                .map(m -> List.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))))
                .toList();
        assertFalse(found.size() > 1, "요약 줄은 실행당 하나다 — 실제: " + info);
        return found.isEmpty() ? List.of() : found.get(0);
    }

    private static ch.qos.logback.classic.Logger retentionLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RetentionCleanupService.class);
    }

    /** INFO 를 켠 채 action 을 돌리고 INFO 줄만 돌려준다. 레벨은 원래대로 되돌린다. */
    private static List<String> captureInfo(Runnable action) {
        ch.qos.logback.classic.Logger logger = retentionLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);   // null 이면 상위 레벨 상속으로 되돌아간다
            detach(logger, appender);
        }
        return messages(appender, Level.INFO);
    }

    private static ListAppender<ILoggingEvent> attach(ch.qos.logback.classic.Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static boolean isRecheckCapCheck(String sql) {
        return sql.contains("COUNT(*) FROM spans WHERE rowid >= ?");
    }

    /** 회수 삭제 문장이 돌 때마다 가짜 시계를 정해진 만큼 민다 — "삭제 한 번이 N초 걸렸다" 흉내. */
    private static final class ClockAdvancingJdbc extends JdbcTemplate {
        private final AtomicLong nanos;
        private final long perDeleteMillis;

        ClockAdvancingJdbc(JdbcTemplate delegate, AtomicLong nanos, long perDeleteMillis) {
            super(Objects.requireNonNull(delegate.getDataSource()));
            this.nanos = nanos;
            this.perDeleteMillis = perDeleteMillis;
        }

        @Override
        public int update(String sql, Object... args) {
            int n = super.update(sql, args);
            if (sql.startsWith("DELETE FROM sql_statements")) {
                nanos.addAndGet(perDeleteMillis * 1_000_000L);
            }
            return n;
        }
    }

    /** 재확인 상한 검사 문장의 돌려주는 값만 바꿔 끼운다. 나머지 문장은 그대로 DB 로 간다. */
    private static final class RecheckCountOverridingJdbc extends JdbcTemplate {
        private final int fakeCount;

        RecheckCountOverridingJdbc(JdbcTemplate delegate, int fakeCount) {
            super(Objects.requireNonNull(delegate.getDataSource()));
            this.fakeCount = fakeCount;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (isRecheckCapCheck(sql) && requiredType == Integer.class) {
                return (T) Integer.valueOf(fakeCount);
            }
            return super.queryForObject(sql, requiredType, args);
        }
    }

    /** 훑기 문장(후보 목록 조회)이 끝난 <b>직후</b> 주어진 일을 한 번 한다. */
    private static final class AfterScanJdbc extends JdbcTemplate {
        private final Runnable afterScan;
        private boolean done = false;

        AfterScanJdbc(JdbcTemplate delegate, Runnable afterScan) {
            super(Objects.requireNonNull(delegate.getDataSource()));
            this.afterScan = afterScan;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            List<T> out = super.queryForList(sql, elementType, args);
            if (!done && sql.contains("FROM sql_statements")) {
                done = true;
                afterScan.run();
            }
            return out;
        }
    }

    /** 재확인 상한 검사 문장이 값을 읽은 <b>직후</b>(값은 그대로 돌려준다) 주어진 일을 한 번 한다. */
    private static final class AfterCapCheckJdbc extends JdbcTemplate {
        private final Runnable afterCheck;
        private boolean injected = false;

        AfterCapCheckJdbc(JdbcTemplate delegate, Runnable afterCheck) {
            super(Objects.requireNonNull(delegate.getDataSource()));
            this.afterCheck = afterCheck;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            T out = super.queryForObject(sql, requiredType, args);
            if (!injected && isRecheckCapCheck(sql)) {
                injected = true;
                afterCheck.run();
            }
            return out;
        }
    }

    /** 첫 {@code execute} 가 <b>돌아온 뒤</b>(= 첫 회전 커밋 뒤) 주어진 일을 한 번 한다. */
    @SuppressWarnings("serial")
    private static final class AfterFirstCommitTx extends TransactionTemplate {
        private final Runnable afterFirstCommit;
        private int calls = 0;
        private boolean injectedAfterFirstCommit = false;

        AfterFirstCommitTx(PlatformTransactionManager tm, Runnable afterFirstCommit) {
            super(tm);
            this.afterFirstCommit = afterFirstCommit;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            T out = super.execute(action);
            if (++calls == 1) {
                afterFirstCommit.run();
                injectedAfterFirstCommit = true;
            }
            return out;
        }
    }
}
