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

import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R12] T-A1 — RetentionCleanupService 단위 테스트 (Design §7.2).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>AC-A1-3: "명시적 3단 삭제 payloads→spans→traces (CASCADE 의존 금지)" (D-04/A1 비협상)</li>
 *   <li>AC-A1-7: "삭제 0건이어도 cleanup 정상 종료 시 retention_meta.last_cleanup_at 갱신"</li>
 *   <li>D-05: "retention 기본 30일 유지 + 설정 페이지에서 변경 가능 (DB 저장 값이 yml 보다 우선)"</li>
 * </ul>
 *
 * <p>경계 확정 (Design §7.1): {@code received_at < cutoff} 엄격 미만 —
 * cutoff−1 삭제 / cutoff 보존 / cutoff+1 보존.
 */
class RetentionCleanupServiceTest {

    private static final long DAY_MS = 86_400_000L;

    /** [Phase R29] 운영 예산 × 가드 상수 — 실 진입점을 타는 회수 시험의 전제(이만큼 없으면 회수 미진입). */
    private static final long PRODUCTION_GUARD_BYTES =
            RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES * RetentionCleanupService.RECLAIM_WAL_BYTES_PER_PAGE;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private SettingsService settingsService;
    private RetentionCleanupService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-retention-test-", ".db");
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
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.settingsService = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        this.service = new RetentionCleanupService(jdbc, txManager, settingsService);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 경계 3입력: cutoff−1 / cutoff / cutoff+1 (Design §7.1) ─────────────

    @Test
    void deletesStrictlyOlderThanCutoffAndKeepsBoundaryRow() {
        long now = System.currentTimeMillis();
        long cutoff = now - 30 * DAY_MS; // yml fallback 30일 (settings 미설정)

        insertTraceTree("t-old", cutoff - 1);   // 삭제 대상 (엄격 미만)
        insertTraceTree("t-edge", cutoff);      // 보존 (경계값 — 미만이 아님)
        insertTraceTree("t-new", cutoff + 1);   // 보존

        service.cleanup(now);

        assertEquals(0, countRows("traces", "t-old"), "cutoff−1 은 삭제");
        assertEquals(1, countRows("traces", "t-edge"), "cutoff 경계값은 보존 (엄격 미만 확정)");
        assertEquals(1, countRows("traces", "t-new"), "cutoff+1 은 보존");
    }

    // ─── 3 테이블 고아 0 (NFR-02 — 명시적 3단 DELETE 정합) ────────────────

    @Test
    void keepsChildTablesConsistentWithZeroOrphans() {
        long now = System.currentTimeMillis();
        long cutoff = now - 30 * DAY_MS;

        insertTraceTree("t-expired", cutoff - DAY_MS);
        insertTraceTree("t-live", now - DAY_MS);

        service.cleanup(now);

        // [Phase R12] AC-A1-3 verbatim: "명시적 3단 삭제 payloads→spans→traces (CASCADE 의존 금지)" (비협상)
        Integer orphanSpans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id NOT IN (SELECT trace_id FROM traces)",
                Integer.class);
        Integer orphanPayloads = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE span_id NOT IN (SELECT span_id FROM spans)",
                Integer.class);
        assertEquals(0, orphanSpans, "고아 span 0 — 3단 삭제 정합");
        assertEquals(0, orphanPayloads, "고아 payload 0 — 3단 삭제 정합");

        // 살아있는 trace 의 자식은 그대로
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id = 't-live'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE span_id = 't-live-span-1'", Integer.class));
    }

    // ─── retention_meta 갱신 — 삭제 0건 포함 (AC-A1-7) ─────────────────────

    @Test
    void updatesLastCleanupAtEvenWhenNothingWasDeleted() {
        long now = System.currentTimeMillis();
        insertTraceTree("t-recent", now - DAY_MS); // 30일 이내 — 삭제 대상 없음

        RetentionCleanupService.CleanupResult result = service.cleanup(now);

        assertEquals(0, result.deletedTraces());
        // AC-A1-7 verbatim: "삭제 0건이어도 cleanup 정상 종료 시 retention_meta.last_cleanup_at 갱신"
        // — 의미는 "마지막 실행 시각" (T-10, Design §2-A1)
        Long lastCleanupAt = jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
        assertEquals(now, lastCleanupAt);
    }

    // ─── 배치 루프 종료 (Design §7.1: BATCH_SIZE=2 + 대상 5건 → 3루프 종료) ──

    @Test
    void terminatesBatchLoopAfterDrainingAllExpiredRows() {
        RetentionCleanupService smallBatch =
                new RetentionCleanupService(jdbc, txManager, settingsService, 2);
        long now = System.currentTimeMillis();
        long cutoff = now - 30 * DAY_MS;
        for (int i = 0; i < 5; i++) {
            insertTraceTree("t-exp-" + i, cutoff - 1 - i);
        }

        RetentionCleanupService.CleanupResult result = smallBatch.cleanup(now);

        assertEquals(3, result.batches(), "2+2+1 = 3 배치 후 종료 (AC-A1-5)");
        assertEquals(5, result.deletedTraces());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE received_at < ?", Integer.class, cutoff),
                "잔여 만료 행 0");
    }

    // ─── D-05 resolve 우선순위: settings(DB) > yml fallback ─────────────────

    @Test
    void resolvesRetentionDaysFromSettingsOverYmlFallback() {
        long now = System.currentTimeMillis();
        // [Phase R12] D-05 verbatim: "retention 기본 30일 유지 + 설정 페이지에서 변경 가능
        // (DB 저장 값이 yml 보다 우선)" (사용자 명시 비협상 결정)
        jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES ('retention.days', '14', ?)", now);

        insertTraceTree("t-20d", now - 20 * DAY_MS); // 14일 기준 만료 (30일 기준이면 보존됐을 행)
        insertTraceTree("t-10d", now - 10 * DAY_MS); // 14일 이내 — 보존

        service.cleanup(now);

        assertEquals(0, countRows("traces", "t-20d"), "settings 14일이 yml 30일보다 우선");
        assertEquals(1, countRows("traces", "t-10d"));
    }

    @Test
    void fallsBackToYmlDaysWhenSettingsRowAbsent() {
        assertEquals(30, settingsService.resolveRetentionDays(),
                "settings 행 부재 → yml apilens.retention.days(30) fallback (D-05 '없으면 yml')");
    }

    // ─── purgeAll: 전체 비우기 (수동 정리 — cutoff 없이 전부 삭제) ───────────

    @Test
    void purgesAllTracesSpansAndPayloadsRegardlessOfAge() {
        long now = System.currentTimeMillis();
        // 만료분과 최근분이 섞여 있어도 전부 삭제되어야 한다 (cutoff 무시).
        insertTraceTree("t-old", now - 100 * DAY_MS);
        insertTraceTree("t-recent", now - DAY_MS);
        insertTraceTree("t-just-now", now);

        RetentionCleanupService.CleanupResult result = service.purgeAll();

        assertEquals(3, result.deletedTraces(), "최근 행 포함 전체 삭제");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM traces", Integer.class),
                "traces 0건");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM spans", Integer.class),
                "spans 0건");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payloads", Integer.class),
                "payloads 0건");
    }

    @Test
    void purgeAllUpdatesLastCleanupAtAndLeavesZeroOrphans() {
        long before = System.currentTimeMillis();
        insertTraceTree("t-a", before - 5 * DAY_MS);
        insertTraceTree("t-b", before - 2 * DAY_MS);

        service.purgeAll();

        // 전체 삭제 후 고아 0 (3단 DELETE 정합 — payloads→spans→traces).
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id NOT IN (SELECT trace_id FROM traces)",
                Integer.class), "고아 span 0");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE span_id NOT IN (SELECT span_id FROM spans)",
                Integer.class), "고아 payload 0");
        // retention_meta.last_cleanup_at 가 작업 시각으로 갱신 (마지막 실행 시각 의미).
        Long lastCleanupAt = jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
        assertNotNull(lastCleanupAt);
        assertTrue(lastCleanupAt >= before, "purgeAll 후 last_cleanup_at 갱신");
    }

    @Test
    void purgeAllOnEmptyDbDeletesNothingAndDoesNotThrow() {
        // 빈 DB 에서도 정상 종료 (대상 0건 → 즉시 종료, PRAGMA/ANALYZE 만 수행).
        RetentionCleanupService.CleanupResult result =
                assertDoesNotThrow(() -> service.purgeAll());
        assertEquals(0, result.deletedTraces());
        assertEquals(0, result.batches());
    }

    // ─── WAL checkpoint(TRUNCATE) 추가가 기존 cleanup 을 깨지 않음 ────────────

    @Test
    void cleanupStillSucceedsWithWalCheckpointAdded() {
        long now = System.currentTimeMillis();
        insertTraceTree("t-expired", now - 100 * DAY_MS);
        insertTraceTree("t-live", now - DAY_MS);

        // WAL checkpoint(TRUNCATE) 가 finalizeMaintenance 에 추가된 뒤에도 throw 없이 동작.
        RetentionCleanupService.CleanupResult result =
                assertDoesNotThrow(() -> service.cleanup(now));

        assertEquals(1, result.deletedTraces(), "만료분만 삭제 (cleanup 기존 동작 보존)");
        assertEquals(1, countRows("traces", "t-live"), "최근 행은 보존");
    }

    // ─── [Phase R13] GT-3 게이트: wal_checkpoint(TRUNCATE) row 매핑 확정 (AC-D1-3) ──

    @Test
    void mapsWalCheckpointTruncateResultColumns() {
        // GT-3: sqlite-jdbc 3.47.1.0 가 PRAGMA wal_checkpoint(TRUNCATE) 를 queryForMap 으로
        // busy/log/checkpointed 3컬럼 row 로 반환하는지 확정 (반환하면 busy 로깅 경로 유효).
        jdbc.execute("PRAGMA journal_mode=WAL");
        insertTraceTree("t-x", System.currentTimeMillis() - DAY_MS);

        java.util.Map<String, Object> ck =
                assertDoesNotThrow(() -> jdbc.queryForMap("PRAGMA wal_checkpoint(TRUNCATE)"));
        // busy 컬럼이 존재하고 Number 로 매핑되면 finalizeMaintenance 의 busy 로깅 경로가 유효.
        assertTrue(ck.containsKey("busy"), "wal_checkpoint row 에 busy 컬럼 존재 (GT-3 확정)");
        assertTrue(ck.get("busy") instanceof Number, "busy 가 Number 로 매핑");
    }

    // ─── job 예외 격리 (AC-A1-8 — throw 전파 0) ─────────────────────────────

    @Test
    void isolatesCleanupExceptionInsideScheduledJob() {
        RetentionCleanupService throwing =
                new RetentionCleanupService(jdbc, txManager, settingsService) {
                    @Override
                    public CleanupResult cleanup() {
                        throw new IllegalStateException("simulated cleanup failure");
                    }
                };
        RetentionCleanupJob job = new RetentionCleanupJob(throwing);

        // AC-A1-8: 예외 격리 — 서버·ingest 영향 0, 다음 주기 자연 재시도 (E-06)
        assertDoesNotThrow(job::runScheduled);
    }

    // ─── [Phase R22] ① 예산 제한 회수 루프 (R22/AC-01-1·01-2·01-8) ───────────

    /**
     * [Phase R22] R22/AC-01-1/R22/AC-01-2 — R22/AC-01-1 verbatim: "회수는 예산 제한 루프다. 루프 진입 전에
     * {@code PRAGMA freelist_count} 를 읽고 {@code min(남은 free page, 예산)} 만큼만 돈다. 예산 단위는
     * <b>페이지 개수</b>다." 사용자 명시 결정(OQ-1). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★★ <b>준비를 안 하면 이 테스트는 통과하면서 아무것도 검증하지 않는다.</b> ★★
     * 단위 테스트 DB 는 Flyway 만 돌아 {@code auto_vacuum = NONE} 이다({@code StartupDbInitializer} 는
     * Spring 기동 때만 돈다). 그 상태에서 {@code PRAGMA incremental_vacuum} 은 <b>no-op</b> 이라 free page 가
     * 안 줄고 루프가 첫 회전에서 끝난다. 그래서 (1) {@code auto_vacuum=INCREMENTAL} 과 {@code VACUUM} 을
     * <b>같은 connection</b> 에서 먼저 걸고 (2) free page 가 실제로 생겼는지 <b>단언으로 먼저 확인</b>한 뒤
     * (3) 줄어듦을 단언한다.
     */
    @Test
    void reclaimsFreePagesWithinTheBudgetDuringCleanup() {
        enableIncrementalAutoVacuum();
        long freedPages = seedAndDropBulkPayloads(120, 40_000);

        long before = freelistCount();
        // (2) 전제 확인 — free page 가 실제로 생겼는가. 안 생겼으면 아래 단언이 무의미(vacuous)해진다.
        assertTrue(before > 0,
                "전제: 삭제로 free page 가 실제로 생겨야 회수 루프가 검증된다 (생성 시도 " + freedPages + " 행)");

        service.cleanup(System.currentTimeMillis());

        long after = freelistCount();
        assertTrue(after < before,
                "예산 안에서 free page 가 실제로 줄어든다 (before=" + before + " after=" + after + ")");
    }

    /**
     * [Phase R22] R22/AC-01-2 verbatim: "루프 종료 조건은 <b>호출 횟수가 아니라 {@code freelist_count}
     * 재확인</b>이다." — {@code auto_vacuum} 이 {@code NONE} 이면 {@code incremental_vacuum} 이 no-op 이라
     * free page 가 안 준다. 그때 루프가 예산(5,000회)만큼 헛돌지 않고 <b>첫 회전에서 즉시 끝나는지</b> 본다.
     *
     * <p>이 DB 는 Flyway 만 돌아 {@code auto_vacuum = NONE} 이므로 준비 없이 그대로가 그 상황이다.
     */
    @Test
    void endsTheReclaimLoopImmediatelyWhenNoPageCanBeFreed() {
        seedAndDropBulkPayloads(40, 4_000);
        long before = freelistCount();
        assertTrue(before > 0, "전제: free page 는 있으나 auto_vacuum=NONE 이라 회수는 불가능한 상태");

        long startedAt = System.currentTimeMillis();
        List<String> lines = captureRetentionLines(
                () -> assertDoesNotThrow(() -> service.cleanup(System.currentTimeMillis())));
        long elapsed = System.currentTimeMillis() - startedAt;

        // 예산만큼 헛돌았다면 이 시간 안에 못 끝난다 (회전마다 회수 묶음 1 + 빈 페이지 읽기 1 · 사이마다 양보 창).
        assertTrue(elapsed < 10_000L, "회수 불가 환경에서 루프가 헛돌지 않는다 (elapsed=" + elapsed + "ms)");
        // [Phase R29] R29/AC-R29-11 — 진행 없음 갈래: 묶음 1 · 회수 0 을 줄로 확인한다(설계 §5.4 ④).
        assertEquals(1, countLinesStartingWith(lines, "reclaim done: reclaimedPages=0 "),
                "진행 없음 갈래의 회수 요약 = 0 페이지 — 실제: " + lines);
        assertEquals(1, countLinesStartingWith(lines, "reclaim batches: batches=1 "),
                "진행 없음 갈래는 묶음 1개에서 즉시 끝난다 — 실제: " + lines);
    }

    /**
     * [Phase R22] R22/AC-01-8 verbatim: "회수 루프는 {@code finalizeMaintenance} 안에서 <b>정리 시각
     * 기록보다 뒤</b>에 놓이고, <b>자체 try-catch</b> 로 감싸인다. 여기서 나온 예외는 밖으로 나가지 않는다."
     * 사용자 명시 결정(C-2).
     *
     * <p>회수 PRAGMA 만 던지게 만들고 나머지는 실제 DB 로 돌린다 — 시각 기록이 <b>이미 끝난 뒤</b>라
     * 그 밤의 기록이 남고, 뒤이은 {@code wal_checkpoint}·{@code ANALYZE} 도 그대로 진행된다.
     */
    @Test
    void keepsTheCleanupTimestampWhenFreePageReclaimFails() {
        JdbcTemplate throwingReclaim = Mockito.spy(jdbc);
        // [Phase R29] R29/AC-R29-05 — 모의를 새 호출 꼴의 <b>메서드 이름과 문자열 둘 다</b>로 맞춘다(안 맞으면 예외가
        //   안 나 시험이 헛돈다). 회수 묶음은 update(String) 로 (N) 꼴을 보낸다.
        Mockito.doThrow(new DataAccessResourceFailureException("simulated reclaim failure"))
                .when(throwingReclaim).update(ArgumentMatchers.startsWith("PRAGMA incremental_vacuum("));
        RetentionCleanupService svc =
                new RetentionCleanupService(throwingReclaim, txManager, settingsService);

        seedAndDropBulkPayloads(20, 4_000);   // free page 를 만들어 회수 루프에 진입시킨다.
        // 전제 — 실 진입점은 운영 예산으로 가드를 지난다(가용이 모자라면 회수 미진입 → 모의가 안 불린다).
        assertTrue(usableBytes() >= PRODUCTION_GUARD_BYTES,
                "전제: 시험 임시 폴더 가용 ≥ 가드 요구 " + PRODUCTION_GUARD_BYTES + " B — 실제 " + usableBytes());
        long now = System.currentTimeMillis();

        assertDoesNotThrow(() -> svc.cleanup(now));
        // 전제 — 모의가 실제로 불렸다(회수 묶음에서 예외가 실제로 났다).
        Mockito.verify(throwingReclaim).update(ArgumentMatchers.startsWith("PRAGMA incremental_vacuum("));

        Long lastCleanupAt = jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
        assertEquals(now, lastCleanupAt, "회수가 터져도 그 밤의 정리 시각은 남는다");
        // ANALYZE 가 실제로 진행됐는지 = 회수 예외가 나머지 단계를 막지 않았는지.
        assertNotNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sqlite_stat1'", Integer.class));
    }

    // ─── [Phase R29] 회수 묶음 · 새 관측 줄 두 줄 (R29/AC-R29-01~05 · 11~14) ──────────────
    //
    //  사용자 명시 결정(UD-R29-2 「값은 사본 실측으로 정한다」). 회수 시험은 시험 틈(작은 예산 · 묶음)으로 돈다 —
    //  운영 예산 20,000 페이지를 만들려면 시험 DB 가 80 MB 가 돼야 한다. 단위 시험 DB 는 auto_vacuum=NONE 이라
    //  회수가 실제로 줄어드는 시험은 같은 연결에서 INCREMENTAL + VACUUM 을 먼저 건다(enableIncrementalAutoVacuum).

    /**
     * [Phase R29] R29/AC-R29-01 verbatim: "회수 묶음 하나 = 같은 연결의 트랜잭션 하나다. 그 안 첫 SQL 은 회수
     * PRAGMA(쓰기)다. 빈 페이지 수 읽기는 그 묶음이 커밋된 뒤에만 한다". 사용자 명시 결정(UD-R29-2).
     *
     * <p>기록은 JdbcTemplate 이 아니라 드라이버에 실제로 간 SQL 을 연결별로 모으는 프록시({@link SqlRecorder})가 한다.
     * 순서가 뒤집히면(빈 페이지 읽기가 묶음 안 앞으로) 04:00 수신과 겹친 묶음이 {@code busy_timeout} 없이 바로 BUSY 가 된다.
     */
    @Test
    void reclaimBatchStartsWithTheVacuumPragmaAndReadsFreelistOnlyAfterCommit() {
        enableIncrementalAutoVacuum();
        seedAndDropBulkPayloads(300, 40_000);
        assertTrue(freelistCount() >= 1_000, "전제: 빈 페이지 ≥ 1,000 — 실제 " + freelistCount());
        SqlRecorder recorder = new SqlRecorder();
        DataSource recorded = recorder.wrap(jdbc.getDataSource());
        RetentionCleanupService svc = new RetentionCleanupService(
                new JdbcTemplate(recorded), new DataSourceTransactionManager(recorded), settingsService);

        svc.reclaimFreePagesWithin(1_000, 250);

        List<List<String>> reclaimTxs = reclaimTransactions(recorder);
        assertFalse(reclaimTxs.isEmpty(), "전제: 회수 트랜잭션이 실제로 잡혀야 한다 — 실제: " + recorder.transactions);
        for (List<String> t : reclaimTxs) {
            assertEquals(1, t.size(), "회수 트랜잭션 안의 SQL 은 한 문장이다 — 실제: " + t);
            assertTrue(t.get(0).startsWith("PRAGMA incremental_vacuum("),
                    "회수 트랜잭션의 첫 문장은 회수 PRAGMA(쓰기)다 — 실제: " + t);
        }
        assertTrue(recorder.transactions.stream().flatMap(List::stream).noneMatch(q -> q.contains("freelist_count")),
                "빈 페이지 읽기는 어느 트랜잭션 안에도 없다 — 실제: " + recorder.transactions);
        assertTrue(recorder.outside.stream().anyMatch(q -> q.contains("freelist_count")),
                "빈 페이지 읽기는 트랜잭션 밖에서 돈다 — 실제 밖 목록: " + recorder.outside);
    }

    /**
     * [Phase R29] R29/AC-R29-02/R29/AC-R29-03 verbatim: "묶음 한 번이 실제로 묶음 크기만큼 페이지를 푼다 … 회수 PRAGMA 의
     * {@code (N)} 꼴을 {@code jdbc.execute(String)} 로 보내지 않는다" · "빈 페이지({@code freelist_count}) 감소 = N · 전체
     * 페이지({@code page_count}) 감소 ≥ N. 전체 페이지 쪽을 등식으로 단언하지 않는다". 사용자 명시 결정(UD-R29-2).
     *
     * <p>묶음이 조용히 1 페이지짜리로 되돌아가면(호출 꼴 회귀) 회수 트랜잭션이 4 가 아니라 1,000 이 되어 여기서 빨갛다 —
     * 가드 상수(10 KiB/page)가 못 덮는 회귀를 이 시험이 막는다.
     */
    @Test
    void reclaimsExactlyTheBudgetInCeilingBudgetOverBatchCommits() {
        enableIncrementalAutoVacuum();
        seedAndDropBulkPayloads(300, 40_000);
        long freeBefore = freelistCount();
        long pagesBefore = pageCount();
        assertTrue(freeBefore >= 1_000, "전제: 빈 페이지 ≥ 1,000 — 실제 " + freeBefore);
        SqlRecorder recorder = new SqlRecorder();
        DataSource recorded = recorder.wrap(jdbc.getDataSource());
        RetentionCleanupService svc = new RetentionCleanupService(
                new JdbcTemplate(recorded), new DataSourceTransactionManager(recorded), settingsService);

        List<String> lines = captureRetentionLines(() -> svc.reclaimFreePagesWithin(1_000, 250));

        assertEquals(1_000, freeBefore - freelistCount(), "빈 페이지 감소 = 예산 1,000");
        assertTrue(pagesBefore - pageCount() >= 1_000,
                "전체 페이지 감소 ≥ 1,000 (포인터 맵 몫만큼 더 클 수 있다) — 실제 " + (pagesBefore - pageCount()));
        assertEquals(4, reclaimTransactions(recorder).size(),
                "회수 트랜잭션 = 올림(1,000 ÷ 250) = 4 — 실제: " + reclaimTransactions(recorder).size());
        assertEquals(1, countLinesStartingWith(lines, "reclaim batches: batches=4 batchPages=250 "),
                "새 줄 ① 이 묶음 수 4 · 묶음 크기 250 을 싣는다 — 실제: " + lines);
    }

    /** [Phase R29] R29/AC-R29-11 — 빈 페이지가 예산보다 적으면 전부 돌려주고, 묶음 수 = 올림(회수 ÷ 묶음 크기). */
    @Test
    void reclaimsEveryFreePageWhenFreeIsBelowTheBudget() {
        enableIncrementalAutoVacuum();
        seedAndDropBulkPayloads(60, 40_000);
        long freeBefore = freelistCount();
        assertTrue(freeBefore > 500 && freeBefore < 1_000, "전제: 500 < 빈 페이지 < 1,000 — 실제 " + freeBefore);

        List<String> lines = captureRetentionLines(() -> service.reclaimFreePagesWithin(1_000, 250));

        assertEquals(0, freelistCount(), "예산이 남으면 빈 페이지를 전부 돌려준다");
        long reclaimed = numberAfter(lines, "reclaim done: reclaimedPages=");
        long batches = numberAfter(lines, "reclaim batches: batches=");
        assertEquals(freeBefore, reclaimed, "회수 요약 = 처음 빈 페이지 수 — 실제: " + lines);
        assertEquals((reclaimed + 249) / 250, batches, "묶음 수 = 올림(회수 ÷ 250) — 실제: " + lines);
    }

    /**
     * [Phase R29] R29/AC-R29-11 — 입구 셋(밤 · 수동 · 전체 삭제)이 같은 루프를 타서 새 줄 ① 을 정리 한 번에 정확히 한 줄
     * 찍는다(BL-R29-06). 실 진입점은 운영 예산으로 가드를 지나므로 가용 여유가 전제다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"nightly", "manual", "purge"})
    void logsOneBatchLinePerCleanupEntrance(String entrance) {
        enableIncrementalAutoVacuum();
        seedAndDropBulkPayloads(60, 40_000);
        assertTrue(freelistCount() > 0, "전제: 회수할 빈 페이지가 있다");
        assertTrue(usableBytes() >= PRODUCTION_GUARD_BYTES,
                "전제: 시험 임시 폴더 가용 ≥ 가드 요구 " + PRODUCTION_GUARD_BYTES + " B — 실제 " + usableBytes());
        long now = System.currentTimeMillis();
        Runnable run = switch (entrance) {
            case "nightly" -> () -> service.cleanup(now);
            case "manual" -> () -> service.cleanupManual(now);
            case "purge" -> service::purgeAll;
            default -> throw new IllegalArgumentException(entrance);
        };

        List<String> lines = captureRetentionLines(run);

        assertEquals(1, countLinesStartingWith(lines, "reclaim done:"), "전제: 회수가 돌았다 — 실제: " + lines);
        assertEquals(1, countLinesStartingWith(lines, "reclaim batches:"),
                entrance + " 입구에서 새 줄 ① 이 정확히 한 줄 — 실제: " + lines);
    }

    /** [Phase R29] R29/AC-R29-12 — 빈 페이지 0 갈래: 시작 줄만 찍고 회수 요약 · 새 줄 ① 은 없다(설계 §5.4 ①). */
    @Test
    void logsNoReclaimLinesWhenThereIsNothingToReclaim() {
        enableIncrementalAutoVacuum();   // VACUUM 이 빈 페이지를 0 으로 만든다.
        assertEquals(0, freelistCount(), "전제: VACUUM 뒤 빈 페이지 0");

        List<String> lines = captureRetentionLines(() -> service.reclaimFreePagesWithin(1_000, 250));

        assertEquals(1, countLinesStartingWith(lines, "reclaim start: freelistPages=0 "),
                "전제: 회수 입구는 지났다 — 실제: " + lines);
        assertEquals(0, countLinesStartingWith(lines, "reclaim done:"), "실제: " + lines);
        assertEquals(0, countLinesStartingWith(lines, "reclaim batches:"), "실제: " + lines);
    }

    /**
     * [Phase R29] R29/AC-R29-13 verbatim: "{@code wal_checkpoint(TRUNCATE)} 경과 ms 와 결과 세 값(busy · log ·
     * checkpointed)이 새 INFO 줄 하나로 찍힌다. 숫자만 · 기존 busy 줄 그대로". 정리 한 번과 [최적화] 한 번에 각 한 줄.
     *
     * <p>WAL 아닌 연결은 결과가 {@code 0|-1|-1} 이라 숫자 칸 꼴을 재려면 이 DB 를 먼저 WAL 로 바꾼다.
     */
    @Test
    void logsTheCheckpointWaitLineOnEveryCleanup() {
        jdbc.execute("PRAGMA journal_mode=WAL");
        long now = System.currentTimeMillis();
        insertTraceTree("t-x", now - DAY_MS);
        java.util.regex.Pattern shape = java.util.regex.Pattern.compile(
                "wal checkpoint: elapsedMs=\\d+ busy=\\d+ walFrames=\\d+ checkpointedFrames=\\d+");

        List<String> cleanupLines = captureRetentionLines(() -> service.cleanup(now));
        List<String> optimizeLines = captureRetentionLines(() -> {
            boolean busy = service.optimizeDatabase();
            assertFalse(busy, "전제: [최적화] 가 성공 갈래로 끝났다");
        });

        List<String> fromCleanup = linesStartingWith(cleanupLines, "wal checkpoint:");
        assertEquals(1, fromCleanup.size(), "정리 한 번에 새 줄 ② 한 줄 — 실제: " + cleanupLines);
        assertTrue(shape.matcher(fromCleanup.get(0)).matches(), "숫자 칸 넷의 꼴 — 실제: " + fromCleanup.get(0));
        List<String> fromOptimize = linesStartingWith(optimizeLines, "wal checkpoint:");
        assertEquals(1, fromOptimize.size(), "[최적화] 한 번에 새 줄 ② 한 줄 — 실제: " + optimizeLines);
        assertTrue(shape.matcher(fromOptimize.get(0)).matches(), "숫자 칸 넷의 꼴 — 실제: " + fromOptimize.get(0));
    }

    /** [Phase R29] R29/AC-R29-13 — busy ≠ 0 갈래: 기존 busy 줄 그대로 + 그 다음에 새 줄 ② 가 세 값을 싣는다. */
    @Test
    void logsTheCheckpointWaitLineBesideTheBusyLine() {
        JdbcTemplate spy = Mockito.spy(jdbc);
        Mockito.doReturn(Map.of("busy", 1, "log", 10, "checkpointed", 5))
                .when(spy).queryForMap("PRAGMA wal_checkpoint(TRUNCATE)");
        RetentionCleanupService svc = new RetentionCleanupService(spy, txManager, settingsService);

        List<String> lines = captureRetentionLines(() -> svc.cleanup(System.currentTimeMillis()));

        List<String> busyLines = linesStartingWith(lines, "wal_checkpoint(TRUNCATE) busy");
        List<String> newLines = linesStartingWith(lines, "wal checkpoint:");
        assertEquals(1, busyLines.size(), "기존 busy 줄 그대로 한 줄 — 실제: " + lines);
        assertEquals(1, newLines.size(), "새 줄 ② 한 줄 — 실제: " + lines);
        assertTrue(newLines.get(0).matches("wal checkpoint: elapsedMs=\\d+ busy=1 walFrames=10 checkpointedFrames=5"),
                "세 값이 모의 결과 그대로 — 실제: " + newLines.get(0));
        assertTrue(lines.indexOf(busyLines.get(0)) < lines.indexOf(newLines.get(0)),
                "새 줄은 기존 busy 줄 다음이다 — 실제: " + lines);
    }

    /**
     * [Phase R29] R29/AC-R29-14 verbatim: "결과 세 값을 0 으로 꾸며 싣지 않는다(줄을 안 찍거나 「값 없음」 꼴 — 설계가
     * 고름). 기존 warn 두 줄 문면 그대로". 설계는 「줄을 안 찍는다」를 골랐다.
     */
    @Test
    void skipsTheCheckpointWaitLineWhenTheResultCannotBeRead() {
        JdbcTemplate spy = Mockito.spy(jdbc);
        Mockito.doThrow(new DataAccessResourceFailureException("simulated mapping failure"))
                .when(spy).queryForMap("PRAGMA wal_checkpoint(TRUNCATE)");
        RetentionCleanupService svc = new RetentionCleanupService(spy, txManager, settingsService);

        List<String> lines = captureRetentionLines(() -> svc.cleanup(System.currentTimeMillis()));

        assertEquals(1, linesStartingWith(lines,
                        "wal_checkpoint(TRUNCATE) result mapping unavailable — falling back to execute").size(),
                "전제: 대체 갈래를 실제로 탔다(기존 warn 문면 그대로) — 실제: " + lines);
        assertEquals(0, linesStartingWith(lines, "wal checkpoint:").size(),
                "결과를 못 읽은 갈래에는 새 줄 ② 가 없다 — 실제: " + lines);
    }

    /**
     * [Phase R29] R29/AC-R29-04/R29/AC-R29-11 — 묶음 수 상한 = 올림(예산 ÷ 묶음) + 1. 커밋마다 다른 정리가 겹쳐 빈 페이지를
     * 100~200 다시 채우면 빈 페이지 차이로 센 회수가 덜 세어 예산을 넘겨 돌 수 있다 — 상한이 그 루프를 5 묶음에서 멈춘다.
     * 겹침은 타이밍이 아니라 순서(커밋 직후 감싸기)로 만든다 — {@code Thread.sleep} 0.
     */
    @Test
    void capsTheReclaimLoopWhenAConcurrentDeleteRefillsFreePages() throws Exception {
        enableIncrementalAutoVacuum();
        seedRefillPayloads(80, 40_000);
        seedAndDropBulkPayloads(300, 40_000);
        assertTrue(freelistCount() >= 1_000, "전제: 빈 페이지 ≥ 1,000 — 실제 " + freelistCount());
        List<Long> refilled = new ArrayList<>();
        RefillAfterCommitTx refillTx = new RefillAfterCommitTx(txManager, () -> {
            long before = freelistCount();
            jdbc.update("DELETE FROM payloads WHERE rowid IN "
                    + "(SELECT rowid FROM payloads WHERE span_id LIKE 'refill-%' LIMIT 15)");
            refilled.add(freelistCount() - before);
        });
        setTx(service, refillTx);

        List<String> lines = captureRetentionLines(
                () -> assertDoesNotThrow(() -> service.reclaimFreePagesWithin(1_000, 250)));

        assertEquals(5, refillTx.commits, "회수 트랜잭션 = 상한 올림(1,000 ÷ 250) + 1 = 5 — 실제: " + refillTx.commits);
        assertEquals(5, refilled.size(), "전제: 커밋마다 한 번씩 다시 채웠다");
        assertTrue(refilled.stream().allMatch(r -> r >= 100 && r <= 200),
                "전제: 커밋마다 빈 페이지가 실제로 100~200 다시 채워졌다 — 실제: " + refilled);
        assertTrue(numberAfter(lines, "reclaim done: reclaimedPages=") < 1_000,
                "차이로 센 회수는 예산보다 작다(겹친 채움 몫) — 실제: " + lines);
        assertEquals(1, countLinesStartingWith(lines, "reclaim batches: batches=5 "),
                "새 줄 ① 이 상한 5 를 싣는다 — 실제: " + lines);
    }

    /**
     * [Phase R29] R29/AC-R29-12 — 가드 거부 갈래: 시작 줄 1 · 회수 요약 0 · 새 줄 ① 0 · 기존 warn 문면 그대로 1
     * (설계 §5.4 ②). 시험 틈의 예산을 크게 줘 가드 요구(≈ 1.0e16 B)가 가용을 넘게 한다 — 생성자 무변.
     */
    @Test
    void skipsTheReclaimWhenTheDiskGuardRejects() {
        seedAndDropBulkPayloads(20, 4_000);
        assertTrue(freelistCount() >= 1, "전제: 빈 페이지 ≥ 1 — 가드까지 가야 한다");

        List<String> lines = captureRetentionLines(
                () -> service.reclaimFreePagesWithin(1_000_000_000_000L, 250));

        assertEquals(1, countLinesStartingWith(lines, "reclaim start:"), "실제: " + lines);
        assertEquals(0, countLinesStartingWith(lines, "reclaim done:"), "실제: " + lines);
        assertEquals(0, countLinesStartingWith(lines, "reclaim batches:"), "실제: " + lines);
        assertEquals(1, countLinesStartingWith(lines, "free page reclaim skipped — insufficient disk."),
                "가드 거부 warn 한 줄(문면 그대로) — 실제: " + lines);
    }

    // ─── [Phase R22] ④ 정리 시각 upsert (R22/AC-04-1) ────────────────────────

    /**
     * [Phase R22] R22/AC-04-1 verbatim: "{@code retention_meta} 갱신이 <b>upsert</b> 다. …
     * {@code retention_meta.id} 가 {@code INTEGER PRIMARY KEY} 라 {@code ON CONFLICT(id)} 가 성립한다."
     * 사용자 명시 결정(OQ-8·9). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>행이 사라진 운영 사고가 실재했다 — 기존 {@code UPDATE} 는 행이 없으면 0행 갱신으로 <b>조용히
     * 성공</b>해 정리 시각이 영영 "이력 없음" 으로 남았다. upsert 는 행을 <b>다시 만든다</b>.
     */
    @Test
    void recreatesTheRetentionMetaRowWhenItIsMissing() {
        jdbc.update("DELETE FROM retention_meta");   // ④가 겪은 운영 상태 재현 (원인은 미규명).
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM retention_meta", Integer.class));

        long now = System.currentTimeMillis();
        service.cleanup(now);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM retention_meta", Integer.class),
                "행이 없어도 upsert 가 다시 만든다");
        assertEquals(now, jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class));
    }

    // ─── fixture helpers ────────────────────────────────────────────────────

    /**
     * {@code auto_vacuum} 을 {@code INCREMENTAL} 로 전환한다 — <b>PRAGMA 와 VACUUM 은 같은 connection</b>.
     * auto_vacuum 은 connection 수준 pending 설정이라 다른 connection 의 VACUUM 은 기존 값으로 재구성해
     * 전환이 silent 실패한다 ({@code StartupDbInitializer} 와 같은 모양).
     */
    private void enableIncrementalAutoVacuum() {
        jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                st.execute("VACUUM");
            }
            return null;
        });
    }

    /** payload 행을 대량으로 넣었다 지워 free page 를 만든다. 반환값 = 넣었다 지운 행 수. */
    private long seedAndDropBulkPayloads(int rows, int bodyBytes) {
        insertTraceTree("t-bulk", System.currentTimeMillis());
        String body = "x".repeat(bodyBytes);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            batch.add(new Object[]{"bulk-" + i, body, (long) bodyBytes});
        }
        jdbc.batchUpdate("INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated) "
                + "VALUES (?, 'out', 'application/json', ?, ?, 0)", batch);
        jdbc.update("DELETE FROM payloads WHERE span_id LIKE 'bulk-%'");
        return rows;
    }

    private long freelistCount() {
        Long v = jdbc.queryForObject("PRAGMA freelist_count", Long.class);
        return v == null ? 0L : v;
    }

    private long pageCount() {
        Long v = jdbc.queryForObject("PRAGMA page_count", Long.class);
        return v == null ? 0L : v;
    }

    /** 시험 DB 파일이 있는 디스크의 가용 바이트(가드가 보는 값과 같은 출처). */
    private long usableBytes() {
        return dbFile.toFile().getUsableSpace();
    }

    /** 커밋마다 지워 빈 페이지를 다시 채울 payload 행을 넣어 둔다(지우지 않는다). */
    private void seedRefillPayloads(int rows, int bodyBytes) {
        String body = "y".repeat(bodyBytes);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            batch.add(new Object[]{"refill-" + i, body, (long) bodyBytes});
        }
        jdbc.batchUpdate("INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated) "
                + "VALUES (?, 'out', 'application/json', ?, ?, 0)", batch);
    }

    private static void setTx(RetentionCleanupService target, TransactionTemplate replacement) throws Exception {
        java.lang.reflect.Field f = RetentionCleanupService.class.getDeclaredField("tx");
        f.setAccessible(true);
        f.set(target, replacement);
    }

    /** 회수 PRAGMA 가 든 트랜잭션만 고른다. */
    private static List<List<String>> reclaimTransactions(SqlRecorder recorder) {
        return recorder.transactions.stream()
                .filter(t -> t.stream().anyMatch(q -> q.contains("incremental_vacuum")))
                .toList();
    }

    private static List<String> linesStartingWith(List<String> lines, String prefix) {
        return lines.stream().filter(m -> m.startsWith(prefix)).toList();
    }

    /** 앞머리 뒤 첫 숫자 — 그 앞머리 줄이 정확히 한 줄이어야 한다. */
    private static long numberAfter(List<String> lines, String prefix) {
        List<String> hits = linesStartingWith(lines, prefix);
        assertEquals(1, hits.size(), "전제: '" + prefix + "' 줄이 한 줄 — 실제: " + lines);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\d+").matcher(hits.get(0).substring(prefix.length()));
        assertTrue(m.find(), "전제: 앞머리 뒤에 숫자 — 실제: " + hits.get(0));
        return Long.parseLong(m.group());
    }

    /** 커밋이 끝난 <b>직후</b>(다음 빈 페이지 읽기 전) 주어진 일을 한다 — 겹친 삭제를 타이밍이 아니라 순서로 만든다. */
    @SuppressWarnings("serial")
    private static final class RefillAfterCommitTx extends TransactionTemplate {
        private final Runnable afterCommit;
        int commits = 0;

        RefillAfterCommitTx(PlatformTransactionManager tm, Runnable afterCommit) {
            super(tm);
            this.afterCommit = afterCommit;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            T out = super.execute(action);
            commits++;
            afterCommit.run();
            return out;
        }
    }

    /**
     * [Phase R29] R29/AC-R29-01 — DataSource·Connection·Statement 를 프록시로 감싸 <b>드라이버에 실제로 간 SQL</b> 을
     * 연결별로 모은다. {@code setAutoCommit(false)} ~ {@code commit}/{@code rollback} 사이는 그 트랜잭션의 목록, 그 밖은
     * {@link #outside} 목록이다.
     *
     * <p>출처: {@code io.apilens.server.ingest.SqlStatementInterningTest} 의 같은 이름 기록기(R27)를 같은 꼴로 옮겨 적었다 —
     * 그 클래스는 다른 패키지의 private 중첩이라 공유하지 않는다. 고칠 때는 두 곳을 함께 본다.
     */
    private static final class SqlRecorder {
        final List<List<String>> transactions = new ArrayList<>();
        final List<String> outside = new ArrayList<>();
        private final Map<java.sql.Connection, List<String>> open = new java.util.IdentityHashMap<>();

        DataSource wrap(DataSource target) {
            return proxy(DataSource.class, target, (p, m, a) -> {
                Object out = invoke(target, m, a);
                if (m.getName().equals("getConnection") && out instanceof java.sql.Connection c) {
                    return wrapConnection(c);
                }
                return out;
            });
        }

        private java.sql.Connection wrapConnection(java.sql.Connection target) {
            java.sql.Connection[] self = new java.sql.Connection[1];
            self[0] = proxy(java.sql.Connection.class, target, (p, m, a) -> {
                String name = m.getName();
                if ((name.equals("prepareStatement") || name.equals("prepareCall")) && a != null
                        && a[0] instanceof String sql) {
                    note(self[0], sql);
                }
                Object out = invoke(target, m, a);
                if (name.equals("setAutoCommit") && a != null && Boolean.FALSE.equals(a[0])) {
                    open.put(self[0], new ArrayList<>());
                } else if (name.equals("commit") || name.equals("rollback")) {
                    List<String> done = open.remove(self[0]);
                    if (done != null) {
                        transactions.add(done);
                    }
                } else if (name.equals("createStatement") && out instanceof java.sql.Statement st) {
                    return wrapStatement(self[0], st);
                }
                return out;
            });
            return self[0];
        }

        private java.sql.Statement wrapStatement(java.sql.Connection owner, java.sql.Statement target) {
            return proxy(java.sql.Statement.class, target, (p, m, a) -> {
                String name = m.getName();
                if ((name.startsWith("execute") || name.equals("addBatch")) && a != null && a.length > 0
                        && a[0] instanceof String sql) {
                    note(owner, sql);
                }
                return invoke(target, m, a);
            });
        }

        private void note(java.sql.Connection owner, String sql) {
            List<String> tx = open.get(owner);
            (tx != null ? tx : outside).add(sql);
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<T> type, Object target, java.lang.reflect.InvocationHandler h) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (p, m, a) -> switch (m.getName()) {
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        case "toString" -> "Recorded(" + target + ")";
                        default -> h.invoke(p, m, a);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method m, Object[] a) throws Throwable {
            try {
                return m.invoke(target, a);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }


    /** trace 1건 + spans 2건 + payload 1건 트리 삽입 (3단 삭제 정합 검증용). */
    private void insertTraceTree(String traceId, long receivedAt) {
        jdbc.update(
                """
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'GET /x', 'svc', ?, 100, 'OK', 2, 1, 0, ?)
                        """,
                traceId, receivedAt, receivedAt
        );
        jdbc.update(
                """
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, 'svc', 'GET /x', 'SERVER', ?, ?, 'OK', NULL)
                        """,
                traceId + "-span-1", traceId, receivedAt, receivedAt + 100
        );
        jdbc.update(
                """
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, ?, 'svc', 'SELECT 1', 'DB', ?, ?, 'OK', NULL)
                        """,
                traceId + "-span-2", traceId, traceId + "-span-1", receivedAt, receivedAt + 50
        );
        jdbc.update(
                """
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'in', 'application/json', '{}', 2, 0)
                        """,
                traceId + "-span-1"
        );
    }

    private int countRows(String table, String traceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE trace_id = ?", Integer.class, traceId);
        return count == null ? 0 : count;
    }

    // ── [Phase R26] R26/AC-R26-02/R26/AC-R26-03 — 회수가 두 경로에 각 1회 · 실패해도 뒷정리는 돈다 ──
    //
    //  AC-R26-02 원문: "[전체 삭제]에서도 SQL 원문 표가 비워진다 — 그 경로의 **마지막 정리 단계**".
    //  AC-R26-03 원문: "회수가 던져도 **뒷정리 단계는 그대로 돈다** · 야간·전체 삭제 각 **정확히 1회**".

    /**
     * 야간 경로와 전체 삭제 경로를 <b>각각 한 번씩</b> 돌려 회수 요약 줄이 <b>각 1줄</b>인지 세고,
     * 회수 안에서 <b>일부러 예외를 내어</b> 정리 시각이 그래도 갱신되는지 본다.
     *
     * <p>예외는 흉내가 아니라 <b>실제로</b> 낸다 — 원문 표를 지워 두면 회수의 첫 조회가 "그런 표 없음" 으로
     * 던진다. 그 예외를 밖으로 던지면 그 밤의 시각 기록·공간 회수·WAL·통계가 통째로 빠진다.
     */
    @Test
    void theStatementGcRunsOncePerPathAndFinalizeStillRunsWhenItThrows() {
        long now = System.currentTimeMillis();

        // ① 야간 경로 — 요약 줄 한 줄.
        List<String> nightly = captureRetentionLines(() -> service.cleanup(now));
        assertEquals(1, countLinesStartingWith(nightly, "sql statement gc:"),
                "야간 경로에서 회수 요약 줄이 정확히 한 줄 — 실제: " + nightly);

        // ② 전체 삭제 경로 — 요약 줄 한 줄.
        List<String> purge = captureRetentionLines(service::purgeAll);
        assertEquals(1, countLinesStartingWith(purge, "sql statement gc:"),
                "전체 삭제 경로에서도 정확히 한 줄 — 실제: " + purge);

        // ③ 회수가 실제로 던지게 만든 뒤에도 뒷정리는 돈다.
        jdbc.execute("DROP TABLE sql_statements");
        assertEquals(0, countTables("sql_statements"), "전제: 원문 표가 실제로 없어야 회수가 던진다");
        long laterNight = now + 1_000L;

        assertDoesNotThrow(() -> service.cleanup(laterNight), "회수가 던져도 밖으로 안 샌다");

        Long after = jdbc.queryForObject("SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
        assertNotNull(after, "회수가 던져도 정리 시각은 기록된다");
        assertEquals(laterNight, after.longValue(), "그 밤의 시각이 그대로 기록된다");
    }

    /** 수신이 없는 상태에서 [전체 삭제]를 누르면 원문 표가 <b>0행</b>이 된다. */
    @Test
    void thePurgePathEmptiesTheStatementTableWhenIngestIsIdle() {
        long now = System.currentTimeMillis();
        insertTraceTree("t-live", now);
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, ?)",
                "a".repeat(64), "SELECT 1", now);
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, ?)",
                "b".repeat(64), "SELECT 2", now);
        // 전제 둘 — 표가 실제로 차 있고, 그중 하나는 살아 있는 span 이 가리킨다(0 == 0 통과 방지).
        assertEquals(2, countStatements(), "전제: 원문 표에 행이 실제로 둘 있어야 한다");
        jdbc.update("UPDATE spans SET attributes_json = ? WHERE span_id = ?",
                "{\"apilens.stmt.ref\":\"" + "a".repeat(64) + "\"}", "t-live-span-2");
        Integer pointing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE json_extract(attributes_json, '$.\"apilens.stmt.ref\"') IS NOT NULL",
                Integer.class);
        assertEquals(1, pointing == null ? 0 : pointing,
                "전제: 살아 있는 span 이 실제로 원문 하나를 가리켜야 '전부 사라진다' 가 뜻을 가진다");

        service.purgeAll();

        assertEquals(0, countStatements(),
                "AC-R26-02 판정: 전체 삭제 뒤 원문 표가 0행 — span 이 다 사라졌으니 남을 참조가 없다");
    }

    private int countStatements() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM sql_statements", Integer.class);
        return c == null ? 0 : c;
    }

    private int countTables(String name) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name);
        return c == null ? 0 : c;
    }

    private static long countLinesStartingWith(List<String> lines, String prefix) {
        return lines.stream().filter(m -> m.startsWith(prefix)).count();
    }

    /** 정리 로거가 남긴 INFO·WARN 줄을 모은다. 생산 코드에 시험 전용 필드를 더하지 않는다. */
    private static List<String> captureRetentionLines(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RetentionCleanupService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
