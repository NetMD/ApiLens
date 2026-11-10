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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R32] R32/AC-R32-03-1/R32/AC-R32-03-2/R32/AC-R32-03-3/R32/AC-R32-03-4/R32/AC-R32-03-5/R32/AC-R32-03-8 — 수집 ·
 * 조회 입구가 YAML 을 받지도 답하지도 않는지, 그리고 JSON 동작 · API 문서는 그대로인지 진짜 컨테이너를 거쳐 잰다.
 * 사용자 명시 결정(YAML POST 202 → 415 · YAML 만 받는 조회 200 → 406 · {@code /v3/api-docs.yaml} 200 그대로).
 *
 * <p>AC-R32-03-8 원문: "위 03-1~03-5 의 값을 고정하는 시험이 실제 요청을 보내 상태를 단언 · 시험 이름마다 실행 1".
 *
 * <p>★왜 소켓인가: {@code Content-Type} · {@code Accept} 를 글자 그대로 보내고 실제 변환기 사슬(설정 클래스를 거친 목록)을
 * 타야 한다. 변환기를 따로 세운 MockMvc 시험은 그 목록을 안 거쳐 이 동작을 못 본다. 보내는 꼴은 같은 저장소
 * {@code AuthWhitelistSiblingPathsIntegrationTest} 의 원문 바이트 송신을 머리 두 개 · 본문 · 응답 형식까지 담게 넓힌 것이다.
 *
 * <p>★행 셈은 {@code count(*)} 로 한다 — 행이 0 일 때도 값이 0 으로 나온다. 앞뒤 셈이 늘 같은 값을 내는 고장(셈 질의가
 * 깨짐)은 JSON 정상 행이 같은 문맥에서 +1 을 보여 막는다.
 *
 * <p>★한계 그 자리에: 인증 없는 입구 둘(수집 · 설정 완료)과 대표 조회 하나만 직접 잰다. 나머지 보호 입구는 같은 변환기
 * 목록을 쓴다는 구조 증거({@code RuntimeBaselineIntegrationTest#registersNoYamlCapableConverter})로 덮는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class YamlIngressIntegrationTest {

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-yaml-ingress-it-", ".db");
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

    @AfterAll
    static void deleteTempDb() throws IOException {
        String base = TEMP_DB.toAbsolutePath().toString();
        Files.deleteIfExists(TEMP_DB);
        Files.deleteIfExists(Path.of(base + "-wal"));
        Files.deleteIfExists(Path.of(base + "-shm"));
    }

    private static final String JSON = "application/json";
    private static final String YAML = "application/yaml";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    // ─── YAML 은 받지 않는다 (AC-R32-03-1 · 03-2 · 03-3) ──────────────────────────

    @Test
    void rejectsAYamlSpanBodyWith415() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", YAML, JSON, spanBody("a32b000000000011", "1767225600000", ""));
        assertEquals(415, res.status(), "YAML 본문은 415 — 실제 " + res);
        assertEquals(before, spanRows(), "YAML 요청으로 생긴 span 행 0");
        assertNoStack(res);
    }

    @Test
    void rejectsAYamlBodyWithANullNumberWith415AndStoresNothing() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", YAML, JSON, spanBody("a32b000000000012", "null", ""));
        assertEquals(415, res.status(), "숫자 칸 null 인 YAML 본문도 415 — 실제 " + res);
        assertEquals(before, spanRows(), "YAML 요청으로 생긴 span 행 0");
        assertEquals(0L, startTimeZeroRows(), "start_time = 0 행 0");
    }

    @Test
    void rejectsAYamlBodyWithTrailingTextWith415AndStoresNothing() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", YAML, JSON, spanBody("a32b000000000013", "1767225600000", " trailing"));
        assertEquals(415, res.status(), "본문 뒤 잉여 글자가 붙은 YAML 본문도 415 — 실제 " + res);
        assertEquals(before, spanRows(), "YAML 요청으로 생긴 span 행 0");
    }

    @Test
    void rejectsAYamlSetupCompleteBodyWith415() throws IOException {
        long before = completedSetupRows();
        Response res = send("POST", "/v1/setup/complete", YAML, JSON, "{\"serverUrl\":\"\",\"services\":[]}");
        assertEquals(415, res.status(), "설정 완료 입구도 YAML 본문은 415 — 실제 " + res);
        assertEquals(before, completedSetupRows(), "YAML 요청으로 설정 완료 행이 바뀌지 않는다");
        assertNoStack(res);
    }

    @Test
    void answers406WhenOnlyYamlIsAcceptable() throws IOException {
        Response res = send("GET", "/v1/services", null, YAML, null);
        assertEquals(406, res.status(), "YAML 만 받겠다는 조회는 406 — 실제 " + res);
    }

    // ─── 그대로 둘 것 (AC-R32-03-4 · 03-5) ────────────────────────────────────────

    @Test
    void keepsTheYamlApiDocsAt200() throws IOException {
        Response res = send("GET", "/v3/api-docs.yaml", null, "*/*", null);
        assertEquals(200, res.status(), "API 문서 YAML 판은 200 그대로 — 실제 " + res);
        assertTrue(res.contentType().startsWith("application/vnd.oai.openapi"), "응답 형식 — 실제 " + res.contentType());
        assertTrue(res.body().contains("openapi:"), "본문이 비지 않은 YAML 문서다 — 앞 200자 " + head(res.body()));
    }

    @Test
    void keepsTheJsonApiDocsAt200() throws IOException {
        Response res = send("GET", "/v3/api-docs", null, "*/*", null);
        assertEquals(200, res.status(), "API 문서 JSON 판은 200 그대로 — 실제 " + res);
        assertTrue(res.contentType().startsWith(JSON), "응답 형식 — 실제 " + res.contentType());
    }

    @Test
    void keepsTheSwaggerUiAt200() throws IOException {
        Response res = send("GET", "/swagger-ui/index.html", null, "*/*", null);
        assertEquals(200, res.status(), "API 문서 화면은 200 그대로 — 실제 " + res);
    }

    @Test
    void keepsAJsonSpanBodyAt202() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", JSON, JSON, spanBody("a32b000000000019", "1767225600000", ""));
        assertEquals(202, res.status(), "JSON 정상 본문은 202 그대로 — 실제 " + res);
        assertEquals(before + 1, spanRows(), "JSON 정상 본문은 span 행 +1(셈 질의가 살아 있다는 양성 대조)");
    }

    @Test
    void keepsAJsonNullNumberAt400() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", JSON, JSON, spanBody("a32b000000000020", "null", ""));
        assertEquals(400, res.status(), "숫자 칸 null 인 JSON 본문은 400 그대로 — 실제 " + res);
        assertEquals(before, spanRows(), "거절된 요청으로 생긴 span 행 0");
    }

    @Test
    void keepsAJsonTrailingTextAt400() throws IOException {
        long before = spanRows();
        Response res = send("POST", "/v1/spans", JSON, JSON, spanBody("a32b000000000021", "1767225600000", " trailing"));
        assertEquals(400, res.status(), "본문 뒤 잉여 글자가 붙은 JSON 본문은 400 그대로 — 실제 " + res);
        assertEquals(before, spanRows(), "거절된 요청으로 생긴 span 행 0");
    }

    @Test
    void keepsJsonServicesAt200() throws IOException {
        Response res = send("GET", "/v1/services", null, JSON, null);
        assertEquals(200, res.status(), "JSON 조회는 200 그대로 — 실제 " + res);
        assertTrue(res.contentType().startsWith(JSON), "응답 형식 — 실제 " + res.contentType());
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /** 수집 본문 하나 — span id 는 시험마다 다르다. JSON 글은 YAML 로도 맞는 글이다. */
    private static String spanBody(String spanId, String startTime, String trailing) {
        return "{\"spans\":[{\"spanId\":\"" + spanId + "\",\"traceId\":\"" + spanId + spanId + "\","
                + "\"serviceName\":\"r32-yaml\",\"operationName\":\"GET /ping\",\"spanKind\":\"SERVER\","
                + "\"startTime\":" + startTime + ",\"endTime\":1767225600010,\"status\":\"OK\"}]}" + trailing;
    }

    private long spanRows() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM spans", Long.class);
        return n == null ? -1L : n;
    }

    private long startTimeZeroRows() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM spans WHERE start_time = 0", Long.class);
        return n == null ? -1L : n;
    }

    private long completedSetupRows() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM setup_state WHERE completed = 1", Long.class);
        return n == null ? -1L : n;
    }

    /** 응답 본문에 서버 스택이 실리지 않는다. */
    private static void assertNoStack(Response res) {
        assertFalse(res.body().contains("\"trace\""), "응답 본문에 trace 키 0 — 실제 " + head(res.body()));
        assertFalse(res.body().contains("at io.apilens."), "응답 본문에 스택 줄 0 — 실제 " + head(res.body()));
    }

    private static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    record Response(int status, String contentType, String body) {
        @Override
        public String toString() {
            return status + " " + contentType + " " + head(body);
        }
    }

    /**
     * 원문 그대로 한 요청을 써 보낸다 — {@code Content-Type} · {@code Accept} 를 글자 그대로 싣는다(값이 null 이면 그 머리를 안
     * 보낸다). 대기는 소켓 읽기 제한 시간뿐이다.
     */
    private Response send(String method, String path, String contentType, String accept, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(port).append("\r\n");
        head.append("Connection: close\r\n");
        if (accept != null) {
            head.append("Accept: ").append(accept).append("\r\n");
        }
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (bytes.length > 0 || "POST".equals(method)) {
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
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

    /** 상태 줄 · 응답 형식 머리 · 본문을 가른다. 본문이 chunked 면 풀어 쓴다(Connection: close 라 끝은 EOF). */
    private static Response parse(byte[] raw) {
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int headEnd = all.indexOf("\r\n\r\n");
        String head = headEnd < 0 ? all : all.substring(0, headEnd);
        String[] headLines = head.split("\r\n");
        String[] parts = headLines[0].split(" ");
        int status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        String contentType = "";
        boolean chunked = false;
        for (String line : headLines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-type:")) {
                contentType = line.substring("content-type:".length()).trim();
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        byte[] bodyBytes = headEnd < 0 ? new byte[0] : Arrays.copyOfRange(raw, headEnd + 4, raw.length);
        if (chunked) {
            bodyBytes = dechunk(bodyBytes);
        }
        return new Response(status, contentType, new String(bodyBytes, StandardCharsets.UTF_8));
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
}
