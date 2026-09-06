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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestProperties;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.instrument.InstrumentAnalysisRepository;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.query.dto.PayloadDto;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R25] AC-25-03-1 ~ AC-25-03-5 — <b>옛 형태 읽기 폴백</b>. 이 파일이 폴백 갈래의 <b>정본 시험</b>이다.
 *
 * <p>AC-25-03-1 원문 (비협상 — UD-3): "이미 쌓여 있는 행은 <b>한 줄도 안 바꾼다</b>(백필 없음).
 * 판정: 마이그레이션 앞뒤로 기존 {@code payloads.body} 값이 바이트 단위로 같다."
 *
 * <p>AC-25-03-2 원문: "읽는 경로는 두 형태를 <b>같은 코드</b>가 다룬다 — 새 행이면 표에서 되돌리고,
 * 옛 행이면 자기 열에서 읽는다. 판정: 옛 행 하나와 새 행 하나가 <b>같은 값</b>으로 나온다."
 *
 * <p>★<b>왜 이 시험이 필요한가</b>: 폴백 갈래를 지우면 그 사이의 본문이 <b>에러 없이 빈 값</b>으로
 * 나온다. 예외가 안 뜨므로 아무도 모른다 — 세 자리(읽기 SQL·순위표 질의·직접 절감 질의)의 주석이
 * 이 파일의 {@link #oldFormPayloadRowReadsTheSameAsNewForm} 를 정본으로 가리킨다.
 *
 * <p>★<b>한계</b>: 이 폴백은 올린 뒤 약 이틀(retention.days=1 + 04:00 정리)이 지나면 아무 데이터도
 * 안 밟는다 — 다음 정비 라운드가 "죽은 코드" 로 볼 자리다. 그래서 지우지 말라는 근거를 시험으로 남긴다.
 *
 * <p><b>정방향 단언만 쓴다</b>: 확인하는 것은 "옛 행이 그대로 읽힌다" 이지 "무엇을 거부한다" 가 아니다.
 */
class LegacyRowFallbackTest {

    private static final String OLD_BODY = "{\"legacy\":\"row stored before v0.7.0\"}";
    private static final String SERVICE = "svc-legacy";

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService ingestService;
    private TraceQueryRepository queryRepository;
    private InstrumentAnalysisRepository analysisRepository;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-legacy-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        this.ingestService = new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
        this.queryRepository = new TraceQueryRepository(jdbc, mapper);
        this.analysisRepository = new InstrumentAnalysisRepository(jdbc);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 본체 ────────────────────────────────────────────────────────────

    /**
     * TS-R25-22 — <b>정본 시험</b>. 세 자리의 폴백 주석이 이 메서드 이름을 그대로 가리킨다.
     *
     * <p>옛 형태 행(본문이 {@code payloads.body} 안에 있고 지문이 NULL)과 새 형태 행(본문이
     * {@code payload_bodies} 에 있고 행은 지문만 가짐)이 상세 조회에서 <b>같은 값</b>으로 나온다.
     */
    @Test
    void oldFormPayloadRowReadsTheSameAsNewForm() {
        insertOldFormRow("span-old", "trace-old", OLD_BODY);
        ingestNewFormRow("span-new", "trace-new", OLD_BODY);

        // 전제: 두 행이 실제로 서로 다른 형태다 (빈 DB 에서 0 == 0 으로 통과하는 것을 막는다).
        assertEquals(1, countOldFormRows(), "전제: 옛 형태 행(지문 없음 + 본문 있음)이 실제로 하나 있어야 한다");
        assertEquals(1, countNewFormRows(), "전제: 새 형태 행(지문 있음 + 본문 비어 있음)이 실제로 하나 있어야 한다");

        List<PayloadDto> oldRead = queryRepository.findPayloads("span-old");
        List<PayloadDto> newRead = queryRepository.findPayloads("span-new");

        assertEquals(1, oldRead.size());
        assertEquals(1, newRead.size());
        assertEquals(OLD_BODY, oldRead.get(0).body(), "옛 행은 자기 열에서 읽힌다");
        assertEquals(OLD_BODY, newRead.get(0).body(), "새 행은 본문 표에서 되돌아온다");
        assertEquals(oldRead.get(0).body(), newRead.get(0).body(),
                "AC-25-03-2 판정: 옛 행 하나와 새 행 하나가 같은 값으로 나온다");
    }

    /**
     * TS-R25-23 — 옛 형태 행의 분석 {@code payloadBytes} 가 <b>옛 값 그대로</b>다.
     *
     * <p>순위표 질의는 {@code COALESCE(pb.body_bytes, length(CAST(p.body AS BLOB)))} 로 두 형태를 함께
     * 다룬다. 옛 행은 결합이 안 붙으므로 오른쪽 갈래(자기 열의 본문 길이)로 떨어져야 한다.
     */
    @Test
    void countsTheStoredBytesOfOldFormRowsExactlyAsBefore() {
        String ascii = "x".repeat(120);   // ASCII 만 — 한 글자 = 1바이트라 길이가 곧 바이트다.
        insertTrace("trace-old", 1_000L);
        insertSpan("span-old", "trace-old", "com.acme.web.LegacyController#list", 1_000L);
        insertPayloadRowInline("span-old", ascii);

        // 전제: 이 행이 실제로 옛 형태다.
        assertEquals(1, countOldFormRows(), "전제: 옛 형태 행이 실제로 있어야 대조가 뜻을 가진다");

        List<InstrumentAnalysisRepository.PayloadRow> rows =
                analysisRepository.aggregatePayloadsByClass(SERVICE, 0L, 2_000L, 2000);

        assertEquals(1, rows.size());
        assertEquals(120L, rows.get(0).payloadBytes(),
                "옛 행은 자기 열의 본문 길이로 센다 — 폴백 갈래를 지우면 여기가 0 이 된다");
    }

    /**
     * TS-R25-24 (BV-R25-07) — 옛 행과 새 행이 <b>섞여 있어도</b> 상세와 분석이 같은 기준으로 읽는다.
     *
     * <p>운영에서 실제로 일어나는 상태다: 올린 순간부터 약 이틀간 두 형태가 한 DB 안에 함께 있다.
     */
    @Test
    void readsAMixOfOldAndNewRowsWithTheSameNumbers() {
        String body = "y".repeat(64);
        insertTrace("trace-mix", 1_000L);
        insertSpan("span-mix-old", "trace-mix", "com.acme.web.MixController#list", 1_000L);
        insertPayloadRowInline("span-mix-old", body);
        insertSpan("span-mix-new", "trace-mix", "com.acme.web.MixController#list", 1_010L);
        insertNewFormPayloadRow("span-mix-new", body);

        // 전제: 두 형태가 실제로 하나씩 있다.
        assertEquals(1, countOldFormRows(), "전제: 옛 형태 행 1");
        assertEquals(1, countNewFormRows(), "전제: 새 형태 행 1");

        assertEquals(body, queryRepository.findPayloads("span-mix-old").get(0).body());
        assertEquals(body, queryRepository.findPayloads("span-mix-new").get(0).body());

        List<InstrumentAnalysisRepository.PayloadRow> rows =
                analysisRepository.aggregatePayloadsByClass(SERVICE, 0L, 2_000L, 2000);
        assertEquals(1, rows.size(), "두 span 이 같은 클래스라 한 줄로 묶인다");
        assertEquals(2L, rows.get(0).payloadCount());
        assertEquals(128L, rows.get(0).payloadBytes(),
                "참조당 합 — 옛 행 64 + 새 행 64. 두 형태가 같은 기준으로 더해진다");
    }

    // ─── 픽스처 ──────────────────────────────────────────────────────────

    /**
     * 옛 형태 payload 행 — 본문을 {@code payloads.body} 에 직접 넣고 지문은 안 넣는다.
     * v0.7.0 을 올리기 <b>전에</b> 쌓인 행의 모양 그대로다(백필을 하지 않으므로 이 모양이 그대로 남는다).
     */
    private void insertOldFormRow(String spanId, String traceId, String body) {
        insertTrace(traceId, 1_000L);
        insertSpan(spanId, traceId, "com.acme.web.LegacyController#list", 1_000L);
        insertPayloadRowInline(spanId, body);
    }

    /** 새 형태 payload 행 — 실제 적재 경로를 그대로 태운다(수동으로 흉내 내지 않는다). */
    private void ingestNewFormRow(String spanId, String traceId, String body) {
        long now = 1_000L;
        Span span = new Span(spanId, traceId, null, SERVICE, "com.acme.web.MixController#list",
                SpanKind.SERVER, now, now + 10, SpanStatus.OK, Map.of(),
                List.of(new Payload(PayloadDirection.OUT, "application/json", body,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, false)));
        ingestService.ingest(new IngestRequest(List.of(span)));
    }

    private void insertTrace(String traceId, long startTime) {
        jdbc.update("""
                        INSERT OR IGNORE INTO traces (trace_id, root_operation, service_name, start_time,
                                                      duration_ms, status, span_count, service_count,
                                                      has_error, received_at)
                        VALUES (?, 'GET /x', ?, ?, 10, 'OK', 1, 1, 0, ?)
                        """,
                traceId, SERVICE, startTime, startTime);
    }

    private void insertSpan(String spanId, String traceId, String operation, long startTime) {
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, ?, ?, 'SERVER', ?, ?, 'OK', NULL)
                        """,
                spanId, traceId, SERVICE, operation, startTime, startTime + 10);
    }

    /** 지문 없이 본문만 든 행 — {@code body_hash} 를 아예 안 싣는다(열 기본값 NULL). */
    private void insertPayloadRowInline(String spanId, String body) {
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'out', 'application/json', ?, ?, 0)
                        """,
                spanId, body, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /** 본문 표에 한 벌 + 행에는 지문만 — 적재 경로가 만드는 모양을 직접 만든 것. */
    private void insertNewFormPayloadRow(String spanId, String body) {
        String hash = sha256Hex(body);
        int bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        jdbc.update("INSERT OR IGNORE INTO payload_bodies (body_hash, body, body_bytes, first_seen_at)"
                + " VALUES (?, ?, ?, ?)", hash, body, bytes, 1_000L);
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes,
                                              truncated, body_hash)
                        VALUES (?, 'out', 'application/json', NULL, ?, 0, ?)
                        """,
                spanId, bytes, hash);
    }

    /** 옛 형태 판별식 — <b>지문이 없고 본문이 있는</b> 행. "지문이 없는 행" 만 세면 본문 없는 정상 행이 섞인다. */
    private int countOldFormRows() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE body_hash IS NULL AND body IS NOT NULL", Integer.class);
        return c == null ? 0 : c;
    }

    private int countNewFormRows() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE body_hash IS NOT NULL AND body IS NULL", Integer.class);
        assertNotNull(c);
        assertTrue(c >= 0);
        return c;
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
