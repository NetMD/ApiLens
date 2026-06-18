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
package io.apilens.agent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.apilens.agent.transport.HttpTransport;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.agent.transport.SpanSender;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.query.TraceQueryRepository;
import io.apilens.server.query.TraceQueryService;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase D 검증 자동화: agent의 transport pipeline이 server까지 도달해서 trace가
 * persisted되고 query endpoint로 조회 가능한지 end-to-end 확인.
 *
 * <p>구성:
 * <ul>
 *   <li>SQLite (file) + Flyway V1 → 실제 schema</li>
 *   <li>IngestService → real persistence (Phase B)</li>
 *   <li>TraceQueryService → real read (Phase C)</li>
 *   <li>com.sun.net.httpserver.HttpServer 가 {@code POST /v1/spans} 받아 IngestService 위임</li>
 *   <li>agent: SpanQueue + SpanSender + HttpTransport 조합 (Phase D), AgentMain 우회</li>
 * </ul>
 */
class AgentToServerIntegrationTest {

    @TempDir
    Path tempDir;
    private Path dbFile;
    private HttpServer httpServer;
    private SpanSender sender;
    private Thread senderThread;
    private SpanQueue queue;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentLogger logger = new AgentLogger(false);
    private TraceQueryService queryService;

    @BeforeEach
    void setup() throws Exception {
        // 1) DB + schema
        dbFile = Files.createTempFile(tempDir, "apilens-integ-", ".db");
        Files.deleteIfExists(dbFile);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 2) Server-side services
        // [Phase R12] AC-B2-3 연쇄: IngestService 주입이 MaskingEngine → MaskingEngineHolder 로
        // 전환 (핫 리로드 — Design §3.1.4). production 동일 경로(repository → holder.reload)로
        // V1 시드 default 룰을 적재한다. agent src/main diff 0 — 본 파일은 agent 모듈 거주
        // 통합 테스트(CLAUDE.md Build lessons §1 거주지 규칙)의 server-side fixture 보정.
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        // [Phase R13 hotfix] IngestService 생성자에 IngestProperties 추가(AC-A2-1) — server 클래스를
        // 직접 생성하는 본 통합 테스트 fixture 도 4-인자로 갱신. 기본 1MB 한도(IngestProperties @DefaultValue 동일값).
        IngestService ingestService = new IngestService(jdbc, maskingHolder, mapper,
                new io.apilens.server.ingest.IngestProperties(1_048_576L));
        queryService = new TraceQueryService(new TraceQueryRepository(jdbc, mapper));

        // 3) HttpServer bridges POST /v1/spans → ingestService.ingest
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/spans", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                byte[] body = in.readAllBytes();
                IngestRequest request = mapper.readValue(body, IngestRequest.class);
                ingestService.ingest(request);
                byte[] resp = "{\"accepted\":1}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, resp.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(resp);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();

        // 4) Agent transport (premain bypassed — directly construct components)
        int port = httpServer.getAddress().getPort();
        String serverUrl = "http://127.0.0.1:" + port;
        queue = new SpanQueue(100);
        HttpTransport transport = new HttpTransport(serverUrl, logger);
        sender = new SpanSender(queue, transport, logger, 50, 100L);
        senderThread = new Thread(sender, "test-apilens-sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    @AfterEach
    void teardown() throws Exception {
        if (sender != null) {
            sender.shutdown();
            senderThread.join(2_000L);
        }
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void agentSendsHelloSpanReachableViaQueryEndpoint() throws Exception {
        // Build the same shape AgentMain.buildHelloSpan() produces (Phase D 성공 기준).
        long now = System.currentTimeMillis();
        Span helloSpan = new Span(
                "hello-span-" + UUID.randomUUID().toString().substring(0, 8),
                "agent-startup-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "integration-test-svc",
                "agent.startup",
                SpanKind.INTERNAL,
                now, now,
                SpanStatus.OK,
                Map.of("apilens.agent.version", "0.1.0"),
                List.of()
        );
        assertTrue(queue.offer(helloSpan));

        // Wait for the sender to flush at least once.
        TraceSummary delivered = waitForTrace(helloSpan.traceId(), 3_000);
        assertNotNull(delivered, "agent → server pipeline did not deliver hello span within 3s");
        assertEquals("integration-test-svc", delivered.serviceName());
        assertEquals(SpanStatus.OK, delivered.status());
        assertEquals(1, delivered.spanCount());
    }

    @Test
    void multipleSpansBatchTogether() throws Exception {
        long now = System.currentTimeMillis();
        String traceId = "trace-batch-" + UUID.randomUUID().toString().substring(0, 8);
        Span root = new Span("s-root", traceId, null, "svc-batch", "GET /probe",
                SpanKind.SERVER, now, now + 50, SpanStatus.OK, null, List.of());
        Span child1 = new Span("s-c1", traceId, "s-root", "svc-batch", "INSERT",
                SpanKind.DB, now + 10, now + 30, SpanStatus.OK, null, List.of());
        Span child2 = new Span("s-c2", traceId, "s-root", "svc-batch", "SELECT",
                SpanKind.DB, now + 30, now + 45, SpanStatus.OK, null, List.of());
        queue.offer(root);
        queue.offer(child1);
        queue.offer(child2);

        TraceSummary delivered = waitForTrace(traceId, 3_000);
        assertNotNull(delivered);
        assertEquals(3, delivered.spanCount());
    }

    private TraceSummary waitForTrace(String traceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TraceListResponse list = queryService.listTraces(
                    null, null, null, null, 100, null);
            for (TraceSummary t : list.traces()) {
                if (traceId.equals(t.traceId())) {
                    return t;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

}
