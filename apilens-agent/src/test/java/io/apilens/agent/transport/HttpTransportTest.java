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
package io.apilens.agent.transport;

import com.sun.net.httpserver.HttpServer;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies HttpTransport against a real HTTP server (JDK built-in
 * {@link HttpServer}, no external test-doubles needed).
 */
class HttpTransportTest {

    private final AgentLogger silent = new AgentLogger(false);
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successfulPostReturnsTrue() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/v1/spans", exchange -> {
            callCount.incrementAndGet();
            byte[] in = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(in, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });

        HttpTransport transport = newTransport();
        boolean sent = transport.send(List.of(span("s1")));

        assertTrue(sent);
        assertEquals(1, callCount.get());
        assertNotNull(capturedBody.get());
        assertTrue(capturedBody.get().contains("\"spans\""));
        assertTrue(capturedBody.get().contains("\"s1\""));
    }

    @Test
    void fourHundredResponseTerminatesWithoutRetry() {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/v1/spans", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });

        HttpTransport transport = newTransport();
        boolean sent = transport.send(List.of(span("s1")));

        assertFalse(sent);
        assertEquals(1, callCount.get(), "4xx must not trigger retry");
    }

    @Test
    void fiveHundredTriggersExactlyOneRetryThenGivesUp() {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/v1/spans", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        // sub-second retry delay so the test completes promptly
        HttpTransport transport = newTransport();
        long start = System.nanoTime();
        boolean sent = transport.send(List.of(span("s1")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(sent);
        assertEquals(2, callCount.get(), "expected exactly one retry on 5xx");
        assertTrue(elapsedMs >= HttpTransport.RETRY_DELAY_MS - 100,
                "retry should respect retry delay, got " + elapsedMs + "ms");
    }

    @Test
    void fiveHundredThenSuccessOnRetryReturnsTrue() {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/v1/spans", exchange -> {
            int n = callCount.incrementAndGet();
            int status = (n == 1) ? 503 : 202;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });

        HttpTransport transport = newTransport();
        boolean sent = transport.send(List.of(span("s1")));

        assertTrue(sent, "second attempt succeeded");
        assertEquals(2, callCount.get());
    }

    @Test
    void connectionFailureSilentDrop() {
        // Point at a closed port without registering any handler, then stop server.
        server.stop(0);
        server = null;

        HttpTransport transport = newTransport();
        // Must not throw — agent contract is silent failure
        boolean sent = transport.send(List.of(span("s1")));

        assertFalse(sent);
    }

    @Test
    void emptyBatchShortCircuits() {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/v1/spans", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });

        HttpTransport transport = newTransport();
        assertTrue(transport.send(List.of()));
        assertTrue(transport.send(null));
        assertEquals(0, callCount.get(), "no HTTP call expected for empty/null batch");
    }

    @Test
    void connectionLostAndRestoredLogOncePerTransition() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(202);

        HttpClient mockClient = mock(HttpClient.class);
        // send #1 (down): both attempts refuse → exactly one "connection lost".
        // send #2 (still down): both attempts refuse → suppressed (no second "lost").
        // send #3 (recovered): first attempt 202 → exactly one "connection restored".
        when(mockClient.<String>send(any(), any()))
                .thenThrow(new ConnectException("Connection refused"))
                .thenThrow(new ConnectException("Connection refused"))
                .thenThrow(new ConnectException("Connection refused"))
                .thenThrow(new ConnectException("Connection refused"))
                .thenReturn(ok);

        // debug=false: suppressed repeats stay off stderr, exactly like production.
        HttpTransport transport = new HttpTransport(
                URI.create("http://127.0.0.1:1/v1/spans"), mockClient, new AgentLogger(false));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            assertFalse(transport.send(List.of(span("s1"))), "server down");
            assertFalse(transport.send(List.of(span("s2"))), "server still down");
            assertTrue(transport.send(List.of(span("s3"))), "server recovered");
        } finally {
            System.setErr(originalErr);
        }

        String out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(1, count(out, "ingest connection lost"),
                "connection loss must log exactly once across repeated down cycles, was:\n" + out);
        assertEquals(1, count(out, "ingest connection restored"),
                "recovery must log exactly once on the down→up transition, was:\n" + out);
    }

    // ─── [Phase R20] R20/AC-05-1 — 202 body best-effort 파싱 (W-13 transport 축) ───

    /**
     * R20/AC-05-1 — 202 body 의 instrumentConfig 가 게이트에 적용되고 전송은 SUCCESS.
     * 줄이는 방향 지시(requireEntryRoot=true)라 reduce-only 3분지 통과.
     */
    @Test
    void appliesInstrumentConfigFromAcceptedBody() {
        io.apilens.agent.instrument.RemoteConfigGate.init(launchConfigDefaults());
        io.apilens.agent.instrument.InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        try {
            byte[] responseBody = "{\"accepted\":1,\"traces\":1,\"instrumentConfig\":{\"requireEntryRoot\":true}}"
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/v1/spans", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.close();
            });

            HttpTransport transport = newTransport();
            boolean sent = transport.send(List.of(span("s1")));

            assertTrue(sent, "config 탑재 202 도 SUCCESS");
            assertTrue(io.apilens.agent.instrument.InstrumentationInstaller.REQUIRE_ENTRY_ROOT,
                    "202 body 의 줄이는 방향 지시가 volatile 게이트에 적용");
        } finally {
            io.apilens.agent.instrument.InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        }
    }

    /**
     * R20/AC-05-1 verbatim (비협상): "파싱 실패(비 JSON·거대 문자열·예상 밖 타입) 시 config 적용만
     * 건너뛰고 <b>SUCCESS 판정·전송 흐름·RETRYABLE 재시도 계정 불변</b>" — "202 파싱 실패 ≠ 전송 실패"(W-13).
     */
    @Test
    void keepsSuccessWhenAcceptedBodyIsGarbage() {
        io.apilens.agent.instrument.RemoteConfigGate.init(launchConfigDefaults());
        AtomicInteger callCount = new AtomicInteger();
        byte[] garbage = "definitely-not-json {{{".getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1/spans", exchange -> {
            callCount.incrementAndGet();
            exchange.sendResponseHeaders(202, garbage.length);
            exchange.getResponseBody().write(garbage);
            exchange.close();
        });

        HttpTransport transport = newTransport();
        boolean sent = transport.send(List.of(span("s1")));

        assertTrue(sent, "202 파싱 실패 ≠ 전송 실패 — SUCCESS 판정 불변");
        assertEquals(1, callCount.get(), "재시도 계정 불변 — 파싱 실패가 RETRYABLE 로 새지 않는다");
    }

    private static io.apilens.agent.config.AgentConfig launchConfigDefaults() {
        return new io.apilens.agent.config.AgentConfig(
                true, null, "http://localhost:8765", "svc",
                io.apilens.agent.config.AgentConfig.DEFAULT_SAMPLING_RATE,
                io.apilens.agent.config.AgentConfig.DEFAULT_BATCH_MAX_SIZE,
                io.apilens.agent.config.AgentConfig.DEFAULT_BATCH_FLUSH_INTERVAL_MS,
                io.apilens.agent.config.AgentConfig.DEFAULT_QUEUE_CAPACITY,
                io.apilens.agent.config.AgentConfig.DEFAULT_PAYLOAD_MAX_BYTES,
                false, false, true, List.of(), false);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private HttpTransport newTransport() {
        URI uri = URI.create("http://127.0.0.1:" + port + "/v1/spans");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        return new HttpTransport(uri, client, silent);
    }

    private static Span span(String id) {
        long now = System.currentTimeMillis();
        return new Span(id, "trace-x", null, "svc", "agent.startup", SpanKind.INTERNAL,
                now, now, SpanStatus.OK, Map.of("apilens.agent.version", "0.1.0"), List.of());
    }
}
