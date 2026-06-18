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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [수동 정리] MaintenanceController MockMvc 단위 테스트 — POST /v1/maintenance/{cleanup,purge}.
 *
 * <p>실제 SQLite 임시 파일 DB + Flyway 마이그레이션으로 page_count/page_size PRAGMA 가
 * 실제 동작하는 환경에서 응답 계약(deletedTraces / freedBytes / dbSizeBytes)을 검증한다.
 * 운영 DB 파일(/Users/netmd/project/ApiLens/apilens.db*)은 건드리지 않는다 (D-04).
 */
class MaintenanceControllerTest {

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-maintenance-test-", ".db");
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
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        SettingsService settingsService = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        RetentionCleanupService cleanupService =
                new RetentionCleanupService(jdbc, txManager, settingsService);

        MaintenanceController controller = new MaintenanceController(cleanupService, jdbc);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void cleanupReturns200WithMaintenanceResultContract() throws Exception {
        long now = System.currentTimeMillis();
        insertTraceTree("t-expired", now - 100 * DAY_MS); // 만료 (30일 초과)
        insertTraceTree("t-live", now - DAY_MS);          // 보존

        mockMvc.perform(post("/v1/maintenance/cleanup"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 만료분 1건만 삭제 (cleanup = retention window 적용).
                .andExpect(jsonPath("$.deletedTraces").value(1))
                // 세 필드 모두 응답에 존재 (프론트 계약 1:1).
                .andExpect(jsonPath("$.freedBytes").exists())
                .andExpect(jsonPath("$.dbSizeBytes").exists());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = 't-live'", Integer.class),
                "최근 trace 는 보존");
    }

    @Test
    void purgeReturns200AndDeletesEverything() throws Exception {
        long now = System.currentTimeMillis();
        insertTraceTree("t-a", now - 100 * DAY_MS);
        insertTraceTree("t-b", now - DAY_MS); // 최근분도 전체 비우기 대상

        mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.deletedTraces").value(2))
                .andExpect(jsonPath("$.freedBytes").exists())
                .andExpect(jsonPath("$.dbSizeBytes").exists());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM traces", Integer.class),
                "purge 후 traces 0건");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM spans", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payloads", Integer.class));
    }

    @Test
    void purgeOnEmptyDbReturns200WithZeroDeleted() throws Exception {
        mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedTraces").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").exists());
    }

    // ─── fixture helper (RetentionCleanupServiceTest 와 동형) ─────────────────

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
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'in', 'application/json', '{}', 2, 0)
                        """,
                traceId + "-span-1"
        );
    }
}
