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

import io.apilens.server.ingest.RawHttp.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 *
 * <p>[Phase R33] R33/AC-R33-07-6 — 원문 송신 도우미 = {@code RawHttp}(같은 패키지 · 옮김). 아래 R33 절은 처리 전 406
 * (매핑의 {@code produces} · {@code consumes})을 잰다. 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406 · V1-Q3 agent-jar 예외 +
 * 시험 1). 설계 본편 §1.4 · §1.5 · §6.3 · 부록 B B2 인용.
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

    // ─── [Phase R33] 처리 전 406 · 매핑 415 (TS-R33-01 ~ TS-R33-09) ───────────────────────────────
    // [Phase R33] R33/AC-R33-07-1 ~ R33/AC-R33-07-5 · R33/AC-R33-04-2 ~ R33/AC-R33-04-4 · R33/AC-R33-03-1 ~ R33/AC-R33-03-3 ·
    //   R33/AC-R33-05-2 · R33/AC-R33-05-4 — 설계 본편 §1.5 BD 행 · 부록 B B2 표 그대로. 200 · 202 · 400 기대는 HEAD(produces 전)
    //   값을 먼저 읽어 둔 값이다(부록 B2 「HEAD 값 먼저」 · dev 결정 요약에 값 기록).

    /** TS-R33-01 · BD-R33-09 — JSON 접미 형식(vnd.x+json)을 받겠다는 조회는 200 그대로. */
    @Test
    void keepsVendorJsonAcceptAt200() throws IOException {
        Response res = send("GET", "/v1/services", null, "application/vnd.x+json", null);
        assertEquals(200, res.status(), "JSON 접미 형식 조회는 200 — 실제 " + res);
        assertTrue(res.contentType().startsWith("application/vnd.x+json"), "응답 형식 — 실제 " + res.contentType());
        assertTrue(res.body().startsWith("[") || res.body().startsWith("{"), "본문이 JSON 이다 — 실제 " + head(res.body()));
    }

    /** TS-R33-02 · BD-R33-10 — YAML 본문 + YAML Accept 는 415(406 아님 · 매핑 판정 순서 consumes → produces). */
    @Test
    void rejectsYamlBodyWithYamlAcceptWith415() throws IOException {
        String spanId = "a33b000000000201";
        assertEquals(0L, spanRow(spanId), "전제: 그 행이 요청 전에 없다");
        Response res = send("POST", "/v1/spans", YAML, YAML, spanBody(spanId, "1767225600000", ""));
        assertEquals(415, res.status(), "YAML 본문 + YAML Accept 는 415 — 실제 " + res);
        assertEquals("", res.contentType(), "415 응답 형식 머리 없음 — 실제 " + res);
        assertEquals("", res.body(), "415 본문 0 바이트 — 실제 " + res);
        assertEquals(0L, spanRow(spanId), "YAML 요청으로 생긴 그 행 0");
        assertNoStack(res);
        Response ok = send("POST", "/v1/spans", JSON, JSON, spanBody(spanId, "1767225600000", ""));
        assertEquals(202, ok.status(), "양성 대조: 같은 본문 JSON 은 202 — 실제 " + ok);
        assertEquals(1L, spanRow(spanId), "양성 대조: 그 행 1(셈 질의가 살아 있다)");
    }

    /** TS-R33-03 · BD-R33-11 — JSON 본문 + YAML Accept 는 처리 전 406 · 저장 0. */
    @Test
    void answers406ToJsonBodyWithYamlAccept() throws IOException {
        String spanId = "a33b000000000301";
        assertEquals(0L, spanRow(spanId), "전제: 그 행이 요청 전에 없다");
        Response res = send("POST", "/v1/spans", JSON, YAML, spanBody(spanId, "1767225600000", ""));
        assertEquals(406, res.status(), "JSON 본문 + YAML Accept 는 406 — 실제 " + res);
        assertEquals("", res.body(), "406 본문 0 바이트 — 실제 " + res);
        assertEquals(0L, spanRow(spanId), "처리 전 거절 — 그 행 0");
        Response ok = send("POST", "/v1/spans", JSON, JSON, spanBody(spanId, "1767225600000", ""));
        assertEquals(202, ok.status(), "양성 대조: Accept JSON 은 202 — 실제 " + ok);
        assertEquals(1L, spanRow(spanId), "양성 대조: 그 행 1");
    }

    /** TS-R33-04 · BD-R33-13 · 정정 X-5 — 처리 전 406 본문 계약(설계 본편 §1.4) · 경로 · 질의 · 머리 값 되울림 0. */
    @Test
    void notAcceptableBodyContract() throws IOException {
        String markedPath = "/v1/traces/zzR33markP?zzR33markQ=1";
        Response yaml = send2("GET", markedPath, null, YAML, null, "X-R33-Mark: zzR33markH");
        assertEquals(406, yaml.status(), "① YAML Accept 는 406 — 실제 " + yaml);
        assertEquals("", yaml.contentType(), "① 응답 형식 머리 없음 — 실제 " + yaml);
        assertEquals("", yaml.body(), "① 본문 0 바이트 — 실제 " + yaml);
        Response html = send2("GET", markedPath, null, "text/html", null, "X-R33-Mark: zzR33markH");
        assertEquals(406, html.status(), "② text/html Accept 도 406 — 실제 " + html);
        assertTrue(html.contentType().startsWith("text/html"), "② 응답 형식 — 실제 " + html.contentType());
        assertTrue(html.body().contains("Whitelabel Error Page"), "② Boot 기본 쪽지 — 실제 " + head(html.body()));
        assertTrue(html.body().contains("status=406"), "② 쪽지에 상태 — 실제 " + head(html.body()));
        assertFalse(html.body().contains("zzR33mark"), "② 경로 · 질의 · 머리 값 되울림 0 — 실제 " + head(html.body()));
        Response services = send("GET", "/v1/services?zzR33markQ=1", null, YAML, null);
        assertEquals(406, services.status(), "③ 질의가 붙은 YAML 조회도 406 — 실제 " + services);
        assertEquals("", services.body(), "③ 본문 0 바이트 — 실제 " + services);
        Response xml = send("GET", "/v1/services", null, "application/xml", null);
        assertEquals(406, xml.status(), "④ XML Accept 는 406 — 실제 " + xml);
        assertEquals("", xml.contentType(), "④ 응답 형식 머리 없음 — 실제 " + xml);
        assertEquals("", xml.body(), "④ 본문 0 바이트 — 실제 " + xml);
    }

    /** TS-R33-05 · BD-R33-08 · 정정 X-10 — 컨트롤러 밖 API 문서 YAML 판은 R32 값 그대로. */
    @Test
    void keepsYamlApiDocsForOpenApiAccept() throws IOException {
        Response oai = send("GET", "/v3/api-docs.yaml", null, "application/vnd.oai.openapi", null);
        assertEquals(200, oai.status(), "① vnd.oai.openapi Accept 는 200 — 실제 " + oai);
        assertTrue(oai.contentType().startsWith("application/vnd.oai.openapi"), "① 응답 형식 — 실제 " + oai.contentType());
        assertTrue(oai.body().contains("openapi:"), "① 본문이 YAML 문서다 — 앞 200자 " + head(oai.body()));
        Response yaml = send("GET", "/v3/api-docs.yaml", null, YAML, null);
        assertEquals(406, yaml.status(), "② YAML Accept 는 406(R32 값 그대로) — 실제 " + yaml);
        Response any = send("GET", "/v3/api-docs.yaml", null, "*/*", null);
        assertEquals(200, any.status(), "③ */* 는 200 — 실제 " + any);
        assertTrue(any.contentType().startsWith("application/vnd.oai.openapi"), "③ 응답 형식 — 실제 " + any.contentType());
    }

    /** TS-R33-06 · BD-R33-12 — agent 꼴(Accept 머리 없음) 수집은 202 · 행 +1. */
    @Test
    void keepsAgentStyleSpanPostAt202() throws IOException {
        String spanId = "a33b000000000601";
        assertEquals(0L, spanRow(spanId), "전제: 그 행이 요청 전에 없다");
        Response res = send("POST", "/v1/spans", JSON, null, spanBody(spanId, "1767225600000", ""));
        assertEquals(202, res.status(), "Accept 없는 JSON 수집은 202 — 실제 " + res);
        assertEquals(1L, spanRow(spanId), "그 행 1");
    }

    /** TS-R33-07 · BD-R33-14 · 정정 T-2 — agent-jar 는 자기 형식(octet-stream)만 낸다 · JSON 단독 Accept 는 처리 전 406. */
    @Test
    void agentJarHonoursItsOwnMediaType() throws IOException {
        String fixture = classpathAgentJar();
        assertEquals(47, fixture.length(), "전제: 시험 클래스패스 agent jar = 시험 자원 가짜 파일(47 바이트)");
        assertTrue(fixture.startsWith("fake"), "전제: 시험 자원 가짜 파일 머리 fake");
        String[] accepts = {"application/octet-stream", "*/*",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"};
        for (String accept : accepts) {
            Response res = send("GET", "/v1/setup/agent-jar", null, accept, null);
            assertEquals(200, res.status(), "Accept " + accept + " 는 200 — 실제 " + res);
            assertTrue(res.contentType().startsWith("application/octet-stream"), "응답 형식 — 실제 " + res.contentType());
            assertEquals(fixture, res.body(), "본문 = 시험 자원 바이트 그대로 — Accept " + accept);
        }
        Response json = send("GET", "/v1/setup/agent-jar", null, JSON, null);
        assertEquals(406, json.status(), "④ JSON 단독 Accept 는 406 — 실제 " + json);
        assertTrue(json.contentType().startsWith(JSON), "④ 응답 형식 = Boot 기본 오류 JSON — 실제 " + json.contentType());
        assertTrue(json.body().contains("\"status\":406"), "④ 본문에 상태 — 실제 " + head(json.body()));
    }

    /** TS-R33-08 · BD-R33-05 · BD-R33-06 — 지우는 매핑 셋은 YAML Accept 면 지우기 전에 406 · JSON 이면 204. */
    @Test
    void deleteWithYamlAcceptRemovesNothing() throws IOException {
        jdbc.update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES('r33-y-cfg', 0, 'wizard')");
        jdbc.update("INSERT OR REPLACE INTO service_instrument_configs(service_name, capture_params, updated_at) "
                + "VALUES('r33-y-cfg', 1, 0)");
        jdbc.update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES('r33-y-svc', 0, 'wizard')");
        jdbc.update("INSERT INTO masking_rules(name, rule_type, pattern, mask_strategy, enabled, is_default, created_at, "
                + "updated_at) VALUES('r33-y-rule','field_name','x-r33y','full',1,0,0,0)");
        Long ruleId = jdbc.queryForObject("SELECT rule_id FROM masking_rules WHERE name = 'r33-y-rule'", Long.class);
        String[][] cases = {
                {"/v1/services/r33-y-cfg/instrument-config",
                        "SELECT count(*) FROM service_instrument_configs WHERE service_name = 'r33-y-cfg'"},
                {"/v1/services/r33-y-svc", "SELECT count(*) FROM services WHERE service_name = 'r33-y-svc'"},
                {"/v1/masking-rules/" + ruleId, "SELECT count(*) FROM masking_rules WHERE rule_id = " + ruleId},
        };
        for (String[] c : cases) {
            assertEquals(1L, count(c[1]), "전제: 대상 행 1 — " + c[0]);
            Response yaml = send("DELETE", c[0], null, YAML, null);
            assertEquals(406, yaml.status(), "YAML Accept 지우기는 406 — " + c[0] + " 실제 " + yaml);
            assertEquals(1L, count(c[1]), "지우기 전 거절 — 행 1 그대로 · " + c[0]);
            Response json = send("DELETE", c[0], null, JSON, null);
            assertEquals(204, json.status(), "JSON Accept 지우기는 204 — " + c[0] + " 실제 " + json);
            assertEquals(0L, count(c[1]), "양성 대조: 행 0 · " + c[0]);
        }
    }

    /** TS-R33-09 · BD-R33-01 ~ BD-R33-04 — cursor 오류 길: YAML 만이면 406 · JSON 이 섞이면 400 이 JSON 으로 써진다. */
    @Test
    void cursorErrorPathWithYamlAccept() throws IOException {
        Response mixed = send("GET", "/v1/traces?cursor=bad", null, "application/json;q=0.5, application/yaml", null);
        assertEquals(400, mixed.status(), "혼합 Accept 는 400 — 실제 " + mixed);
        assertTrue(mixed.contentType().startsWith(JSON), "혼합 Accept 400 응답 형식 = JSON — 실제 " + mixed.contentType());
        assertTrue(mixed.body().startsWith("{\"error\""), "혼합 Accept 400 본문 = {\"error\" … — 실제 " + head(mixed.body()));
        Response json = send("GET", "/v1/traces?cursor=bad", null, JSON, null);
        assertEquals(400, json.status(), "JSON Accept 는 400 — 실제 " + json);
        assertTrue(json.body().startsWith("{\"error\""), "JSON 400 본문 = {\"error\" … — 실제 " + head(json.body()));
        for (String path : new String[] {"/v1/traces?cursor=bad", "/v1/traces?cursor=!!bad"}) {
            Response yaml = send("GET", path, null, YAML, null);
            assertEquals(406, yaml.status(), "YAML Accept cursor 오류는 406(처리 전) — " + path + " 실제 " + yaml);
            assertEquals("", yaml.body(), "406 본문 0 바이트 — " + path);
        }
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

    private long spanRow(String spanId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM spans WHERE span_id = ?", Long.class, spanId);
        return n == null ? -1L : n;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? -1L : n;
    }

    /** 시험 클래스패스의 agent jar 자원(서버가 내려 주는 것과 같은 자원 이름) 글자. */
    private static String classpathAgentJar() throws IOException {
        try (InputStream in = YamlIngressIntegrationTest.class.getResourceAsStream("/agent/apilens-agent.jar")) {
            assertTrue(in != null, "전제: 시험 클래스패스에 agent jar 자원이 있다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 응답 본문에 서버 스택이 실리지 않는다. */
    private static void assertNoStack(Response res) {
        assertFalse(res.body().contains("\"trace\""), "응답 본문에 trace 키 0 — 실제 " + head(res.body()));
        assertFalse(res.body().contains("at io.apilens."), "응답 본문에 스택 줄 0 — 실제 " + head(res.body()));
    }

    private static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    /** 원문 송신 — 도우미 {@code RawHttp} 로 옮겼다(R33/AC-R33-07-6 · 기존 시험 줄 · 단언 글자 그대로). */
    private Response send(String method, String path, String contentType, String accept, String body) throws IOException {
        return RawHttp.send(port, method, path, contentType, accept, body);
    }

    /** 덧머리 줄을 싣는 송신(TS-R33-04 머리 되울림). */
    private Response send2(String method, String path, String contentType, String accept, String body,
                           String... extraHeaderLines) throws IOException {
        return RawHttp.send(port, method, path, contentType, accept, body, extraHeaderLines);
    }
}
