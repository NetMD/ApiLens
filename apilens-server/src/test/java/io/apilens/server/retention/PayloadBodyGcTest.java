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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.server.ingest.IngestPauseState;
import io.apilens.server.ingest.IngestProperties;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R25] AC-25-01-5/AC-25-01-6/AC-25-01-8 — <b>본문 정리</b>. 아무 {@code payloads} 행도 안
 * 가리키는 본문을 밤 정리와 전체 지우기 뒤에 지운다.
 *
 * <p>AC-25-01-5 원문: "아무 행도 안 가리키게 된 본문은 <b>밤 정리와 전체 지우기 뒤에</b> 지운다.
 * 판정: 정리 뒤 '아무도 안 가리키는 본문' 수가 0."
 *
 * <p>AC-25-01-8 원문 (비협상 — CXP-2): "본문 정리가 던져도 <b>마무리 단계는 그대로 돈다</b>.
 * 판정: 예약 경로는 정리 시각이 갱신되고, 수동 경로는 200 을 돌려준다."
 *
 * <p>★<b>참조 개수를 세어 두지 않는다.</b> 스캔 방식은 느리지만 <b>틀릴 수 없고</b>, 어긋난 계수는
 * 시험이 못 잡는다. 그래서 이 파일은 계수가 아니라 <b>실제로 남은 행</b>만 본다.
 *
 * <p>★<b>이 파일이 설계 §1.3 의 실행 게이트 {@code RG-1} 을 겸한다</b> — 서브쿼리 {@code LIMIT} 형태의
 * DELETE 가 이 드라이버(sqlite-jdbc)에서 실제로 도는지는 코드를 읽어서는 알 수 없고, 여기서 실행으로 안다.
 * {@code DELETE ... LIMIT} 은 SQLite 컴파일 옵션에 의존하므로 그 형태를 쓰지 않았다.
 *
 * <p><b>정방향 단언만 쓴다</b>: 확인하는 것은 "안 가리키는 본문이 지워지고 가리키는 본문은 남는다" 이지
 * "무엇을 거부한다" 가 아니다.
 */
class PayloadBodyGcTest {

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private PlatformTransactionManager txManager;
    private RetentionCleanupService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-body-gc-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        this.dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.txManager = new DataSourceTransactionManager(dataSource);
        SettingsService settingsService = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        this.service = new RetentionCleanupService(jdbc, txManager, settingsService);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 본체 ────────────────────────────────────────────────────────────

    /**
     * TS-R25-25 — 아무도 안 가리키게 된 본문이 <b>밤 정리 뒤에</b> 지워진다.
     *
     * <p>★이 시험이 설계 {@code RG-1}(서브쿼리 {@code LIMIT} DELETE 가 이 드라이버에서 도는가)의
     * 실행 증거다. 안 돌면 여기가 빨개진다.
     */
    @Test
    void removesBodiesThatNoRowPointsToAfterTheNightlyCleanup() {
        long now = System.currentTimeMillis();
        String expiredHash = seedTraceWithBody("t-expired", now - 100 * DAY_MS, "expired body");

        // 전제: 정리 전에 그 본문이 실제로 있고, 실제로 참조되고 있다(빈 DB 에서 0 == 0 통과 방지).
        assertEquals(1, countBodies(), "전제: 본문 표에 행이 실제로 하나 있어야 한다");
        assertEquals(1, countPayloadsPointingTo(expiredHash), "전제: 그 본문을 가리키는 행이 실제로 있어야 한다");

        service.cleanup(now);

        assertEquals(0, countBodies(), "AC-25-01-5 판정: 정리 뒤 아무도 안 가리키는 본문 수가 0");
        assertEquals(0, countUnreferencedBodies());
    }

    /** TS-R25-26 — 아직 가리키는 행이 있는 본문은 <b>남는다</b>(끊어진 참조 0). */
    @Test
    void keepsBodiesThatAreStillReferencedAndLeavesNoDanglingRefs() {
        long now = System.currentTimeMillis();
        String expiredHash = seedTraceWithBody("t-expired", now - 100 * DAY_MS, "expired body");
        String liveHash = seedTraceWithBody("t-live", now - DAY_MS, "live body");

        assertEquals(2, countBodies(), "전제: 서로 다른 본문 두 벌");
        assertTrue(!expiredHash.equals(liveHash), "전제: 두 본문의 지문이 실제로 달라야 한다");

        service.cleanup(now);

        assertEquals(1, countBodies(), "살아 있는 trace 의 본문은 남는다");
        assertEquals(1, countPayloadsPointingTo(liveHash), "그 본문을 가리키는 행도 그대로다");
        assertEquals(0, countDanglingRefs(),
                "끊어진 참조 0 — 가리키는 행이 있는데 본문이 사라진 자리가 없다");
    }

    /**
     * TS-R25-27 — 회전 상한에 닿으면 <b>경고를 남기고 멈추되</b>, 다음 실행이 이어서 지운다(멱등).
     *
     * <p>상수값(1,000 × 50)으로는 50,000행을 만들어야 해서 실물로 못 밟는다 — 그래서 회전 크기·상한을
     * 인자로 받는 package-private 갈래를 시험이 직접 부른다. 운영 경로는 언제나 무인자 호출이다.
     *
     * <p>★<b>상한 도달은 실패가 아니다</b>: 막지 않고 다음 실행이 잇는다. 그 "이어서 지운다" 가 이
     * 시험의 본체다 — 한 번 더 부르면 남은 것이 사라진다.
     */
    @Test
    void stopsAtTheRoundCapAndLetsTheNextRunFinishTheRest() {
        for (int i = 0; i < 5; i++) {
            insertUnreferencedBody("orphan-body-" + i);
        }
        assertEquals(5, countUnreferencedBodies(), "전제: 안 가리키는 본문이 실제로 5개 있어야 한다");

        // 회전당 2행 · 상한 2회전 = 최대 4행. 한 번으로는 다 못 지운다.
        service.gcUnreferencedPayloadBodies(2, 2);
        assertEquals(1, countUnreferencedBodies(), "상한에 닿아 1개가 남는다 — 이것이 정상 동작이다");

        // 다음 실행이 이어서 지운다 (스캔 방식이라 밀려도 대상이 안 사라진다).
        service.gcUnreferencedPayloadBodies(2, 2);
        assertEquals(0, countUnreferencedBodies(), "다음 실행이 남은 몫을 지운다");
    }

    /**
     * TS-R25-28 (CXP-2 ①) — <b>예약 경로</b>: 본문 정리가 던져도 마무리 단계가 그대로 돈다.
     *
     * <p>정리 시각 기록·공간 회수·WAL·통계는 {@code finalizeMaintenance} 가 한다. 본문 정리가 예외를
     * 밖으로 던지면 그 전부가 통째로 빠져 <b>그 밤의 기록이 사라진다</b> — 그래서 자체 {@code try-catch}
     * 로 흡수한다.
     *
     * <p>예외는 흉내가 아니라 <b>실제로</b> 낸다: 본문 표를 지워 두면 GC 의 DELETE 가 "그런 표 없음" 으로
     * 던진다. 삭제 자체(3단 배치 DELETE)는 그 앞에서 이미 커밋돼 있다.
     */
    @Test
    void updatesTheCleanupTimestampEvenWhenTheBodyCleanupThrows() {
        long now = System.currentTimeMillis();
        seedTraceWithBody("t-expired", now - 100 * DAY_MS, "expired body");
        Long before = lastCleanupAt();

        jdbc.execute("DROP TABLE payload_bodies");   // 이 뒤로 본문 정리는 반드시 던진다.
        assertEquals(0, countTables("payload_bodies"), "전제: 본문 표가 실제로 없어야 GC 가 던진다");

        RetentionCleanupService.CleanupResult result =
                assertDoesNotThrow(() -> service.cleanup(now), "정리가 던져도 밖으로 안 샌다");

        assertEquals(1, result.deletedTraces(), "삭제 자체는 정상으로 끝난다");
        Long after = lastCleanupAt();
        assertNotNull(after, "AC-25-01-8 판정: 예약 경로의 정리 시각이 갱신된다");
        assertTrue(before == null || after >= before, "정리 시각이 뒤로 가지 않는다");
        assertEquals(now, after.longValue(), "그 밤의 시각이 그대로 기록된다");
    }

    /**
     * TS-R25-29 (CXP-2 ②) — <b>수동 경로</b>: 본문 정리가 던져도 200 과 {@code deletedTraces} 가 정상이다.
     *
     * <p>화면의 [전체 지우기] 는 이 응답 하나만 본다. 정리 실패가 500 으로 새면 사용자는 "지우기가
     * 실패했다" 고 읽지만 <b>실제로는 지워졌다</b> — 그 어긋남을 막는 것이 이 단언이다.
     */
    @Test
    void returnsOkOnTheManualPathEvenWhenTheBodyCleanupThrows() throws Exception {
        long now = System.currentTimeMillis();
        seedTraceWithBody("t-a", now - 100 * DAY_MS, "body a");
        seedTraceWithBody("t-b", now - DAY_MS, "body b");
        assertEquals(2, countTraces(), "전제: 지울 trace 가 실제로 둘 있어야 한다");

        jdbc.execute("DROP TABLE payload_bodies");
        assertEquals(0, countTables("payload_bodies"), "전제: 본문 표가 실제로 없어야 GC 가 던진다");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new MaintenanceController(service, jdbc, new IngestPauseState(), newIngestService())).build();

        mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedTraces").value(2));

        assertEquals(0, countTraces(), "전체 지우기는 그대로 끝난다");
    }

    // ─── 픽스처 ──────────────────────────────────────────────────────────

    private IngestService newIngestService() {
        ObjectMapper mapper = new ObjectMapper();
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        return new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    /** trace 하나 + span 하나 + 새 형태 payload 하나(본문 표 1행 + 지문 참조). 지문을 돌려준다. */
    private String seedTraceWithBody(String traceId, long receivedAt, String body) {
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'GET /x', 'svc', ?, 100, 'OK', 1, 1, 0, ?)
                        """,
                traceId, receivedAt, receivedAt);
        String spanId = traceId + "-span-1";
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, 'svc', 'GET /x', 'SERVER', ?, ?, 'OK', NULL)
                        """,
                spanId, traceId, receivedAt, receivedAt + 100);
        String hash = sha256Hex(body);
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        jdbc.update("INSERT OR IGNORE INTO payload_bodies (body_hash, body, body_bytes, first_seen_at)"
                + " VALUES (?, ?, ?, ?)", hash, body, bytes, receivedAt);
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes,
                                              truncated, body_hash)
                        VALUES (?, 'out', 'application/json', NULL, ?, 0, ?)
                        """,
                spanId, bytes, hash);
        return hash;
    }

    /** 어떤 행도 안 가리키는 본문 한 벌 — 정리 대상 그 자체. */
    private void insertUnreferencedBody(String body) {
        jdbc.update("INSERT OR IGNORE INTO payload_bodies (body_hash, body, body_bytes, first_seen_at)"
                        + " VALUES (?, ?, ?, ?)",
                sha256Hex(body), body, body.getBytes(StandardCharsets.UTF_8).length, 1_000L);
    }

    private int countBodies() {
        return count("SELECT COUNT(*) FROM payload_bodies");
    }

    private int countTraces() {
        return count("SELECT COUNT(*) FROM traces");
    }

    private int countUnreferencedBodies() {
        return count("SELECT COUNT(*) FROM payload_bodies b"
                + " WHERE NOT EXISTS (SELECT 1 FROM payloads p WHERE p.body_hash = b.body_hash)");
    }

    private int countDanglingRefs() {
        return count("SELECT COUNT(*) FROM payloads p WHERE p.body_hash IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM payload_bodies b WHERE b.body_hash = p.body_hash)");
    }

    private int countPayloadsPointingTo(String hash) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE body_hash = ?", Integer.class, hash);
        return c == null ? 0 : c;
    }

    private int countTables(String name) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", Integer.class, name);
        return c == null ? 0 : c;
    }

    private Long lastCleanupAt() {
        return jdbc.queryForObject("SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class);
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
