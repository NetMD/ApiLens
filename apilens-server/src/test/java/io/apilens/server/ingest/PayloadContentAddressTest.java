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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.MaskingEngine;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.RegexTimeoutException;
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
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R25] AC-25-01-1 ~ AC-25-01-7 — payload 본문을 저장소 전체에서 <b>한 번만</b> 저장한다.
 *
 * <p>AC-25-01-1 원문: "새로 들어온 payload 행은 본문을 자기 안에 담지 않고 <b>가리키기만</b> 한다.
 * 판정: 새 형태 행에서 {@code body} 와 지문이 <b>동시에 채워진 행이 0</b>." (비협상 — UD-1)
 *
 * <p><b>정방향 단언만 쓴다</b>: 이 시험들이 확인하는 것은 "본문이 한 벌만 저장되고 읽으면 원래 값이
 * 돌아온다" 이지 "무엇을 거부한다" 가 아니다.
 *
 * <p>★<b>기대값이 0 이 될 수 있는 자리에는 전제를 먼저 세운다</b> — 빈 DB 에서 {@code 0 == 0} 으로
 * 통과하는 시험은 아무것도 검증하지 않는다.
 */
class PayloadContentAddressTest {

    /** 계측기가 먼저 자르는 크기(agent 기본). 이 크기에서는 서버 가드가 발동하지 않아야 한다. */
    private static final int AGENT_LIMIT_BYTES = 65_536;

    /** 수집기 가드 한도(기본). 이 값을 <b>1바이트 넘기면</b> 서버가 자른다. */
    private static final long SERVER_LIMIT_BYTES = 1_048_576L;

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private MaskingEngineHolder maskingHolder;
    private IngestService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-cas-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        this.service = new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(SERVER_LIMIT_BYTES));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 본체 ────────────────────────────────────────────────────────────

    /** TS-R25-05 — 같은 본문 두 payload → 본문 표 <b>1행</b> · payloads 의 body 는 비어 있다. */
    @Test
    void storesOneBodyRowWhenTwoPayloadsCarryTheSameBody() {
        String body = "{\"same\":\"content\"}";
        service.ingest(request(span("s1", "t1", payload(PayloadDirection.IN, body))));
        service.ingest(request(span("s2", "t2", payload(PayloadDirection.IN, body))));

        assertEquals(2, count("SELECT COUNT(*) FROM payloads"), "행은 둘이다");
        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"), "본문은 한 벌만 저장된다");
        assertEquals(0, count("SELECT COUNT(*) FROM payloads WHERE body IS NOT NULL"),
                "새 형태 행은 본문을 자기 안에 담지 않는다");
        assertEquals(0, count("SELECT COUNT(*) FROM payloads WHERE body IS NOT NULL AND body_hash IS NOT NULL"),
                "AC-25-01-1 판정: 두 형태를 동시에 가진 행은 0 이다");
        assertEquals(body, storedBodyOf("s1"), "읽으면 원래 값이 그대로 돌아온다");
        assertEquals(body, storedBodyOf("s2"));
    }

    /** TS-R25-06 — <b>재적재 무중복</b>: 같은 묶음을 두 번 넣어도 표가 안 는다. */
    @Test
    void keepsTheBodyTableUnchangedWhenTheSameChunkIsIngestedTwice() {
        IngestRequest req = request(span("s1", "t1", payload(PayloadDirection.IN, "{\"k\":1}")));
        service.ingest(req);
        int payloadsAfterFirst = count("SELECT COUNT(*) FROM payloads");
        int bodiesAfterFirst = count("SELECT COUNT(*) FROM payload_bodies");
        assertEquals(1, bodiesAfterFirst, "전제: 첫 적재로 본문 행이 실제로 하나 생겨야 대조가 뜻을 가진다");

        service.ingest(req); // 재적재

        assertEquals(payloadsAfterFirst, count("SELECT COUNT(*) FROM payloads"),
                "payload 는 delete-then-insert 라 중복 0");
        assertEquals(bodiesAfterFirst, count("SELECT COUNT(*) FROM payload_bodies"),
                "본문 표는 지문이 열쇠라 아무 일도 안 일어난다");
        assertEquals("{\"k\":1}", storedBodyOf("s1"), "재적재 뒤에도 읽는 값이 같다");
    }

    /** TS-R25-07 — <b>새 키 첫 적재</b>가 한 번만 넣은 것과 같다(지울 것이 0행인 경로). */
    @Test
    void firstIngestOfANewKeyMatchesASingleInsert() {
        assertEquals(0, count("SELECT COUNT(*) FROM payload_bodies"),
                "전제: 시작 상태가 비어 있어야 '첫 적재' 라는 말이 성립한다");

        service.ingest(request(span("s-new", "t1", payload(PayloadDirection.IN, "first"))));

        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"));
        assertEquals(1, count("SELECT COUNT(*) FROM payloads WHERE span_id = 's-new'"));
        assertEquals("first", storedBodyOf("s-new"));
        assertEquals(5L, jdbc.queryForObject(
                "SELECT body_bytes FROM payload_bodies WHERE body_hash = ?", Long.class, sha256Hex("first")));
    }

    /** TS-R25-08 (BV-R25-01) — 본문 없음은 <b>정상 입력</b>이다. 표에 아무것도 안 만든다. */
    @Test
    void treatsAMissingBodyAsANormalRowWithoutTouchingTheBodyTable() {
        // 전제 — 대조 앞에 시작값을 먼저 재서 "빈 DB 에서 0 == 0" 통과를 막는다.
        int bodiesBefore = count("SELECT COUNT(*) FROM payload_bodies");
        assertEquals(0, bodiesBefore);

        service.ingest(request(span("s-null", "t1",
                new Payload(PayloadDirection.IN, "application/json", null, 0L, false))));

        assertEquals(1, count("SELECT COUNT(*) FROM payloads WHERE span_id = 's-null'"), "행 자체는 만들어진다");
        assertEquals(bodiesBefore, count("SELECT COUNT(*) FROM payload_bodies"),
                "본문 표 행 순증 0 — 계측이 값을 못 잡은 빈 자리표다");
        assertNull(jdbc.queryForObject(
                "SELECT body_hash FROM payloads WHERE span_id = 's-null'", String.class));
        assertNull(storedBodyOf("s-null"), "읽은 값도 오늘과 같다(NULL)");
    }

    /** TS-R25-09 (BV-R25-02) — 빈 문자열은 평범한 값이다. 지문 한 행이 생기고 크기는 0 이다. */
    @Test
    void storesAnEmptyStringBodyAsAnOrdinaryValue() {
        String emptyHash = sha256Hex("");
        assertEquals(0, count("SELECT COUNT(*) FROM payload_bodies WHERE body_hash = '" + emptyHash + "'"),
                "전제: 삽입 전 그 지문 행이 없어야 대조가 뜻을 가진다");

        service.ingest(request(span("s-empty", "t1", payload(PayloadDirection.IN, ""))));

        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT body_bytes FROM payload_bodies WHERE body_hash = ?", Long.class, emptyHash));
        assertEquals("", storedBodyOf("s-empty"), "읽은 값도 빈 문자열이다(NULL 이 아니다)");
    }

    /** TS-R25-10 (BV-R25-04) — 수집기 가드가 자르면 <b>잘린 뒤의 바이트</b>가 지문 대상이다. */
    @Test
    void hashesTheTruncatedBodyWhenTheServerGuardFires() {
        String body = "x".repeat((int) SERVER_LIMIT_BYTES + 1);
        service.ingest(request(span("s-cut", "t1",
                new Payload(PayloadDirection.IN, "text/plain", body, body.length(), false))));

        // 전제 — 절단이 실제로 일어났는가. 안 일어났으면 아래 대조가 다른 것을 재는 셈이다.
        assertEquals(1, jdbc.queryForObject(
                "SELECT truncated FROM payloads WHERE span_id = 's-cut'", Integer.class),
                "전제: 가드가 실제로 발동해야 '절단된 바이트의 지문' 을 잴 수 있다");

        String stored = storedBodyOf("s-cut");
        assertNotNull(stored);
        assertEquals(SERVER_LIMIT_BYTES, stored.getBytes(StandardCharsets.UTF_8).length, "한도까지 잘려 저장된다");
        assertEquals(sha256Hex(stored), jdbc.queryForObject(
                "SELECT body_hash FROM payloads WHERE span_id = 's-cut'", String.class),
                "지문 대상은 마스킹·절단을 거친 저장 바이트 그대로다");
    }

    /** TS-R25-11 (BV-R25-05) — 정규식 격하 본문은 <b>한 지문</b>으로 모인다. */
    @Test
    void collapsesDegradedBodiesIntoASingleFingerprint() {
        MaskingEngine throwing = Mockito.mock(MaskingEngine.class);
        Mockito.when(throwing.mask(Mockito.any(), Mockito.any())).thenThrow(RegexTimeoutException.INSTANCE);
        IngestService degrading = new IngestService(jdbc,
                new ThrowingMaskingHolder(new MaskingRuleRepository(jdbc), mapper, throwing),
                mapper, new IngestProperties(SERVER_LIMIT_BYTES));

        degrading.ingest(request(span("s-d1", "t1", payload(PayloadDirection.IN, "{\"a\":\"evil-1\"}"))));
        degrading.ingest(request(span("s-d2", "t2", payload(PayloadDirection.IN, "{\"b\":\"evil-2\"}"))));

        // 전제 — 격하가 실제로 발동했는가(저장값이 상수 마스킹인가).
        assertEquals("***", storedBodyOf("s-d1"), "전제: 격하가 발동해야 '한 지문으로 모인다' 를 잴 수 있다");
        assertEquals("***", storedBodyOf("s-d2"));
        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"),
                "서로 다른 원문이었어도 저장되는 값이 같으면 본문은 한 벌이다");
    }

    /** TS-R25-12 (BV-R25-06) — 같은 본문이 양쪽 방향에 있어도 저장은 한 벌이다. */
    @Test
    void storesOneBodyWhenTheSameContentAppearsInBothDirections() {
        String body = "{\"echo\":true}";
        service.ingest(request(span("s-both", "t1",
                payload(PayloadDirection.IN, body), payload(PayloadDirection.OUT, body))));

        assertEquals(2, count("SELECT COUNT(*) FROM payloads WHERE span_id = 's-both'"),
                "전제: 두 행이 실제로 저장돼야 '한 벌' 이라는 말이 뜻을 가진다");
        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"));
        assertEquals(List.of("in", "out"), jdbc.queryForList(
                "SELECT direction FROM payloads WHERE span_id = 's-both' ORDER BY payload_id", String.class));
    }

    /** TS-R25-13 (BV-R25-03) — 계측기 상한과 같은 크기에서는 서버 가드가 안 돈다. */
    @Test
    void storesTheBodyUntouchedAtTheAgentLimitSize() {
        assertEquals(SERVER_LIMIT_BYTES, 1_048_576L,
                "전제: 수집기 한도가 계측기 상한보다 커야 이 크기에서 가드가 idle 이다");
        String body = "y".repeat(AGENT_LIMIT_BYTES);

        service.ingest(request(span("s-64k", "t1",
                new Payload(PayloadDirection.IN, "text/plain", body, AGENT_LIMIT_BYTES, false))));

        assertEquals(0, jdbc.queryForObject(
                "SELECT truncated FROM payloads WHERE span_id = 's-64k'", Integer.class), "절단 없음");
        assertEquals(1, count("SELECT COUNT(*) FROM payload_bodies"));
        assertEquals(body, storedBodyOf("s-64k"), "무손실");
        assertEquals((long) AGENT_LIMIT_BYTES, jdbc.queryForObject(
                "SELECT body_bytes FROM payload_bodies WHERE body_hash = ?", Long.class, sha256Hex(body)));
    }

    /**
     * TS-R25-14 — 지문이 같은데 크기가 다르면 <b>먼저 있던 본문을 지키고</b> 경고를 남긴다.
     *
     * <p>실물 충돌은 만들 수 없으므로(SHA-256) 표에 <b>어긋난 행을 미리 심어</b> 같은 갈래를 밟는다.
     * ★이 대조는 <b>크기가 다를 때만</b> 안다 — 크기가 같고 내용만 다른 충돌은 못 잡는다.
     * 틀리는 방향은 안전한 쪽(먼저 것 보존)이다.
     */
    @Test
    void keepsTheEarlierBodyAndWarnsWhenTheSameFingerprintCarriesADifferentSize() {
        String incoming = "abc";
        String hash = sha256Hex(incoming);
        jdbc.update("INSERT INTO payload_bodies (body_hash, body, body_bytes, first_seen_at) VALUES (?, ?, ?, ?)",
                hash, "PRESERVED", 999L, 1L);
        assertEquals(999L, jdbc.queryForObject(
                "SELECT body_bytes FROM payload_bodies WHERE body_hash = ?", Long.class, hash),
                "전제: 어긋난 행이 실제로 심어져야 이 갈래를 밟는다");

        ch.qos.logback.classic.Logger logger = ingestLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            service.ingest(request(span("s-col", "t1", payload(PayloadDirection.IN, incoming))));
        } finally {
            detach(logger, appender);
        }

        assertEquals("PRESERVED", jdbc.queryForObject(
                "SELECT body FROM payload_bodies WHERE body_hash = ?", String.class, hash),
                "먼저 있던 본문을 덮지 않는다");
        assertEquals(999L, jdbc.queryForObject(
                "SELECT body_bytes FROM payload_bodies WHERE body_hash = ?", Long.class, hash));
        assertTrue(messages(appender, Level.WARN).stream()
                        .anyMatch(m -> m.startsWith("payload body hash collision:")),
                "어긋남이 경고 한 줄로 남는다");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private static IngestRequest request(Span... spans) {
        return new IngestRequest(List.of(spans));
    }

    private static Span span(String spanId, String traceId, Payload... payloads) {
        return new Span(spanId, traceId, null, "svc", "GET /x", SpanKind.SERVER,
                1_000L, 1_001L, SpanStatus.OK, null, List.of(payloads));
    }

    private static Payload payload(PayloadDirection direction, String body) {
        long size = body == null ? 0L : body.getBytes(StandardCharsets.UTF_8).length;
        return new Payload(direction, "application/json", body, size, false);
    }

    /** 저장된 본문을 읽는 단일 자리 — {@code TraceQueryRepository.findPayloads} 와 같은 모양이다. */
    private String storedBodyOf(String spanId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(pb.body, p.body) FROM payloads p "
                        + "LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash "
                        + "WHERE p.span_id = ?",
                String.class, spanId);
    }

    private int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }

    /** 제품 코드와 <b>독립으로</b> 계산한 지문 — 같은 함수를 불러 서로를 증명하는 순환을 피한다. */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ch.qos.logback.classic.Logger ingestLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestService.class);
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

    /** current() 가 확정 throw 엔진을 반환하는 테스트 전용 holder(ReDoS 시험과 같은 관용구). */
    private static final class ThrowingMaskingHolder extends MaskingEngineHolder {
        private final MaskingEngine engine;

        private ThrowingMaskingHolder(MaskingRuleRepository repository, ObjectMapper mapper, MaskingEngine engine) {
            super(repository, mapper);
            this.engine = engine;
        }

        @Override
        public MaskingEngine current() {
            return engine;
        }
    }
}
