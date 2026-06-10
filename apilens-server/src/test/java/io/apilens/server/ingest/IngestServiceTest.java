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
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test using a fresh in-memory SQLite database with the V1 Flyway
 * migration applied, exercising the same code path as a real {@code POST /v1/spans}
 * minus the controller layer.
 */
class IngestServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setupSchema() throws Exception {
        // SQLite in-memory DBs are per-connection; Flyway and JdbcTemplate each
        // open their own. Use a temp file so all connections see the same schema.
        dbFile = Files.createTempFile(tempDir, "apilens-test-", ".db");
        Files.deleteIfExists(dbFile); // SQLite will create fresh

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


    @Test
    void persistsSpansAndComputesTraceSummary() {
        Span root = new Span(
                "span-root", "trace-1", null,
                "checkout", "POST /api/orders", SpanKind.SERVER,
                1_000L, 1_120L,
                SpanStatus.OK,
                Map.of("http.method", "POST"),
                List.of(new Payload(PayloadDirection.IN, "application/json",
                        "{\"orderId\":42,\"password\":\"secret\"}", 36, false))
        );
        Span db = new Span(
                "span-db", "trace-1", "span-root",
                "checkout", "INSERT orders", SpanKind.DB,
                1_010L, 1_080L,
                SpanStatus.OK,
                null,
                List.of()
        );

        IngestResponse response = service.ingest(new IngestRequest(List.of(root, db)));

        assertEquals(2, response.accepted());
        assertEquals(1, response.traces());

        Map<String, Object> trace = jdbc.queryForMap(
                "SELECT * FROM traces WHERE trace_id = ?", "trace-1");
        assertEquals("POST /api/orders", trace.get("root_operation"));
        assertEquals("checkout", trace.get("service_name"));
        assertEquals(1_000L, ((Number) trace.get("start_time")).longValue());
        assertEquals(120L, ((Number) trace.get("duration_ms")).longValue()); // 1120 - 1000
        assertEquals("OK", trace.get("status"));
        assertEquals(2, ((Number) trace.get("span_count")).intValue());
        assertEquals(1, ((Number) trace.get("service_count")).intValue());
        assertEquals(0, ((Number) trace.get("has_error")).intValue());
        assertNotNull(trace.get("received_at"));

        Integer spanRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id = ?", Integer.class, "trace-1");
        assertEquals(2, spanRowCount);
    }

    @Test
    void masksPayloadBodyBeforePersisting() {
        Span span = new Span(
                "s1", "t1", null,
                "svc", "POST /login", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null,
                List.of(new Payload(
                        PayloadDirection.IN, "application/json",
                        "{\"user\":\"alice\",\"password\":\"hunter2\"}",
                        37, false))
        );
        service.ingest(new IngestRequest(List.of(span)));

        String storedBody = jdbc.queryForObject(
                "SELECT body FROM payloads WHERE span_id = ?", String.class, "s1");
        assertNotNull(storedBody);
        assertFalse(storedBody.contains("hunter2"), "raw password leaked: " + storedBody);
        assertTrue(storedBody.contains("***"));
        assertTrue(storedBody.contains("alice"));
    }

    @Test
    void errorSpanFlipsTraceStatus() {
        Span span = new Span(
                "s1", "t-err", null,
                "svc", "GET /broken", SpanKind.SERVER,
                100L, 200L, SpanStatus.ERROR,
                Map.of("exception.type", "NullPointerException"),
                List.of()
        );
        service.ingest(new IngestRequest(List.of(span)));

        Map<String, Object> trace = jdbc.queryForMap(
                "SELECT status, has_error FROM traces WHERE trace_id = ?", "t-err");
        assertEquals("ERROR", trace.get("status"));
        assertEquals(1, ((Number) trace.get("has_error")).intValue());
    }

    @Test
    void directionPersistedAsLowercase() {
        Span span = new Span(
                "s1", "t1", null,
                "svc", "GET /x", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null,
                List.of(
                        new Payload(PayloadDirection.IN, "text/plain", "req", 3, false),
                        new Payload(PayloadDirection.OUT, "text/plain", "resp", 4, false)
                )
        );
        service.ingest(new IngestRequest(List.of(span)));

        List<String> directions = jdbc.queryForList(
                "SELECT direction FROM payloads WHERE span_id = ? ORDER BY payload_id",
                String.class, "s1");
        assertEquals(List.of("in", "out"), directions);
    }

    @Test
    void nullAttributesStoredAsNull() {
        Span span = new Span(
                "s1", "t1", null,
                "svc", "GET /x", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        service.ingest(new IngestRequest(List.of(span)));

        String attrs = jdbc.queryForObject(
                "SELECT attributes_json FROM spans WHERE span_id = ?", String.class, "s1");
        assertNull(attrs);
    }

    @Test
    void rejectsEmptyBatch() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.ingest(new IngestRequest(List.of())));
        assertTrue(ex.getMessage().contains("non-empty"));
    }

    @Test
    void rejectsSpanMissingId() {
        Span span = new Span(
                null, "t1", null,
                "svc", "GET /x", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(new IngestRequest(List.of(span))));
    }

    @Test
    void rejectsEndTimeBeforeStartTime() {
        Span span = new Span(
                "s1", "t1", null,
                "svc", "GET /x", SpanKind.SERVER,
                500L, 100L, SpanStatus.OK,
                null, List.of()
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(new IngestRequest(List.of(span))));
    }

    @Test
    void resendingSameSpanIsIdempotent() {
        Span span = new Span(
                "dup", "t1", null,
                "svc", "GET /x", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        service.ingest(new IngestRequest(List.of(span)));
        service.ingest(new IngestRequest(List.of(span))); // resend

        Integer spanCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = ?", Integer.class, "dup");
        assertEquals(1, spanCount);
        Integer traceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = ?", Integer.class, "t1");
        assertEquals(1, traceCount);
    }
}
