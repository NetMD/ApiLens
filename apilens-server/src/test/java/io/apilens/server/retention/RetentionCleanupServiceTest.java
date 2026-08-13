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
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertDoesNotThrow(() -> service.cleanup(System.currentTimeMillis()));
        long elapsed = System.currentTimeMillis() - startedAt;

        // 예산 5,000회를 헛돌았다면 이 시간 안에 못 끝난다 (회전마다 PRAGMA 2회).
        assertTrue(elapsed < 10_000L, "회수 불가 환경에서 루프가 헛돌지 않는다 (elapsed=" + elapsed + "ms)");
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
        Mockito.doThrow(new DataAccessResourceFailureException("simulated reclaim failure"))
                .when(throwingReclaim).execute("PRAGMA incremental_vacuum");
        RetentionCleanupService svc =
                new RetentionCleanupService(throwingReclaim, txManager, settingsService);

        seedAndDropBulkPayloads(20, 4_000);   // free page 를 만들어 회수 루프에 진입시킨다.
        long now = System.currentTimeMillis();

        assertDoesNotThrow(() -> svc.cleanup(now));

        Long lastCleanupAt = jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
        assertEquals(now, lastCleanupAt, "회수가 터져도 그 밤의 정리 시각은 남는다");
        // ANALYZE 가 실제로 진행됐는지 = 회수 예외가 나머지 단계를 막지 않았는지.
        assertNotNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sqlite_stat1'", Integer.class));
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
}
