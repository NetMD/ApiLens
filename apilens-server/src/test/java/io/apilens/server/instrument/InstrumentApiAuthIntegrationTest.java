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
package io.apilens.server.instrument;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R19] 신규 {@code /v1/instrument/**} 가 실제로 보호되는지를 <b>전체 컨텍스트</b>로 잰다.
 *
 * <p><b>왜 통합 테스트여야 하는가(사각지대 명문화)</b>: 기존 인증 판정 테스트
 * {@code io.apilens.server.auth.AuthWhitelistTest} 는 {@code MockHttpServletRequest} 기반
 * <b>단위 테스트</b>다 — 서블릿 컨테이너의 경로 정규화를 거치지 않고 인증 필터도 경유하지 않는다.
 * 따라서 "신규 endpoint 가 실제로 401 을 내는가" 는 그 테스트가 덮지 못한다.
 *
 * <p>비협상 AC verbatim 인용 — AC-08-7: "신규 {@code /v1/**} 는 화이트리스트에 추가하지 않는다"
 * (사용자 명시 비협상 결정 S-8). {@code AuthWhitelist} 의 역방향 기본값이 신규 경로를 자동으로
 * 보호하므로 이 라운드의 옳은 구현은 <b>그 파일을 한 글자도 건드리지 않는 것</b>이고,
 * 이 테스트가 그 사실을 실행으로 증명한다.
 *
 * <p>API 키를 <b>설정한 상태</b>로 띄운다 — 미설정이면 필터가 전부 통과시켜 검증이 무의미해진다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=test-instrument-key"}
)
class InstrumentApiAuthIntegrationTest {

    private static final String ANALYSIS_PATH = "/v1/instrument/analysis";
    private static final String VALID_BODY = "{\"serviceName\":\"svc\",\"windowHours\":1}";

    // application.yml 의 상대경로 SQLite 가 작업 디렉토리를 오염시키므로 temp 파일로 override.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-instrument-auth-it-", ".db");
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

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    /** A-1 — 토큰이 없으면 401. */
    @Test
    void returns401OnAnalysisWithoutToken() {
        ResponseEntity<String> res = post(ANALYSIS_PATH, VALID_BODY, null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "a brand new /v1/** endpoint must inherit default-deny without touching AuthWhitelist");
    }

    /** A-2 — 틀린 토큰도 401. */
    @Test
    void returns401OnAnalysisWithWrongToken() {
        ResponseEntity<String> res = post(ANALYSIS_PATH, VALID_BODY, "wrong-token");

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
    }

    /** A-3 — 올바른 토큰 + 올바른 본문이면 200 (401 이 "인증 비활성" 이 아님을 증명). */
    @Test
    void returns200OnAnalysisWithValidToken() {
        ResponseEntity<String> res = post(ANALYSIS_PATH, VALID_BODY, "test-instrument-key");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(bodyOf(res).contains("\"window\""), "the analysis response must carry its window");
    }

    /** A-3' — 시뮬레이션도 같은 계약(절감과 부작용이 한 응답에 함께 온다). */
    @Test
    void returns200OnSimulationWithValidTokenCarryingSavingsAndImpact() {
        long to = System.currentTimeMillis();
        String body = "{\"serviceName\":\"svc\",\"fromMs\":" + (to - 3_600_000L) + ",\"toMs\":" + to
                + ",\"targets\":[]}";

        ResponseEntity<String> res = post("/v1/instrument/simulation", body, "test-instrument-key");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        String payload = bodyOf(res);
        assertTrue(payload.contains("\"savings\"") && payload.contains("\"impact\""),
                "savings and impact must always travel together");
    }

    /**
     * BE↔FE 계약 봉인 — {@code GET /v1/services} 응답에 {@code agentVersion} <b>필드 자체</b>가
     * 언제나 있어야 한다. 값이 없을 때는 {@code null} 로 나가야 하고, 필드가 통째로 빠지면 화면이
     * "값 없음(—)" 과 "확인 안 됨" 을 구분하지 못한다(직렬화 설정이 null 필드를 생략하도록 바뀌면
     * 조용히 깨지는 자리라 실제 응답 문자열로 못 박는다).
     */
    @Test
    void exposesAgentVersionFieldOnEveryServicesEntry() {
        // 서비스 한 건을 만들어 두고(자동 등록 경로가 아니라 직접 INSERT) 실제 응답 문자열을 본다.
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES ('contract-svc', ?, ?, 'auto')", now - 60_000L, now);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-instrument-key");
        ResponseEntity<String> res = rest.exchange("/v1/services", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        String payload = bodyOf(res);
        assertTrue(payload.contains("\"agentVersion\""),
                "every service entry must carry the agentVersion field: " + payload);
        assertTrue(payload.contains("\"agentVersion\":null"),
                "a service that has not reported yet must send an explicit null, not omit the field: " + payload);
    }

    /**
     * A-4 (우회 벡터) — 상위 디렉터리 표기로 보호 밖 경로에 닿으려는 시도.
     *
     * <p>실측 결과 <b>401 + 인증 필터의 본문</b>({@code {"error":"unauthorized"}})이 돌아온다 —
     * 즉 요청이 "핸들러가 없어서" 실패한 것이 아니라 <b>필터가 실제로 막았다</b>. 이 구분이 중요한
     * 이유: 경로가 클라이언트나 컨테이너에서 {@code /setup/state} 로 접혀 버리면 화이트리스트
     * 바깥으로 빠져나가 404 가 나고, 그러면 "막혔다" 가 아니라 "우연히 없었다" 가 된다.
     */
    @Test
    void keepsProtectionOnDotDotTraversalWithoutToken() {
        ResponseEntity<String> res = post("/v1/instrument/../setup/state", "{}", null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "path traversal must be stopped by the auth filter, not merely miss a handler");
        assertTrue(bodyOf(res).contains("unauthorized"),
                "the body must come from the auth filter (proving the filter, not a 404, rejected it)");
    }

    /** A-5 (우회 벡터) — percent-encoding 으로 같은 시도. 실측 결과 역시 필터가 401 로 막는다. */
    @Test
    void keepsProtectionOnEncodedTraversalWithoutToken() {
        ResponseEntity<String> res = post("/v1/instrument/%2e%2e/setup/state", "{}", null);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "percent-encoded traversal must be stopped by the auth filter");
        assertTrue(bodyOf(res).contains("unauthorized"), "the body must come from the auth filter");
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 본문이 없는 응답은 그 자체로 계약 위반이므로 여기서 잡는다(호출부 null 검사 반복 제거). */
    private static String bodyOf(ResponseEntity<String> res) {
        String body = res.getBody();
        assertNotNull(body, "the response must carry a body");
        return body;
    }
}
