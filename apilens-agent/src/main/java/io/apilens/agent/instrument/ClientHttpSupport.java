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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reflection, URL sanitising and naming helpers for the outbound HTTP (CLIENT) span.
 *
 * <p>Package-private on purpose: the only callers are {@link AdviceSupport} and tests. Every
 * method here swallows its own failures and never throws — the advice that ends up calling
 * this code is inlined into the host's HTTP client.
 *
 * <p>Reflection rule: {@link Method} handles are obtained from the <em>public</em> Spring
 * types only ({@code HttpRequest}, {@code HttpMethod}, {@code ClientHttpResponse},
 * {@code HttpStatusCode}), looked up through the host object's own class loader. The
 * concrete request/response classes are package-private, so a handle taken from them would
 * fail with an access error on every call.
 */
final class ClientHttpSupport {

    // [Phase R31] R31/AC-R31-02/AC-R31-11/AC-R31-15 — 속성 키 다섯. 오류 판정 키(http.status_code)는
    //   SERVER 와 같은 이름이라 AdviceSupport.exit 의 4xx/5xx 판정이 그대로 걸린다(설계 DD-R31-09).
    //   상대 주소만 현행 OTel 이름(server.address · server.port). 사용자 명시 비협상 결정(UD-R31-2).
    //   CLAUDE.md '아키텍처 핵심 원칙'(Trace 모델은 OpenTelemetry 호환) 인용.
    static final String KEY_METHOD = "http.method";
    static final String KEY_URL = "http.url";
    static final String KEY_SERVER_ADDRESS = "server.address";
    static final String KEY_SERVER_PORT = "server.port";
    static final String KEY_STATUS_CODE = "http.status_code";

    /** [Phase R31] 설계 DD-R31-10 — 경로 상한(속성 칸 크기 가드가 서버에 없어 agent 쪽 입력 경계). */
    static final int PATH_MAX_CHARS = 2048;

    /** 경로 상한을 넘었을 때 붙이는 표시. */
    static final String PATH_TRUNCATED_SUFFIX = "...";

    static final String UNKNOWN_METHOD = "UNKNOWN";

    private static final String NAME_PREFIX = "HTTP ";

    private static final String HTTP_REQUEST = "org.springframework.http.HttpRequest";
    private static final String HTTP_METHOD = "org.springframework.http.HttpMethod";
    private static final String CLIENT_HTTP_RESPONSE = "org.springframework.http.client.ClientHttpResponse";
    private static final String HTTP_STATUS_CODE = "org.springframework.http.HttpStatusCode";

    /**
     * [Phase R31] 설계 DD-R31-04 — 구현 클래스마다 손잡이 묶음 한 벌. ClassValue 라 클래스가 내려가면
     * 함께 사라진다(클래스 로더를 붙잡지 않음). 못 찾은 칸은 null 이고 나머지 칸은 쓴다.
     */
    private static final ClassValue<Handles> HANDLES = new ClassValue<>() {
        @Override
        protected Handles computeValue(Class<?> type) {
            return Handles.lookup(type);
        }
    };

    private ClientHttpSupport() {
    }

    /**
     * Method handles resolved from the public Spring types. Any slot may be null — the
     * caller simply skips what it cannot read.
     */
    record Handles(Method requestMethod, Method requestUri, Method methodName,
                   Method responseStatus, Method statusValue) {

        static final Handles NONE = new Handles(null, null, null, null, null);

        static Handles lookup(Class<?> type) {
            ClassLoader loader = type.getClassLoader();
            Method requestMethod = null;
            Method requestUri = null;
            Method methodName = null;
            Method responseStatus = null;
            Method statusValue = null;
            Class<?> httpRequest = publicType(HTTP_REQUEST, loader);
            if (httpRequest != null && httpRequest.isAssignableFrom(type)) {
                requestMethod = publicMethod(httpRequest, "getMethod");
                requestUri = publicMethod(httpRequest, "getURI");
                Class<?> httpMethod = publicType(HTTP_METHOD, loader);
                if (httpMethod != null) {
                    methodName = publicMethod(httpMethod, "name");
                }
            }
            Class<?> response = publicType(CLIENT_HTTP_RESPONSE, loader);
            if (response != null && response.isAssignableFrom(type)) {
                responseStatus = publicMethod(response, "getStatusCode");
                // Spring 5.x 에는 이 타입이 없다 → 값 손잡이 null → readStatus 가 상태 호출을 아예 안 한다.
                Class<?> statusCode = publicType(HTTP_STATUS_CODE, loader);
                if (statusCode != null) {
                    statusValue = publicMethod(statusCode, "value");
                }
            }
            return new Handles(requestMethod, requestUri, methodName, responseStatus, statusValue);
        }

        private static Class<?> publicType(String name, ClassLoader loader) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable t) {
                return null;
            }
        }

        private static Method publicMethod(Class<?> owner, String name) {
            try {
                return owner.getMethod(name);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Request side of one call, read once at enter: the span name and its attribute map. */
    record ClientCall(String operationName, Map<String, Object> attributes) {
    }

    /**
     * Result of reading the response status. Both null = agent-side failure (no handle,
     * access error, non-integer value) — silently dropped, the span stays OK.
     */
    record StatusRead(Integer code, Throwable hostFailure) {

        static final StatusRead AGENT_FAILURE = new StatusRead(null, null);
    }

    /** Sanitised pieces of a request URI (no query, fragment or user info is ever read). */
    record UrlParts(String scheme, String hostDisplay, String address, Integer port,
                    int explicitPort, String path) {

        /** {@code http.url} value — only the port written in the URL, no default filled in. */
        String url() {
            if (hostDisplay == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            if (scheme != null) {
                sb.append(scheme).append("://");
            }
            sb.append(hostDisplay);
            if (explicitPort >= 0) {
                sb.append(':').append(explicitPort);
            }
            return sb.append(path).toString();
        }

        /** Host part of the span name — default port filled in, nothing when the port is unknown. */
        String hostPort() {
            if (hostDisplay == null) {
                return null;
            }
            return port == null ? hostDisplay : hostDisplay + ":" + port;
        }
    }

    /**
     * Reads the request once ({@code getMethod} and {@code getURI} one call each) and builds
     * the span name plus the attribute map. Never throws; on any failure the name falls back
     * to {@code HTTP UNKNOWN} and the map holds whatever could be read.
     */
    static ClientCall describe(Object request) {
        String method = null;
        URI uri = null;
        try {
            Handles h = request == null ? Handles.NONE : HANDLES.get(request.getClass());
            method = readMethod(request, h);
            uri = readUri(request, h);
        } catch (Throwable t) {
            // 조용히 — 읽은 만큼만 쓴다
        }
        return of(method, uri);
    }

    /**
     * Builds the span name and attributes from an already-read method and URI (test entry
     * point for URL hygiene without a live request object). Never throws.
     */
    static ClientCall of(String method, URI uri) {
        Map<String, Object> attrs = new HashMap<>();
        String m = method == null || method.isEmpty() ? UNKNOWN_METHOD : method;
        UrlParts parts = null;
        try {
            if (method != null && !method.isEmpty()) {
                attrs.put(KEY_METHOD, method);
            }
            parts = sanitize(uri);
            if (parts != null) {
                String url = parts.url();
                if (url != null) {
                    attrs.put(KEY_URL, url);
                }
                if (parts.address() != null) {
                    attrs.put(KEY_SERVER_ADDRESS, parts.address());
                }
                if (parts.port() != null) {
                    attrs.put(KEY_SERVER_PORT, parts.port());
                }
            }
        } catch (Throwable t) {
            // 조용히 — 읽은 만큼만 쓴다
        }
        return new ClientCall(nameOf(m, parts), attrs);
    }

    /** Span name view of {@link #describe(Object)} (test entry point). */
    static String operationName(Object request) {
        return describe(request).operationName();
    }

    /** Attribute view of {@link #describe(Object)} — a mutable map (test entry point). */
    static Map<String, Object> requestAttributes(Object request) {
        return describe(request).attributes();
    }

    /**
     * [Phase R31] 설계 DD-R31-08 — {@code HTTP {METHOD} {host}:{port}{rawPath}}. 이름에 {@code #} 를
     * 넣지 않는다 — 화면의 이름 줄이기(마지막 점 뒤로 자름)가 IP 호스트를 조각내기 때문이다.
     * 메서드를 못 읽으면 {@code HTTP UNKNOWN}, 호스트를 못 읽으면 메서드까지만.
     */
    static String nameOf(String method, UrlParts parts) {
        String m = method == null ? UNKNOWN_METHOD : method;
        if (UNKNOWN_METHOD.equals(m) || parts == null || parts.hostPort() == null) {
            return NAME_PREFIX + m;
        }
        return NAME_PREFIX + m + " " + parts.hostPort() + parts.path();
    }

    /**
     * [Phase R31] R31/AC-R31-14/AC-R31-15 — URL 위생. 스킴 · 호스트 · 포트 · 날 경로만 읽는다(쿼리 ·
     * 프래그먼트 · 사용자 정보는 아예 안 읽음). 사용자 명시 비협상 결정(UD-R31-3 — 본문 · 쿼리 0).
     * CLAUDE.md 'v0.1 범위'(마스킹은 server-side — 속성은 마스킹을 안 거치므로 agent 쪽에서 덜어냄) 인용.
     * <ul>
     *   <li>스킴은 소문자로 비교하고 소문자로 쓴다(설계 정정 DV-A-R31-12 ①).</li>
     *   <li>밑줄 든 호스트는 {@code getHost()} 가 null → 날 authority 의 마지막 {@code @} 뒤에서 가른다.</li>
     *   <li>{@code server.address} 는 괄호 없는 IPv6 · 이름과 {@code http.url} 은 괄호 꼴.</li>
     *   <li>포트가 URL 에 없으면 {@code server.port} = http 80 · https 443 · 그 밖 스킴은 키 없음.</li>
     *   <li>날 경로는 퍼센트 인코딩 그대로({@code %23} 을 풀지 않음) · {@code ;} 뒤 경로 매개변수도 경로의 일부.</li>
     * </ul>
     * Never throws — returns null when nothing usable is in the URI.
     */
    static UrlParts sanitize(URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int explicitPort = uri.getPort();
            if (host == null) {
                // 밑줄 든 호스트 등 서버 기반 authority 로 못 읽은 경우 — 날 authority 에서 직접 가른다.
                String authority = uri.getRawAuthority();
                if (authority != null) {
                    int at = authority.lastIndexOf('@');
                    String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
                    int colon = hostPort.lastIndexOf(':');
                    if (!hostPort.startsWith("[") && colon > 0 && isDigits(hostPort.substring(colon + 1))) {
                        host = hostPort.substring(0, colon);
                        explicitPort = Integer.parseInt(hostPort.substring(colon + 1));
                    } else if (!hostPort.isEmpty()) {
                        host = hostPort;
                    }
                }
            }
            String address = host;
            if (host != null && host.length() >= 2 && host.startsWith("[") && host.endsWith("]")) {
                address = host.substring(1, host.length() - 1);
            }
            if (host != null && host.isEmpty()) {
                host = null;
                address = null;
            }
            Integer port = null;
            if (explicitPort >= 0) {
                port = explicitPort;
            } else if ("http".equals(scheme)) {
                port = 80;
            } else if ("https".equals(scheme)) {
                port = 443;
            }
            String path = capPath(uri.getRawPath());
            return new UrlParts(scheme, host, address, host == null ? null : port, explicitPort, path);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 경계: 길이 == 상한 → 그대로(≤ 비교) · 넘으면 앞 상한 글자 + {@code ...}. */
    static String capPath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        if (rawPath.length() <= PATH_MAX_CHARS) {
            return rawPath;
        }
        return rawPath.substring(0, PATH_MAX_CHARS) + PATH_TRUNCATED_SUFFIX;
    }

    /**
     * Reads the status code of a host response. Three outcomes (설계 DD-R31-06): a code;
     * a host failure (the cause of the {@code InvocationTargetException} — recorded, never
     * rethrown to the host); or an agent-side failure (both null — silently dropped).
     */
    static StatusRead readStatus(Object response) {
        if (response == null) {
            return StatusRead.AGENT_FAILURE;
        }
        try {
            return readStatus(response, HANDLES.get(response.getClass()));
        } catch (Throwable t) {
            return StatusRead.AGENT_FAILURE;
        }
    }

    /**
     * Test entry point with explicit handles. [Phase R31] 설계 정정 DV-A-R31-4 — 첫 동작: 상태 손잡이나
     * 값 손잡이가 하나라도 null 이면(예: Spring 5.x) 상태 호출을 <b>아예 하지 않는다</b> — 응답 대기를
     * 당겨 오는 일 0.
     */
    static StatusRead readStatus(Object response, Handles h) {
        if (response == null || h == null || h.responseStatus() == null || h.statusValue() == null) {
            return StatusRead.AGENT_FAILURE;
        }
        Object status;
        try {
            status = h.responseStatus().invoke(response);
        } catch (InvocationTargetException e) {
            // 호스트 쪽 실패(시간 초과 등) — 원인을 풀어 기록용으로만 넘긴다. 호스트에 다시 던지지 않는다.
            Throwable cause = e.getCause();
            return new StatusRead(null, cause != null ? cause : e);
        } catch (Throwable t) {
            return StatusRead.AGENT_FAILURE;
        }
        if (status == null) {
            return StatusRead.AGENT_FAILURE;
        }
        try {
            Object value = h.statusValue().invoke(status);
            if (value instanceof Integer code) {
                return new StatusRead(code, null);
            }
            return StatusRead.AGENT_FAILURE;
        } catch (Throwable t) {
            return StatusRead.AGENT_FAILURE;
        }
    }

    private static String readMethod(Object request, Handles h) {
        if (request == null || h.requestMethod() == null) {
            return null;
        }
        try {
            Object httpMethod = h.requestMethod().invoke(request);
            if (httpMethod == null) {
                return null;
            }
            Object name = h.methodName() != null ? h.methodName().invoke(httpMethod) : null;
            if (name instanceof String s && !s.isEmpty()) {
                return s.toUpperCase(Locale.ROOT);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static URI readUri(Object request, Handles h) {
        if (request == null || h.requestUri() == null) {
            return null;
        }
        try {
            Object uri = h.requestUri().invoke(request);
            return uri instanceof URI u ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty() || s.length() > 5) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
