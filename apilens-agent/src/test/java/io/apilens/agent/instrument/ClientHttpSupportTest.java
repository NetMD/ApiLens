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

import com.sun.net.httpserver.HttpServer;
import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.common.Span;
import io.apilens.common.SpanStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R31] 외부 호출 도우미({@link ClientHttpSupport}) 를 직조 없이 직접 부르는 시험 — 설계 부록 A2-1
 * TC-R31-01~16 + 정정 신설 TC-R31-17 · 18 · 19.
 *
 * <p>R31/AC-R31-14 verbatim (UD-R31-3 비협상): "BV-R31-05 URL → `http.url` = `http://127.0.0.1:{포트}/a/b` ·
 * operation 이름과 속성 값 어디에도 `token` · `pw` · `frag` 글자 0"
 *
 * <p>R31/AC-R31-15 verbatim (UD-R31-2 비협상): "BV-R31-06 URL(포트 생략)에서 포트 키가 있고 값이 양의 정수 —
 * `http` 이면 80(`-1` · 0 · 키 없음 모두 빨강)"
 *
 * <p>R31/AC-R31-11 verbatim (UD-R31-2 비협상): "package-private 실물 요청 · 응답 객체에서 `http.method` =
 * `"POST"` · URL · 상태 코드 · 호스트 키 = `"127.0.0.1"` · 포트 키 = 로컬 서버 포트(숫자) 다섯 **값**이
 * 기대값과 같다"
 */
class ClientHttpSupportTest {

    private static HttpServer server;
    private static ExecutorService executor;
    private static int port;

    private SpanQueue queue;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/ok", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/s299", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(299, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @BeforeEach
    void setup() {
        queue = new SpanQueue(64);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = java.util.Set.of();
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        // [Phase R31] 설계 정정 DV-A-R31-8 — 가드도 시험마다 지운다.
        AdviceSupport.clearClientGuard();
        InstrumentationInstaller.QUEUE = null;
    }

    // ─── URL 위생 (AC-R31-14 · 15 · 설계 DD-R31-10 · 11) ─────────────────────────

    /** TC-R31-01 — AC-R31-14: 쿼리 · 프래그먼트 · 사용자 정보가 이름 · 속성 어디에도 없다. */
    @Test
    void keepsOnlySchemeHostPortAndPathInUrlAndName() {
        ClientHttpSupport.ClientCall call = ClientHttpSupport.of("GET",
                URI.create("http://id:pw@127.0.0.1:41234/a/b?token=x#frag"));

        assertEquals("http://127.0.0.1:41234/a/b", call.attributes().get("http.url"));
        assertEquals("HTTP GET 127.0.0.1:41234/a/b", call.operationName());
        String all = call.operationName() + " " + call.attributes();
        for (String leak : new String[]{"token", "pw@", "frag", "id:"}) {
            assertFalse(all.contains(leak), "위생 밖 글자가 남음: " + leak + " in " + all);
        }
    }

    /** TC-R31-02 — AC-R31-15: http 포트 생략 → server.port = 80(Integer) · 키 있음. */
    @Test
    void fillsDefaultPort80ForHttp() {
        Map<String, Object> attrs = ClientHttpSupport.of("GET", URI.create("http://h/a")).attributes();

        assertTrue(attrs.containsKey("server.port"), "포트를 모를 때 키를 빼면 빨강");
        assertEquals(Integer.valueOf(80), attrs.get("server.port"));
        assertInstanceOf(Integer.class, attrs.get("server.port"));
        assertEquals("http://h/a", attrs.get("http.url"), "http.url 에는 기본 포트를 채우지 않는다");
    }

    /** TC-R31-03 — 설계 DD-R31-11: https 포트 생략 → 443. */
    @Test
    void fillsDefaultPort443ForHttps() {
        Map<String, Object> attrs = ClientHttpSupport.of("GET", URI.create("https://h/a")).attributes();

        assertEquals(Integer.valueOf(443), attrs.get("server.port"));
    }

    /** TC-R31-04 — 설계 DD-R31-11: 모르는 스킴 → server.port 키 없음 · 이름에 포트 없음. */
    @Test
    void omitsPortKeyForUnknownScheme() {
        ClientHttpSupport.ClientCall call = ClientHttpSupport.of("GET", URI.create("ws://h/a"));

        assertFalse(call.attributes().containsKey("server.port"), "모르는 스킴에 0 · -1 을 지어내지 않는다");
        assertEquals("HTTP GET h/a", call.operationName());
    }

    /** TC-R31-05 — BV-R31-12: IPv6 → server.address 는 괄호 없이 · 이름은 괄호 꼴. */
    @Test
    void keepsIpv6BracketsInNameOnly() {
        ClientHttpSupport.ClientCall call = ClientHttpSupport.of("GET", URI.create("http://[::1]:8080/x"));

        assertEquals("::1", call.attributes().get("server.address"));
        assertTrue(call.operationName().contains("[::1]:8080/x"), call.operationName());
    }

    /** TC-R31-06 — BV-R31-13: 빈 경로 → url · 이름 끝에 null 글자 없음. */
    @Test
    void acceptsEmptyPath() {
        ClientHttpSupport.ClientCall call = ClientHttpSupport.of("GET", URI.create("http://h:1234"));

        assertEquals("http://h:1234", call.attributes().get("http.url"));
        assertEquals("HTTP GET h:1234", call.operationName());
    }

    /** TC-R31-07 — GT-R31-15: 밑줄 든 호스트(getHost() == null) → 날 authority 에서 가름. */
    @Test
    void readsUnderscoreHostFromRawAuthority() {
        Map<String, Object> attrs = ClientHttpSupport.of("GET", URI.create("http://svc_a:8080/p")).attributes();

        assertEquals("svc_a", attrs.get("server.address"));
        assertEquals(Integer.valueOf(8080), attrs.get("server.port"));

        // [R31 · AC-R31-14/15 · SEC-R31-G1] 밑줄 호스트 + 사용자 정보 — 날 authority 갈래에서도 사용자 정보 · 쿼리를 버린다.
        ClientHttpSupport.ClientCall withUserInfo =
                ClientHttpSupport.of("GET", URI.create("http://user:pw@svc_a:8080/p?q=1"));
        Map<String, Object> userInfoAttrs = withUserInfo.attributes();
        assertEquals("svc_a", userInfoAttrs.get("server.address"));
        assertEquals(Integer.valueOf(8080), userInfoAttrs.get("server.port"));
        assertEquals("http://svc_a:8080/p", userInfoAttrs.get("http.url"));
        String url = String.valueOf(userInfoAttrs.get("http.url"));
        String all = withUserInfo.operationName() + " " + userInfoAttrs;
        for (String leak : new String[]{"user", "pw", "q=1"}) {
            assertFalse(url.contains(leak), "http.url 에 위생 밖 글자: " + leak + " in " + url);
            assertFalse(all.contains(leak), "이름 · 속성에 위생 밖 글자: " + leak + " in " + all);
        }
    }

    /** TC-R31-08 — 설계 DD-R31-10 경계: 경로 2,048자 = 그대로 · 2,049자 = 앞 2,048자 + "...". */
    @Test
    void capsPathAt2048Chars() {
        String path2048 = "/" + "a".repeat(2047);
        String path2049 = "/" + "a".repeat(2048);

        assertEquals("http://h" + path2048,
                ClientHttpSupport.of("GET", URI.create("http://h" + path2048)).attributes().get("http.url"));
        assertEquals("http://h" + path2049.substring(0, 2048) + "...",
                ClientHttpSupport.of("GET", URI.create("http://h" + path2049)).attributes().get("http.url"));
    }

    /** TC-R31-09 — AC-R31-26 보조: 날 경로 그대로({@code %23} 을 풀지 않음) → 이름 · url 에 {@code #} 0. */
    @Test
    void keepsPercentEncodedPathRaw() {
        ClientHttpSupport.ClientCall call = ClientHttpSupport.of("GET", URI.create("http://h/a%23b"));

        assertFalse(call.operationName().contains("#"), call.operationName());
        assertFalse(String.valueOf(call.attributes().get("http.url")).contains("#"));
        assertTrue(call.operationName().endsWith("/a%23b"), call.operationName());
    }

    // ─── 실물 package-private 객체 (AC-R31-11 · 12 · RK-R31-15) ─────────────────

    /** TC-R31-10 — AC-R31-11 요청 쪽: 실물 SimpleClientHttpRequest 에서 네 값. */
    @Test
    void readsFourRequestValuesFromPackagePrivateRequest() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/ok");
        ClientHttpRequest request = new SimpleClientHttpRequestFactory().createRequest(uri, HttpMethod.POST);

        Map<String, Object> attrs = ClientHttpSupport.requestAttributes(request);

        assertEquals("POST", attrs.get("http.method"));
        assertEquals("http://127.0.0.1:" + port + "/ok", attrs.get("http.url"));
        assertEquals("127.0.0.1", attrs.get("server.address"));
        assertEquals(Integer.valueOf(port), attrs.get("server.port"));
    }

    /** TC-R31-11 — AC-R31-11 상태 쪽: 같은 요청 실행 → 로컬 서버 200 → readStatus = 200(Integer). */
    @Test
    void readsStatus200FromPackagePrivateResponse() throws Exception {
        assertEquals(Integer.valueOf(200), statusOf("/ok"));
    }

    /** TC-R31-12 — AC-R31-12: 표준 밖 상태 299 → 299(열거형 전제 없음). */
    @Test
    void readsNonStandardStatus299() throws Exception {
        assertEquals(Integer.valueOf(299), statusOf("/s299"));
    }

    // ─── agent 안쪽 실패 · 호스트 실패 (AC-R31-67 · RC-R31-16) ─────────────────

    /** TC-R31-13 — AC-R31-67: 응답이 아닌 객체 → 안쪽 실패(코드 · 호스트 실패 둘 다 없음) · 던짐 0. */
    @Test
    void returnsAgentFailureForNonResponseObject() {
        ClientHttpSupport.StatusRead read = assertDoesNotThrow(() -> ClientHttpSupport.readStatus(new Object()));

        assertNull(read.code());
        assertNull(read.hostFailure());
    }

    /** TC-R31-14 — RC-R31-16 단위: 상태 읽기가 IOException → 호스트 실패 = 그 인스턴스(감싼 예외 아님). */
    @Test
    void unwrapsHostFailureFromStatusRead() {
        IOException boom = new IOException("boom");
        ClientHttpResponse response = (ClientHttpResponse) Proxy.newProxyInstance(
                ClientHttpSupportTest.class.getClassLoader(), new Class<?>[]{ClientHttpResponse.class},
                (proxy, method, args) -> {
                    if ("getStatusCode".equals(method.getName())) {
                        throw boom;
                    }
                    return null;
                });

        ClientHttpSupport.StatusRead read = ClientHttpSupport.readStatus(response);

        assertNull(read.code());
        assertSame(boom, read.hostFailure(), "InvocationTargetException 이 아니라 원인 그 자체");
    }

    /** TC-R31-15 — AC-R31-67: 요청이 아닌 객체 → 이름 {@code HTTP UNKNOWN} · 던짐 0. */
    @Test
    void returnsUnknownNameForNonRequestObject() {
        String name = assertDoesNotThrow(() -> ClientHttpSupport.operationName(new Object()));

        assertEquals("HTTP UNKNOWN", name);
    }

    /** TC-R31-16 — AC-R31-67: 진짜 frame + 응답 아닌 객체 → span 1 · OK · 상태 키 없음 · 스택 비워짐. */
    @Test
    void recordsOkSpanWithoutStatusOnAgentFailure() {
        TraceContext.Frame frame = AdviceSupport.enterClientSpan(new Object());
        assertNotNull(frame);
        assertNotSame(TraceContext.Frame.SKIPPED, frame);

        assertDoesNotThrow(() -> AdviceSupport.exitClientSpan(frame, new Object(), new Object(), null));

        List<Span> spans = drain();
        assertEquals(1, spans.size());
        assertEquals(SpanStatus.OK, spans.get(0).status(), "안쪽 실패를 ERROR 로 칠하면 빨강");
        Map<String, Object> attrs = spans.get(0).attributes() == null ? Map.of() : spans.get(0).attributes();
        assertFalse(attrs.containsKey("http.status_code"));
        assertEquals(0, TraceContext.depth(), "frame 이 스택에 남으면 빨강");
    }

    // ─── 정정 신설 (DV-A-R31-4 · DV-A-R31-12) ─────────────────────────────────

    /**
     * TC-R31-17 — AC-R31-67 · 설계 정정 DV-A-R31-4: 값 손잡이가 null 인 손잡이 묶음(Spring 5.x 꼴)이면 응답의
     * 상태 메서드를 <b>부르지 않는다</b>(응답 대기 당김 0) · 코드 · 호스트 실패 둘 다 null.
     */
    @Test
    void skipsStatusCallWhenValueHandleMissing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClientHttpResponse counting = (ClientHttpResponse) Proxy.newProxyInstance(
                ClientHttpSupportTest.class.getClassLoader(), new Class<?>[]{ClientHttpResponse.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    return null;
                });
        Method statusHandle = ClientHttpResponse.class.getMethod("getStatusCode");
        ClientHttpSupport.Handles noValueHandle = new ClientHttpSupport.Handles(null, null, null, statusHandle, null);

        ClientHttpSupport.StatusRead read = ClientHttpSupport.readStatus(counting, noValueHandle);

        assertEquals(0, calls.get(), "손잡이가 빠졌는데 상태를 부르면 응답 대기를 당긴다");
        assertNull(read.code());
        assertNull(read.hostFailure());
    }

    /** TC-R31-18 — 설계 DD-R31-11 · 정정 DV-A-R31-12 ①: 스킴 대소문자 — {@code HTTPS} 도 443 · url 은 소문자. */
    @Test
    void lowercasesSchemeForPortAndUrl() {
        Map<String, Object> attrs = ClientHttpSupport.of("GET", URI.create("HTTPS://h/a")).attributes();

        assertEquals(Integer.valueOf(443), attrs.get("server.port"));
        assertEquals("https://h/a", attrs.get("http.url"));
    }

    /**
     * TC-R31-19 — BV-R31-12 곁 · 정정 DV-A-R31-12 ③: 영역 표시 든 IPv6 → 던짐 0 · server.address 에 괄호 0 ·
     * 이름 호스트는 괄호 꼴. 괄호 안 글자 = JDK 21 이 준 그대로(dev 첫 실행 값 {@code fe80::1%25en0}).
     */
    @Test
    void acceptsScopedIpv6Host() {
        ClientHttpSupport.ClientCall call = assertDoesNotThrow(() ->
                ClientHttpSupport.of("GET", URI.create("http://[fe80::1%25en0]:8080/x")));

        Object address = call.attributes().get("server.address");
        assertNotNull(address);
        assertFalse(address.toString().contains("[") || address.toString().contains("]"), address.toString());
        assertEquals("fe80::1%25en0", address);
        assertEquals("HTTP GET [fe80::1%25en0]:8080/x", call.operationName());
    }

    private Integer statusOf(String path) throws Exception {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        ClientHttpRequest request = factory.createRequest(URI.create("http://127.0.0.1:" + port + path), HttpMethod.POST);
        request.getBody().write(new byte[]{1});
        try (ClientHttpResponse response = request.execute()) {
            return ClientHttpSupport.readStatus(response).code();
        }
    }

    private List<Span> drain() {
        List<Span> out = new ArrayList<>();
        queue.drainTo(out);
        return out;
    }
}
