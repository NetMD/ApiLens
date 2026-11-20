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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R33] R33/AC-R33-06-2 재작업 1 — 키를 켠 서버에서 토큰 없는 보호 요청은 Accept 꼴과 무관하게 <b>401 이 406 보다
 * 먼저</b>이고 바뀐 것이 0 이다. 토큰을 실으면 같은 못 쓰는 Accept 가 처리 전 406 · 바뀐 것 0 이고, JSON Accept 는 반영된다.
 * 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406 · 인증 필터가 매핑보다 먼저). 설계 본편 §1.2 「키를 켠 기동에서 토큰 없는
 * 보호 경로는 매핑보다 먼저 401(BD-R33-16)」 인용.
 *
 * <p>★키는 시험 전용 글자다(운영 키 아님). 진짜 컨테이너를 거친다 — 인증은 서블릿 필터라 모의 요청으로는 차례를 못 잰다.
 *
 * <p>★한계 그 자리에: 보호 경로 셋(설정 · 정리 · 서비스 지움)만 표본으로 잰다. 판정 차례는 경로와 무관하다(필터 → 매핑 →
 * 처리 전 판정 → 처리기) — 다른 보호 경로는 같은 필터 · 같은 판정 한 자리를 지난다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=" + AcceptGateApiKeyOrderIntegrationTest.API_KEY}
)
class AcceptGateApiKeyOrderIntegrationTest {

    static final String API_KEY = "test-accept-gate-order-key";

    static final String JSON = "application/json";

    static final String BEARER = "Authorization: Bearer " + API_KEY;

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-accept-gate-order-it-", ".db");
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

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    /** 토큰 없는 요청에 실을 Accept 꼴 — YAML 단독(보안 1단계 §9 예시) · 못 쓰는 문자셋 · q=0. */
    static Stream<String> acceptsWithoutToken() {
        return Stream.of("application/yaml", "application/json;charset=ISO-8859-1", "application/json;q=0", "*/*;q=0");
    }

    /**
     * SEC-R33-04 · 보안 NB-R33-12 · QA NB-22 — 토큰 없음 × 보호 경로 셋 × Accept 꼴 넷 = 401 · {@code {"error":"unauthorized"}}
     * · 바뀐 것 0. 406 이 401 을 앞지르면 이 시험이 빨개진다.
     */
    @ParameterizedTest(name = "토큰 없음 · Accept {0}")
    @MethodSource("acceptsWithoutToken")
    void protectedPathsAnswer401BeforeAcceptJudgement(String accept) throws IOException {
        seed();
        String s0 = state();

        List<Response> responses = List.of(
                RawHttp.send(port, "PUT", "/v1/settings", JSON, accept, "{\"retention.days\": 9}"),
                RawHttp.send(port, "POST", "/v1/maintenance/purge", null, accept, null),
                RawHttp.send(port, "DELETE", "/v1/services/r33-order-svc", null, accept, null));
        for (Response res : responses) {
            assertEquals(401, res.status(), "Accept " + accept + " — 토큰 없으면 401 이 먼저 · 실제 " + res);
            assertTrue(res.contentType().startsWith(JSON), "401 응답 형식 JSON · 실제 " + res);
            assertEquals("{\"error\":\"unauthorized\"}", res.body(), "401 본문 · 실제 " + res);
        }
        assertEquals(s0, state(), "Accept " + accept + " — 토큰 없는 요청 뒤 바뀐 것 0");
    }

    /**
     * 토큰을 실으면 같은 못 쓰는 Accept 는 처리 전 406 · 바뀐 것 0(인증 통과 뒤의 판정) · JSON Accept 는 처리된다(양성 대조 —
     * 토큰이 실제로 통과한다는 것과 「바뀐 것 0」이 늘 0 이라서 통과한 게 아님을 함께 잰다).
     */
    @Test
    void tokenPassesThenUnwritableAcceptStill406AndJsonApplies() throws IOException {
        seed();
        String s0 = state();

        Response settings = RawHttp.send(port, "PUT", "/v1/settings", JSON, "application/json;charset=ISO-8859-1",
                "{\"retention.days\": 9}", BEARER);
        Response purge = RawHttp.send(port, "POST", "/v1/maintenance/purge", null, "application/json;q=0", null, BEARER);
        Response delete = RawHttp.send(port, "DELETE", "/v1/services/r33-order-svc", null,
                "application/json;charset=ISO-8859-1", null, BEARER);
        assertEquals(406, settings.status(), "토큰 · 문자셋 ISO 설정 — 처리 전 406 · 실제 " + settings);
        assertEquals(406, purge.status(), "토큰 · q=0 정리 — 처리 전 406 · 실제 " + purge);
        assertEquals(406, delete.status(), "토큰 · 문자셋 ISO 서비스 지움 — 처리 전 406 · 실제 " + delete);
        assertEquals(s0, state(), "토큰 · 못 쓰는 Accept 뒤 바뀐 것 0");

        Response applied = RawHttp.send(port, "PUT", "/v1/settings", JSON, JSON, "{\"retention.days\": 9}", BEARER);
        assertEquals(200, applied.status(), "토큰 · JSON Accept 설정 200 · 실제 " + applied);
        assertTrue(state().startsWith("days=9 "), "토큰 · JSON Accept 뒤 설정 반영 · 실제 " + state());
    }

    /** 설정 값 없음 · trace 1 · 서비스 1 을 다시 심는다. */
    private void seed() {
        jdbc.update("DELETE FROM settings WHERE key = 'retention.days'");
        jdbc.update("INSERT OR REPLACE INTO traces(trace_id, root_operation, service_name, start_time, duration_ms, status, "
                + "span_count, service_count, has_error, received_at) VALUES('r33-order', 'GET /x', 'r33-order-svc', ?, 10, "
                + "'OK', 1, 1, 0, ?)", System.currentTimeMillis(), System.currentTimeMillis());
        jdbc.update("INSERT OR REPLACE INTO services(service_name, registered_at, source) VALUES('r33-order-svc', 0, 'wizard')");
    }

    /** 재는 값 셋을 이은 글자 — 설정 값 · trace 행 수 · 서비스 행 수. */
    private String state() {
        List<String> days = jdbc.queryForList("SELECT value FROM settings WHERE key = 'retention.days'", String.class);
        Long traces = jdbc.queryForObject("SELECT count(*) FROM traces", Long.class);
        Long services = jdbc.queryForObject("SELECT count(*) FROM services WHERE service_name = 'r33-order-svc'", Long.class);
        return "days=" + (days.isEmpty() ? "없음" : days.get(0)) + " traces=" + traces + " services=" + services;
    }
}
