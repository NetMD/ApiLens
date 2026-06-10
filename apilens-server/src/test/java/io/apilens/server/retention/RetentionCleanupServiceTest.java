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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    // ─── fixture helpers ────────────────────────────────────────────────────

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
