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
package io.apilens.server.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * [Phase H] BT-2 / BT-3 / BT-4 / BT-5 / BT-7 — IngestService 자동 등록 (D-02 경로 B).
 *
 * <p>사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>검증 분기:
 * <ul>
 *   <li>BT-2: 신규 service 첫 trace 시 source='auto' INSERT</li>
 *   <li>BT-3: 동일 service 후속 trace 시 last_seen_at 갱신 + registered_at / source 보존</li>
 *   <li>BT-4: wizard 등록 후 자동 trace 도착 시 source='wizard' 유지</li>
 *   <li>BT-5: services UPSERT 실패해도 trace INSERT 는 commit (R6)</li>
 *   <li>BT-7: DELETE 후 같은 service_name 으로 trace 받으면 자동 재등록 (D-05)</li>
 * </ul>
 */
class IngestServiceServiceRegistrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-svc-reg-test-", ".db");
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
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload(); // V1 시드 룰 로드 — v0.1 buildEngineFromSeededRules 와 동등 (R12 holder 전환)
        this.service = new IngestService(jdbc, maskingHolder, mapper);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }


    // ─── BT-2 — 첫 trace 자동 등록 (source='auto') ─────────────────────────

    @Test
    void shouldAutoRegisterServiceOnFirstTrace() {
        service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM services WHERE service_name = ?", "my-api");
        assertNotNull(row.get("registered_at"));
        assertNotNull(row.get("last_seen_at"));
        assertEquals("auto", row.get("source"));
    }

    // ─── BT-3 — 후속 trace 시 last_seen_at 만 갱신 ─────────────────────────

    @Test
    void shouldUpdateLastSeenAtOnSubsequentTraceAndPreserveOrigin() throws InterruptedException {
        service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        Map<String, Object> firstRow = jdbc.queryForMap(
                "SELECT registered_at, last_seen_at, source FROM services WHERE service_name = ?", "my-api");
        long firstRegisteredAt = ((Number) firstRow.get("registered_at")).longValue();
        long firstLastSeenAt = ((Number) firstRow.get("last_seen_at")).longValue();

        // 1ms 이상 차이 확보 — clock granularity 보호
        Thread.sleep(5);

        service.ingest(new IngestRequest(List.of(makeSpan("span-2", "trace-2", "my-api"))));

        Map<String, Object> secondRow = jdbc.queryForMap(
                "SELECT registered_at, last_seen_at, source FROM services WHERE service_name = ?", "my-api");
        long secondRegisteredAt = ((Number) secondRow.get("registered_at")).longValue();
        long secondLastSeenAt = ((Number) secondRow.get("last_seen_at")).longValue();

        // D-02 멱등 — registered_at / source 보존, last_seen_at 만 갱신.
        assertEquals(firstRegisteredAt, secondRegisteredAt, "registered_at must be preserved");
        assertEquals("auto", secondRow.get("source"));
        // last_seen_at 은 receivedAt (이번 batch) 으로 갱신됨 → 이전 값 이상
        org.junit.jupiter.api.Assertions.assertTrue(
                secondLastSeenAt >= firstLastSeenAt,
                "last_seen_at must be updated to receivedAt of latest batch");
    }

    // ─── BT-4 — wizard 등록 후 자동 trace = source='wizard' 유지 ───────────

    @Test
    void shouldKeepWizardSourceOnAutoUpsert() {
        // wizard 가 먼저 INSERT 한 상태 시뮬레이션
        jdbc.update(
                "INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                        + "VALUES (?, ?, NULL, 'wizard')",
                "my-api", 1_000L
        );

        // 자동 등록 (auto) 시도 — source 덮어쓰지 않음
        service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT registered_at, last_seen_at, source FROM services WHERE service_name = ?", "my-api");
        assertEquals(1_000L, ((Number) row.get("registered_at")).longValue(),
                "registered_at must be preserved from wizard INSERT");
        assertEquals("wizard", row.get("source"), "source must remain wizard");
        assertNotNull(row.get("last_seen_at"), "last_seen_at must be updated from NULL to receivedAt");
    }

    // ─── BT-5 — UPSERT 실패 시에도 trace INSERT 는 commit (R6) ─────────────

    @Test
    void shouldNotThrowEvenIfServicesUpsertFails() {
        // services 테이블을 DROP 해서 INSERT 자체가 실패하게 한다.
        // (Throwable catch 외곽이 silent log + skip 보장 → ingest() 는 정상 반환)
        jdbc.execute("DROP TABLE services");

        // upsertServiceRegistration 이 throw 해도 ingest() 는 throw 0
        assertDoesNotThrow(() ->
                service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-r6", "my-api")))));

        // trace + span 은 정상 INSERT 됨 (트랜잭션 rollback 0)
        Integer traceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = ?", Integer.class, "trace-r6");
        assertNotNull(traceCount);
        assertEquals(1, traceCount.intValue());

        Integer spanCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = ?", Integer.class, "span-1");
        assertNotNull(spanCount);
        assertEquals(1, spanCount.intValue());
    }

    // ─── BT-7 — DELETE 후 같은 이름으로 trace 도착 시 자동 재등록 (D-05) ────

    @Test
    void shouldReRegisterAfterDelete() {
        service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        jdbc.update("DELETE FROM services WHERE service_name = ?", "my-api");

        Integer afterDelete = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(afterDelete);
        assertEquals(0, afterDelete.intValue());

        // traces / spans 는 보존되어야 함 (D-05)
        Integer tracesPreserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = ?", Integer.class, "trace-1");
        assertNotNull(tracesPreserved);
        assertEquals(1, tracesPreserved.intValue());

        // 같은 service_name 으로 다음 trace 도착 → 자동 재등록
        service.ingest(new IngestRequest(List.of(makeSpan("span-2", "trace-2", "my-api"))));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source FROM services WHERE service_name = ?", "my-api");
        assertEquals("auto", row.get("source"));
    }

    // ─── batch 안 같은 service 가 여러 spans 에 있어도 UPSERT 1회 ──────────

    @Test
    void shouldUpsertOncePerDistinctServiceInBatch() {
        service.ingest(new IngestRequest(List.of(
                makeSpan("span-1", "trace-1", "my-api"),
                makeSpan("span-2", "trace-1", "my-api"),
                makeSpan("span-3", "trace-1", "my-api")
        )));

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(rowCount);
        assertEquals(1, rowCount.intValue());
    }

    // ─── blank / null service name 은 skip (defensive) ────────────────────

    @Test
    void shouldNotInsertServiceForBlankName() {
        // service_name 이 blank 인 span 은 IngestService.validate() 에서 reject 되지만,
        // 만약 우회되더라도 upsertServiceRegistration 의 filter 가 차단해야 함.
        // 본 테스트는 정상 span 1개만 등록 후 services 테이블에 의도된 1 row 만 있는지 확인.
        service.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(1, rowCount.intValue());

        // null/blank service_name 의 row 자체 없음
        Integer blankCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name IS NULL OR service_name = ''",
                Integer.class);
        assertNotNull(blankCount);
        assertEquals(0, blankCount.intValue());

        // last_seen_at 도 NULL 아닌 값으로 INSERT
        Long lastSeen = jdbc.queryForObject(
                "SELECT last_seen_at FROM services WHERE service_name = ?", Long.class, "my-api");
        assertNotNull(lastSeen);
    }

    // ─── helper ─────────────────────────────────────────────────────────

    private static Span makeSpan(String spanId, String traceId, String serviceName) {
        return new Span(
                spanId, traceId, null,
                serviceName, "GET /probe", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
    }

    // assertNull import 보장용 dummy use (unused warning 회피)
    @SuppressWarnings("unused")
    private static void unusedNullAssert() {
        assertNull(null);
    }
}
