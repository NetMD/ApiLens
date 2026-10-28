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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [Phase R26] R26/AC-R26-30 — 인증 면제 판정을 <b>진짜 서블릿 컨테이너를 거쳐</b> 잰다.
 *
 * <p>AC-R26-30 원문: "인증 면제 경로에 {@code %} 가 들어오면 면제하지 않는다. 넣는 자리는 값 없음
 * 검사 <b>다음</b> · {@code /v1/**} 신규 경로 무접촉".
 *
 * <p>★<b>왜 단위 시험만으로는 모자란가(사각 명문)</b>: 같은 묶음의 단위 시험은 요청 흉내 객체에
 * 경로 문자열을 <b>직접 넣는다</b> — 컨테이너의 경로 정규화를 한 번도 안 거친다. 그래서
 * {@code %2e%2e} 같은 우회 벡터가 정규화 뒤에 어떤 문자열로 필터에 도착하는지 못 본다.
 * 이 파일이 그 사각을 메운다. 단위 시험을 대신하는 것이 아니라 <b>덧대는 것</b>이다.
 *
 * <p>★API 키를 <b>설정한 채로</b> 띄워야 "면제" 가 뜻을 가진다 — 키가 없으면 전부 통과라
 * 면제 여부를 가릴 수 없다. 그래서 회귀 두 건(평범한 setup 경로 · 정적 자산)을 함께 둔다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=test-whitelist-key"}
)
// [Phase R30] R30/AC-R30-20 — Boot 4 의 @SpringBootTest 는 TestRestTemplate 빈을 더는 주지 않는다 → 명시로 켠다.
@AutoConfigureTestRestTemplate
class AuthWhitelistPathIntegrationTest {

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-whitelist-it-", ".db");
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

    /**
     * percent 이스케이프가 섞인 setup 경로는 <b>면제되지 않는다</b>. 의도된 거부 = 정방향.
     *
     * <p>단언은 "200 이 아니다" 가 아니라 <b>"면제 통과가 아니다"</b> 로 읽는다 — 컨테이너가 그 경로를
     * 400 으로 먼저 끊든 필터가 401 로 끊든, 어느 쪽이든 <b>토큰 없이 setup 이 열리는 일은 없다</b>.
     *
     * <p>[2026-09-24] RA-R26-11 — 단언을 실제 값 <b>401</b> 로 좁혔다(단독 실행 실측: 이 컨테이너는
     * 그 경로를 400 으로 끊지 않고 필터까지 보내 401 이 난다). "200 이 아니다" 는 500·404 같은
     * <b>엉뚱한 실패</b>도 초록으로 통과시키는 빈 그물이었다. 컨테이너를 바꿔 400 이 나오게 되면
     * 여기가 빨개지는데, 그때는 면제 판정이 아니라 컨테이너 정규화가 바뀐 것이니 값을 다시 잰다.
     */
    @Test
    void protectsAPercentEncodedSetupPath() {
        ResponseEntity<String> res = rest.exchange(
                URI.create(rest.getRootUri() + "/v1/setup/%2e%2e/traces"),
                org.springframework.http.HttpMethod.GET, null, String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "이스케이프가 섞인 경로가 토큰 없이 열리면 안 된다 — 실제: " + res.getStatusCode());
    }

    /**
     * [Phase R29] R29/AC-R29-35 verbatim: "{@code /v1/setup/../trace-points} 를 글자 그대로(토큰 없이) 보내면 처리기에
     * 못 닿고 대표 점 본문이 안 나온다. {@code AuthWhitelist.java} diff 0". SEC-R28-02 방향 시험.
     *
     * <p>면제 접두({@code /v1/setup/})라 필터는 지나가지만 Spring 경로가 원문 {@code ..} 를 접지 않아 맞는 처리기가 없다 →
     * <b>404</b>(R28 보안 실기동 SV-03 · SV-18 과 같은 값 · 이 파일 첫 실행값으로 확인). 본문 단언은 따옴표를 포함한 JSON 키
     * {@code "points"} 0 이다 — 404 기본 본문의 {@code path} 값에 그 낱말이 글자로 실리기 때문이다.
     * 단위 시험(요청 흉내 객체)은 컨테이너 정규화를 못 거쳐 이 방향을 못 본다(사각 명시).
     */
    @Test
    void keepsTheDotDotSetupPathAwayFromThePointsHandler() {
        ResponseEntity<String> res = rest.exchange(
                URI.create(rest.getRootUri() + "/v1/setup/../trace-points?service=svc&since=0&until=1000"),
                org.springframework.http.HttpMethod.GET, null, String.class);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(),
                "`..` 를 접지 않은 경로는 대표 점 처리기에 닿지 않는다 — 실제: " + res.getStatusCode() + " " + res.getBody());
        String body = res.getBody() == null ? "" : res.getBody();
        assertFalse(body.contains("\"points\""), "대표 점 본문(JSON 키 points)이 안 나온다 — 실제: " + body);
    }

    /**
     * [Phase R29] R29/AC-R29-55 verbatim: "API 키를 설정한 서버에서, 경로 첫 마디에 {@code ;} 가 붙은 조회 요청을 토큰 없이
     * 보내면 {@code 401} 이고 본문에 데이터 키({@code traces} · {@code points} · {@code services} · payload 본문)가 없다.
     * 작성 시점 실측 경로 = {@code /v1;/traces} · {@code /v1;a=b/trace-points} · {@code /v1;/services} ·
     * {@code /v1;/traces/{t}/spans/{s}/payloads} · {@code /actuator;/info}(보안 SV-12~16 · §4b D). 인코딩 꼴
     * {@code /v1%3B/traces} 도 {@code 401}". 출처 = SEC-R29-01 · 사용자 결정 (가) 2026-10-20.
     *
     * <p>왜 컨테이너를 거치는 통합 판인가: 면제 판정은 원문 경로를 보고 Spring MVC 라우팅은 행렬 파라미터({@code ;…})를 뗀 경로를
     * 본다. 그 <b>둘이 갈리는 자리</b>가 결함이라 요청 흉내 객체(단위 시험)로는 라우팅 쪽이 안 보인다 — 실제로 보호 처리기에
     * 닿는지는 진짜 서블릿 컨테이너와 디스패처를 거쳐야 보인다.
     *
     * <p>본문 단언은 따옴표를 포함한 JSON 키다 — {@code "body"} 는 payload 응답의 본문 키(보안 §4b D 실물)다. 401 본문은
     * {@code {"error":"unauthorized"}} 라 네 키 어느 것도 안 실린다. payload 경로의 id({@code t1} · {@code s1})는 없는 값이어도
     * 된다 — 면제 판정이 처리기보다 <b>먼저</b>라, 막혔으면 id 와 무관하게 401 이다.
     *
     * <p>마지막 값({@code %3B})은 대조다 — R26 의 percent 가드가 이미 막던 꼴이라 고치기 전에도 401 이다. 고치기 전 실행값은
     * dev-backend 재작업 회차 1 결정 요약에 기록(같은 시험이 {@code ;} 갈래에서 401 이 아니었음).
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "/v1;/traces?service=svc",
            "/v1;a=b/trace-points?service=svc&since=0&until=1000",
            "/v1;/services",
            "/v1;/traces/t1/spans/s1/payloads",
            "/actuator;/info",
            "/v1%3B/traces?service=svc"
    })
    void protectsMatrixParameterPathsWithoutAToken(String path) {
        ResponseEntity<String> res = rest.exchange(
                URI.create(rest.getRootUri() + path),
                org.springframework.http.HttpMethod.GET, null, String.class);

        String body = res.getBody() == null ? "" : res.getBody();
        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "첫 마디에 행렬 파라미터가 붙은 경로가 토큰 없이 보호 처리기에 닿으면 안 된다 — 실제: "
                        + res.getStatusCode() + " " + body);
        assertFalse(body.contains("\"traces\""), "트레이스 목록 키가 안 나온다 — 실제: " + body);
        assertFalse(body.contains("\"points\""), "대표 점 키가 안 나온다 — 실제: " + body);
        assertFalse(body.contains("\"services\""), "서비스 목록 키가 안 나온다 — 실제: " + body);
        assertFalse(body.contains("\"body\""), "payload 본문 키가 안 나온다 — 실제: " + body);
    }

    /** 회귀 — 평범한 setup 경로는 <b>여전히</b> 토큰 없이 열린다(면제 범위를 좁히기만 했음을 못 박는다). */
    @Test
    void stillExemptsThePlainSetupPath() {
        ResponseEntity<String> res = rest.getForEntity("/v1/setup/state", String.class);

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "설치 마법사 경로는 토큰 없이 열려야 한다(면제 보존)");
    }

    /**
     * 회귀 — 정적 자산 경로도 <b>여전히</b> 면제다.
     *
     * <p>화면 자원이 임베드 안 된 빌드에서는 404 가 나는데, 그것도 <b>면제된 증거</b>다
     * (면제가 안 됐으면 401 이다). 그래서 단언은 "401 이 아니다" 로 읽는다.
     */
    @Test
    void stillExemptsAStaticAssetPath() {
        ResponseEntity<String> res = rest.getForEntity("/assets/index-abc.js", String.class);

        assertNotEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode(),
                "정적 자산은 면제 범위 안이다 — 실제: " + res.getStatusCode());
    }
}
