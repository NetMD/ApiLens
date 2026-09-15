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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
     */
    @Test
    void protectsAPercentEncodedSetupPath() {
        ResponseEntity<String> res = rest.exchange(
                URI.create(rest.getRootUri() + "/v1/setup/%2e%2e/traces"),
                org.springframework.http.HttpMethod.GET, null, String.class);

        assertNotEquals(HttpStatus.OK, res.getStatusCode(),
                "이스케이프가 섞인 경로가 토큰 없이 열리면 안 된다 — 실제: " + res.getStatusCode());
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
