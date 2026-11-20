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
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.retention.RetentionCleanupService;
import io.apilens.server.retention.RetentionProperties;
import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import io.apilens.server.support.TestMappers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R33] R33/AC-R33-17-2 — 지문 길이가 다른 두 벌(옛 64자 · 새 32자)이 <b>한 DB 안에 함께 있어도</b>
 * 조회는 두 원문을 다 돌려주고, 회수는 길이와 무관하게 참조로만 판정한다.
 *
 * <p>AC-R33-17-2 원문: "공존 · 되돌림 testcase 하나 이상 — 같은 DB 에 64자 행과 32자 행을 둔 뒤 둘 다 원문이 읽힌다".
 * 사용자 명시 비협상 결정(UQ-R33-4 지문 둘 · 32자 · 스키마 0 — 마이그레이션 · 백필 · 읽기 폴백 없이 두 길이가 공존).
 * CLAUDE.md '데이터 모델' 인용 — {@code payload_bodies} · {@code sql_statements} 는 지문 키 표다.
 *
 * <p>심는 모양: ⓐ 64자 묶음은 0.11.1 이 쓴 모양을 <b>손으로</b> 만든다(지금 코드는 64자를 쓰지 않는다)
 * ⓑ 32자 묶음은 <b>실제 적재 경로</b>를 태운다 ⓒ 어떤 행도 가리키지 않는 행을 두 길이로 하나씩 둔다.
 *
 * <p>★한계: 서로 다른 원문이 같은 32자 지문을 내는 충돌은 이 시험이 만들 수 없다 — 재지 않는다
 * (그 모양은 {@code IngestService#fingerprint} javadoc 과 설계 본편 §3.3 정정 판이 적는다).
 *
 * <p><b>정방향 단언만 쓴다</b>: 확인하는 것은 "두 길이가 다 읽히고 참조된 행은 남는다" 이다.
 */
class FingerprintCoexistenceTest {

    private static final String SERVICE = "svc-fp";
    private static final String OPERATION = "com.acme.web.MixController#list";

    private static final String STMT_OLD = "SELECT id FROM orders WHERE status = ?";
    private static final String STMT_NEW = "SELECT id FROM invoices WHERE paid = ?";
    private static final String BODY_OLD = "body written by the 64-character release";
    private static final String BODY_NEW = "body written by the 32-character release";

    private static final String STMT_ORPHAN_OLD = "SELECT 1 FROM orphan_old";
    private static final String STMT_ORPHAN_NEW = "SELECT 1 FROM orphan_new";
    private static final String BODY_ORPHAN_OLD = "orphan body old";
    private static final String BODY_ORPHAN_NEW = "orphan body new";

    private final ObjectMapper mapper = TestMappers.forMasking();

    private final JsonMapper jsonMapper = TestMappers.forServices();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService ingestService;
    private TraceQueryRepository queryRepository;
    private InstrumentAnalysisRepository analysisRepository;
    private RetentionCleanupService cleanupService;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-fp-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(ds);
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        this.ingestService = new IngestService(jdbc, maskingHolder, jsonMapper, new IngestProperties(1_048_576L));
        this.queryRepository = new TraceQueryRepository(jdbc, jsonMapper);
        this.analysisRepository = new InstrumentAnalysisRepository(jdbc);
        this.cleanupService = new RetentionCleanupService(jdbc, new DataSourceTransactionManager(ds),
                new SettingsService(jdbc, new SettingsRegistry(), new RetentionProperties(30, "0 0 4 * * *")));

        seedLegacy64Rows("T64", "S64");
        ingestCurrent32Rows("T32", "S32");
        seedUnreferencedRowsOfBothLengths();
        assertSeededShape();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 본체 ────────────────────────────────────────────────────────────

    @Test
    void bothLengthsResolveOnRead() {
        SpanDto oldSpan = singleSpan("T64");
        assertEquals(STMT_OLD, oldSpan.attributes().get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "64자 참조가 옛 원문으로 풀린다");
        assertFalse(oldSpan.attributes().containsKey(IngestService.STMT_REF_ATTRIBUTE),
                "푼 뒤 응답에 지문 키가 남지 않는다");

        SpanDto newSpan = singleSpan("T32");
        assertEquals(STMT_NEW, newSpan.attributes().get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "32자 참조가 새 원문으로 풀린다");
        assertFalse(newSpan.attributes().containsKey(IngestService.STMT_REF_ATTRIBUTE));

        assertEquals(BODY_OLD, queryRepository.findPayloads("S64").get(0).body(), "64자 지문 payload 본문");
        assertEquals(BODY_NEW, queryRepository.findPayloads("S32").get(0).body(), "32자 지문 payload 본문");

        List<InstrumentAnalysisRepository.PayloadRow> rows =
                analysisRepository.aggregatePayloadsByClass(SERVICE, 0L, 2_000L, 2000);
        assertEquals(1, rows.size(), "두 span 이 같은 클래스라 한 줄로 묶인다");
        assertEquals(2L, rows.get(0).payloadCount());
        assertEquals(utf8Bytes(BODY_OLD) + utf8Bytes(BODY_NEW), rows.get(0).payloadBytes(),
                "두 길이의 본문 크기가 같은 기준으로 더해진다");
    }

    @Test
    void cleanupKeepsReferencedRowsOfBothLengths() {
        cleanupService.cleanupManual();

        Map<Integer, Integer> stmtLengths = lengthHistogram("SELECT stmt_hash FROM sql_statements");
        assertEquals(Map.of(32, 1, 64, 1), stmtLengths, "참조된 SQL 원문은 두 길이 다 남고, 참조 없는 둘은 지워진다");
        assertEquals(List.of(IngestService.fingerprint(STMT_NEW), legacyFingerprint64(STMT_OLD)),
                jdbc.queryForList("SELECT stmt_hash FROM sql_statements ORDER BY length(stmt_hash)", String.class),
                "남은 것 = 두 span 이 가리키는 지문");

        Map<Integer, Integer> bodyLengths = lengthHistogram("SELECT body_hash FROM payload_bodies");
        assertEquals(Map.of(32, 1, 64, 1), bodyLengths, "참조된 본문은 두 길이 다 남고, 참조 없는 둘은 지워진다");
        assertEquals(List.of(IngestService.fingerprint(BODY_NEW), legacyFingerprint64(BODY_OLD)),
                jdbc.queryForList("SELECT body_hash FROM payload_bodies ORDER BY length(body_hash)", String.class));

        assertEquals(2, count("SELECT COUNT(*) FROM traces"), "보관 기간 안의 trace 는 그대로");
        assertEquals(2, count("SELECT COUNT(*) FROM payloads"));
        assertEquals(BODY_OLD, queryRepository.findPayloads("S64").get(0).body(), "회수 뒤에도 64자 행이 읽힌다");
        assertEquals(STMT_OLD, singleSpan("T64").attributes().get(IngestService.DB_STATEMENT_ATTRIBUTE));
    }

    // ─── 심기 ────────────────────────────────────────────────────────────

    /** 0.11.1 이 쓴 모양 — 지문 64자 · span 은 원문 대신 참조 키만 · payload 행은 본문 없이 지문만. */
    private void seedLegacy64Rows(String traceId, String spanId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, ?, ?, 1000, 10, 'OK', 1, 1, 0, ?)
                        """,
                traceId, OPERATION, SERVICE, now);
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, ?, ?, 'SERVER', 1000, 1010, 'OK', ?)
                        """,
                spanId, traceId, SERVICE, OPERATION,
                "{\"" + IngestService.STMT_REF_ATTRIBUTE + "\":\"" + legacyFingerprint64(STMT_OLD) + "\"}");
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, ?)",
                legacyFingerprint64(STMT_OLD), STMT_OLD, now);
        jdbc.update("INSERT INTO payload_bodies (body_hash, body, body_bytes, first_seen_at) VALUES (?, ?, ?, ?)",
                legacyFingerprint64(BODY_OLD), BODY_OLD, utf8Bytes(BODY_OLD), now);
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes,
                                              truncated, body_hash)
                        VALUES (?, 'out', 'text/plain', NULL, ?, 0, ?)
                        """,
                spanId, utf8Bytes(BODY_OLD), legacyFingerprint64(BODY_OLD));
    }

    /** 지금 코드가 쓰는 모양 — 실제 적재 경로를 그대로 태운다(손으로 흉내 내지 않는다). */
    private void ingestCurrent32Rows(String traceId, String spanId) {
        Span span = new Span(spanId, traceId, null, SERVICE, OPERATION, SpanKind.SERVER,
                1_000L, 1_010L, SpanStatus.OK,
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, STMT_NEW),
                List.of(new Payload(PayloadDirection.OUT, "text/plain", BODY_NEW, utf8Bytes(BODY_NEW), false)));
        ingestService.ingest(new IngestRequest(List.of(span)));
    }

    /** 어떤 span · payload 행도 가리키지 않는 행 — 두 길이로 하나씩, 서로 다른 원문. */
    private void seedUnreferencedRowsOfBothLengths() {
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, 1000)",
                legacyFingerprint64(STMT_ORPHAN_OLD), STMT_ORPHAN_OLD);
        jdbc.update("INSERT INTO sql_statements (stmt_hash, statement, first_seen_at) VALUES (?, ?, 1000)",
                IngestService.fingerprint(STMT_ORPHAN_NEW), STMT_ORPHAN_NEW);
        jdbc.update("INSERT INTO payload_bodies (body_hash, body, body_bytes, first_seen_at) VALUES (?, ?, ?, 1000)",
                legacyFingerprint64(BODY_ORPHAN_OLD), BODY_ORPHAN_OLD, utf8Bytes(BODY_ORPHAN_OLD));
        jdbc.update("INSERT INTO payload_bodies (body_hash, body, body_bytes, first_seen_at) VALUES (?, ?, ?, 1000)",
                IngestService.fingerprint(BODY_ORPHAN_NEW), BODY_ORPHAN_NEW, utf8Bytes(BODY_ORPHAN_NEW));
    }

    /** 전제 — 두 길이가 실제로 함께 있다. 비어 있으면 아래 대조가 아무것도 재지 않는다. */
    private void assertSeededShape() {
        assertEquals(Map.of(32, 2, 64, 2), lengthHistogram("SELECT stmt_hash FROM sql_statements"),
                "전제: SQL 원문 표에 32자 2 · 64자 2");
        assertEquals(Map.of(32, 2, 64, 2), lengthHistogram("SELECT body_hash FROM payload_bodies"),
                "전제: 본문 표에 32자 2 · 64자 2");
        assertEquals(Map.of(32, 1, 64, 1), lengthHistogram(
                        "SELECT json_extract(attributes_json, '$.\"" + IngestService.STMT_REF_ATTRIBUTE + "\"')"
                                + " FROM spans WHERE json_extract(attributes_json, '$.\""
                                + IngestService.STMT_REF_ATTRIBUTE + "\"') IS NOT NULL"),
                "전제: span 참조 32자 1 · 64자 1");
        assertEquals(BODY_NEW, jdbc.queryForObject(
                        "SELECT pb.body FROM payloads p JOIN payload_bodies pb ON pb.body_hash = p.body_hash"
                                + " WHERE p.span_id = 'S32'", String.class),
                "전제: 새 본문이 마스킹 없이 그대로 저장됐다");
        assertTrue(legacyFingerprint64(STMT_OLD).startsWith(IngestService.fingerprint(STMT_OLD)),
                "옛 64자의 앞 32자 = 새 지문 — 같은 SHA-256 을 자른 것뿐이다");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private SpanDto singleSpan(String traceId) {
        List<SpanDto> spans = queryRepository.findSpans(traceId);
        assertEquals(1, spans.size(), "전제: trace 마다 span 하나");
        return spans.get(0);
    }

    private Map<Integer, Integer> lengthHistogram(String sqlReturningKeys) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (String key : jdbc.queryForList(sqlReturningKeys, String.class)) {
            out.merge(key.length(), 1, Integer::sum);
        }
        return out;
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    private static long utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 0.11.1 까지 쓰던 지문 — SHA-256 소문자 16진 64자. 옛 행을 손으로 만들 때만 쓴다(이 파일 하나에만 둔다).
     * 지금 코드의 규칙은 {@link IngestService#fingerprint(String)} 하나다.
     */
    private static String legacyFingerprint64(String value) {
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
