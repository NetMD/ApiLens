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
package io.apilens.server.setup;

import io.apilens.server.setup.dto.ServiceRegistration;
import io.apilens.server.setup.dto.SetupCompleteRequest;
import io.apilens.server.setup.dto.SetupCompleteResponse;
import io.apilens.server.setup.dto.SetupStateResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase H] BT-9 / BT-10 — Setup endpoint 비즈니스 로직 검증.
 *
 * <p>D-01 / D-02 / D-04 / NFR-04 (멱등) / Q-01 (services null/[]). 사용자 명시 비협상 결정.
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 */
class SetupServiceTest {

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private SetupService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-setup-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.service = new SetupService(new SetupRepository(jdbc));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── GET /v1/setup/state 응답 구조 ────────────────────────────────────

    @Test
    void initialStateIsNotCompleted() {
        SetupStateResponse state = service.getState();
        assertFalse(state.completed());
        assertNull(state.completedAt());
        assertNull(state.serverUrl());
    }

    // ─── POST /v1/setup/complete — services nullable optional (Q-01) ─────

    @Test
    void shouldAcceptNullServices() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("http://apilens-host:8765", null));
        assertTrue(resp.completed());
        assertTrue(resp.completedAt() > 0L);

        // services 테이블에 INSERT 0
        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    @Test
    void shouldAcceptEmptyServicesList() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("http://apilens-host:8765", List.of()));
        assertTrue(resp.completed());

        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    @Test
    void shouldRegisterWizardServices() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("my-api"))
                ));
        assertTrue(resp.completed());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM services WHERE service_name = ?", "my-api");
        assertEquals("wizard", row.get("source"));
        assertNotNull(row.get("registered_at"));
        assertNull(row.get("last_seen_at"), "wizard 등록 시 last_seen_at 은 NULL");
    }

    // ─── NFR-04 멱등 — 재호출 시 갱신 ─────────────────────────────────────

    @Test
    void shouldBeIdempotentOnRepeatedComplete() throws InterruptedException {
        SetupCompleteResponse first = service.complete(
                new SetupCompleteRequest("http://old-host:8765", null));
        long firstCompletedAt = first.completedAt();

        // 1ms 이상 차이 확보
        Thread.sleep(5);

        SetupCompleteResponse second = service.complete(
                new SetupCompleteRequest("http://new-host:8765", null));
        long secondCompletedAt = second.completedAt();

        assertTrue(second.completed());
        assertTrue(secondCompletedAt >= firstCompletedAt);

        SetupStateResponse state = service.getState();
        assertTrue(state.completed());
        assertEquals("http://new-host:8765", state.serverUrl(), "serverUrl 갱신");
    }

    // ─── wizard 가 같은 이름 두 번 → ON CONFLICT DO NOTHING ────────────────

    @Test
    void duplicateWizardServiceInsertsAreIdempotent() {
        service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("my-api"))
        ));
        Long firstRegisteredAt = jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = ?", Long.class, "my-api");
        assertNotNull(firstRegisteredAt);

        // 동일 이름 wizard 재호출
        service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("my-api"))
        ));
        Long secondRegisteredAt = jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = ?", Long.class, "my-api");
        assertNotNull(secondRegisteredAt);

        // ON CONFLICT DO NOTHING — 첫 INSERT 시점 보존
        assertEquals(firstRegisteredAt.longValue(), secondRegisteredAt.longValue());
    }

    // ─── validation — serverUrl 형식 ─────────────────────────────────────

    /**
     * D-04 (skip 허용) + design §8.2 + Plan §2 AC-04-2 비협상 결정:
     * skip 경로에서 {@code serverUrl=""} + {@code services=[]} 는 정상 분기 (200).
     * setup_state.completed=1 / completed_at != null / server_url 은 NULL 정규화.
     * <p>회차 R9 BE-FAIL-01 회귀 가드 — 과거 IllegalArgumentException 던지던 잘못된 lock-in 반전.
     */
    @Test
    void shouldAcceptBlankServerUrlForSkipFlow() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("", List.of()));

        assertTrue(resp.completed(), "skip 경로에서도 completed=true 반환");
        assertTrue(resp.completedAt() > 0L, "completedAt 은 epoch ms");

        // setup_state 확정 — completed=1 / completed_at != null / server_url IS NULL
        SetupStateResponse state = service.getState();
        assertTrue(state.completed(), "skip 후 setup_state.completed=1");
        assertNotNull(state.completedAt(), "completed_at 저장됨");
        assertNull(state.serverUrl(), "빈 문자열은 NULL 로 정규화 저장");

        // services 테이블에 INSERT 0
        Integer rowCount = jdbc.queryForObject("SELECT COUNT(*) FROM services", Integer.class);
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    /**
     * D-04 정합: skip 경로 services=null 변형도 동등하게 200 (Q-01 omit/null/[] 동등).
     */
    @Test
    void shouldAcceptBlankServerUrlWithNullServicesForSkipFlow() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("", null));
        assertTrue(resp.completed());

        SetupStateResponse state = service.getState();
        assertTrue(state.completed());
        assertNull(state.serverUrl(), "빈 문자열은 NULL 로 정규화");
    }

    /**
     * NFR-04 멱등 정합 회귀 가드: skip 으로 일단 마킹한 후
     * 운영자가 wizard 재진입해 정상 완료 (Server URL 입력 + 서비스 1개) 시
     * setup_state.server_url 정상 갱신 + services 정상 INSERT 됨.
     */
    @Test
    void skipThenProperCompletionUpdatesServerUrlAndRegistersServices()
            throws InterruptedException {
        // 1) skip
        SetupCompleteResponse skip = service.complete(new SetupCompleteRequest("", List.of()));
        assertTrue(skip.completed());
        long skipAt = skip.completedAt();

        SetupStateResponse afterSkip = service.getState();
        assertTrue(afterSkip.completed());
        assertNull(afterSkip.serverUrl());

        Thread.sleep(5);

        // 2) wizard 재진입 후 정상 완료
        SetupCompleteResponse proper = service.complete(new SetupCompleteRequest(
                "http://apilens-host:8765",
                List.of(new ServiceRegistration("payment-svc"))
        ));
        assertTrue(proper.completed());
        assertTrue(proper.completedAt() >= skipAt, "completedAt 멱등 갱신");

        // 3) setup_state.server_url 갱신
        SetupStateResponse afterProper = service.getState();
        assertEquals("http://apilens-host:8765", afterProper.serverUrl(),
                "skip 후 정상 완료 시 server_url 갱신");

        // 4) services 정상 INSERT (skip 시점엔 0, 이후 1)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM services WHERE service_name = ?", "payment-svc");
        assertEquals("wizard", row.get("source"));
        assertNotNull(row.get("registered_at"));
        assertNull(row.get("last_seen_at"), "wizard 등록 시 last_seen_at NULL");
    }

    @Test
    void rejectsNonHttpServerUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("ftp://x:8765", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("localhost:8765", null)));
    }

    @Test
    void acceptsHttpsServerUrl() {
        SetupCompleteResponse resp = service.complete(
                new SetupCompleteRequest("https://apilens.example.com:8765", null));
        assertTrue(resp.completed());
    }

    // ─── validation — service name 형식 ──────────────────────────────────

    @Test
    void rejectsServiceNameWithSpaces() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("my api"))
                )));
    }

    @Test
    void rejectsServiceNameWithKorean() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration("결제"))
                )));
    }

    @Test
    void rejectsBlankServiceName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest(
                        "http://apilens-host:8765",
                        List.of(new ServiceRegistration(""))
                )));
    }

    // ── [Phase R26] R26/AC-R26-29 — 입력 검증을 거부 방향으로만 넓힘 ──
    //
    //  AC-R26-29 원문: "Setup 서비스 배열 **상한 50** 초과 시 400 · 서버 주소는 호스트가 있어야 통과 ·
    //  **빈 값·없는 값이 통과하던 기존 갈래는 그대로**".

    /** 상한(50) 바로 위인 51개를 넣어 거부되는지 본다. 같은 요청에서 <b>경계값 50 은 통과</b>도 함께 잰다. */
    @Test
    void rejectsMoreServicesThanTheLimit() {
        List<ServiceRegistration> fifty = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new ServiceRegistration("svc-" + i))
                .toList();
        // 전제: 상한 자리(50)는 실제로 통과해야 아래 거부가 "상한 때문" 임이 확정된다.
        assertTrue(service.complete(new SetupCompleteRequest("http://apilens-host:8765", fifty)).completed(),
                "전제: 경계값 50 은 통과한다");

        List<ServiceRegistration> fiftyOne = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new ServiceRegistration("svc-" + i))
                .toList();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("http://apilens-host:8765", fiftyOne)));
        assertTrue(e.getMessage().contains("50"),
                "문면에 몇 개까지 되는지가 들어야 운영자가 몇 개를 줄일지 안다 — 실제: " + e.getMessage());
    }

    /**
     * 호스트가 없는 주소를 거부한다. 접두만 보던 종전 검사는 {@code "http://"} 하나를 통과시켰다.
     *
     * <p>★같은 시험에서 <b>기존 통과 갈래가 그대로인지</b>도 잰다 — 빈 값과 없는 값은 여전히 통과한다
     * (설치를 건너뛰는 경로라 이 라운드가 좁히지 않기로 한 자리다).
     */
    @Test
    void rejectsAServerUrlWithoutAHost() {
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("http://", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("https:///traces", null)));

        assertTrue(service.complete(new SetupCompleteRequest("", null)).completed(),
                "빈 값은 여전히 통과한다(설치 건너뛰기 경로 보존)");
        assertTrue(service.complete(new SetupCompleteRequest(null, null)).completed(),
                "없는 값도 여전히 통과한다(설치 건너뛰기 경로 보존)");
    }

    // ── [Phase R27] R27/AC-27-04-3 — 서버 주소 포트 상한 65535(UA-R27-3 · 사용자 명시 결정) ──
    //
    //  AC-27-04-3 원문: "포트 65536 이상 → 서버 400 + 화면 「URL 포트 형식 오류 (예: :8765)」 · 65535 → 둘 다 통과 ·
    //  빈 값·null skip 경로(SetupService.java:106-108) 불변 · 서버·화면 **같은 커밋**".
    //  ★D-04(비협상): 빈 값·null 통과는 위 shouldAcceptBlank* 두 시험과 rejectsAServerUrlWithoutAHost 가 그대로 잰다.

    /**
     * 화면 {@code javaUriHost.test.ts} 의 {@code serverUrlProblemLikeServer} 와 <b>같은 13 벡터</b>로 서버의 거부 여부를 잰다
     * (거부 7 · 통과 6). 두 시험이 같은 벡터에서 같은 답을 내야 화면이 서버와 같은 기준으로 [다음]을 잠근다.
     *
     * <p>JDK 21 실측(2026-09-25 · openjdk 21.0.12.1): {@code URI.getPort()} 는 {@code :65536}·{@code :065536}·
     * {@code user@host:65536}·{@code :0000000000000065536} 에서 65536, {@code :99999} 에서 99999 를 그대로 돌려준다.
     * {@code :2147483648} 은 int 를 넘어 호스트가 null 이 되므로 <b>호스트 없음</b> 문구로 거부된다 — 화면은 포트 형식
     * 오류로 거부한다. 문구는 갈리고 거부 여부만 같다(범위 밖으로 둔 갈림). 사용자 정보 속 숫자({@code u:99999@x})는
     * 포트가 아니다.
     */
    @ParameterizedTest(name = "[{index}] {0} → rejected={1}")
    @CsvSource(delimiter = '|', value = {
            "http://host:65536 | true",
            "http://host:065536 | true",
            "http://host:99999 | true",
            "http://[::1]:65536 | true",
            "http://host:2147483648 | true",
            "http://user@host:65536 | true",
            "http://host:0000000000000065536 | true",
            "http://host:65535 | false",
            "http://host:0 | false",
            "http://host: | false",
            "http://[::1]: | false",
            "http://host | false",
            "http://u:99999@x | false",
    })
    void judgesTheSharedPortBoundaryLikeTheScreen(String url, boolean rejected) {
        if (rejected) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.complete(new SetupCompleteRequest(url, null)),
                    "서버는 이 주소를 거부한다 — 화면도 거부해야 한다");
        } else {
            assertTrue(service.complete(new SetupCompleteRequest(url, null)).completed(),
                    "서버는 이 주소를 받는다 — 화면도 받아야 한다");
            assertEquals(url, service.getState().serverUrl(), "받은 주소는 그대로 저장된다");
        }
    }

    /**
     * 상한 바로 위({@code :65536})의 거부 문면에 <b>몇까지 되는지</b>(65535)가 들어 있고, 거부된 요청은 아무것도
     * 저장하지 않는다. 문면은 영어 한 문장 · 내부 정보 0 이다(설치 400 은 인증 없이 보이는 응답이다).
     */
    @Test
    void rejectsAPortAboveTheMaximumWithTheLimitInTheMessage() {
        // 전제: 상한 자리(65535)는 통과해 저장된다 — 아래 거부가 「상한 때문」 임이 여기서 확정된다.
        assertTrue(service.complete(new SetupCompleteRequest("http://apilens-host:65535", null)).completed(),
                "전제: 경계값 65535 는 통과한다");
        assertEquals("http://apilens-host:65535", service.getState().serverUrl(), "전제: 경계값 주소가 저장됐다");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.complete(new SetupCompleteRequest("http://apilens-host:65536", null)));
        assertEquals("serverUrl port must be 65535 or less", e.getMessage(),
                "문면에 상한 숫자가 들어야 운영자가 무엇을 고칠지 안다");
        assertEquals("http://apilens-host:65535", service.getState().serverUrl(),
                "거부된 요청은 아무것도 저장하지 않는다(검사가 저장보다 앞선다)");
        assertEquals(SetupService.SERVER_URL_PORT_MAX, 65_535, "상한 상수는 TCP 포트 끝 값이다");
    }

    // ── [Phase R27] SEC-R27-L1 — 무인증 400 사유는 고정 문면 7종 중 하나다(시험만 · 생산 코드 변경 0) ──
    //
    //  설치 400 의 사유는 인증 면제 경로(POST /v1/setup/complete)의 응답이고, R27 부터 화면에 글자 그대로 뜬다.
    //  지금 7종은 전부 고정 문면이지만, 나중에 문면에 요청 값이나 예외 원문을 이어 붙이면 그 값이 무인증 응답과
    //  화면에 실린다. 그것을 막는 자동 장치가 이 시험이다.

    /** validate 가 던지는 고정 문면 7종 — 새 거부 갈래를 더하면 이 목록도 함께 늘린다(안 늘리면 빨강). */
    private static final java.util.Set<String> FIXED_REJECT_SENTENCES = java.util.Set.of(
            "request body is required",
            "serverUrl must start with http:// or https://",
            "serverUrl must include a host (예: http://192.168.0.10:8765)",
            "serverUrl port must be 65535 or less",
            "too many services — at most 50 are allowed",
            "service name is required",
            "service name format invalid");

    /** 요청 값에 심는 표식 — 사유 문면에 이 조각이 하나라도 보이면 요청 값이 응답에 새어 나간 것이다. */
    private static final List<String> MARKERS = List.of("zz-marker-zz", "<script>", "/etc/passwd");

    static java.util.stream.Stream<Arguments> markedRejectRequests() {
        List<ServiceRegistration> fiftyOneMarked = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> new ServiceRegistration("zz-marker-zz-" + i))
                .toList();
        return java.util.stream.Stream.of(
                Arguments.of("접두 틀림", new SetupCompleteRequest("ftp://zz-marker-zz/etc/passwd<script>", null),
                        "serverUrl must start with http:// or https://"),
                Arguments.of("호스트 없음", new SetupCompleteRequest("http:///zz-marker-zz/etc/passwd<script>", null),
                        "serverUrl must include a host (예: http://192.168.0.10:8765)"),
                Arguments.of("포트 초과", new SetupCompleteRequest("http://zz-marker-zz:65536/etc/passwd", null),
                        "serverUrl port must be 65535 or less"),
                Arguments.of("서비스 51개", new SetupCompleteRequest("http://apilens-host:8765", fiftyOneMarked),
                        "too many services — at most 50 are allowed"),
                Arguments.of("이름 형식 틀림", new SetupCompleteRequest("http://apilens-host:8765",
                                List.of(new ServiceRegistration("zz-marker-zz <script>/etc/passwd"))),
                        "service name format invalid"));
    }

    /**
     * 표식 글자를 실은 요청 5갈래(접두 틀림 · 호스트 없음 · 포트 초과 · 서비스 51개 · 이름 형식 틀림)를 거부시키고,
     * 사유가 ⓐ 그 갈래의 고정 문면이고 ⓑ 7종 목록에 들며 ⓒ 표식 조각을 하나도 안 싣는지 본다.
     *
     * <p>★갈래마다 기대 문면을 따로 단언한다 — 모든 입력이 앞 검사 하나에서 걸리면 뒤 갈래를 안 밟고도 초록이
     * 되는 빈 그물이 된다. 기록(2026-09-25 실측): 포트 문면에 요청 주소를 임시로 이어 붙인 판에서 빨갛다.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("markedRejectRequests")
    void keepsEveryRejectReasonToAFixedSentence(String branch, SetupCompleteRequest request, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.complete(request),
                "전제: 이 갈래(" + branch + ")는 거부된다");
        assertEquals(expected, e.getMessage(), "전제: 이 입력이 실제로 그 갈래(" + branch + ")에서 걸렸다");
        assertTrue(FIXED_REJECT_SENTENCES.contains(e.getMessage()),
                "무인증 400 사유는 고정 문면 7종 중 하나다 — 실제: " + e.getMessage());
        for (String marker : MARKERS) {
            assertFalse(e.getMessage().contains(marker),
                    "사유에 요청 값 조각(" + marker + ")이 실리면 안 된다 — 실제: " + e.getMessage());
        }
    }
}
