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
import java.util.function.Predicate;

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
        // ★ 여기서 시작하지 않는다. 각 테스트가 큐를 다 채운 뒤 startSender() 로 깨운다.
        //   sender 는 첫 span 이 들어오면 곧바로 깨어나서 "그 순간 큐에 있는 것만" 가져간다
        //   (SpanSender.pollAndSend — poll 로 하나 받고 drainTo 로 논블로킹 훑기). 그래서 미리
        //   돌려 두면, 테스트가 span 을 여러 번 넣는 사이에 스레드가 한 번만 밀려나도 배치가
        //   갈라진다. 갈라지면 서버에 먼저 도착한 몫만으로 요약이 만들어져서 span 수가 잠깐
        //   모자란 상태가 보인다. 한가한 기계에서는 거의 안 나지만 CI 처럼 바쁜 기계에서는
        //   실제로 났다 — 2026-08-21 Release 빌드 실패(multipleSpansBatchTogether span 수 불일치).
        //   큐를 먼저 채우고 나중에 깨우면 배치 경계가 정해져서 이 흔들림이 사라진다.
    }

    /** 큐를 다 채운 뒤에 부른다 — 배치 경계를 정해 두기 위함이다(setup 의 설명 참고). */
    private void startSender() {
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
        startSender();

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
        // 셋을 다 넣은 뒤에 깨운다 — 그래야 한 배치로 나간다(setup 의 설명 참고).
        startSender();

        // ★ "보이자마자" 가 아니라 "span 3개가 다 반영될 때까지" 기다린다.
        //   배치가 갈라지면 span 수가 잠깐 1이나 2로 보이는데, 그 중간 상태를 잡아채면
        //   span 이 멀쩡히 다 도착하는데도 실패한다. 기다리는 조건과 확인하는 값이
        //   어긋나 있던 자리다.
        TraceSummary delivered = waitForTrace(traceId, 3_000, t -> t.spanCount() == 3);
        assertNotNull(delivered, "span 3개가 3초 안에 모두 도착해 요약에 반영돼야 한다");
        assertEquals(3, delivered.spanCount());
    }

    private TraceSummary waitForTrace(String traceId, long timeoutMs) throws InterruptedException {
        return waitForTrace(traceId, timeoutMs, t -> true);
    }

    private TraceSummary waitForTrace(String traceId, long timeoutMs, Predicate<TraceSummary> until)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TraceListResponse list = queryService.listTraces(
                    null, null, null, null, 100, null);
            for (TraceSummary t : list.traces()) {
                if (traceId.equals(t.traceId()) && until.test(t)) {
                    return t;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

}
