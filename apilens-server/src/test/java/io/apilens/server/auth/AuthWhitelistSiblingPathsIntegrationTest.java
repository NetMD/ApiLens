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
package io.apilens.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R30] R30/AC-R30-36/R30/AC-R30-37/R30/AC-R30-38/R30/AC-R30-50 — 인증 면제 판정의 <b>형제 진입로 전부</b>를
 * 한 묶음으로 진짜 서블릿 컨테이너를 거쳐 잰다(설계 DT-R30-01 · 부록 E SB-R30-01~43 · 사전 체크리스트 #1).
 *
 * <p>AC-R30-36(보충) 원문: "형제 진입로 묶음 시험 한 파일: 진입로 갈래(설계 확정 · {@code ;} · {@code %3B} · {@code //} ·
 * 대소문자 · {@code %2F} · {@code ..} 포함) × 면제 / 보호 경로 → 기대 응답 표 · 갈래마다 서버가 받은 {@code getRequestURI()}
 * 원문을 표 칸에 함께 적고 보낸 글자와 같을 때만 그 행을 센다".
 *
 * <p>★<b>왜 소켓인가</b>(설계 DD-R30-10): 보내는 수단이 경로를 접거나({@code ..} · {@code //}) 다시 바꾸면(%) 그 행은 무엇을
 * 쟀는지 알 수 없다. 원문 바이트를 직접 쓰면 보낸 글자가 곧 원문이다. 그리고 이 수단은 Boot 3.4 · 3.5 · 4.1 어느 판에서도
 * <b>같은 코드</b>로 컴파일된다(판에 묶인 시험 API 0 — 포트도 {@code local.server.port} 속성으로 받는다).
 *
 * <p>★<b>표로 남기는 것과 단언하는 것을 가른다</b>: 단언은 판이 바뀌어도 같아야 하는 불변만이다 — ① 보호 기준 행은 토큰 없이
 * 401 ② 면제 기준 행은 2xx ③ 보호 대상 × 갈래 행은 상태가 400 · 401 · 404 중 하나이고 본문에 데이터 키가 없다.
 * 상태 값 · 필터 도달 · 서버가 받은 원문 · 같음 여부는 {@code build/sibling-paths/tomcat-{판}.tsv} 로만 남기고 QA 가 세 판
 * 표를 행 단위로 맞춘다. 보호 행이 2xx 면 ③ 이 먼저 빨개진다(출고 차단 갈래 AC-R30-37). 그 밖의 다름은 보안 판정으로 넘긴다.
 *
 * <p>★「200 이 아니다」 대신 세 값으로 좁혔다 — 「200 이 아니다」는 500 같은 엉뚱한 실패도 통과시키는 빈 그물이다
 * (같은 폴더 {@code AuthWhitelistPathIntegrationTest} 의 RA-R26-11 교훈).
 *
 * <p>★한계 그 자리에: 갈래 9 는 표본이다 — 새 우회 꼴을 다 덮지 않는다. 보호 대상을 늘릴 때는 9 갈래 전부를 함께 더한다.
 * {@code AuthWhitelist.java} 는 이 시험 때문에 고치지 않는다(diff 0 · 사용자 명시 비협상 결정 · CLAUDE.md '절대 변경하지
 * 말아야 할 결정 사항' 의 포트 8765 · 신뢰망 전제 인용).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=" + AuthWhitelistSiblingPathsIntegrationTest.API_KEY}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthWhitelistSiblingPathsIntegrationTest {

    static final String API_KEY = "test-sibling-paths-key";

    /** 서버가 받은 원문을 행 열쇠({@code X-Probe})로 담는다 — 시험 전용 필터가 채운다. */
    private static final Map<String, String> RECORDED = new ConcurrentHashMap<>();

    private static final String PROBE_HEADER = "X-Probe";

    private static final ObjectMapper JSON = new ObjectMapper();

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-sibling-paths-it-", ".db");
            Files.deleteIfExists(TEMP_DB);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEMP_DB.toAbsolutePath()
                        + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000");
    }

    /**
     * [Phase R30] R30/AC-R30-36 — 서버 기록 필터. 인증 필터보다 <b>먼저</b> 돌아야 거절된 요청의 원문도 남는다
     * (설계 정정 DV-A-R30-04 — 등록은 {@code FilterRegistrationBean} + 최상위 순서). 컨테이너가 필터 앞에서 끊은
     * 요청(예: 인코딩된 {@code /})은 여기 안 닿는다 → 표의 「필터 도달」 칸이 「아니오」가 된다.
     */
    @TestConfiguration
    static class ProbeRecorderConfig {
        @Bean
        FilterRegistrationBean<Filter> siblingPathsProbeRecorder() {
            Filter recorder = (request, response, chain) -> {
                if (request instanceof HttpServletRequest http) {
                    String probe = http.getHeader(PROBE_HEADER);
                    if (probe != null) {
                        String query = http.getQueryString();
                        RECORDED.put(probe, http.getRequestURI() + (query == null ? "" : "?" + query));
                    }
                }
                chain.doFilter(request, response);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(recorder);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    @Value("${local.server.port}")
    private int port;

    /** 보호 대상(P1~P4)마다 토큰과 함께 먼저 불러 얻은 응답 최상위 키 — 갈래 행의 「데이터 키」. */
    private final Map<String, Set<String>> dataKeys = new HashMap<>();

    /** 표 행(TSV) — {@code @AfterAll} 에서 한 번 쓴다. */
    private final List<String> table = Collections.synchronizedList(new ArrayList<>());

    enum Kind { PROTECTED_BASE, EXEMPT_BASE, SIBLING }

    /** 부록 E 한 행. {@code target} = 보호 대상 열(P1~P4 · 면제 기준은 빈 칸). */
    record Row(String id, String method, String raw, Kind kind, String target) {
        @Override
        public String toString() {
            return id + " " + method + " " + raw;
        }
    }

    /** 보호 대상 넷의 원문(토큰과 함께 부르는 전제 단언 · 보호 기준 행이 같은 원문을 토큰 없이 보낸다). */
    private static final Map<String, String> TARGETS = Map.of(
            "P1", "/v1/traces?service=svc",
            "P2", "/v1/services",
            "P3", "/v1/maintenance/status",
            "P4", "/actuator/info");

    /**
     * 부록 E 의 43 행 — 갈래 9 × 보호 4(SB-R30-01~36) + 보호 기준 4(37~40) + 면제 기준 3(41~43). 행 번호 =
     * (갈래 − 1) × 4 + 보호 대상 번호. 차례는 표 차례 그대로다.
     */
    static Stream<Row> rows() {
        // P1~P3 는 /v1/{R} 꼴 · P4 는 /actuator 꼴 — 갈래마다 원문을 펼쳐 쓴다(부록 E 열 그대로).
        String[] r = {"traces?service=svc", "services", "maintenance/status"};
        List<Row> rows = new ArrayList<>();
        String[][] branches = {
                // g1 %2e%2e · g2 .. · g3 ; 첫 부분 · g4 ;x=y · g5 %3B · g6 %2F · g7 // 앞 · g8 // 가운데 · g9 대소문자
                {"/v1/setup/%2e%2e/", "/actuator/health/%2e%2e/info"},
                {"/v1/setup/../", "/actuator/health/../info"},
                {"/v1;/", "/actuator;/info"},
                {"/v1;x=y/", "/actuator;x=y/info"},
                {"/v1%3B/", "/actuator%3B/info"},
                {"/v1%2F", "/actuator%2Finfo"},
                {"//v1/", "//actuator/info"},
                {"/v1//", "/actuator//info"},
                {"/V1/", "/ACTUATOR/info"},
        };
        int n = 1;
        for (String[] branch : branches) {
            for (int p = 0; p < 3; p++) {
                rows.add(new Row(id(n++), "GET", branch[0] + r[p], Kind.SIBLING, "P" + (p + 1)));
            }
            rows.add(new Row(id(n++), "GET", branch[1], Kind.SIBLING, "P4"));
        }
        rows.add(new Row(id(n++), "GET", TARGETS.get("P1"), Kind.PROTECTED_BASE, "P1"));
        rows.add(new Row(id(n++), "GET", TARGETS.get("P2"), Kind.PROTECTED_BASE, "P2"));
        rows.add(new Row(id(n++), "GET", TARGETS.get("P3"), Kind.PROTECTED_BASE, "P3"));
        rows.add(new Row(id(n++), "GET", TARGETS.get("P4"), Kind.PROTECTED_BASE, "P4"));
        rows.add(new Row(id(n++), "POST", "/v1/spans", Kind.EXEMPT_BASE, ""));
        rows.add(new Row(id(n++), "GET", "/v1/setup/state", Kind.EXEMPT_BASE, ""));
        rows.add(new Row(id(n++), "GET", "/actuator/health", Kind.EXEMPT_BASE, ""));
        return rows.stream();
    }

    private static String id(int n) {
        return String.format("SB-R30-%02d", n);
    }

    /**
     * 전제 단언(설계 §5.2 ④ · 정정 DV-T-R30-7 · DV-A-R30-04) — testcase 로 세지 않는다.
     * ① 보호 대상 넷을 토큰과 함께 불러 최상위 키 ≥ 1 을 얻는다 — 0 이면 ③ 의 「데이터 키 0」이 빈 그물이라 먼저 실패한다.
     * ② 보호 기준 원문 넷을 토큰 없이 보내 기록 필터가 넷 다 받았는지 본다 — 기록 필터가 안 돌면 표의 원문 칸이 전부 빈다.
     */
    @BeforeAll
    void requireDataKeysAndAWorkingRecorder() throws IOException {
        for (String target : List.of("P1", "P2", "P3", "P4")) {
            Response res = send("GET", TARGETS.get(target), "PRE-TOKEN-" + target, true);
            assertEquals(200, res.status(), "전제: 보호 대상을 토큰과 함께 부르면 200 — " + target + " 실제 " + res.status());
            Set<String> keys = topLevelKeys(res.body());
            assertFalse(keys.isEmpty(), "전제: 보호 대상 응답의 최상위 키가 1 이상이어야 갈래 행 판정이 뜻을 가진다 — " + target);
            dataKeys.put(target, keys);
        }
        int reached = 0;
        for (String target : List.of("P1", "P2", "P3", "P4")) {
            String probe = "PRE-NOTOKEN-" + target;
            send("GET", TARGETS.get(target), probe, false);
            if (RECORDED.containsKey(probe)) {
                reached++;
            }
        }
        assertEquals(4, reached, "전제: 보호 기준 원문 넷이 모두 기록 필터에 닿아야 한다(필터 도달 4/4)");
    }

    /**
     * [Phase R30] R30/AC-R30-36 — 행마다 보내고 표에 남긴 뒤 판이 바뀌어도 같아야 하는 불변만 단언한다.
     * AC-R30-37 원문: "보호 경로가 토큰 없이 2xx 인 행이 하나라도 나오면 출고 차단". AC-R30-50 원문: "기본 면제 행
     * ({@code POST /v1/spans} · setup 묶음 대표 1 · {@code /actuator/health})이 두 판 어느 쪽에서든 2xx 가 아니면 출고 차단".
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rows")
    void keepsEachSiblingPathOnItsExpectedSide(Row row) throws IOException {
        Response res = send(row.method(), row.raw(), row.id(), false);
        String recorded = RECORDED.get(row.id());
        boolean reached = recorded != null;
        boolean same = reached && recorded.equals(row.raw());
        table.add(String.join("\t", row.id(), row.method(), row.raw(), String.valueOf(res.status()),
                reached ? "yes" : "no", reached ? recorded : "-", same ? "yes" : "no"));

        switch (row.kind()) {
            case PROTECTED_BASE -> assertEquals(401, res.status(),
                    "보호 기준 행은 토큰 없이 401 — " + row + " 실제 " + res.status());
            case EXEMPT_BASE -> assertTrue(res.status() >= 200 && res.status() < 300,
                    "면제 기준 행은 토큰 없이 2xx — " + row + " 실제 " + res.status() + " " + res.body());
            case SIBLING -> {
                assertTrue(res.status() == 400 || res.status() == 401 || res.status() == 404,
                        "보호 대상 × 갈래 행은 400 · 401 · 404 중 하나 — " + row + " 실제 " + res.status() + " " + res.body());
                Set<String> leaked = new LinkedHashSet<>(dataKeys.get(row.target()));
                leaked.retainAll(bodyKeys(res.body(), dataKeys.get(row.target())));
                assertTrue(leaked.isEmpty(), "본문에 보호 대상의 데이터 키가 없어야 한다 — " + row + " 새어 나온 키 " + leaked);
            }
        }
    }

    /** 표를 한 번 쓴다 — 판정과 무관하게(빨강이어도) 남긴다. QA 가 판마다 작업 자리로 옮긴다. */
    @AfterAll
    void writeTableAndDeleteTempDb() throws IOException {
        String info = ServerInfo.getServerInfo();
        String version = info.substring(info.indexOf('/') + 1);
        Path out = Path.of("build", "sibling-paths", "tomcat-" + version + ".tsv");
        Files.createDirectories(out.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# " + info + " · Spring Boot " + org.springframework.boot.SpringBootVersion.getVersion());
        lines.add(String.join("\t", "id", "method", "sent", "status", "filter_reached", "server_recorded", "same"));
        List<String> sorted = new ArrayList<>(table);
        Collections.sort(sorted);
        lines.addAll(sorted);
        Files.write(out, lines, StandardCharsets.UTF_8);

        String base = TEMP_DB.toAbsolutePath().toString();
        Files.deleteIfExists(TEMP_DB);
        Files.deleteIfExists(Path.of(base + "-wal"));
        Files.deleteIfExists(Path.of(base + "-shm"));
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────────────────

    record Response(int status, String body) {
    }

    /** 원문 그대로 한 줄을 써 보낸다. 바이트를 직접 쓰므로 보낸 글자 = 원문이다. 대기는 소켓 읽기 제한 시간뿐이다. */
    private Response send(String method, String raw, String probe, boolean withToken) throws IOException {
        byte[] body = "POST".equals(method)
                ? ("{\"spans\":[{\"spanId\":\"c0ffee0000000041\",\"traceId\":\"c0ffee00000000000000000000000041\","
                + "\"serviceName\":\"sibling-svc\",\"operationName\":\"GET /ping\",\"spanKind\":\"SERVER\","
                + "\"startTime\":1767225600000,\"endTime\":1767225600010,\"status\":\"OK\"}]}")
                .getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(raw).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(port).append("\r\n");
        head.append("Connection: close\r\n");
        head.append(PROBE_HEADER).append(": ").append(probe).append("\r\n");
        if (withToken) {
            head.append("Authorization: Bearer ").append(API_KEY).append("\r\n");
        }
        if (body.length > 0) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return parse(readAll(socket.getInputStream()));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** 상태 줄 · 머리 · 본문을 가른다. 본문이 chunked 면 풀어 쓴다(Connection: close 라 끝은 EOF). */
    private static Response parse(byte[] raw) {
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int headEnd = all.indexOf("\r\n\r\n");
        String head = headEnd < 0 ? all : all.substring(0, headEnd);
        String statusLine = head.split("\r\n", 2)[0];
        String[] parts = statusLine.split(" ");
        int status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        byte[] bodyBytes = headEnd < 0 ? new byte[0]
                : java.util.Arrays.copyOfRange(raw, headEnd + 4, raw.length);
        if (head.toLowerCase().contains("transfer-encoding: chunked")) {
            bodyBytes = dechunk(bodyBytes);
        }
        return new Response(status, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int lineEnd = indexOfCrlf(in, i);
            if (lineEnd < 0) {
                break;
            }
            String sizeText = new String(in, i, lineEnd - i, StandardCharsets.ISO_8859_1).trim();
            int semi = sizeText.indexOf(';');
            int size = Integer.parseInt(semi >= 0 ? sizeText.substring(0, semi) : sizeText, 16);
            if (size == 0) {
                break;
            }
            int start = lineEnd + 2;
            out.write(in, start, Math.min(size, in.length - start));
            i = start + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] in, int from) {
        for (int i = from; i + 1 < in.length; i++) {
            if (in[i] == '\r' && in[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 응답 본문이 JSON 객체면 최상위 키 · 아니면 빈 집합. */
    private static Set<String> topLevelKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonNode node = JSON.readTree(body);
            if (node != null && node.isObject()) {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    keys.add(e.getKey());
                }
            }
        } catch (IOException ignored) {
            // JSON 이 아니면 키가 없다 — 호출자가 글자 대조로 넘어간다.
        }
        return keys;
    }

    /**
     * 갈래 행 본문에서 데이터 키를 찾는다. JSON 객체면 최상위 키로 본다(오류 본문의 {@code path} 값에 경로 낱말이 글자로
     * 실려도 키가 아니다). JSON 이 아니면(컨테이너 HTML 오류 쪽 등) 따옴표를 포함한 키 글자가 본문에 있는지 본다.
     */
    private static Set<String> bodyKeys(String body, Set<String> candidates) {
        Set<String> keys = topLevelKeys(body);
        if (!keys.isEmpty() || body.trim().startsWith("{")) {
            return keys;
        }
        Set<String> found = new LinkedHashSet<>();
        for (String c : candidates) {
            if (body.contains("\"" + c + "\"")) {
                found.add(c);
            }
        }
        return found;
    }
}
