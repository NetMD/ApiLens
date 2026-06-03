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
package io.apilens.server.setup;

import io.apilens.server.setup.dto.ServiceRegistration;
import io.apilens.server.setup.dto.SetupCompleteRequest;
import io.apilens.server.setup.dto.SetupCompleteResponse;
import io.apilens.server.setup.dto.SetupStateResponse;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase H] BT-9 / BT-10 — Setup endpoint 비즈니스 로직 검증.
 *
 * <p>D-01 / D-02 / D-04 / NFR-04 (멱등) / Q-01 (services null/[]). 사용자 명시 비협상 결정.
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 */
class SetupServiceTest {

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private SetupService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-setup-test-", ".db");
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
        this.service = new SetupService(new SetupRepository(jdbc));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── GET /v1/setup/state 응답 구조 ────────────────────────────────────

    @Test
    void initialStateIsNotCompleted() {
        SetupStateResponse state = service.getState();
        assertFalse(state.completed());
        assertNull(state.completedAt());
        assertNull(state.serverUrl());
    }

    // ─── POST /v1/setup/complete — services nullable optional (Q-01) ─────

    @Test
    void shouldAcceptNullServices() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("http://apilens-host:8765", null));
        assertTrue(resp.completed());
        assertTrue(resp.completedAt() > 0L);

        // services 테이블에 INSERT 0
        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    @Test
    void shouldAcceptEmptyServicesList() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("http://apilens-host:8765", List.of()));
        assertTrue(resp.completed());

        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    @Test
    void shouldRegisterWizardServices() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("my-api"))
                ));
        assertTrue(resp.completed());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM services WHERE service_name = ?", "my-api");
        assertEquals("wizard", row.get("source"));
        assertNotNull(row.get("registered_at"));
        assertNull(row.get("last_seen_at"), "wizard 등록 시 last_seen_at 은 NULL");
    }

    // ─── NFR-04 멱등 — 재호출 시 갱신 ─────────────────────────────────────

    @Test
    void shouldBeIdempotentOnRepeatedComplete() throws InterruptedException {
        SetupCompleteResponse first = service.complete(
                new SetupCompleteRequest("http://old-host:8765", null));
        long firstCompletedAt = first.completedAt();

        // 1ms 이상 차이 확보
        Thread.sleep(5);

        SetupCompleteResponse second = service.complete(
                new SetupCompleteRequest("http://new-host:8765", null));
        long secondCompletedAt = second.completedAt();

        assertTrue(second.completed());
        assertTrue(secondCompletedAt >= firstCompletedAt);

        SetupStateResponse state = service.getState();
        assertTrue(state.completed());
        assertEquals("http://new-host:8765", state.serverUrl(), "serverUrl 갱신");
    }

    // ─── wizard 가 같은 이름 두 번 → ON CONFLICT DO NOTHING ────────────────

    @Test
    void duplicateWizardServiceInsertsAreIdempotent() {
        service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("my-api"))
        ));
        Long firstRegisteredAt = jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = ?", Long.class, "my-api");
        assertNotNull(firstRegisteredAt);

        // 동일 이름 wizard 재호출
        service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("my-api"))
        ));
        Long secondRegisteredAt = jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = ?", Long.class, "my-api");
        assertNotNull(secondRegisteredAt);

        // ON CONFLICT DO NOTHING — 첫 INSERT 시점 보존
        assertEquals(firstRegisteredAt.longValue(), secondRegisteredAt.longValue());
    }

    // ─── validation — serverUrl 형식 ─────────────────────────────────────

    /**
     * D-04 (skip 허용) + design §8.2 + Plan §2 AC-04-2 비협상 결정:
     * skip 경로에서 {@code serverUrl=""} + {@code services=[]} 는 정상 분기 (200).
     * setup_state.completed=1 / completed_at != null / server_url 은 NULL 정규화.
     * <p>회차 R9 BE-FAIL-01 회귀 가드 — 과거 IllegalArgumentException 던지던 잘못된 lock-in 반전.
     */
    @Test
    void shouldAcceptBlankServerUrlForSkipFlow() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("", List.of()));

        assertTrue(resp.completed(), "skip 경로에서도 completed=true 반환");
        assertTrue(resp.completedAt() > 0L, "completedAt 은 epoch ms");

        // setup_state 확정 — completed=1 / completed_at != null / server_url IS NULL
        SetupStateResponse state = service.getState();
        assertTrue(state.completed(), "skip 후 setup_state.completed=1");
        assertNotNull(state.completedAt(), "completed_at 저장됨");
        assertNull(state.serverUrl(), "빈 문자열은 NULL 로 정규화 저장");

        // services 테이블에 INSERT 0
        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    /**
     * D-04 정합: skip 경로 services=null 변형도 동등하게 200 (Q-01 omit/null/[] 동등).
     */
    @Test
    void shouldAcceptBlankServerUrlWithNullServicesForSkipFlow() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("", null));
        assertTrue(resp.completed());

        SetupStateResponse state = service.getState();
        assertTrue(state.completed());
        assertNull(state.serverUrl(), "빈 문자열은 NULL 로 정규화");
    }

    /**
     * NFR-04 멱등 정합 회귀 가드: skip 으로 일단 마킹한 후
     * 운영자가 wizard 재진입해 정상 완료 (Server URL 입력 + 서비스 1개) 시
     * setup_state.server_url 정상 갱신 + services 정상 INSERT 됨.
     */
    @Test
    void skipThenProperCompletionUpdatesServerUrlAndRegistersServices()
            throws InterruptedException {
        // 1) skip
        SetupCompleteResponse skip = service.complete(new SetupCompleteRequest("", List.of()));
        assertTrue(skip.completed());
        long skipAt = skip.completedAt();

        SetupStateResponse afterSkip = service.getState();
        assertTrue(afterSkip.completed());
        assertNull(afterSkip.serverUrl());

        Thread.sleep(5);

        // 2) wizard 재진입 후 정상 완료
        SetupCompleteResponse proper = service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("payment-svc"))
        ));
        assertTrue(proper.completed());
        assertTrue(proper.completedAt() >= skipAt, "completedAt 멱등 갱신");

        // 3) setup_state.server_url 갱신
        SetupStateResponse afterProper = service.getState();
        assertEquals("http://apilens-host:8765", afterProper.serverUrl(),
                "skip 후 정상 완료 시 server_url 갱신");

        // 4) services 정상 INSERT (skip 시점엔 0, 이후 1)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM services WHERE service_name = ?", "payment-svc");
        assertEquals("wizard", row.get("source"));
        assertNotNull(row.get("registered_at"));
        assertNull(row.get("last_seen_at"), "wizard 등록 시 last_seen_at NULL");
    }

    @Test
    void rejectsNonHttpServerUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("ftp://x:8765", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("localhost:8765", null)));
    }

    @Test
    void acceptsHttpsServerUrl() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("https://apilens.example.com:8765", null));
        assertTrue(resp.completed());
    }

    // ─── validation — service name 형식 ──────────────────────────────────

    @Test
    void rejectsServiceNameWithSpaces() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("my api"))
                )));
    }

    @Test
    void rejectsServiceNameWithKorean() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("결제"))
                )));
    }

    @Test
    void rejectsBlankServiceName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration(""))
                )));
    }
}
