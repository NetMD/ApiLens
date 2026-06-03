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
import io.apilens.common.MaskingEngine;
import io.apilens.common.MaskingRule;
import io.apilens.common.MaskingRuleType;
import io.apilens.common.MaskingStrategy;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.query.dto.PayloadDto;
import io.apilens.server.query.dto.PayloadListResponse;
import io.apilens.server.query.dto.ServiceInfo;
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.query.dto.TraceDetailResponse;
import io.apilens.server.query.dto.TraceListResponse;
import io.apilens.server.query.dto.TraceSummary;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests: data flows in via {@link IngestService} (the real path agents
 * will use) and out via {@link TraceQueryService}. No bespoke INSERT helpers —
 * we test the shipping behaviour, not a fixture-only world.
 */
class TraceQueryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private IngestService ingestService;
    private TraceQueryService queryService;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-query-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        MaskingEngine engine = buildEngineFromSeededRules(jdbc);
        this.ingestService = new IngestService(jdbc, engine, mapper);
        this.queryService = new TraceQueryService(new TraceQueryRepository(jdbc, mapper));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private MaskingEngine buildEngineFromSeededRules(JdbcTemplate jdbc) {
        List<MaskingRule> rules = jdbc.query(
                "SELECT name, rule_type, pattern, mask_strategy, enabled FROM masking_rules WHERE enabled = 1",
                (rs, rowNum) -> new MaskingRule(
                        rs.getString("name"),
                        MaskingRuleType.valueOf(rs.getString("rule_type").toUpperCase()),
                        rs.getString("pattern"),
                        MaskingStrategy.valueOf(rs.getString("mask_strategy").toUpperCase()),
                        rs.getInt("enabled") == 1
                )
        );
        return new MaskingEngine(rules, mapper);
    }

    // ─── 1. List round-trip ──────────────────────────────────────────────

    @Test
    void ingestThenListReturnsPersistedTrace() {
        ingestSimpleTrace("trace-list-1", "checkout", 1_000L, 1_120L, SpanStatus.OK);

        TraceListResponse response = queryService.listTraces(
                null, null, null, null, null, null);

        assertEquals(1, response.traces().size());
        TraceSummary t = response.traces().get(0);
        assertEquals("trace-list-1", t.traceId());
        assertEquals("checkout", t.serviceName());
        assertEquals(120L, t.durationMs());
        assertEquals(SpanStatus.OK, t.status());
        assertFalse(t.hasError());
        assertNull(response.nextCursor());
    }

    // ─── 2. Detail round-trip + parsed attributes ────────────────────────

    @Test
    void detailReturnsFlatSpansWithParsedAttributes() {
        Span root = new Span(
                "span-r", "trace-detail-1", null,
                "checkout", "POST /api/orders", SpanKind.SERVER,
                1_000L, 1_120L, SpanStatus.OK,
                Map.of("http.method", "POST", "http.status_code", 200),
                List.of()
        );
        Span db = new Span(
                "span-db", "trace-detail-1", "span-r",
                "checkout", "INSERT orders", SpanKind.DB,
                1_010L, 1_080L, SpanStatus.OK,
                Map.of("db.statement", "INSERT INTO orders ..."),
                List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(root, db)));

        TraceDetailResponse detail = queryService.getTrace("trace-detail-1");

        assertEquals("trace-detail-1", detail.trace().traceId());
        assertEquals(2, detail.spans().size());

        // start_time ASC ordering: root (1000) < db (1010)
        SpanDto firstSpan = detail.spans().get(0);
        SpanDto secondSpan = detail.spans().get(1);
        assertEquals("span-r", firstSpan.spanId());
        assertEquals("span-db", secondSpan.spanId());
        assertNull(firstSpan.parentSpanId());
        assertEquals("span-r", secondSpan.parentSpanId());

        // attributes parsed back to Map (not raw String)
        assertEquals("POST", firstSpan.attributes().get("http.method"));
        assertEquals(200, ((Number) firstSpan.attributes().get("http.status_code")).intValue());
        assertEquals("INSERT INTO orders ...", secondSpan.attributes().get("db.statement"));
    }

    // ─── 3. Payload lazy load with masking preserved ─────────────────────

    @Test
    void payloadEndpointReturnsMaskedBody() {
        Span span = new Span(
                "s-pwd", "trace-pwd", null,
                "auth", "POST /login", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null,
                List.of(
                        new Payload(PayloadDirection.IN, "application/json",
                                "{\"user\":\"alice\",\"password\":\"hunter2\"}", 37, false),
                        new Payload(PayloadDirection.OUT, "application/json",
                                "{\"ok\":true}", 11, false)
                )
        );
        ingestService.ingest(new IngestRequest(List.of(span)));

        PayloadListResponse payloads = queryService.getPayloads("trace-pwd", "s-pwd");

        assertEquals(2, payloads.payloads().size());
        PayloadDto in = payloads.payloads().get(0);
        assertEquals("in", in.direction());
        assertNotNull(in.body());
        assertFalse(in.body().contains("hunter2"), "masking lost: " + in.body());
        assertTrue(in.body().contains("***"));
        assertTrue(in.body().contains("alice"));

        PayloadDto out = payloads.payloads().get(1);
        assertEquals("out", out.direction());
        assertEquals("{\"ok\":true}", out.body());
    }

    @Test
    void spanWithoutPayloadsReturnsEmptyArrayNotFourOhFour() {
        Span span = new Span(
                "s-empty", "trace-empty", null,
                "svc", "GET /x", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(span)));

        PayloadListResponse payloads = queryService.getPayloads("trace-empty", "s-empty");
        assertEquals(0, payloads.payloads().size());
    }

    // ─── 4. Filters: service, status, since/until ────────────────────────

    @Test
    void filtersByServiceAndStatusAndTimeRange() {
        ingestSimpleTrace("t-svc-a-ok", "service-a", 1_000L, 1_100L, SpanStatus.OK);
        ingestSimpleTrace("t-svc-a-err", "service-a", 2_000L, 2_100L, SpanStatus.ERROR);
        ingestSimpleTrace("t-svc-b-ok", "service-b", 3_000L, 3_100L, SpanStatus.OK);

        // service filter
        TraceListResponse aOnly = queryService.listTraces(
                "service-a", null, null, null, null, null);
        assertEquals(2, aOnly.traces().size());
        aOnly.traces().forEach(t -> assertEquals("service-a", t.serviceName()));

        // status filter
        TraceListResponse errorsOnly = queryService.listTraces(
                null, null, null, SpanStatus.ERROR, null, null);
        assertEquals(1, errorsOnly.traces().size());
        assertEquals("t-svc-a-err", errorsOnly.traces().get(0).traceId());

        // since/until: [1500, 2500) → only t-svc-a-err (start_time=2000)
        TraceListResponse windowed = queryService.listTraces(
                null, 1_500L, 2_500L, null, null, null);
        assertEquals(1, windowed.traces().size());
        assertEquals("t-svc-a-err", windowed.traces().get(0).traceId());
    }

    // ─── 5. Cursor pagination: 200 traces, two pages of 100 ──────────────

    @Test
    void cursorPaginationReturnsAllTracesWithoutDuplicates() {
        // Insert 200 traces with strictly increasing start_time (so DESC ordering is deterministic)
        for (int i = 0; i < 200; i++) {
            ingestSimpleTrace("trace-page-" + i, "svc-page",
                    1_000_000L + i, 1_000_000L + i + 50, SpanStatus.OK);
        }

        TraceListResponse page1 = queryService.listTraces(
                null, null, null, null, 100, null);
        assertEquals(100, page1.traces().size());
        assertNotNull(page1.nextCursor());

        TraceListResponse page2 = queryService.listTraces(
                null, null, null, null, 100, page1.nextCursor());
        assertEquals(100, page2.traces().size());
        assertNull(page2.nextCursor(), "no next page expected when all 200 fetched");

        Set<String> seen = new HashSet<>();
        page1.traces().forEach(t -> seen.add(t.traceId()));
        page2.traces().forEach(t -> seen.add(t.traceId()));
        assertEquals(200, seen.size(), "no duplicates across pages");

        // page1 must contain newer traces (higher start_time) than page2
        long minPage1 = page1.traces().stream().mapToLong(TraceSummary::startTime).min().orElseThrow();
        long maxPage2 = page2.traces().stream().mapToLong(TraceSummary::startTime).max().orElseThrow();
        assertTrue(minPage1 > maxPage2, "page1 must be newer than page2");
    }

    // ─── 6. Not found ─────────────────────────────────────────────────────

    @Test
    void traceNotFoundThrows404() {
        TraceNotFoundException ex = assertThrows(TraceNotFoundException.class,
                () -> queryService.getTrace("does-not-exist"));
        assertEquals("does-not-exist", ex.getTraceId());
    }

    @Test
    void spanNotFoundThrows404() {
        ingestSimpleTrace("t-real", "svc", 100L, 200L, SpanStatus.OK);

        SpanNotFoundException ex = assertThrows(SpanNotFoundException.class,
                () -> queryService.getPayloads("t-real", "no-such-span"));
        assertEquals("t-real", ex.getTraceId());
        assertEquals("no-such-span", ex.getSpanId());
    }

    @Test
    void payloadEndpointRejectsWrongTraceIdEvenIfSpanIdExists() {
        // span-x belongs to trace-A, not trace-B
        ingestSimpleTrace("trace-A", "svc", 100L, 200L, SpanStatus.OK, "span-x");

        assertThrows(SpanNotFoundException.class,
                () -> queryService.getPayloads("trace-B", "span-x"));
    }

    // ─── 7. Limit cap ────────────────────────────────────────────────────

    @Test
    void limitCapsAtFiveHundred() {
        for (int i = 0; i < 3; i++) {
            ingestSimpleTrace("t-cap-" + i, "svc", 1_000L + i, 1_100L + i, SpanStatus.OK);
        }
        // requesting 501 must not throw — silently capped to 500
        TraceListResponse capped = queryService.listTraces(
                null, null, null, null, 501, null);
        assertEquals(3, capped.traces().size()); // only 3 exist; just verify no error
    }

    @Test
    void limitBelowOneRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listTraces(null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listTraces(null, null, null, null, -1, null));
    }

    // ─── 8. Cursor decode failure ────────────────────────────────────────

    @Test
    void invalidCursorRejected() {
        assertThrows(CursorCodec.InvalidCursorException.class,
                () -> queryService.listTraces(null, null, null, null, null, "!!!not-base64!!!"));
        assertThrows(CursorCodec.InvalidCursorException.class,
                () -> queryService.listTraces(null, null, null, null, null,
                        java.util.Base64.getUrlEncoder().withoutPadding()
                                .encodeToString("nocolon".getBytes())));
    }

    @Test
    void invalidCursorIsAlsoIllegalArgumentException() {
        // CursorCodec.InvalidCursorException extends IllegalArgumentException,
        // so the controller's existing IllegalArgumentException handler covers it.
        assertThrows(IllegalArgumentException.class,
                () -> queryService.listTraces(null, null, null, null, null, "!!!"));
    }

    // ─── Extra: services list ────────────────────────────────────────────

    // [Phase H] AC-06-3 — W-01 breaking change 정합 (lastSeen → lastSeenAt + 4 필드 추가).
    // 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
    @Test
    void servicesListAggregatesAcrossTraces() {
        ingestSimpleTrace("t1", "svc-a", 1_000L, 1_100L, SpanStatus.OK);
        ingestSimpleTrace("t2", "svc-a", 2_000L, 2_050L, SpanStatus.OK);
        ingestSimpleTrace("t3", "svc-b", 3_000L, 3_100L, SpanStatus.ERROR);

        List<ServiceInfo> services = queryService.listServices().services();

        assertEquals(2, services.size());
        // ordered by name ASC
        ServiceInfo a = services.get(0);
        ServiceInfo b = services.get(1);
        assertEquals("svc-a", a.name());
        assertEquals(2L, a.traceCount());
        // D-02 경로 B 자동 등록 — IngestService.upsertServiceRegistration 가 매 batch 마다
        // last_seen_at = receivedAt 으로 갱신. 마지막 trace 의 receivedAt 보존.
        assertNotNull(a.lastSeenAt());
        assertEquals("auto", a.source());
        // 첫 trace 시점 registered_at 이 0 이상의 epoch (now) 으로 박힘
        assertTrue(a.registeredAt() > 0L);
        // healthStatus 분기: 방금 INSERT 했으므로 'active' (now - lastSeenAt 작음)
        assertEquals("active", a.healthStatus());

        assertEquals("svc-b", b.name());
        assertEquals(1L, b.traceCount());
        assertNotNull(b.lastSeenAt());
        assertEquals("auto", b.source());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private void ingestSimpleTrace(String traceId, String serviceName, long startTime, long endTime, SpanStatus status) {
        ingestSimpleTrace(traceId, serviceName, startTime, endTime, status,
                "span-" + UUID.randomUUID());
    }

    private void ingestSimpleTrace(String traceId, String serviceName, long startTime, long endTime,
                                   SpanStatus status, String spanId) {
        Span span = new Span(
                spanId, traceId, null,
                serviceName, "GET /probe", SpanKind.SERVER,
                startTime, endTime, status,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(span)));
    }
}
