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
package io.apilens.server.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.server.setup.AgentJarExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-context integration test for the OpenAPI/Swagger docs surface.
 *
 * <p>This is the first {@code @SpringBootTest} in the server module (the sole blind spot
 * called out by the design): every other controller test uses standaloneSetup and never
 * exercises the real filter chain, servlet normalization, or springdoc handler registration.
 * Only a full context proves that a running server exempts swagger and that springdoc actually
 * registers its handlers.
 *
 * <p>// [Phase R16] 비협상 AC(D1) verbatim: "swagger 인증 면제 유지(무인증 접근)".
 * // 사용자 명시 비협상 결정. CLAUDE.md '절대 변경하지 말아야 할 결정 사항'(신뢰망 전제) 인용.
 * // API Key 를 설정한 상태(apilens.auth.api-key=test-swagger-key)로 띄워야 "면제" 가 의미를 가진다 —
 * // 무인증 폴백이면 전부 통과라 면제 검증이 무의미. T-INT-3(보호 경로 401) 이 T-INT-1/2 의 200 을
 * // "인증 비활성" 이 아닌 "의도된 면제" 로 못 박는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=test-swagger-key"}
)
// [Phase R30] R30/AC-R30-20 — Boot 4 의 @SpringBootTest 는 TestRestTemplate 빈을 더는 주지 않는다 → 명시로 켠다.
@AutoConfigureTestRestTemplate
class OpenApiDocsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로,
    // temp 파일 SQLite 로 override (AgentToServerIntegrationTest 의 temp-file + Flyway 패턴 미러).
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-openapi-it-", ".db");
            Files.deleteIfExists(TEMP_DB); // sqlite/Flyway 가 새 파일로 생성하도록 비워둔다
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
    private AgentJarExtractor agentJarExtractor;

    /**
     * T-INT-1 — 면제 + 스펙 자동생성 + 컨텍스트 로드 + build-info 배선(info.version 은 Gradle version 을 따른다).
     */
    @Test
    void returns200AndOpenApiSpecOnDocsWithoutToken() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);

        assertEquals(HttpStatus.OK, res.getStatusCode(), "/v3/api-docs must be exempt (200) even with API key set");
        JsonNode root = MAPPER.readTree(res.getBody());
        assertTrue(root.hasNonNull("openapi"), "spec must contain an 'openapi' field");
        JsonNode info = root.get("info");
        assertNotNull(info, "spec must contain an 'info' block");
        // 게이트 E — info.version 은 build-info 주입값. [Phase R20] bump(0.5.0→0.6.0)가 문서까지 전파됨을 실측 봉인.
        // [Phase R23] R23/AC-17-4 — bump(0.6.1→0.6.2) 동반 정정. 이 단언은 build.gradle.kts 의
        //   version 값을 리터럴로 붙잡으므로, 제품 버전을 올릴 때 **반드시 함께** 올려야 한다(안 올리면 RED).
        //   ★그렇게 깨지는 것은 **회귀가 아니라 게이트가 살아 있다는 신호**다 — 버전을 올렸는데 이 줄이
        //   그대로면 문서에 실리는 버전이 조용히 낡는다.
        // [Phase R24] R24/AC-07-6 — bump(0.6.2 → 0.6.3) 동반 정정. 이 줄이 빨개진 것이 이번에도
        //   게이트가 살아 있다는 증거다(실측: expected 0.6.2 / actual 0.6.3 로 한 건 빨개진 뒤 정정).
        // [Phase R25] AC-25-08-2 — bump(0.6.3 → 0.7.0) 동반 정정. 이번에도 같은 모양으로 한 건 빨개진 뒤
        //   고쳤다(실측: expected 0.6.3 / actual 0.7.0). ★위 머리 주석의 버전 리터럴은 지웠다 —
        //   같은 값이 두 자리에 있으면 한 자리만 고치는 편집이 나머지를 조용히 낡게 만든다.
        // [Phase R26] AC-R26-48 — bump(0.7.0 → 0.7.1) 동반 정정. 이번에는 **빨개지기 전에** 같은 접촉에서
        //   함께 올렸다(build.gradle.kts 의 version 줄에도 그 사실을 적어 두었다).
        // [Phase R27] R27/AC-27-05-1 — bump(0.7.1 → 0.8.0) 동반 정정. 이번에도 빨개지기 전에 같은 접촉에서 올렸다.
        // [Phase R28] AC-28-06-1 — bump(0.8.0 → 0.9.0) 동반 정정. 이번에도 빨개지기 전에 같은 접촉에서 올렸다.
        // [Phase R29] R29/AC-R29-40 — bump(0.9.0 → 0.9.1) 동반 정정. 이번에도 빨개지기 전에 같은 접촉에서 올렸다.
        assertEquals("0.9.1", info.get("version").asText(), "info.version must track the Gradle build version");
        assertEquals("ApiLens API", info.get("title").asText());
    }

    /**
     * [Phase R29] R29/AC-R29-36 verbatim: "서버 시험이 agent jar 대상 폴더로 임시 폴더를 쓴다. 그 속성은 그 값을 읽는 첫
     * 소비자({@code AgentJarExtractor} 생성)보다 먼저 설정된다". MR-R28-02 — 시험 자원 {@code application.properties} 의
     * 대상 폴더 속성이 이 컨텍스트의 추출기에 실제로 닿았는지 본다(시험이 47 B 시험용 jar 로 사용자 홈의 jar 를 덮지 않게).
     */
    @Test
    void extractsTheAgentJarOutsideTheUserHomeInTests() {
        String extracted = agentJarExtractor.getExtractedPath();
        assertNotNull(extracted, "전제: 시험 컨텍스트에서도 추출이 실제로 일어났다(시험 자원에 jar 가 있다)");

        String userHome = System.getProperty("user.home");
        assertFalse(extracted.startsWith(userHome),
                "시험이 푼 agent jar 는 사용자 홈 밖이다 — 실제: " + extracted);
        assertNotEquals(Path.of(userHome, ".apilens", "apilens-agent.jar").toAbsolutePath().toString(), extracted,
                "운영 자리(~/.apilens/apilens-agent.jar)를 덮지 않는다");
    }

    /**
     * T-INT-2 — UI 임베드 + 면제 + SPA forward 미간섭(springdoc canonical 진입 = /swagger-ui/index.html).
     */
    @Test
    void returns200OnSwaggerUiWithoutToken() {
        ResponseEntity<String> res = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "swagger-ui index must be exempt (200)");
    }

    /**
     * T-INT-3 (핵심) — 필터 실활성 sanity. 이 경로가 401 이어야 T-INT-1/2 의 200 이
     * "인증 비활성" 이 아닌 "의도된 면제" 임이 증명된다.
     */
    @Test
    void returns401OnProtectedPathWithoutToken() {
        ResponseEntity<String> res = rest.getForEntity("/v1/traces", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "/v1/traces without token must be 401 — proves the filter is active");
    }

    /**
     * [Phase R28] AC-28-01-12 — 새 대표 점 경로는 {@code /v1/setup/} 로 시작하지 않아 기본 차단이다(인증 면제 목록
     * 무접촉). 키를 설정한 상태에서 토큰 없이 부르면 <b>401</b> — 대표 점 본문이 나오지 않는다. 단위 시험(standalone
     * MockMvc)은 필터를 안 거치므로 이 판정은 통합 케이스로만 잰다.
     */
    @Test
    void returns401OnTracePointsWithoutToken() {
        ResponseEntity<String> res =
                rest.getForEntity("/v1/trace-points?service=x&since=0&until=1", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "/v1/trace-points without token must be 401 — default-deny 자동 보호(화이트리스트 무수정)");
        assertTrue(res.getBody() == null || !res.getBody().contains("bucketMs"),
                "401 본문에 대표 점 응답이 실리지 않는다 — 실제: " + res.getBody());
    }

    /**
     * [Phase R28] AC-28-01-12 — 새 대표 점 경로가 요약 글과 함께 명세에 실린다(springdoc 자동 도출).
     */
    @Test
    void documentsTracePointsOperationWithSummary() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        JsonNode paths = MAPPER.readTree(res.getBody()).path("paths");

        assertOperationSummary(paths, "/v1/trace-points", "get");
    }

    /**
     * [Phase R20] R20/AC-03-3 verbatim (비협상): "신규 경로는 /v1/** 아래에 두고 AuthWhitelist diff 0
     * (불변식 6) 상태에서 default-deny 자동 보호. 키 설정 환경에서 무인증 호출이 거부됨을 테스트로
     * 확인." — 단위 테스트(standalone MockMvc)는 필터 미경유라 통합 케이스로만 검증 가능(B-18).
     */
    @Test
    void returns401OnInstrumentConfigWithoutToken() {
        ResponseEntity<String> getRes =
                rest.getForEntity("/v1/services/x/instrument-config", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, getRes.getStatusCode(),
                "GET instrument-config without token must be 401 — default-deny 자동 보호(화이트리스트 무수정)");

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> putRes = rest.exchange(
                "/v1/services/x/instrument-config", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>("{\"captureParams\": false}", headers),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, putRes.getStatusCode(),
                "PUT instrument-config without token must be 401");
    }

    /**
     * T-INT-4 — 손 @ApiResponse 반영(202/503/400) + 23 경로 스캔 확인(/v1/spans 존재).
     */
    @Test
    void exposesIngestResponseCodesInSpec() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());

        JsonNode responses = MAPPER.readTree(res.getBody())
                .path("paths").path("/v1/spans").path("post").path("responses");
        assertTrue(responses.has("202"), "ingest spec must document 202");
        assertTrue(responses.has("503"), "ingest spec must document 503");
        assertTrue(responses.has("400"), "ingest spec must document 400");
    }

    /**
     * [Phase R17] T-INT-5 (FR-04) — 공통 오류 응답 표준 component 등록 봉인.
     *   components.schemas.ErrorResponse 가 flat { error } 표준으로 최종 스펙에 존재해야 한다(EXT-010 단일 출처).
     */
    @Test
    void registersSharedErrorResponseComponent() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());

        JsonNode errorSchema = MAPPER.readTree(res.getBody())
                .path("components").path("schemas").path("ErrorResponse");
        assertTrue(errorSchema.isObject() && !errorSchema.isEmpty(),
                "components.schemas.ErrorResponse must exist (FR-04)");
        assertTrue(errorSchema.path("properties").has("error"),
                "ErrorResponse must expose flat 'error' property");
    }

    /**
     * [Phase R17] T-INT-6 (FR-05) — maintenance 6종 @Operation summary 부여 봉인.
     *   cleanup/purge/optimize/status/pause/resume 이 모두 비어 있지 않은 summary 를 노출해야 한다.
     */
    @Test
    void documentsMaintenanceOperationsWithSummaries() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        JsonNode paths = MAPPER.readTree(res.getBody()).path("paths");

        assertOperationSummary(paths, "/v1/maintenance/cleanup", "post");
        assertOperationSummary(paths, "/v1/maintenance/purge", "post");
        assertOperationSummary(paths, "/v1/maintenance/optimize", "post");
        assertOperationSummary(paths, "/v1/maintenance/status", "get");
        assertOperationSummary(paths, "/v1/maintenance/pause", "post");
        assertOperationSummary(paths, "/v1/maintenance/resume", "post");
    }

    /**
     * [Phase R19] T-INT-7 — 신규 계측 분석 endpoint 2종이 스펙에 자동 노출되는지 봉인.
     *   계약 표를 손으로 적지 않는 대신({@code docs/api.md} 신규 표 0건) springdoc 자동 도출에 맡겼으므로,
     *   "자동 도출이 실제로 두 경로를 담는가" 가 이 라운드 문서 전략의 유일한 전제다.
     */
    @Test
    void documentsInstrumentAnalysisOperationsWithSummaries() throws Exception {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        JsonNode paths = MAPPER.readTree(res.getBody()).path("paths");

        assertOperationSummary(paths, "/v1/instrument/analysis", "post");
        assertOperationSummary(paths, "/v1/instrument/simulation", "post");
    }

    private static void assertOperationSummary(JsonNode paths, String path, String method) {
        JsonNode summary = paths.path(path).path(method).path("summary");
        assertTrue(summary.isTextual() && !summary.asText().isBlank(),
                path + " (" + method + ") must have a non-empty @Operation summary (FR-05)");
    }
}
