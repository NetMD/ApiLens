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

import io.apilens.server.ingest.RawHttp.Response;
import io.apilens.server.web.JsonAcceptGate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R33] R33/AC-R33-02-2/R33/AC-R33-06-2/R33/AC-R33-06-3 — 바꾸는 매핑(17)이 처리 전 406 뒤에도 고치기 전과 같게
 * 반영되는지, 그리고 본문 없는 바꾸는 매핑(8)이 받는 형식 선언 없이 그대로 부를 수 있는지 진짜 컨테이너를 거쳐 잰다.
 * 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406). 설계 본편 §1.1 · §1.5 BD-R33-15 · BD-R33-18 · 부록 B B3 인용.
 *
 * <p>AC-R33-02-2 원문: "본문 없는 바꾸는 매핑 8 을 본문 · Content-Type 없이 JSON Accept 로 부르면 고치기 전과 같은 상태
 * 코드다 — 415 0".
 *
 * <p>★틀: 임시 DB · 키 꺼짐(시험 기본 · 토큰 없이 부름) · 원문 송신 도우미 {@code RawHttp}(같은 패키지). 바꾸는 17 갈래 표
 * 시험(TS-R33-10)은 같은 파일에 더한다(설계 본편 §6.3 · 부록 B B3).
 *
 * <p>★한계 그 자리에: 이 파일의 시험은 같은 Spring 문맥(같은 임시 DB · 같은 일시정지 상태)을 나눠 쓴다 — 일시정지를 거는
 * 시험은 {@code finally} 에서 JSON 으로 재개해 다음 시험에 상태를 넘기지 않는다. 행마다 심음이 자기 전제를 만들어(지우고
 * 다시 넣음) 두 시험의 차례와 무관하게 서지만, 다시 돌릴 때 같은 차례가 되게 차례를 고정한다(TS-R33-10 → TS-R33-11 ·
 * 설계 정정 DV-R33-T-10 「행 차례 = 공급 메서드 차례 · 정리 · 지움은 맨 뒤」).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MutatingAcceptGateIntegrationTest {

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-mutating-accept-it-", ".db");
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

    static final String JSON = "application/json";
    static final String YAML = "application/yaml";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    // ─── TS-R33-10 바꾸는 17 갈래 표 (BD-R33-18) ─────────────────────────────────────────

    /**
     * TS-R33-10 · BD-R33-18 — 바꾸는 매핑 17 을 YAML 만 받는 Accept 로 부르면 처리 전 406 이고 바뀐 것 0, 같은 요청을 JSON
     * Accept 로 보내면 고치기 전(HEAD {@code 4831eae})과 같게 반영된다. 행 = 설계 부록 B B3 표(갈래 표 행 11 · 일시정지 2 ·
     * 저장 없는 POST 3 · optimize 1).
     *
     * <p>R33/AC-R33-06-2 [보충] 원문: "바꾸는 매핑 17(단위 = 메서드 매핑)을 YAML Accept 로 부르면 처리 전 406 이고 바뀐 것
     * 0 — 갈래마다 재는 값". R33/AC-R33-06-3 [보충] 원문: "같은 17 을 JSON Accept 로 부르면 고치기 전과 같다(성공 갈래)".
     *
     * <p>행마다 차례(설계 부록 B B3 · 고정): ① 심음(자기 전제를 만든다) ② · ③ 재는 값 {@code s0} 와 전제 단언 ④ YAML 요청 →
     * 406 · 응답 형식 머리 없음 · 본문 0 바이트 ⑤ {@code s1 == s0} ⑥ JSON 요청 → 기대 상태(HEAD 값) ⑦ {@code s2} = 「JSON
     * 뒤」 값(양성 대조 — 「0 == 0」 통과를 막는다) ⑧ 일시정지 갈래는 {@code finally} 에서 JSON 재개.
     *
     * <p>★한계 그 자리에: 저장 없는 POST 3 은 재는 값이 없다(설계 본편 §1.1 「상태만」) — 행 이름 꼬리 {@code (state-only)} 로
     * 남긴다. 그 셋은 「처리 전」을 상태 코드 · 빈 본문으로만 잰다.
     */
    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("mutatingRows")
    void mutatingMappingsAnswer406BeforeHandlingAndJsonStillApplies(Row row) throws IOException {
        Ctx c = new Ctx(jdbc, port);
        try {
            String path = row.seed().run(c);
            String s0 = row.probe().run(c);
            if (row.before() != null) {
                assertTrue(row.before().test(s0), row + " — 전제: " + row.beforeText() + " · 실제 " + s0);
            }

            Response yaml = RawHttp.send(port, row.method(), path, row.yamlBody() == null ? null : JSON, YAML,
                    row.yamlBody());
            assertEquals(406, yaml.status(), row + " — YAML Accept 는 처리 전 406 · 실제 " + yaml);
            assertEquals("", yaml.contentType(), row + " — 406 응답 형식 머리 없음 · 실제 " + yaml);
            assertEquals("", yaml.body(), row + " — 406 본문 0 바이트");
            String s1 = row.probe().run(c);
            assertEquals(s0, s1, row + " — YAML Accept 뒤 바뀐 것 0");

            Response json = RawHttp.send(port, row.method(), path, row.jsonBody() == null ? null : JSON, JSON,
                    row.jsonBody());
            assertEquals(row.jsonStatus(), json.status(), row + " — JSON Accept 는 고치기 전과 같은 상태 · 실제 " + json);
            if (row.jsonStatus() == 204) {
                assertEquals("", json.body(), row + " — 204 본문 없음");
            } else {
                assertTrue(json.contentType().startsWith(JSON), row + " — JSON 응답 형식 · 실제 " + json);
                assertTrue(json.body().startsWith("{"), row + " — JSON 본문 꼴 · 실제 " + json);
            }
            String s2 = row.probe().run(c);
            if (row.after() != null) {
                assertTrue(row.after().test(s2), row + " — JSON 뒤: " + row.afterText() + " · 실제 " + s2);
            }
            if (row.kind() == Kind.OPTIMIZE) {
                assertTrue(json.body().contains("\"busy\":false"), row + " — 정리 성공 갈래(busy 아님) · 실제 " + json);
            }
        } finally {
            if (row.kind() == Kind.PAUSE_STATE) {
                send("POST", "/v1/maintenance/resume", null, JSON, null);
            }
        }
    }

    /**
     * 행 공급 — 차례 = 설계 부록 B B3 갈래 차례이되 정리 · 지움(NG-R33-08 · 09)을 맨 뒤에 둔다(설계 정정 DV-R33-T-10).
     * 심음은 지우고 다시 넣는 꼴이라 한 문맥에서 두 번 불러도 같은 전제를 만든다.
     */
    static Stream<Arguments> mutatingRows() {
        long simFrom = System.currentTimeMillis() - 2 * 3_600_000L;
        List<Row> rows = new ArrayList<>();

        // ⓐ 표 행 11 — 대상 표의 행 · 값 전후
        rows.add(new Row("NG-R33-02 PUT /v1/settings", Kind.TABLE_ROW, "PUT",
                c -> {
                    c.jdbc().update("DELETE FROM settings WHERE key = 'retention.days'");
                    return "/v1/settings";
                },
                "{\"retention.days\": 11}", "{\"retention.days\": 12}",
                c -> text(c, "SELECT value FROM settings WHERE key = 'retention.days'"),
                v -> !v.equals("11") && !v.equals("12"), "값이 11 도 12 도 아님",
                200, "12"::equals, "12"));
        rows.add(new Row("NG-R33-05 PUT /v1/services/{serviceName}/instrument-config", Kind.TABLE_ROW, "PUT",
                c -> {
                    seedConfig(c, "r33-cfg");
                    return "/v1/services/r33-cfg/instrument-config";
                },
                "{\"captureParams\": false}", "{\"captureParams\": false}",
                c -> text(c, "SELECT capture_params FROM service_instrument_configs WHERE service_name = 'r33-cfg'"),
                "1"::equals, "capture_params 1",
                200, "0"::equals, "capture_params 0"));
        rows.add(new Row("NG-R33-07 DELETE /v1/services/{serviceName}/instrument-config", Kind.TABLE_ROW, "DELETE",
                c -> {
                    seedConfig(c, "r33-cfg-del");
                    return "/v1/services/r33-cfg-del/instrument-config";
                },
                null, null,
                c -> text(c, "SELECT count(*) FROM service_instrument_configs WHERE service_name = 'r33-cfg-del'"),
                "1"::equals, "그 이름 행 1",
                204, "0"::equals, "그 이름 행 0"));
        rows.add(new Row("NG-R33-14 POST /v1/spans", Kind.TABLE_ROW, "POST",
                c -> {
                    c.jdbc().update("DELETE FROM spans WHERE span_id IN ('a33b000000001401', 'a33b000000001402')");
                    c.jdbc().update("DELETE FROM traces WHERE trace_id IN ('a33b000000001401a33b000000001401', "
                            + "'a33b000000001402a33b000000001402')");
                    return "/v1/spans";
                },
                spanBody("a33b000000001401"), spanBody("a33b000000001402"),
                c -> text(c, "SELECT count(*) FROM spans WHERE span_id IN ('a33b000000001401', 'a33b000000001402')"),
                "0"::equals, "두 span 행 0",
                202, "1"::equals, "JSON 쪽 span 행 1"));
        rows.add(new Row("NG-R33-17 POST /v1/setup/complete", Kind.TABLE_ROW, "POST",
                c -> {
                    c.jdbc().update("UPDATE setup_state SET server_url = NULL WHERE id = 1");
                    return "/v1/setup/complete";
                },
                "{\"serverUrl\":\"http://127.0.0.1:18001\",\"services\":[]}",
                "{\"serverUrl\":\"http://127.0.0.1:18002\",\"services\":[]}",
                c -> text(c, "SELECT server_url FROM setup_state WHERE id = 1"),
                v -> !v.contains(":18001") && !v.contains(":18002"), "두 주소 아님",
                200, "http://127.0.0.1:18002"::equals, "http://127.0.0.1:18002"));
        rows.add(new Row("NG-R33-26 DELETE /v1/services/{serviceName}", Kind.TABLE_ROW, "DELETE",
                c -> {
                    c.jdbc().update("INSERT OR REPLACE INTO services(service_name, registered_at, source) "
                            + "VALUES('r33-svc-del', 0, 'wizard')");
                    return "/v1/services/r33-svc-del";
                },
                null, null,
                c -> text(c, "SELECT count(*) FROM services WHERE service_name = 'r33-svc-del'"),
                "1"::equals, "그 이름 행 1",
                204, "0"::equals, "그 이름 행 0"));
        rows.add(new Row("NG-R33-29 POST /v1/masking-rules", Kind.TABLE_ROW, "POST",
                c -> {
                    c.jdbc().update("DELETE FROM masking_rules WHERE name = 'r33-rule-new'");
                    return "/v1/masking-rules";
                },
                "{\"name\":\"r33-rule-new\",\"ruleType\":\"field_name\",\"pattern\":\"x-r33\",\"maskStrategy\":\"full\"}",
                "{\"name\":\"r33-rule-new\",\"ruleType\":\"field_name\",\"pattern\":\"x-r33\",\"maskStrategy\":\"full\"}",
                c -> text(c, "SELECT count(*) FROM masking_rules WHERE name = 'r33-rule-new'"),
                "0"::equals, "그 이름 행 0",
                201, "1"::equals, "그 이름 행 1"));
        rows.add(new Row("NG-R33-30 PATCH /v1/masking-rules/{ruleId}", Kind.TABLE_ROW, "PATCH",
                c -> "/v1/masking-rules/" + seedRule(c, "r33-rule-patch"),
                "{\"enabled\": false}", "{\"enabled\": false}",
                c -> text(c, "SELECT enabled FROM masking_rules WHERE name = 'r33-rule-patch'"),
                "1"::equals, "enabled 1",
                200, "0"::equals, "enabled 0"));
        rows.add(new Row("NG-R33-31 DELETE /v1/masking-rules/{ruleId}", Kind.TABLE_ROW, "DELETE",
                c -> "/v1/masking-rules/" + seedRule(c, "r33-rule-del"),
                null, null,
                c -> text(c, "SELECT count(*) FROM masking_rules WHERE name = 'r33-rule-del'"),
                "1"::equals, "그 룰 행 1",
                204, "0"::equals, "그 룰 행 0"));

        // ⓑ 일시정지 2 — 메모리 상태(GET 상태로 읽음)
        rows.add(new Row("NG-R33-12 POST /v1/maintenance/pause", Kind.PAUSE_STATE, "POST",
                c -> {
                    RawHttp.send(c.port(), "POST", "/v1/maintenance/resume", null, JSON, null);
                    return "/v1/maintenance/pause";
                },
                null, null,
                MutatingAcceptGateIntegrationTest::pausedFlag,
                "false"::equals, "paused false",
                200, "true"::equals, "paused true"));
        rows.add(new Row("NG-R33-13 POST /v1/maintenance/resume", Kind.PAUSE_STATE, "POST",
                c -> {
                    RawHttp.send(c.port(), "POST", "/v1/maintenance/pause", null, JSON, null);
                    return "/v1/maintenance/resume";
                },
                null, null,
                MutatingAcceptGateIntegrationTest::pausedFlag,
                "true"::equals, "JSON 으로 먼저 멈춤 — paused true",
                200, "false"::equals, "paused false"));

        // ⓒ 저장 없는 POST 3 — 재는 값 없음(상태만)
        rows.add(new Row("NG-R33-03 POST /v1/instrument/analysis (state-only)", Kind.STATELESS, "POST",
                c -> "/v1/instrument/analysis",
                "{\"serviceName\":\"r33-gate\",\"windowHours\":1}", "{\"serviceName\":\"r33-gate\",\"windowHours\":1}",
                c -> "", null, "", 200, null, ""));
        String simBody = "{\"serviceName\":\"r33-gate\",\"fromMs\":" + simFrom + ",\"toMs\":" + (simFrom + 3_600_000L)
                + ",\"targets\":[]}";
        rows.add(new Row("NG-R33-04 POST /v1/instrument/simulation (state-only)", Kind.STATELESS, "POST",
                c -> "/v1/instrument/simulation",
                simBody, simBody,
                c -> "", null, "", 200, null, ""));
        String previewBody = "{\"sample\":\"{\\\"password\\\":\\\"x\\\"}\",\"contentType\":\"application/json\","
                + "\"ruleStates\":[]}";
        rows.add(new Row("NG-R33-32 POST /v1/masking-rules/preview (state-only)", Kind.STATELESS, "POST",
                c -> "/v1/masking-rules/preview",
                previewBody, previewBody,
                c -> "", null, "", 200, null, ""));

        // ⓓ optimize 1 — 엔진 값(page_count · freelist_count)
        rows.add(new Row("NG-R33-10 POST /v1/maintenance/optimize", Kind.OPTIMIZE, "POST",
                c -> {
                    seedFreePages(c);
                    return "/v1/maintenance/optimize";
                },
                null, null,
                MutatingAcceptGateIntegrationTest::pageCounts,
                v -> freelistOf(v) > 0, "freelist_count 0 보다 큼",
                200, v -> freelistOf(v) == 0, "freelist_count 0"));

        // ⓐ 정리 · 지움 — 맨 뒤(DV-R33-T-10)
        rows.add(new Row("NG-R33-08 POST /v1/maintenance/cleanup", Kind.TABLE_ROW, "POST",
                c -> {
                    seedExpiredTrace(c);
                    return "/v1/maintenance/cleanup";
                },
                null, null,
                MutatingAcceptGateIntegrationTest::expiredTraceRows,
                "trace=1 span=1 payload=1 last=1"::equals, "만료 trace · span · payload 각 1 · 마지막 정리 시각 = 옛 값 1",
                200, v -> v.startsWith("trace=0 span=0 payload=0 last=") && !v.endsWith(" last=1"),
                "세 행 0 · 마지막 정리 시각이 바뀜"));
        rows.add(new Row("NG-R33-09 POST /v1/maintenance/purge", Kind.TABLE_ROW, "POST",
                c -> {
                    c.jdbc().update("INSERT OR REPLACE INTO traces(trace_id, root_operation, service_name, start_time, "
                            + "duration_ms, status, span_count, service_count, has_error, received_at) "
                            + "VALUES('r33-purge', 'GET /x', 'r33-gate', ?, 10, 'OK', 1, 1, 0, ?)",
                            System.currentTimeMillis(), System.currentTimeMillis());
                    return "/v1/maintenance/purge";
                },
                null, null,
                c -> text(c, "SELECT count(*) FROM traces"),
                v -> Long.parseLong(v) >= 1, "traces 1 이상",
                200, "0"::equals, "traces 0"));

        return rows.stream().map(Arguments::of);
    }

    // ─── TS-R33-11 본문 없는 바꾸는 8 (BD-R33-15) ─────────────────────────────────────────

    /**
     * TS-R33-11 · BD-R33-15 — 본문 없는 바꾸는 매핑 8 을 JSON Accept · Content-Type 없음 · 본문 없음으로 부르면 고치기 전
     * (HEAD {@code 4831eae}) 상태와 같다 · 415 0. 차례 = 설계 부록 B B3(재개를 맨 뒤).
     */
    @Order(2)
    @Test
    void bodylessMutationsStillAcceptNoContentType() throws IOException {
        jdbc.update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES('r33-cfg-nb', 0, 'wizard')");
        jdbc.update("INSERT OR REPLACE INTO service_instrument_configs(service_name, capture_params, updated_at) "
                + "VALUES('r33-cfg-nb', 1, 0)");
        jdbc.update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES('r33-svc-nb', 0, 'wizard')");
        jdbc.update("INSERT INTO masking_rules(name, rule_type, pattern, mask_strategy, enabled, is_default, created_at, "
                + "updated_at) VALUES('r33-rule-nb','field_name','x-r33nb','full',1,0,0,0)");
        Long ruleId = jdbc.queryForObject("SELECT rule_id FROM masking_rules WHERE name = 'r33-rule-nb'", Long.class);
        Object[][] calls = {
                {"DELETE", "/v1/services/r33-cfg-nb/instrument-config", 204},
                {"DELETE", "/v1/services/r33-svc-nb", 204},
                {"DELETE", "/v1/masking-rules/" + ruleId, 204},
                {"POST", "/v1/maintenance/cleanup", 200},
                {"POST", "/v1/maintenance/purge", 200},
                {"POST", "/v1/maintenance/optimize", 200},
                {"POST", "/v1/maintenance/pause", 200},
                {"POST", "/v1/maintenance/resume", 200},
        };
        try {
            for (Object[] c : calls) {
                Response res = send((String) c[0], (String) c[1], null, JSON, null);
                assertNotEquals(415, res.status(), "본문 없는 바꾸는 매핑에 415 0 — " + c[0] + " " + c[1] + " 실제 " + res);
                assertEquals((int) c[2], res.status(), "고치기 전과 같은 상태 — " + c[0] + " " + c[1] + " 실제 " + res);
            }
        } finally {
            send("POST", "/v1/maintenance/resume", null, JSON, null);
        }
    }

    // ─── 재작업 1 — Accept 의 q 값 · 문자셋 갈래도 처리 전 406 ───────────────────────────────

    /**
     * JSON 을 쓸 수 없는 Accept 꼴 — 바꾸는 17 행마다 전부 보낸다. 출처 = QA 같은 뿌리 이웃 표(NB-01 ~ NB-18)와 보안 이웃 갈래
     * 표(NB-R33-01 ~ 11)의 Accept 열 전수 + 섞인 문자셋 한 꼴(높은 q 의 못 쓰는 문자셋이 쓰기 형식으로 골라지는 갈래).
     */
    static final List<String> UNWRITABLE_ACCEPTS = List.of(
            "application/json;charset=ISO-8859-1",
            "application/json;charset=UTF-16",
            "application/json;q=0",
            "application/json;q=0, application/yaml",
            "application/json;q=0, application/yaml;q=1",
            "application/yaml, application/json;q=0",
            "application/*+json;q=0",
            "*/*;q=0",
            "application/*;q=0",
            "text/html, application/json;q=0",
            "application/vnd.x+json;q=0",
            "application/json;charset=ISO-8859-1, application/json;q=0.5");

    /** 「Accept 머리 없음」 자리표시 — 이 글자면 Accept 머리를 안 싣는다(agent 꼴). */
    static final String NO_ACCEPT = "(Accept 없음)";

    static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    /** JSON 을 쓸 수 있는 Accept 꼴(대조) — 고치기 전과 같게 처리돼야 한다. */
    static final List<String> WRITABLE_ACCEPTS = List.of(
            NO_ACCEPT, "*/*", BROWSER_ACCEPT, "application/json;charset=utf-8", "application/*+json");

    static Stream<Arguments> unwritableAcceptRows() {
        return mutatingRows().flatMap(a -> UNWRITABLE_ACCEPTS.stream().map(acc -> Arguments.of(a.get()[0], acc)));
    }

    static Stream<Arguments> writableAcceptRows() {
        return mutatingRows().flatMap(a -> WRITABLE_ACCEPTS.stream().map(acc -> Arguments.of(a.get()[0], acc)));
    }

    /**
     * [Phase R33] R33/AC-R33-06-2 재작업 1 — 바꾸는 매핑 17 에 q=0 · 못 쓰는 문자셋 Accept 를 보내면 처리 전 406 이고 재는 값이
     * 그대로다. 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406). 공개 문서 {@code docs/api.md} 406 줄 인용: 「Requests whose
     * Accept excludes JSON end with 406 before processing; nothing is changed.」
     *
     * <p>고치기 전(HEAD {@code 85cd1d4}) 값: q=0 갈래는 처리돼 2xx · 문자셋 갈래는 처리된 뒤 500(쓰기 실패) — 둘 다 바뀜이 남았다.
     * 본문 갈래 행은 JSON 본문 · JSON Content-Type 을 그대로 싣는다(415 가 먼저 나서 406 을 가리지 않게).
     *
     * <p>★한계 그 자리에: 406 본문은 Accept 마다 Boot 오류 처리기가 고른다(빈 본문 · 오류 JSON) — 여기서는 상태와 바뀐 것 0 만
     * 단언한다. 본문 계약(설계 §1.4 의 yaml · text/html 두 줄)은 기존 시험이 잰다.
     */
    @Order(3)
    @ParameterizedTest(name = "{0} · Accept {1}")
    @MethodSource("unwritableAcceptRows")
    void mutatingMappingsAnswer406BeforeHandlingWhenAcceptCannotTakeJson(Row row, String accept) throws IOException {
        Ctx c = new Ctx(jdbc, port);
        try {
            String path = row.seed().run(c);
            String s0 = row.probe().run(c);
            if (row.before() != null) {
                assertTrue(row.before().test(s0), row + " — 전제: " + row.beforeText() + " · 실제 " + s0);
            }
            Response res = RawHttp.send(port, row.method(), path, row.jsonBody() == null ? null : JSON, accept,
                    row.jsonBody());
            assertEquals(406, res.status(), row + " · Accept " + accept + " — 처리 전 406 · 실제 " + res);
            String s1 = row.probe().run(c);
            assertEquals(s0, s1, row + " · Accept " + accept + " — 바뀐 것 0");
        } finally {
            if (row.kind() == Kind.PAUSE_STATE) {
                send("POST", "/v1/maintenance/resume", null, JSON, null);
            }
        }
    }

    /**
     * [Phase R33] R33/AC-R33-06-3 재작업 1 대조 — JSON 을 쓸 수 있는 Accept(머리 없음 = agent · {@code *}{@code /*} · 브라우저 꼴
     * · {@code charset=utf-8} · {@code application/*+json})는 고치기 전과 같은 상태로 처리되고 「JSON 뒤」 값이 된다.
     */
    @Order(4)
    @ParameterizedTest(name = "{0} · Accept {1}")
    @MethodSource("writableAcceptRows")
    void mutatingMappingsStillApplyWhenAcceptCanTakeJson(Row row, String accept) throws IOException {
        Ctx c = new Ctx(jdbc, port);
        try {
            String path = row.seed().run(c);
            String s0 = row.probe().run(c);
            if (row.before() != null) {
                assertTrue(row.before().test(s0), row + " — 전제: " + row.beforeText() + " · 실제 " + s0);
            }
            Response res = RawHttp.send(port, row.method(), path, row.jsonBody() == null ? null : JSON,
                    NO_ACCEPT.equals(accept) ? null : accept, row.jsonBody());
            assertEquals(row.jsonStatus(), res.status(), row + " · Accept " + accept + " — 고치기 전과 같은 상태 · 실제 " + res);
            String s2 = row.probe().run(c);
            if (row.after() != null) {
                assertTrue(row.after().test(s2), row + " · Accept " + accept + " — 처리 뒤: " + row.afterText()
                        + " · 실제 " + s2);
            }
        } finally {
            if (row.kind() == Kind.PAUSE_STATE) {
                send("POST", "/v1/maintenance/resume", null, JSON, null);
            }
        }
    }

    /**
     * [Phase R33] R33/AC-R33-05-1 재작업 1 — 조회 매핑과 agent-jar 도 같은 한 자리에서 판정한다. 조회 = 못 쓰는 문자셋 · q=0 은
     * 406(고치기 전: 문자셋 500 · q=0 200) · {@code charset=UTF-8} · {@code application/vnd.x+json} 은 200 그대로 · 이름 없는
     * 문자셋은 고치기 전에도 처리 전 406. agent-jar = 그 매핑의 형식(octet-stream) 기준 — q=0 이면 406 · 문자셋은 안 본다.
     *
     * <p>★{@code charset=US-ASCII}: 범위 잠금 문면이 통과 문자셋을 UTF 다섯으로 정했다(「만」) — 그 밖이라 406. 쓰기 쪽 변환기는
     * 이 이름도 받는다(고치기 전 200) — 결정 요약 자진 신고 표에 한 줄로 올렸다.
     */
    @Order(5)
    @Test
    void readAndAgentJarMappingsJudgeAcceptBeforeHandling() throws IOException {
        String[][] calls = {
                {"/v1/services", "application/json;charset=ISO-8859-1", "406"},
                {"/v1/services", "application/json;charset=UTF-16", "406"},
                {"/v1/services", "application/json;q=0", "406"},
                {"/v1/services", "*/*;q=0", "406"},
                {"/v1/services", "application/json;charset=US-ASCII", "406"},
                {"/v1/services", "application/json;charset=bogus-xx", "406"},
                {"/v1/services", "application/json;charset=UTF-8", "200"},
                {"/v1/services", "application/json;charset=UTF-16BE", "200"},
                {"/v1/services", "application/vnd.x+json", "200"},
                {"/v1/services", NO_ACCEPT, "200"},
                {"/v1/setup/agent-jar", "application/octet-stream;q=0", "406"},
                {"/v1/setup/agent-jar", "*/*;q=0", "406"},
                {"/v1/setup/agent-jar", "application/octet-stream", "200"},
                {"/v1/setup/agent-jar", "application/octet-stream;charset=ISO-8859-1", "200"},
                {"/v1/setup/agent-jar", BROWSER_ACCEPT, "200"},
        };
        List<String> wrong = new ArrayList<>();
        for (String[] call : calls) {
            Response res = send("GET", call[0], null, NO_ACCEPT.equals(call[1]) ? null : call[1], null);
            if (res.status() != Integer.parseInt(call[2])) {
                wrong.add("GET " + call[0] + " · Accept " + call[1] + " 기대 " + call[2] + " → 실제 " + res);
            }
        }
        assertTrue(wrong.isEmpty(), "조회 · agent-jar 판정 어긋남 " + wrong.size() + " — " + wrong);
    }

    /**
     * [Phase R33] R33/AC-R33-06-2 재작업 1 — 처리 전 판정 함수의 경계(서버를 안 거침). q 값 0 ↔ 0.001 · 통과 문자셋 다섯 ↔ 그
     * 밖(UTF-16 · UTF-32 · US-ASCII · ISO-8859-1) · 걸러서 빈 목록 · 머리 없음 · 섞인 목록에서 쓰기 단계가 고를 형식 · agent-jar
     * 형식(octet-stream)은 문자셋을 안 본다.
     */
    @Order(6)
    @ParameterizedTest(name = "{0} · {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "''                                                          | json  | true",
            "application/json                                            | json  | true",
            "application/json;q=0                                        | json  | false",
            "application/json;q=0.000                                    | json  | false",
            "application/json;q=0.001                                    | json  | true",
            "*/*;q=0, application/json;q=0                               | json  | false",
            "application/json;q=0, */*;q=0.001                           | json  | true",
            "application/json;charset=UTF-8                              | json  | true",
            "application/json;charset=utf-8                              | json  | true",
            "application/json;charset=UTF-16BE                           | json  | true",
            "application/json;charset=UTF-16LE                           | json  | true",
            "application/json;charset=UTF-32BE                           | json  | true",
            "application/json;charset=UTF-32LE                           | json  | true",
            "application/json;charset=UTF-16                             | json  | false",
            "application/json;charset=UTF-32                             | json  | false",
            "application/json;charset=US-ASCII                           | json  | false",
            "application/json;charset=ISO-8859-1                         | json  | false",
            "*/*;charset=ISO-8859-1                                      | json  | false",
            "application/json;charset=ISO-8859-1, application/json;q=0.5 | json  | false",
            "application/json;charset=ISO-8859-1;q=0.4, application/json | json  | true",
            "application/vnd.x+json                                      | json  | true",
            "application/vnd.x+json;q=0                                  | json  | false",
            "application/octet-stream                                    | octet | true",
            "application/octet-stream;q=0                                | octet | false",
            "application/octet-stream;charset=ISO-8859-1                 | octet | true",
            "*/*                                                         | octet | true",
            "application/json                                            | octet | false",
    })
    void judgesAcceptAtTheBoundaries(String accept, String kind, boolean expected) {
        List<MediaType> producible = "json".equals(kind)
                ? List.of(MediaType.APPLICATION_JSON, MediaType.parseMediaType("application/*+json"))
                : List.of(MediaType.APPLICATION_OCTET_STREAM);
        List<String> values = accept.isEmpty() ? List.of() : List.of(accept);
        assertEquals(expected, JsonAcceptGate.acceptsWritable(values, producible), "Accept " + accept + " · " + kind);
    }

    // ─── TS-R33-10 행 꼴 · 심음 · 재는 값 ───────────────────────────────────────────────────

    /** 바꿈 갈래 — 설계 본편 §1.1 표의 「바꿈 갈래」 칸(ⓐ 표 행 · ⓑ 일시정지 · ⓒ 저장 없는 POST · ⓓ optimize). */
    enum Kind { TABLE_ROW, PAUSE_STATE, STATELESS, OPTIMIZE }

    /** 행이 쓰는 시험 문맥 — 같은 임시 DB 의 JDBC 와 수집기 포트. */
    record Ctx(JdbcTemplate jdbc, int port) {
    }

    /** 심음(부를 경로를 돌려준다) · 재는 값(글자로 돌려준다) 한 걸음. */
    @FunctionalInterface
    interface Step {
        String run(Ctx c) throws IOException;
    }

    /**
     * 갈래 표 한 행 — 요청 꼴 · 심음 · 재는 값 · 전제 · JSON 기대 상태 · JSON 뒤 값. 본문 없는 매핑은 두 본문이 null(요청에
     * 응답 형식 머리 · 본문을 싣지 않는다).
     */
    record Row(String name, Kind kind, String method, Step seed, String yamlBody, String jsonBody,
               Step probe, Predicate<String> before, String beforeText,
               int jsonStatus, Predicate<String> after, String afterText) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** 첫 행 첫 칸을 글자로 — 행이 없거나 값이 NULL 이면 「없음」. */
    static String text(Ctx c, String sql) {
        List<Map<String, Object>> rows = c.jdbc().queryForList(sql);
        if (rows.isEmpty()) {
            return "없음";
        }
        Object v = rows.get(0).values().iterator().next();
        return v == null ? "없음" : String.valueOf(v);
    }

    /** 서비스 행 + 계측 설정 행(capture_params 1)을 다시 심는다. */
    static void seedConfig(Ctx c, String serviceName) {
        c.jdbc().update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES(?, 0, 'wizard')",
                serviceName);
        c.jdbc().update("INSERT OR REPLACE INTO service_instrument_configs(service_name, capture_params, updated_at) "
                + "VALUES(?, 1, 0)", serviceName);
    }

    /** 사용자 룰 한 행(enabled 1)을 그 이름으로 다시 심고 id 를 돌려준다. */
    static long seedRule(Ctx c, String name) {
        c.jdbc().update("DELETE FROM masking_rules WHERE name = ?", name);
        c.jdbc().update("INSERT INTO masking_rules(name, rule_type, pattern, mask_strategy, enabled, is_default, "
                + "created_at, updated_at) VALUES(?, 'field_name', 'x-r33p', 'full', 1, 0, 0, 0)", name);
        Long id = c.jdbc().queryForObject("SELECT rule_id FROM masking_rules WHERE name = ?", Long.class, name);
        return id == null ? -1L : id;
    }

    /** 수집 본문 하나 — span id 16 자 · trace id 는 그 두 번. */
    static String spanBody(String spanId) {
        return "{\"spans\":[{\"spanId\":\"" + spanId + "\",\"traceId\":\"" + spanId + spanId + "\","
                + "\"serviceName\":\"r33-gate\",\"operationName\":\"GET /ping\",\"spanKind\":\"SERVER\","
                + "\"startTime\":1767225600000,\"endTime\":1767225600010,\"status\":\"OK\"}]}";
    }

    /** 일시정지 상태 — JSON 으로 상태 조회를 불러 응답의 paused 값을 읽는다. */
    static String pausedFlag(Ctx c) throws IOException {
        Response res = RawHttp.send(c.port(), "GET", "/v1/maintenance/status", null, JSON, null);
        if (res.body().contains("\"paused\":true")) {
            return "true";
        }
        if (res.body().contains("\"paused\":false")) {
            return "false";
        }
        return "모름 " + res;
    }

    /** 엔진 값 — 페이지 수와 빈 페이지 수를 이은 글자. */
    static String pageCounts(Ctx c) {
        Long pages = c.jdbc().queryForObject("PRAGMA page_count", Long.class);
        Long free = c.jdbc().queryForObject("PRAGMA freelist_count", Long.class);
        return "page_count=" + pages + " freelist_count=" + free;
    }

    static long freelistOf(String counts) {
        return Long.parseLong(counts.substring(counts.indexOf("freelist_count=") + "freelist_count=".length()));
    }

    /** 본문 표에 2,000 행(1,024 바이트)을 넣었다 지워 빈 페이지를 만든다. */
    static void seedFreePages(Ctx c) {
        c.jdbc().update("DELETE FROM payload_bodies WHERE body_hash LIKE 'r33opt%'");
        String body = "x".repeat(1024);
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            args.add(new Object[] {"r33opt" + i, body, 1024});
        }
        c.jdbc().batchUpdate("INSERT INTO payload_bodies(body_hash, body, body_bytes, first_seen_at) VALUES(?, ?, ?, 0)",
                args);
        c.jdbc().update("DELETE FROM payload_bodies WHERE body_hash LIKE 'r33opt%'");
    }

    /**
     * 만료 trace 한 묶음(trace · span · payload 각 1 · 수신 시각 1,000)과 마지막 정리 시각 = 옛 값 1 을 다시 심는다 — 「전후
     * 다름」이 밀리초 경계에 안 걸리게 옛 값을 박는다(설계 정정 DV-R33-T-10).
     */
    static void seedExpiredTrace(Ctx c) {
        JdbcTemplate j = c.jdbc();
        j.update("INSERT INTO retention_meta(id, last_cleanup_at) VALUES(1, 1) "
                + "ON CONFLICT(id) DO UPDATE SET last_cleanup_at = excluded.last_cleanup_at");
        j.update("DELETE FROM payloads WHERE span_id = 'r33-exp-span'");
        j.update("DELETE FROM spans WHERE span_id = 'r33-exp-span'");
        j.update("DELETE FROM traces WHERE trace_id = 'r33-exp'");
        j.update("INSERT INTO traces(trace_id, root_operation, service_name, start_time, duration_ms, status, span_count, "
                + "service_count, has_error, received_at) VALUES('r33-exp', 'GET /x', 'r33-gate', 1000, 10, 'OK', 1, 1, 0, 1000)");
        j.update("INSERT INTO spans(span_id, trace_id, parent_span_id, service_name, operation_name, span_kind, start_time, "
                + "end_time, status, attributes_json) VALUES('r33-exp-span', 'r33-exp', NULL, 'r33-gate', 'GET /x', 'SERVER', "
                + "1000, 1010, 'OK', NULL)");
        j.update("INSERT INTO payloads(span_id, direction, content_type, body, size_bytes, truncated) "
                + "VALUES('r33-exp-span', 'out', 'application/json', '{\"k\":1}', 7, 0)");
    }

    /** 만료 묶음의 세 표 행 수와 마지막 정리 시각을 이은 글자. */
    static String expiredTraceRows(Ctx c) {
        return "trace=" + text(c, "SELECT count(*) FROM traces WHERE trace_id = 'r33-exp'")
                + " span=" + text(c, "SELECT count(*) FROM spans WHERE span_id = 'r33-exp-span'")
                + " payload=" + text(c, "SELECT count(*) FROM payloads WHERE span_id = 'r33-exp-span'")
                + " last=" + text(c, "SELECT last_cleanup_at FROM retention_meta WHERE id = 1");
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private Response send(String method, String path, String contentType, String accept, String body) throws IOException {
        return RawHttp.send(port, method, path, contentType, accept, body);
    }
}
