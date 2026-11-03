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
package io.apilens.agent.instrument;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R31] 외부 호출 구간 직조 시험 — 설계 DD-R31-17 (가) in-JVM 직조 · 부록 A2-2 TC-R31-20~40 + 정정 신설
 * TC-R31-47. production 과 같은 조각(InstrumentationInstaller 의 baseBuilder · ignoreMatcher · withClientHttp)
 * 으로 CLIENT 변환 하나만 설치하고, 시험마다 {@code reset(RETRANSFORMATION)} 으로 되돌린다(같은 JVM 의 다른
 * 시험에 미치는 것 0). 대상 클래스는 {@code @BeforeAll} 에서 미리 실어 모든 시험이 재변환 길을 탄다(정정 DV-T-R31-3).
 *
 * <p>가짜 서버의 {@code Thread.sleep} · 래치 대기는 바깥 시스템 흉내(응답 지연)다 — 시간 단언 쪽 대기가 아니다.
 *
 * <p>R31/AC-R31-01 verbatim (UD-R31-9 비협상): "본문 있는 POST 에 로컬 서버가 응답 머리를 500 ms 늦게 보내면
 * CLIENT span 1건의 `endTime − startTime` 이 500 ms 이상 · 1,000 ms 미만(UC-R31-2) — 10회 연속"
 *
 * <p>R31/AC-R31-03 verbatim (UD-R31-9 · UD-R31-2 비협상): "응답 머리 전 읽기 시간 초과(…) → `status == ERROR` ·
 * `exception.type` = `execute()` 층 예외의 단순 이름(기대 = `SocketTimeoutException` 과 같은 클래스 또는 그 하위) ·
 * `exception.message` 키가 있고 빈 글자가 아님 · span 길이 ≥ 읽기 시간 초과 값 — 넷 다 단언 · 10회 연속"
 *
 * <p>R31/AC-R31-17 verbatim (UD-R31-10 비협상): "진입점 옵션을 **주지 않은**(기본값) 설치 · 열린 기록 없는
 * 스레드(depth 0) → CLIENT 1개가 흐름의 시작점"
 *
 * <p>R31/AC-R31-19 verbatim (UD-R31-10 비협상): "`exclude-packages=org.springframework.http.client.` 를 준 설치
 * → CLIENT 0 · 안 준 설치 → 1(두 실행 각각 개수 단언)"
 *
 * <p>R31/AC-R31-63 verbatim (UD-R31-1 비협상): "로컬 서버가 받은 요청 머리 목록이 계측 있음 · 없음에서 같고,
 * 두 실행 각각 흐름 이음 머리가 없다"(머리 이름 낱말은 가드 G-R31-10 축이라 이 주석에 옮겨 적지 않는다)
 */
class ClientHttpRequestWeavingTest {

    private static final String TARGET = "org.springframework.http.client.AbstractClientHttpRequest";

    /** 응답 머리 지연(ms) — TC-R31-21 · 39 · AC-R31-53 과 같은 값. */
    private static final int HEADER_DELAY_MS = 500;

    private static Instrumentation inst;
    private static HttpServer server;
    private static ExecutorService executor;
    private static int port;

    /** 회차마다 새로 끼우는 래치(정정 DV-T-R31-2) — 머리 안 보냄 / 본문 멈춤 처리기가 이것을 잡는다. */
    private static volatile CountDownLatch hangLatch = new CountDownLatch(0);
    private static volatile CountDownLatch bodyLatch = new CountDownLatch(0);

    private static final List<Set<String>> RECEIVED_HEADER_NAMES = new CopyOnWriteArrayList<>();

    private SpanQueue queue;

    @BeforeAll
    static void setupAll() throws Exception {
        // 정정 DV-T-R31-3 — 대상 클래스를 먼저 실어 둔다 → 모든 시험이 재변환 길(TC-R31-20 이 loaded=true 로 단언).
        Class.forName(TARGET, true, ClientHttpRequestWeavingTest.class.getClassLoader());
        inst = ByteBuddyAgent.install();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();          // 정정 DV-T-R31-2 — 실행기 수 ≥ 동시에 잡혀 있을 처리기 수
        server.setExecutor(executor);
        server.createContext("/delay", ex -> {
            drainRequest(ex);
            sleepQuietly(HEADER_DELAY_MS);
            respond(ex, 200, "ok");
        });
        server.createContext("/ok", ex -> {
            drainRequest(ex);
            respond(ex, 200, "ok");
        });
        server.createContext("/hang", ex -> {
            CountDownLatch latch = hangLatch;
            drainRequest(ex);
            awaitQuietly(latch, 5_000);
            ex.close();
        });
        server.createContext("/stall", ex -> {
            CountDownLatch latch = bodyLatch;
            drainRequest(ex);
            ex.sendResponseHeaders(200, 0);
            OutputStream body = ex.getResponseBody();
            body.write('a');
            body.flush();
            awaitQuietly(latch, 1_000);                    // D = 1,000 ms 는 상한으로만
            try {
                body.write('b');
            } catch (IOException ignore) {
                // 호스트가 이미 끊음
            }
            ex.close();
        });
        server.createContext("/status/404", ex -> {
            drainRequest(ex);
            respond(ex, 404, "nf");
        });
        server.createContext("/status/503", ex -> {
            drainRequest(ex);
            respond(ex, 503, "down");
        });
        server.createContext("/redirect", ex -> {
            drainRequest(ex);
            ex.getResponseHeaders().add("Location", "/moved");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/moved", ex -> {
            drainRequest(ex);
            respond(ex, 200, "moved");
        });
        server.createContext("/headers", ex -> {
            Set<String> names = new TreeSet<>();
            for (String n : ex.getRequestHeaders().keySet()) {
                names.add(n.toLowerCase(Locale.ROOT));
            }
            RECEIVED_HEADER_NAMES.add(names);
            drainRequest(ex);
            respond(ex, 200, "ok");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void tearDownAll() {
        hangLatch.countDown();
        bodyLatch.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @BeforeEach
    void setup() {
        queue = new SpanQueue(256);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of();
        RECEIVED_HEADER_NAMES.clear();
    }

    @AfterEach
    void teardown() {
        hangLatch.countDown();
        bodyLatch.countDown();
        TraceContext.clear();
        AdviceSupport.clearClientGuard();
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.QUEUE = null;
    }

    // ─── 전제(DG-2) ─────────────────────────────────────────────────────────

    /** TC-R31-20 — 전제(DG-2): 변환 1(loaded = true · 재변환 길) · 오류 0. 부착이 안 되면 건너뛰지 않고 빨강. */
    @Test
    void transformsTargetOnceOnRetransformationPath() {
        Installed in = install(List.of());
        try {
            assertEquals(List.of(TARGET + "|loaded=true"), in.listener.transformed,
                    "변환 1 · 재변환 길(loaded=true) — 정정 DV-T-R31-3");
            assertEquals(List.of(), in.listener.errors, "변환 오류 0");
        } finally {
            in.reset();
        }
    }

    // ─── 응답 머리까지 · 상태 (AC-R31-01 · 02 · 03) ───────────────────────────

    /** TC-R31-21 — AC-R31-01: 본문 있는 POST · 머리 500 ms 지연 · 10회 — 매회 CLIENT 1 · 500 ≤ 길이 < 1,000. */
    @Test
    void measuresUpToResponseHeadersForPostWithBody() throws Exception {
        Installed in = install(List.of());
        try {
            long slowest = 0;
            for (int i = 0; i < 10; i++) {
                int status = hostPost(uri("/delay"), 5_000, 100);
                assertEquals(200, status);
                Span span = singleClient();
                long dur = span.endTime() - span.startTime();
                slowest = Math.max(slowest, dur);
                assertTrue(dur >= HEADER_DELAY_MS, "회차 " + i + " 응답 머리 전에 닫음: " + dur);
                assertTrue(dur < 1_000, "회차 " + i + " 머리를 두 번 기다림: " + dur);
            }
            timing("TC-R31-21", slowest);
        } finally {
            in.reset();
        }
    }

    /** TC-R31-22 — AC-R31-02: 같은 호출의 {@code http.status_code} = 200 · Integer. */
    @Test
    void recordsIntegerStatusCode200() throws Exception {
        Installed in = install(List.of());
        try {
            hostPost(uri("/delay"), 5_000, 100);
            Span span = singleClient();
            assertEquals(Integer.valueOf(200), span.attributes().get("http.status_code"));
            assertInstanceOf(Integer.class, span.attributes().get("http.status_code"));
        } finally {
            in.reset();
        }
    }

    /**
     * TC-R31-23 — AC-R31-03: 읽기 시간 초과 300 ms · 상대가 머리를 안 보냄 · POST · 10회 — ERROR · 예외 타입
     * (stacktrace 첫 줄 FQN → isAssignableFrom · 정정 DV-A-R31-1) · 메시지 있음 · 길이 ≥ 300 — 넷 다.
     */
    @Test
    void recordsHeaderTimeoutAsErrorWithExceptionKeys() throws Exception {
        Installed in = install(List.of());
        try {
            long slowest = 0;
            for (int i = 0; i < 10; i++) {
                hangLatch = new CountDownLatch(1);
                IOException host = assertThrows(IOException.class, () -> hostPost(uri("/hang"), 300, 10));
                hangLatch.countDown();
                assertNotNull(host);
                Span span = singleClient();
                long dur = span.endTime() - span.startTime();
                slowest = Math.max(slowest, dur);
                assertEquals(SpanStatus.ERROR, span.status(), "회차 " + i);
                assertExceptionType(SocketTimeoutException.class, span);
                Object message = span.attributes().get("exception.message");
                assertNotNull(message, "메시지 키 빠짐");
                assertFalse(message.toString().isEmpty());
                assertTrue(dur >= 300, "회차 " + i + " 길이 < 읽기 시간 초과: " + dur);
            }
            timing("TC-R31-23", slowest);
        } finally {
            in.reset();
        }
    }

    /**
     * TC-R31-24 — AC-R31-04: RestClient(Simple 팩토리)로 TC-23 입력 · 없음 1 · 있음 1 — 두 실행 각각
     * {@code ResourceAccessException} · 타입 · 메시지 같음. 원인 사슬은 기록만 한다.
     *
     * <p>알려진 차이(설계 DD-R31-30 사실 · 정정 DV-A-R31-5 · LM-R31-10): 계측 있음 쪽 원인 사슬이 한 단 더 길다 —
     * 호스트가 받는 예외는 같은 클래스 · 같은 메시지의 새 예외이고, 그 원인이 agent 가 먼저 맞은 예외다.
     */
    @Test
    void keepsHostExceptionTypeAndMessageOnHeaderTimeout() throws Exception {
        hangLatch = new CountDownLatch(1);
        Exception without = callRestClientCatching(uri("/hang"), 300, false);
        hangLatch.countDown();
        Installed in = install(List.of());
        try {
            hangLatch = new CountDownLatch(1);
            Exception with = callRestClientCatching(uri("/hang"), 300, false);
            hangLatch.countDown();
            assertSameHostOutcome(ResourceAccessException.class, without, with);
            System.out.println("[R31-CAUSES] TC-R31-24 without=" + causeChain(without) + " with=" + causeChain(with));
        } finally {
            in.reset();
        }
    }

    /** TC-R31-25 — AC-R31-05: 듣는 쪽 없는 고정 127.0.0.1:1(정정 DV-T-R31-7) → ERROR · ConnectException · 메시지 키. */
    @Test
    void recordsConnectionRefusedAsError() throws Exception {
        Installed in = install(List.of());
        try {
            assertThrows(IOException.class, () -> hostGet(URI.create("http://127.0.0.1:1/x"), 2_000));
            Span span = singleClient();
            assertEquals(SpanStatus.ERROR, span.status());
            assertExceptionType(ConnectException.class, span);
            assertTrue(span.attributes().containsKey("exception.message"), "메시지 키 빠짐");
        } finally {
            in.reset();
        }
    }

    /** TC-R31-26 — AC-R31-06: RestClient 로 연결 거부 · 없음 · 있음 — 각각 ResourceAccessException · 둘 같음. */
    @Test
    void keepsHostExceptionTypeAndMessageOnConnectionRefused() throws Exception {
        URI refused = URI.create("http://127.0.0.1:1/x");
        Exception without = callRestClientCatching(refused, 2_000, false);
        Installed in = install(List.of());
        try {
            Exception with = callRestClientCatching(refused, 2_000, false);
            assertSameHostOutcome(ResourceAccessException.class, without, with);
        } finally {
            in.reset();
        }
    }

    /** TC-R31-27 — AC-R31-07: 404 · 503 → 각각 ERROR · 상태 Integer 404 · 503. */
    @Test
    void recordsClientAndServerErrorStatusesAsError() throws Exception {
        Installed in = install(List.of());
        try {
            for (int code : new int[]{404, 503}) {
                assertEquals(code, hostGet(uri("/status/" + code), 5_000));
                Span span = singleClient();
                assertEquals(SpanStatus.ERROR, span.status(), "상태 " + code);
                assertEquals(Integer.valueOf(code), span.attributes().get("http.status_code"));
            }
        } finally {
            in.reset();
        }
    }

    /** TC-R31-28 — AC-R31-08 · BV-R31-08: POST · 302 + Location(따라가지 않음 — 사실 GT-R31-13) → OK · 302. */
    @Test
    void keepsUnfollowedRedirectAsOk() throws Exception {
        Installed in = install(List.of());
        try {
            assertEquals(302, hostPost(uri("/redirect"), 5_000, 10));
            Span span = singleClient();
            assertEquals(SpanStatus.OK, span.status());
            assertEquals(Integer.valueOf(302), span.attributes().get("http.status_code"));
        } finally {
            in.reset();
        }
    }

    // ─── 재진입 가드 (AC-R31-09 · 10 · 65 · 66) ────────────────────────────────

    /** TC-R31-29 — AC-R31-09: 인터셉터 1개 RestClient 호출 1번 → CLIENT 1(바깥 · 안쪽 둘 다 직조 대상이라는 전제 먼저). */
    @Test
    void recordsOneSpanForInterceptedCall() throws Exception {
        Class<?> intercepting = Class.forName("org.springframework.http.client.InterceptingClientHttpRequest");
        assertTrue(AbstractClientHttpRequest.class.isAssignableFrom(intercepting),
                "전제 — 바깥 요청도 같은 직조 지점을 지난다(그래야 1 이 가드의 증명)");
        Installed in = install(List.of());
        try {
            callRestClient(uri("/ok"), 5_000, true);
            assertEquals(1, clientSpans().size(), "가드가 없으면 2");
        } finally {
            in.reset();
        }
    }

    /** TC-R31-30 — AC-R31-10: 같은 스레드 두 번 — 각 호출 뒤 CLIENT 1(둘째가 기록됐다 = 첫 exit 에서 가드가 풀림). */
    @Test
    void releasesGuardBetweenSequentialCalls() throws Exception {
        Installed in = install(List.of());
        try {
            hostGet(uri("/ok"), 5_000);
            assertEquals(1, clientSpans().size(), "첫 호출");
            hostGet(uri("/ok"), 5_000);
            assertEquals(1, clientSpans().size(), "해제 누락이면 둘째 0");
        } finally {
            in.reset();
        }
    }

    /** TC-R31-31 — AC-R31-16(UD-R31-3 비협상): 본문 100 B POST → span payload = 빈 목록 · 전제 = span 1. */
    @Test
    void keepsPayloadsEmptyForPostWithBody() throws Exception {
        Installed in = install(List.of());
        try {
            hostPost(uri("/ok"), 5_000, 100);
            Span span = singleClient();
            assertEquals(List.of(), span.payloads());
        } finally {
            in.reset();
        }
    }

    /** TC-R31-32 — AC-R31-17: 옵션 꺼짐(기본) · 깊이 0 → CLIENT 1 · 흐름의 시작점(부모 없음). */
    @Test
    void startsTraceWithClientSpanWhenEntryOptionOff() throws Exception {
        Installed in = install(List.of());
        try {
            hostGet(uri("/ok"), 5_000);
            Span span = singleClient();
            assertNull(span.parentSpanId());
        } finally {
            in.reset();
        }
    }

    /** TC-R31-33 — AC-R31-18: 옵션 켬 · 깊이 0 → 0 · SERVER 아래 → 1(부모 = SERVER) — 전제 = 같은 시험의 둘째 값 1. */
    @Test
    void recordsClientOnlyUnderServerWhenEntryOptionOn() throws Exception {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;
        Installed in = install(List.of());
        try {
            hostGet(uri("/ok"), 5_000);
            int depthZero = clientSpans().size();

            TraceContext.Frame serverFrame = AdviceSupport.enter("com.acme.Api#get", "SERVER");
            hostGet(uri("/ok"), 5_000);
            AdviceSupport.exit(serverFrame, null, null, null);
            List<Span> underServer = clientSpans();

            assertEquals(1, underServer.size(), "전제 — SERVER 아래 1");
            assertEquals(serverFrame.spanId, underServer.get(0).parentSpanId());
            assertEquals(0, depthZero, "옵션 켬 · 깊이 0 → 0");
        } finally {
            in.reset();
        }
    }

    /** TC-R31-34 — AC-R31-65: 옵션 켬 · 억제 호출(인터셉터 두 층) → 같은 스레드 SERVER 아래 호출 → CLIENT 1. */
    @Test
    void recordsClientAfterSuppressedCallOnSameThread() throws Exception {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;
        Installed in = install(List.of());
        try {
            callRestClient(uri("/ok"), 5_000, true);
            assertEquals(0, clientSpans().size(), "억제");

            TraceContext.Frame serverFrame = AdviceSupport.enter("com.acme.Api#get", "SERVER");
            hostGet(uri("/ok"), 5_000);
            AdviceSupport.exit(serverFrame, null, null, null);
            assertEquals(1, clientSpans().size(), "억제 갈래에서 가드를 세우면 여기가 0");
        } finally {
            in.reset();
        }
    }

    /** TC-R31-35 — AC-R31-66: 연결 거부 → 열린 포트 — 둘째 CLIENT 1(예외 갈래 해제). */
    @Test
    void releasesGuardAfterConnectionRefused() throws Exception {
        Installed in = install(List.of());
        try {
            assertThrows(IOException.class, () -> hostGet(URI.create("http://127.0.0.1:1/x"), 2_000));
            assertEquals(1, clientSpans().size(), "첫 호출(오류)도 기록");
            hostGet(uri("/ok"), 5_000);
            Span second = singleClient();
            assertEquals(SpanStatus.OK, second.status());
        } finally {
            in.reset();
        }
    }

    // ─── 끄는 길 (AC-R31-19) ────────────────────────────────────────────────

    /** TC-R31-36 — AC-R31-19: 접두를 준 설치 → 0 + onIgnored 에 그 타입 · 안 준 설치 → 1(두 실행 각각 개수). */
    @Test
    void honoursOperatorExcludePrefix() throws Exception {
        Installed excluded = install(List.of("org.springframework.http.client."));
        int withPrefix;
        List<String> ignored;
        try {
            hostGet(uri("/ok"), 5_000);
            withPrefix = clientSpans().size();
            ignored = new ArrayList<>(excluded.listener.ignored);
            assertEquals(List.of(), excluded.listener.transformed, "접두를 준 설치는 변환 0");
        } finally {
            excluded.reset();
        }
        Installed plain = install(List.of());
        try {
            hostGet(uri("/ok"), 5_000);
            assertEquals(1, clientSpans().size(), "안 준 설치 → 1");
        } finally {
            plain.reset();
        }
        assertTrue(ignored.contains(TARGET), "전제 — 무시 신호에 그 타입: " + ignored.size());
        assertEquals(0, withPrefix, "준 설치 → 0");
    }

    // ─── 측정 범위 (AC-R31-21 · 44) ────────────────────────────────────────

    /**
     * TC-R31-37 — AC-R31-21 · AC-R31-44: 머리(200) 즉시 · 본문 멈춤(D 상한 1,000 ms) · 호스트 본문 읽기 시간 초과
     * 200 ms · 10회 — OK · 길이 &lt; 500 · 예외 키 0 · 전제 = 호스트가 본문 단계에서 예외를 실제로 받음.
     *
     * <p>측정 범위(설계 DD-R31-12 정본 글자): 외부 호출의 시간 · 상태는 응답 머리(상태 코드)까지입니다. 본문 전송 지연과 본문 단계의 실패는 아직 반영되지 않습니다
     */
    @Test
    void measuresUpToHeadersNotBodyStage() throws Exception {
        // [R31 · AC-R31-21 · QR-R31-01] 호스트 본문 읽기 시간 초과 — 팩토리 설정과 아래 둘째 합격선이 같은 값을 읽는다.
        final int hostBodyReadTimeoutMs = 200;
        Installed in = install(List.of());
        try {
            long slowest = 0;
            for (int i = 0; i < 10; i++) {
                bodyLatch = new CountDownLatch(1);
                SimpleClientHttpRequestFactory f = factory(hostBodyReadTimeoutMs);
                ClientHttpRequest request = f.createRequest(uri("/stall"), HttpMethod.GET);
                IOException bodyFailure = null;
                try (ClientHttpResponse response = request.execute()) {
                    assertEquals(200, response.getStatusCode().value());
                    try (InputStream body = response.getBody()) {
                        body.readAllBytes();
                    } catch (IOException e) {
                        bodyFailure = e;
                    }
                }
                bodyLatch.countDown();
                assertNotNull(bodyFailure, "전제 — 호스트가 본문 단계에서 예외를 받음(회차 " + i + ")");
                Span span = singleClient();
                long dur = span.endTime() - span.startTime();
                slowest = Math.max(slowest, dur);
                assertEquals(SpanStatus.OK, span.status());
                assertTrue(dur < 500, "회차 " + i + " 본문까지 기다림: " + dur);
                // [R31 · AC-R31-21 · QR-R31-01] 빨강 입력 「본문 끝까지 기다림」 가름 — agent 가 본문을 읽으면
                // 같은 연결의 호스트 시간 초과에서 끊겨 span 이 그 값 이상으로 끝난다(QA 변이 실측 233 ms).
                assertTrue(dur < hostBodyReadTimeoutMs,
                        "회차 " + i + " 호스트 본문 읽기 시간 초과(" + hostBodyReadTimeoutMs + " ms)까지 기다림: " + dur);
                assertFalse(span.attributes().containsKey("exception.type"));
                assertFalse(span.attributes().containsKey("exception.message"));
            }
            timing("TC-R31-37", slowest);
        } finally {
            in.reset();
        }
    }

    // ─── 흐름 이음 머리 (AC-R31-63) ─────────────────────────────────────────

    /** TC-R31-38 — AC-R31-63: 받은 요청 머리 이름 목록 · 없음 · 있음 — 두 목록 같음 · 각각 흐름 이음 머리 없음. */
    @Test
    void keepsOutgoingRequestHeadersUnchanged() throws Exception {
        hostGet(uri("/headers"), 5_000);
        Installed in = install(List.of());
        try {
            hostGet(uri("/headers"), 5_000);
        } finally {
            in.reset();
        }
        assertEquals(2, RECEIVED_HEADER_NAMES.size());
        Set<String> without = RECEIVED_HEADER_NAMES.get(0);
        Set<String> with = RECEIVED_HEADER_NAMES.get(1);
        assertFalse(without.isEmpty(), "전제 — 목록이 비지 않음(Host 등)");
        assertFalse(with.isEmpty());
        String linkHeader = "trace" + "parent";        // 가드 G-R31-10 축의 낱말은 주석 · 글자 상수로 옮기지 않는다
        assertFalse(without.contains(linkHeader));
        assertFalse(with.contains(linkHeader));
        assertEquals(without, with, "agent 가 머리를 더하면 빨강");
    }

    // ─── 추가 차단 0 (AC-R31-74) ────────────────────────────────────────────

    /** TC-R31-39 — AC-R31-74: 머리 500 ms 지연 POST · 호스트 execute()+상태 읽기 시간 · 없음 10 · 있음 10 — 모두 ≥ 500 · 중앙값 차 &lt; 250. */
    @Test
    void addsNoExtraBlockingToHostCall() throws Exception {
        List<Long> without = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            without.add(hostPostMillis(uri("/delay")));
        }
        List<Long> with = new ArrayList<>();
        Installed in = install(List.of());
        try {
            for (int i = 0; i < 10; i++) {
                with.add(hostPostMillis(uri("/delay")));
            }
        } finally {
            in.reset();
        }
        for (long t : without) {
            assertTrue(t >= HEADER_DELAY_MS, "없음 회차 " + t);
        }
        for (long t : with) {
            assertTrue(t >= HEADER_DELAY_MS, "있음 회차 " + t);
        }
        long diff = Math.abs(median(with) - median(without));
        System.out.println("[R31-TIMING] TC-R31-39 medianWithout=" + median(without) + " medianWith=" + median(with)
                + " diff=" + diff + " maxWith=" + Collections.max(with));
        assertTrue(diff < 250, "중앙값 차 " + diff + " — 머리를 두 번 기다리면 ≈500");
    }

    /** TC-R31-40 — [ApiLens/S-170ⓐ] 회귀: 비교 도우미는 두 결과가 같기만 하면 통과하지 않는다(요구 타입을 두 실행 각각). */
    @Test
    void comparisonHelperRequiresExpectedTypeOnEachRun() {
        IllegalStateException a = new IllegalStateException("same");
        IllegalStateException b = new IllegalStateException("same");

        assertThrows(AssertionError.class, () -> assertSameHostOutcome(ResourceAccessException.class, a, b));
    }

    /**
     * TC-R31-47 — 정정 DV-A-R31-3 · 짝 DD-R31-08: GET · 302 + {@code Location: /moved}(같은 가짜 서버 200 — Simple
     * 팩토리는 GET 만 따라감) → span 1 · 이름의 경로 = {@code http.url} 경로 = 처음 요청 경로 · 이름의 호스트:포트 =
     * server.address · server.port · 상태 200. URI 는 enter 에서 한 번만 읽으므로 따라간 뒤 주소가 이름에 안 섞인다.
     */
    @Test
    void namesFollowedRedirectByOriginalRequestPath() throws Exception {
        Installed in = install(List.of());
        try {
            assertEquals(200, hostGet(uri("/redirect"), 5_000));
            Span span = singleClient();
            Map<String, Object> a = span.attributes();
            assertEquals(Integer.valueOf(200), a.get("http.status_code"));
            assertEquals("http://127.0.0.1:" + port + "/redirect", a.get("http.url"));
            String hostPort = a.get("server.address") + ":" + a.get("server.port");
            assertEquals("HTTP GET " + hostPort + "/redirect", span.operationName());
        } finally {
            in.reset();
        }
    }

    // ─── 도우미 ─────────────────────────────────────────────────────────────

    /** 비교 시험 도우미 — 두 실행 <b>각각</b> 기대 타입 · 그다음 타입 · 메시지 같음([ApiLens/S-170ⓐ]). */
    static void assertSameHostOutcome(Class<? extends Throwable> expected, Throwable without, Throwable with) {
        assertNotNull(without, "없음 실행이 예외를 안 받음");
        assertNotNull(with, "있음 실행이 예외를 안 받음");
        assertTrue(expected.isInstance(without), "없음 실행 타입 " + without.getClass().getName());
        assertTrue(expected.isInstance(with), "있음 실행 타입 " + with.getClass().getName());
        assertEquals(without.getClass(), with.getClass(), "두 실행 타입이 다름");
        assertEquals(without.getMessage(), with.getMessage(), "두 실행 메시지가 다름");
    }

    /**
     * 예외 타입 판정(정정 DV-A-R31-1): {@code exception.stacktrace} 첫 줄의 {@code ": "} 앞(없으면 줄 전체 —
     * printStackTrace 첫 줄 = Throwable#toString = FQN)을 Class.forName → 기대 타입 isAssignableFrom ·
     * {@code exception.type} = 그 클래스 단순 이름 — 둘 다 단언.
     */
    private static void assertExceptionType(Class<? extends Throwable> expected, Span span) throws ClassNotFoundException {
        Object stack = span.attributes().get("exception.stacktrace");
        assertNotNull(stack, "exception.stacktrace 없음");
        String first = stack.toString().lines().findFirst().orElse("");
        int sep = first.indexOf(": ");
        String fqn = (sep >= 0 ? first.substring(0, sep) : first).trim();
        Class<?> recorded = Class.forName(fqn);
        assertTrue(expected.isAssignableFrom(recorded), "기록된 타입 " + fqn + " 이 " + expected.getName() + " 이 아님");
        assertEquals(recorded.getSimpleName(), span.attributes().get("exception.type"));
    }

    private Installed install(List<String> excludePackages) {
        RecordingListener listener = new RecordingListener();
        ResettableClassFileTransformer transformer = InstrumentationInstaller.withClientHttp(
                        InstrumentationInstaller.baseBuilder()
                                .with(listener)
                                .ignore(InstrumentationInstaller.ignoreMatcher(excludePackages)))
                .installOn(inst);
        return new Installed(transformer, listener);
    }

    private static final class Installed {
        final ResettableClassFileTransformer transformer;
        final RecordingListener listener;

        Installed(ResettableClassFileTransformer transformer, RecordingListener listener) {
            this.transformer = transformer;
            this.listener = listener;
        }

        void reset() {
            // 정정 DV-A-R31-8 — 되돌림이 실제로 됐는지 단언(다른 시험으로 변환이 넘어가지 않게).
            assertTrue(transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION), "변환 되돌림 실패");
        }
    }

    private static final class RecordingListener extends AgentBuilder.Listener.Adapter {
        final List<String> transformed = new CopyOnWriteArrayList<>();
        final List<String> ignored = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onTransformation(TypeDescription type, ClassLoader cl, JavaModule m, boolean loaded, DynamicType dt) {
            transformed.add(type.getName() + "|loaded=" + loaded);
        }

        @Override
        public void onIgnored(TypeDescription type, ClassLoader cl, JavaModule m, boolean loaded) {
            if (type.getName().startsWith("org.springframework.http.client.")) {
                ignored.add(type.getName());
            }
        }

        @Override
        public void onError(String typeName, ClassLoader cl, JavaModule m, boolean loaded, Throwable t) {
            errors.add(typeName + ": " + t);
        }
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static SimpleClientHttpRequestFactory factory(int readTimeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return f;
    }

    /** 호스트 흉내 — 본문 있는 POST: execute() → 상태 읽기 → 닫기. */
    private static int hostPost(URI uri, int readTimeoutMs, int bodyBytes) throws IOException {
        ClientHttpRequest request = factory(readTimeoutMs).createRequest(uri, HttpMethod.POST);
        request.getBody().write(new byte[bodyBytes]);
        try (ClientHttpResponse response = request.execute()) {
            return response.getStatusCode().value();
        }
    }

    private static int hostGet(URI uri, int readTimeoutMs) throws IOException {
        ClientHttpRequest request = factory(readTimeoutMs).createRequest(uri, HttpMethod.GET);
        try (ClientHttpResponse response = request.execute()) {
            return response.getStatusCode().value();
        }
    }

    /** 호스트가 execute() + 상태 읽기에 쓴 시간(ms · System.nanoTime) — 본문 있는 POST. */
    private static long hostPostMillis(URI uri) throws IOException {
        ClientHttpRequest request = factory(5_000).createRequest(uri, HttpMethod.POST);
        request.getBody().write(new byte[100]);
        long t0 = System.nanoTime();
        ClientHttpResponse response = request.execute();
        int status = response.getStatusCode().value();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        response.close();
        assertEquals(200, status);
        return elapsed;
    }

    private static void callRestClient(URI uri, int readTimeoutMs, boolean withInterceptor) {
        RestClient.Builder b = RestClient.builder().requestFactory(factory(readTimeoutMs));
        if (withInterceptor) {
            b = b.requestInterceptor((req, body, ex) -> ex.execute(req, body));
        }
        b.build().post().uri(uri).body("x").retrieve().toBodilessEntity();
    }

    private static Exception callRestClientCatching(URI uri, int readTimeoutMs, boolean withInterceptor) {
        try {
            callRestClient(uri, readTimeoutMs, withInterceptor);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static List<String> causeChain(Throwable t) {
        List<String> out = new ArrayList<>();
        for (Throwable c = t; c != null && out.size() < 10; c = c.getCause()) {
            out.add(c.getClass().getSimpleName());
        }
        return out;
    }

    private List<Span> clientSpans() {
        List<Span> all = new ArrayList<>();
        queue.drainTo(all);
        List<Span> out = new ArrayList<>();
        for (Span s : all) {
            if (s.spanKind() == SpanKind.CLIENT) {
                out.add(s);
            }
        }
        return out;
    }

    private Span singleClient() {
        List<Span> spans = clientSpans();
        assertEquals(1, spans.size(), "CLIENT span 수");
        return spans.get(0);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static void timing(String tc, long slowestMs) {
        System.out.println("[R31-TIMING] " + tc + " slowestSpanMs=" + slowestMs);
    }

    private static void drainRequest(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            in.readAllBytes();
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long ms) {
        try {
            latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
