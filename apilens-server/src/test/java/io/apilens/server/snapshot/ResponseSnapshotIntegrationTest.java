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
package io.apilens.server.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [Phase R30] R30/AC-R30-02/R30/AC-R30-13 — 응답 모양 스냅샷. Boot 3.4.1 에서 뜬 응답을 기준으로 두고 판을 올린 뒤
 * 같은 고정 자료 위에서 다시 불러 <b>트리</b>로 맞춘다(설계 DT-R30-02 · §5.3 · 부록 D · 정정 DV-T-R30-2 · DV-X-R30-02).
 *
 * <p>AC-R30-02(보충) 원문: "3.4.1 응답 모양 스냅샷: 고정 자료(파일 이름 · 행 수를 적음) 위에서 뜬 대상 endpoint 응답 ·
 * {@code /v3/api-docs} · 401 본문 · 대표 400 · 404 본문 각 1 · ingest 실패 본문 둘(원시 {@code null} · 잉여 토큰)의 응답
 * 코드 · 본문 · 뜬 시각이 하류가 다시 돌릴 수 있는 자리에 있다 · ② 스냅샷이 비었거나 없으면 비교 시험이 초록으로 통과하지
 * 않는다" (비협상).
 *
 * <p>★<b>두 모드</b>: 시스템 속성 {@code apilens.snapshot.record} 에 출력 절대경로가 있으면 <b>기록</b>(대상마다 응답을
 * 모아 {@code @AfterAll} 에서 한 번 쓴다) · 비어 있으면 <b>비교</b>(시험 자료 {@code snapshots/responses-3.4.1.json} 과
 * 맞춘다). 서버 빌드가 Gradle 속성 {@code apilensSnapshotRecord} 를 그 시스템 속성으로 넘긴다(평소 = 빈 값 = 비교).
 *
 * <p>★<b>비교 도구는 Jackson 2 로 고정</b>(설계 DD-R30-11): 서버가 Jackson 3 로 바뀌는 라운드에 비교 도구까지 바뀌면 같은
 * 기본값 차이가 양쪽에 함께 들어가 안 보일 수 있다. 판에 안 묶인 해석기로 재야 차이가 서버 쪽에만 남는다.
 * 비교 = 키 · 값 · JSON 타입(정수 ↔ 소수도 다르게) · 객체 키 순서는 무시 · 배열은 차례대로.
 *
 * <p>★<b>꼴만 보는 자리</b>(존재 · JSON 타입만): ① 스냅샷 머리 {@code volatile} 배열의 포인터 — 같은 3.4.1 로 두 번 떠서
 * 트리가 다른 포인터 전부(기계 산출 · 사람 고르기 0 · 0b 커밋 뒤 늘리지 않는다) ② 유지보수 상태(SN-R30-10)의 엔진 값 —
 * 최상위 키 이름에 {@code Bytes} · {@code Pages} · {@code page} 가 든 것(정정 DV-S-R30-8 ⑦ · 첫 비교 전 고정).
 *
 * <p>★<b>허용 차이</b>(부록 D.2 · 첫 4.1 비교보다 앞에 고정 · 목록을 늘리는 것은 처리가 아니다): AL-R30-1 =
 * {@code /v3/api-docs} 의 {@code /info/version} 은 양쪽을 자리표시로 · AL-R30-2 · 3 = ingest 실패 두 대상은 상태가 4xx 로
 * 바뀌어도 된다(본문은 Boot 오류 본문이라 맞추지 않는다 · <b>5xx 면 실패</b>). AL-R30-4 는 처리 순서 규칙이라 코드가 없다.
 * [Phase R33] AL-R33-1 · 2 = 허용이 아니라 <b>요구</b>({@link #requireR33MediaKeys} · 사용자 결정 RD-R33-1 ⓑ · 설계 정정 부록 §1):
 * {@code /v3/api-docs} 의 매체 키는 응답 2xx = JSON 두 키(agent jar = octet-stream 하나) · 요청 본문 = JSON 두 키 · 선언된 오류
 * 응답 네 자리({@link #R33_DECLARED_JSON_ERRORS}) = JSON 두 키 · 그 밖의 오류 응답은 {@code *}{@code /*} 그대로이거나 JSON 두 키이고,
 * 형제 키 값이 같을 때만 기준 꼴로 맞춘 뒤 비교한다 · 오류 상태 JSON 키는 공용 조각
 * 참조(AL-R33-2). 기준 자료(3.4.1)는 그대로다 — 기준판을 「실제」 자리에 넣으면 빨강이다({@link #requiresTheR33MediaKeysOnTheApiDocs}).
 *
 * <p>★한계 그 자리에: 대상 19 만 본다 — 대상 밖 endpoint 의 모양 변화는 {@code /v3/api-docs} 트리가 계약으로 덮는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"apilens.auth.api-key=" + ResponseSnapshotIntegrationTest.API_KEY}
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseSnapshotIntegrationTest {

    static final String API_KEY = "test-response-snapshot-key";

    /** 기록 모드 스위치 — 값 = 출력 절대경로 · 빈 값 = 비교 모드. */
    static final String RECORD_PROPERTY = "apilens.snapshot.record";

    static final String SNAPSHOT_RESOURCE = "/snapshots/responses-3.4.1.json";

    static final String SERVICE = "snapshot-svc";

    /**
     * [Phase R30] R30/AC-R30-13 — AL-R30-2 · 3. Jackson 3 기본값 처리(설계 DD-R30-07 의 {@code use-jackson2-defaults})가
     * 발동하면 이 값을 {@code false} 로 바꾼다 — 그때 두 대상의 기대는 3.4.1 기록값 그대로다(정정 DV-S-R30-4).
     */
    static final boolean INGEST_FAILURE_MODE_CHANGE_ALLOWED = true;

    /** [Phase R33] AL-R33-1 · 2 — 요구 매체 키 · agent jar 경로 · 공용 조각 참조를 요구하는 오류 상태(설계 정정 부록 §1). */
    static final String R33_JSON = "application/json";
    static final Set<String> R33_JSON_KEYS = Set.of(R33_JSON, "application/*+json");
    static final String R33_AGENT_JAR_PATH = "/v1/setup/agent-jar";
    static final Set<String> R33_ERROR_REF_STATUSES = Set.of("400", "404", "409", "503");
    static final String R33_ERROR_REF = "#/components/schemas/ErrorResponse";

    /**
     * [Phase R33] R33/AC-R33-01-2 재작업 1 — 매핑 선언에 JSON 키를 단 오류 응답 자리((메서드 · 경로 · 상태) 고정 목록). 이 자리는
     * {@code *}{@code /*} 하나면 AL-R33-1 빨강이다 — {@code *}{@code /*} 그대로 기준 대조에 남는 것은 목록 밖 자리(처리기에서
     * 나온 오류 응답)뿐이다. 목록이 실물 명세와 같은지는 {@link #pinsTheDeclaredJsonErrorResponsesToTheLiveApiDocs} 가 잰다.
     */
    static final Set<String> R33_DECLARED_JSON_ERRORS = Set.of(
            "GET /v1/services/{serviceName}/instrument-config 404",
            "PUT /v1/services/{serviceName}/instrument-config 400",
            "POST /v1/spans 400",
            "POST /v1/spans 503");

    /** 고정 자료의 첫 시각(2026-01-01T00:00:00Z) — 시각을 고정해 응답 값이 실행 시각과 무관하게 한다. */
    static final long T0 = 1_767_225_600_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    // 실행마다 새 파일이다 — 두 번 기록하면 각각 새 임시 DB 위에서 뜬다(정정 DV-S-R30-8 ⑥).
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-response-snapshot-it-", ".db");
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

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String recordPath = System.getProperty(RECORD_PROPERTY, "").trim();

    private final Map<String, JsonNode> recorded = new LinkedHashMap<>();

    private Map<String, JsonNode> expectedBySn;

    private Set<String> volatilePointers;

    /** 대상 하나 — 부록 D.1 한 행. */
    record Target(String sn, String method, String path, boolean withToken, String body) {
        @Override
        public String toString() {
            return sn + " " + method + " " + path + (withToken ? "" : " (토큰 없음)");
        }
    }

    /**
     * 부록 D.1 차례 그대로 — 고정 자료 적재(SN-R30-19)가 맨 먼저 · ingest 실패 둘(SN-R30-17 · 18)이 맨 끝
     * (3.4.1 이 그것을 받아 저장하면 앞 대상 값이 바뀐다). 차례는 {@code @MethodSource} 인자 차례로 고정된다.
     */
    static Stream<Target> targets() {
        String t1 = traceId(1);
        String s1Root = spanId(1, 0);
        return Stream.of(
                new Target("SN-R30-19", "POST", "/v1/spans", true, fixtureBody()),
                new Target("SN-R30-01", "GET", "/v1/traces?service=" + SERVICE, true, null),
                new Target("SN-R30-02", "GET", "/v1/traces/" + t1, true, null),
                new Target("SN-R30-03", "GET", "/v1/traces/" + t1 + "/spans/" + s1Root + "/payloads", true, null),
                new Target("SN-R30-04", "GET", "/v1/trace-points?service=" + SERVICE + "&since=" + (T0 - 60_000L)
                        + "&until=" + (T0 + 3_600_000L), true, null),
                new Target("SN-R30-05", "GET", "/v1/services", true, null),
                new Target("SN-R30-06", "GET", "/v1/masking-rules", true, null),
                new Target("SN-R30-07", "POST", "/v1/masking-rules/preview", true,
                        "{\"sample\":null,\"contentType\":null,\"ruleStates\":null}"),
                new Target("SN-R30-08", "GET", "/v1/settings", true, null),
                new Target("SN-R30-09", "GET", "/v1/setup/state", true, null),
                new Target("SN-R30-10", "GET", "/v1/maintenance/status", true, null),
                new Target("SN-R30-11", "GET", "/v1/services/" + SERVICE + "/instrument-config", true, null),
                new Target("SN-R30-12", "POST", "/v1/instrument/analysis", true,
                        "{\"serviceName\":\"" + SERVICE + "\",\"windowHours\":24}"),
                new Target("SN-R30-13", "GET", "/v3/api-docs", true, null),
                new Target("SN-R30-14", "GET", "/v1/traces?service=" + SERVICE, false, null),
                new Target("SN-R30-15", "GET", "/v1/traces?service=" + SERVICE + "&limit=abc", true, null),
                new Target("SN-R30-16", "GET", "/v1/traces/" + "0".repeat(32), true, null),
                new Target("SN-R30-17", "POST", "/v1/spans", true, primitiveNullBody()),
                new Target("SN-R30-18", "POST", "/v1/spans", true, trailingTokenBody())
        );
    }

    /**
     * 비교 모드의 전제 단언 — 비교 앞이다(AC-R30-02 ②). 파일이 없거나 대상이 0 이면 여기서 실패해 19 대상이 전부 빨개진다.
     */
    @BeforeAll
    void loadSnapshotWhenComparing() throws IOException {
        if (!recordPath.isEmpty()) {
            return;
        }
        JsonNode snapshot;
        try (InputStream in = ResponseSnapshotIntegrationTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            snapshot = in == null ? null : JSON.readTree(in);
        }
        requireUsable(snapshot);
        expectedBySn = new HashMap<>();
        for (JsonNode t : snapshot.get("targets")) {
            expectedBySn.put(t.get("sn").asText(), t);
        }
        volatilePointers = new HashSet<>();
        JsonNode vol = snapshot.get("volatile");
        if (vol != null) {
            vol.forEach(v -> volatilePointers.add(v.asText()));
        }
    }

    /**
     * [Phase R30] R30/AC-R30-02/R30/AC-R30-13 — 대상 하나를 불러 기록하거나 맞춘다. AC-R30-13(보충) 원문: "4.1 응답이
     * AC-R30-02(보충) 스냅샷과 같은 고정 자료 위에서 키 · 값 · JSON 타입 셋 모두 같다(순서 무시) · 바뀌는 값(시각 · 지문 ·
     * 시간 길이 류)은 이름 목록으로 꼴만 본다 · 허용 차이 목록 … 을 뺀 차이 0" (비협상).
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("targets")
    void keepsTheResponseShapeOf(Target target) throws IOException, InterruptedException {
        ObjectNode actual = call(target);
        if (!recordPath.isEmpty()) {
            int status = actual.get("status").asInt();
            assertTrue(status >= 100 && status < 600, "기록 모드: 응답을 받았다 — " + target);
            recorded.put(target.sn(), actual);
            return;
        }
        JsonNode expected = expectedBySn.get(target.sn());
        assertNotNull(expected, "스냅샷에 대상이 있어야 한다 — " + target);
        List<String> diffs = compare(target.sn(), expected, actual, volatilePointers);
        assertTrue(diffs.isEmpty(), "허용 차이 · 꼴만 보는 값 밖의 차이 0 — " + target + " 차이 " + diffs);
    }

    /**
     * [Phase R30] R30/AC-R30-02 ② — 스냅샷이 비었거나 없으면 비교가 초록으로 통과하지 않는다(빈 그물 막기).
     * 비교 앞 전제 단언({@link #requireUsable})이 세 입력(없음 · 대상 0 · 대상 칸 없음) 모두에서 실패하는지 본다.
     */
    @Test
    void failsClosedOnAnEmptyOrMissingSnapshot() {
        assertThrows(AssertionError.class, () -> requireUsable(null));
        assertThrows(AssertionError.class, () -> requireUsable(JSON.createObjectNode()));
        ObjectNode emptyTargets = JSON.createObjectNode();
        emptyTargets.putArray("targets");
        assertThrows(AssertionError.class, () -> requireUsable(emptyTargets));
    }

    /**
     * [Phase R30] R30/AC-R30-15 — 회귀 시험(외부 검토 CXA-1 재작업). 실패 입력 두 대상에 기준판과 같은 202 가 돌아오면
     * 비교가 <b>차이를 낸다</b>(4xx 단언이 상태 차이와 무관하게 돈다). 4xx(400)면 차이 0 · 5xx 면 차이를 낸다.
     * 허용이 꺼진 판(DD-R30-07 처리 발동)에서는 이 시험의 전제가 사라지므로 상수를 보고 건너뛰지 않고 먼저 단언한다.
     */
    @Test
    void reportsAStatusDifferenceWhenAMalformedIngestBodyIsAccepted() {
        assertTrue(INGEST_FAILURE_MODE_CHANGE_ALLOWED, "전제: 실패 모드 변경 허용이 켜져 있어야 이 회귀 시험이 뜻을 가진다");
        for (String sn : List.of("SN-R30-17", "SN-R30-18")) {
            ObjectNode recorded = JSON.createObjectNode();
            recorded.put("status", 202);
            recorded.putObject("body").put("accepted", 1).put("traces", 1);
            assertTrue(!compare(sn, recorded, recorded.deepCopy(), Set.of()).isEmpty(),
                    "기준판과 같은 202 가 와도 차이로 잡혀야 한다 — " + sn);
            ObjectNode serverError = JSON.createObjectNode();
            serverError.put("status", 500);
            serverError.putObject("body").put("error", "x");
            assertTrue(!compare(sn, recorded, serverError, Set.of()).isEmpty(), "5xx 는 차이 — " + sn);
            ObjectNode rejected = JSON.createObjectNode();
            rejected.put("status", 400);
            rejected.putObject("body").put("status", 400).put("error", "Bad Request");
            assertTrue(compare(sn, recorded, rejected, Set.of()).isEmpty(), "4xx 는 허용 차이 안 — " + sn);
        }
    }

    /**
     * [Phase R33] R33/AC-R33-01-2 — TS-R33-17 · 회귀 시험(사용자 결정 RD-R33-1 ⓑ · 설계 정정 부록 §1). AL-R33-1 · 2 가
     * 「허용」이 아니라 「요구」인지 기준 자료 하나로 잰다: ① 기준판(3.4.1)을 실제 자리에 넣으면 <b>빨강</b>(요구 키 없음)
     * ② 요구 꼴로 바꾼 판은 차이 0(양성 대조 — 요구가 채울 수 있는 것임) ③ 같은 판에서 오류 응답의 공용 조각 참조만 빼면 빨강.
     */
    @Test
    void requiresTheR33MediaKeysOnTheApiDocs() throws IOException {
        JsonNode baseline;
        try (InputStream in = ResponseSnapshotIntegrationTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            requireUsable(baseline = in == null ? null : JSON.readTree(in));
        }
        JsonNode spec = null;
        for (JsonNode t : baseline.get("targets")) {
            if ("SN-R30-13".equals(t.get("sn").asText())) {
                spec = t;
            }
        }
        assertNotNull(spec, "전제: 기준 자료에 SN-R30-13 이 있다");
        List<String> asIs = compare("SN-R30-13", spec, spec.deepCopy(), Set.of());
        assertTrue(asIs.stream().anyMatch(d -> d.contains("AL-R33-1")), "① 기준판을 실제로 넣으면 AL-R33-1 빨강 — " + asIs);
        List<String> shaped = compare("SN-R30-13", spec, r33Shaped(spec, true), Set.of());
        assertTrue(shaped.isEmpty(), "② 요구 꼴 판은 차이 0 — " + shaped);
        List<String> noRef = compare("SN-R30-13", spec, r33Shaped(spec, false), Set.of());
        assertTrue(noRef.stream().anyMatch(d -> d.contains("AL-R33-2")), "③ 오류 응답 참조를 빼면 AL-R33-2 빨강 — " + noRef);
    }

    /**
     * [Phase R33] R33/AC-R33-01-2 재작업 1 — 오류 응답의 JSON 매체는 기준 자료로 만든 기대 객체(공용 조각 참조 하나 + 기준
     * schema 의 예시) <b>전체</b>와 같을 때만 기준 꼴로 접힌다 — 접기가 차이를 지우지 않는다(설계 정정 부록 ⓑ 「명세 나머지
     * 트리는 계속 3.4.1 과 대조」). 회귀 사례 셋은 {@code POST /v1/spans} 400 의 JSON 두 형제 키에 <b>같은</b> 손질을 한다 —
     * 형제 키 같음 검사는 통과하고 기대 객체 비교에서만 잡히는 꼴이다.
     *
     * <p>고치기 전(HEAD {@code 85cd1d4}) 값: ⓐ · ⓑ 는 차이 0(바꾼 글자가 접기로 사라짐) · ⓒ 만 잡혔다.
     */
    @Test
    void foldsErrorMediaOnlyWhenTheWholeExpectedObjectMatches() throws IOException {
        JsonNode spec = baselineSpecTarget();
        List<String> shaped = compare("SN-R30-13", spec, r33Shaped(spec, true), Set.of());
        assertTrue(shaped.isEmpty(), "양성 대조 — 요구 꼴 판은 차이 0 · " + shaped);

        JsonNode examples = r33Shaped(spec, true);
        for (ObjectNode media : spans400JsonMedia(examples)) {
            media.putObject("examples").putObject("r33").put("value", "zzR33examples");
        }
        List<String> a = compare("SN-R30-13", spec, examples, Set.of());
        assertTrue(a.stream().anyMatch(d -> d.contains("AL-R33-2")), "ⓐ 형제 키 둘에 같은 examples — 빨강 · " + a);

        JsonNode besideRef = r33Shaped(spec, true);
        for (ObjectNode media : spans400JsonMedia(besideRef)) {
            ((ObjectNode) media.get("schema")).put("maxLength", 7).put("description", "zzR33beside");
        }
        List<String> b = compare("SN-R30-13", spec, besideRef, Set.of());
        assertTrue(b.stream().anyMatch(d -> d.contains("AL-R33-2")), "ⓑ 참조 옆 제약 키 — 빨강 · " + b);

        JsonNode example = r33Shaped(spec, true);
        for (ObjectNode media : spans400JsonMedia(example)) {
            media.put("example", "zzR33example");
        }
        List<String> c = compare("SN-R30-13", spec, example, Set.of());
        assertTrue(c.stream().anyMatch(d -> d.contains("AL-R33-2")), "ⓒ 예시만 바꿈 — 빨강 유지 · " + c);
    }

    /**
     * [Phase R33] R33/AC-R33-01-2 재작업 1 — 선언된 JSON 키 오류 응답 자리({@link #R33_DECLARED_JSON_ERRORS})가 기준판
     * {@code *}{@code /*} 로 되돌아가면 빨강이다(설계 정정 부록 ⓑ 「AL-R33-2 = 오류 상태(400 · 404 · 409 · 503) JSON 키의 schema =
     * {@code $ref #/components/schemas/ErrorResponse}(요구)」). 요구 꼴 판에서 <b>한 자리씩만</b> 3.4.1 기준 매체로 되돌린다 —
     * 판올림이 오류 응답의 선언 매체를 떨어뜨리는 표류 꼴이다({@code POST /v1/spans} 400 이 그 첫 사례).
     *
     * <p>고치기 전(HEAD {@code 4494030}) 값: 네 자리 모두 차이 0 — 키 집합이 {@code *}{@code /*} 하나면 요구를 건너뛰었고, 그대로
     * 남은 기준 매체가 트리 비교도 통과했다.
     */
    @Test
    void reportsADeclaredJsonErrorResponseRevertedToTheBaselineWildcard() throws IOException {
        JsonNode spec = baselineSpecTarget();
        List<String> shaped = compare("SN-R30-13", spec, r33Shaped(spec, true), Set.of());
        assertTrue(shaped.isEmpty(), "양성 대조 — 요구 꼴 판은 차이 0 · " + shaped);

        for (String site : new TreeSet<>(R33_DECLARED_JSON_ERRORS)) {
            String[] f = site.split(" ");
            JsonNode baseContent = responseAt(spec, f[0], f[1], f[2]).path("content");
            assertEquals(Set.of("*/*"), keysOf(baseContent), "전제: 기준판의 " + site + " 매체 키는 */* 하나");
            JsonNode reverted = r33Shaped(spec, true);
            ((ObjectNode) responseAt(reverted, f[0], f[1], f[2])).set("content", baseContent.deepCopy());
            List<String> d = compare("SN-R30-13", spec, reverted, Set.of());
            assertTrue(d.stream().anyMatch(x -> x.startsWith("SN-R30-13:" + site + " AL-R33-1")),
                    "기준판 */* 로 되돌린 " + site + " — AL-R33-1 빨강 · " + d);
        }
    }

    /**
     * [Phase R33] R33/AC-R33-01-2 재작업 1 — 고정 목록({@link #R33_DECLARED_JSON_ERRORS})이 실물 {@code /v3/api-docs} 의 JSON 키
     * 오류 응답 자리와 같다: 목록 밖 JSON 키 오류 응답 0 · 목록 안 네 자리 모두 JSON 두 키. 목록 밖 오류 응답은
     * {@code *}{@code /*} 그대로 기준 대조에 남는다(그 자리가 0 이 아님도 본다).
     */
    @Test
    void pinsTheDeclaredJsonErrorResponsesToTheLiveApiDocs() throws IOException, InterruptedException {
        assumeTrue(recordPath.isEmpty(), "기록 모드(3.4.1 기준판을 뜨는 실행)에서는 R33 요구가 없다");
        ObjectNode live = call(new Target("SN-R30-13", "GET", "/v3/api-docs", true, null));
        assertEquals(200, live.get("status").asInt(), "전제: /v3/api-docs 200");
        Set<String> jsonErrors = new TreeSet<>();
        int wildcardErrors = 0;
        for (Map.Entry<String, JsonNode> p : live.path("body").path("paths").properties()) {
            for (Map.Entry<String, JsonNode> m : p.getValue().properties()) {
                for (Map.Entry<String, JsonNode> r : m.getValue().path("responses").properties()) {
                    JsonNode content = r.getValue().path("content");
                    if (r.getKey().startsWith("2") || !content.isObject()) {
                        continue;
                    }
                    Set<String> keys = keysOf(content);
                    if (keys.equals(Set.of("*/*"))) {
                        wildcardErrors++;
                        continue;
                    }
                    String site = errorSite(m.getKey(), p.getKey(), r.getKey());
                    jsonErrors.add(site);
                    assertEquals(R33_JSON_KEYS, keys, "목록 안 자리는 JSON 두 키 — " + site);
                }
            }
        }
        assertEquals(new TreeSet<>(R33_DECLARED_JSON_ERRORS), jsonErrors,
                "고정 목록 = 실물 명세의 JSON 키 오류 응답 자리(목록 밖 0 · 목록 안 전부)");
        assertTrue(wildcardErrors > 0, "목록 밖 오류 응답(*/* 그대로 · 기준 대조) 자리가 있다 — " + wildcardErrors);
    }

    /** (메서드 · 경로 · 상태) 자리 이름 — {@link #R33_DECLARED_JSON_ERRORS} 의 꼴. */
    private static String errorSite(String method, String path, String status) {
        return method.toUpperCase(Locale.ROOT) + " " + path + " " + status;
    }

    /** 대상({@code body} 가 명세)에서 한 응답 자리. */
    private static JsonNode responseAt(JsonNode target, String method, String path, String status) {
        return target.path("body").path("paths").path(path).path(method.toLowerCase(Locale.ROOT)).path("responses")
                .path(status);
    }

    /** 기준 자료(3.4.1)의 {@code /v3/api-docs} 대상(SN-R30-13) 한 개. */
    private static JsonNode baselineSpecTarget() throws IOException {
        JsonNode baseline;
        try (InputStream in = ResponseSnapshotIntegrationTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            requireUsable(baseline = in == null ? null : JSON.readTree(in));
        }
        for (JsonNode t : baseline.get("targets")) {
            if ("SN-R30-13".equals(t.get("sn").asText())) {
                return t;
            }
        }
        return fail("전제: 기준 자료에 SN-R30-13 이 있다");
    }

    /** 요구 꼴 사본에서 {@code POST /v1/spans} 400 의 JSON 두 형제 키 매체 — 둘 다 있어야 한다(전제). */
    private static List<ObjectNode> spans400JsonMedia(JsonNode shaped) {
        JsonNode content = shaped.path("body").path("paths").path("/v1/spans").path("post").path("responses")
                .path("400").path("content");
        List<ObjectNode> media = new ArrayList<>();
        for (String key : R33_JSON_KEYS) {
            assertTrue(content.path(key).isObject(), "전제: /v1/spans 400 의 " + key + " 매체가 있다 · " + content);
            media.add((ObjectNode) content.get(key));
        }
        return media;
    }

    /**
     * 기준 명세를 R33 요구 꼴로 바꾼 사본 — 응답 2xx 와 요청 본문을 JSON 두 키(agent jar = octet-stream)로 · 선언된 오류 응답
     * 네 자리({@link #R33_DECLARED_JSON_ERRORS})를 JSON 두 키로(withRef 면 공용 조각 참조 + 옮긴 예시 · 아니면 옛 inline schema 그대로).
     */
    private static JsonNode r33Shaped(JsonNode target, boolean withRef) {
        ObjectNode copy = target.deepCopy();
        for (Map.Entry<String, JsonNode> p : copy.path("body").path("paths").properties()) {
            for (Map.Entry<String, JsonNode> m : p.getValue().properties()) {
                JsonNode body = m.getValue().path("requestBody").path("content");
                if (body.isObject() && body.has(R33_JSON)) {
                    ((ObjectNode) body).set("application/*+json", body.get(R33_JSON).deepCopy());
                }
                for (Map.Entry<String, JsonNode> r : m.getValue().path("responses").properties()) {
                    JsonNode c = r.getValue().path("content");
                    boolean declaredError = R33_DECLARED_JSON_ERRORS.contains(errorSite(m.getKey(), p.getKey(), r.getKey()));
                    if (!c.isObject() || !c.has("*/*") || !(r.getKey().startsWith("2") || declaredError)) {
                        continue;
                    }
                    JsonNode media = c.get("*/*");
                    if (declaredError && withRef) {
                        ObjectNode moved = JSON.createObjectNode();
                        moved.putObject("schema").put("$ref", R33_ERROR_REF);
                        moved.set("example", media.path("schema").get("example"));
                        media = moved;
                    }
                    ObjectNode cc = ((ObjectNode) c).removeAll();
                    if (R33_AGENT_JAR_PATH.equals(p.getKey())) {
                        cc.set("application/octet-stream", media);
                    } else {
                        cc.set(R33_JSON, media);
                        cc.set("application/*+json", media.deepCopy());
                    }
                }
            }
        }
        return copy;
    }

    /** 기록 모드면 한 번 쓴다(정정 DV-X-R30-02 — {@code @AfterAll} 기록 1회). 임시 DB 는 늘 지운다. */
    @AfterAll
    void writeRecordingOnceAndDeleteTempDb() throws IOException {
        try {
            if (!recordPath.isEmpty()) {
                ObjectNode root = JSON.createObjectNode();
                root.put("capturedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
                root.put("springBootVersion", SpringBootVersion.getVersion());
                root.put("serverInfo", ServerInfo.getServerInfo());
                ObjectNode fixture = root.putObject("fixture");
                fixture.put("source", "ResponseSnapshotIntegrationTest#fixtureBody");
                fixture.put("service", SERVICE);
                fixture.put("traces", 3);
                fixture.put("spans", 9);
                fixture.put("payloads", 2);
                fixture.put("maskingReactivePayloads", 1);
                fixture.put("firstStartTime", T0);
                ObjectNode notes = root.putObject("notes");
                notes.put("SN-R30-15", "limit=abc (숫자 매개변수에 글자)");
                notes.put("SN-R30-16", "없는 trace id = 0 32 자");
                notes.put("SN-R30-17", "spans[0].startTime = null (원시 필드)");
                notes.put("SN-R30-18", "올바른 본문 끝에 잉여 토큰 ' 1'");
                notes.put("volatile", "같은 판으로 두 번 기록한 두 파일의 트리가 다른 포인터 전부(대상:JSON 포인터) — 0b 커밋 뒤 늘리지 않는다");
                root.putArray("volatile");
                ArrayNode list = root.putArray("targets");
                recorded.values().forEach(list::add);
                Path out = Path.of(recordPath);
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                        StandardCharsets.UTF_8);
            }
        } finally {
            String base = TEMP_DB.toAbsolutePath().toString();
            Files.deleteIfExists(TEMP_DB);
            Files.deleteIfExists(Path.of(base + "-wal"));
            Files.deleteIfExists(Path.of(base + "-shm"));
        }
    }

    // ─── 비교 ────────────────────────────────────────────────────────────────

    /** 비교 앞 전제 — 스냅샷이 있고 대상이 1 이상이어야 한다. */
    static void requireUsable(JsonNode snapshot) {
        if (snapshot == null || !snapshot.has("targets") || !snapshot.get("targets").isArray()
                || snapshot.get("targets").isEmpty()) {
            fail("응답 스냅샷이 없거나 대상이 0 이다 — 비교를 초록으로 통과시키지 않는다(" + SNAPSHOT_RESOURCE + ")");
        }
    }

    /** 대상 하나의 기대와 실제를 맞춘다. 차이 목록(대상:포인터 · 사유)을 돌려준다. */
    static List<String> compare(String sn, JsonNode expected, JsonNode actual, Set<String> volatilePointers) {
        List<String> diffs = new ArrayList<>();
        int expStatus = expected.get("status").asInt();
        int actStatus = actual.get("status").asInt();
        boolean ingestFailureTarget = "SN-R30-17".equals(sn) || "SN-R30-18".equals(sn);
        // [Phase R30] R30/AC-R30-15 — 실패 입력 두 대상은 허용이 켜져 있으면 **기준판 상태와 무관하게** 실제 상태가 4xx 인지 먼저
        //   본다(외부 검토 CXA-1 재작업). 기준판(3.4.1)이 202 라서, 이 단언이 상태가 다를 때만 돌면 4.1 이 202 로 되돌아가도
        //   「차이 0」으로 지나갔다. AC-R30-15(보충) 원문: "4.1 응답 = 4xx(5xx 0)". 허용을 끄면(DD-R30-07 처리 발동) 아래
        //   일반 비교로 3.4.1 기록값과 같아야 한다.
        if (ingestFailureTarget && INGEST_FAILURE_MODE_CHANGE_ALLOWED) {
            if (actStatus < 400 || actStatus >= 500) {
                diffs.add(sn + ":#status 실패 모드 기대 4xx → " + actStatus + "(기준판 " + expStatus + ")");
            }
            return diffs; // AL-R30-2 · 3 — 4xx 면 허용 · 본문은 Boot 오류 본문이라 맞추지 않는다
        }
        if (expStatus != actStatus && !volatilePointers.contains(sn + ":#status")) {
            diffs.add(sn + ":#status " + expStatus + " → " + actStatus);
            return diffs;
        }
        JsonNode expBody = expected.get("body");
        JsonNode actBody = actual.get("body");
        if ("SN-R30-13".equals(sn)) {
            expBody = withPlaceholderVersion(expBody);
            actBody = withPlaceholderVersion(actBody);
            actBody = requireR33MediaKeys(expBody, actBody, diffs);
        }
        Set<String> shapeOnly = new HashSet<>();
        for (String p : volatilePointers) {
            if (p.startsWith(sn + ":")) {
                shapeOnly.add(p.substring(sn.length() + 1));
            }
        }
        if ("SN-R30-10".equals(sn) && expBody != null && expBody.isObject()) {
            for (Map.Entry<String, JsonNode> e : expBody.properties()) {
                String k = e.getKey();
                if (k.contains("Bytes") || k.contains("Pages") || k.contains("page")) {
                    shapeOnly.add("/" + k);
                }
            }
        }
        walk(sn, "", expBody, actBody, shapeOnly, diffs);
        return diffs;
    }

    /**
     * [Phase R33] R33/AC-R33-01-2/R33/AC-R33-02-1 — AL-R33-1 · AL-R33-2(요구 꼴). 사용자 명시 결정(RD-R33-1 ⓑ — 「허용」이
     * 아니라 「요구」 · 기준 자료 무접촉). 실제 명세의 {@code content} 키 집합이 요구 집합과 같고 형제 키 값이 같을 때만 기준
     * 꼴(응답 {@code *}{@code /*} · 요청 본문 {@code application/json})로 접어 돌려준다 — 다르면 차이를 적고 그대로 둔다(아래
     * 트리 비교가 한 번 더 잡는다). 오류 상태(400 · 404 · 409 · 503)의 JSON 키는 공용 조각 참조여야 하고, 그 customizer 가 옮긴
     * 예시는 기준 schema 의 예시와 같아야 한다. 설계 정정 부록 §1 인용.
     *
     * <p>★한계 그 자리에: 처리기({@code @ExceptionHandler})에서 나온 오류 응답은 매핑 선언을 안 타 {@code *}{@code /*} 그대로다
     * (R33 실측 50 자리) — 그 자리는 기준 대조로 남는다.
     *
     * <p>[Phase R33] 재작업 1 — 위 예외는 (메서드 · 경로 · 상태) 고정 목록({@link #R33_DECLARED_JSON_ERRORS}) <b>밖</b>에만 건다.
     * 목록 안 네 자리는 {@code *}{@code /*} 하나면 AL-R33-1 빨강이다({@link #reportsADeclaredJsonErrorResponseRevertedToTheBaselineWildcard}).
     */
    static JsonNode requireR33MediaKeys(JsonNode expBody, JsonNode actBody, List<String> diffs) {
        if (actBody == null || !actBody.isObject() || !actBody.path("paths").isObject()) {
            return actBody;
        }
        ObjectNode act = actBody.deepCopy();
        for (Map.Entry<String, JsonNode> p : act.get("paths").properties()) {
            for (Map.Entry<String, JsonNode> m : p.getValue().properties()) {
                JsonNode op = m.getValue();
                String where = "SN-R30-13:" + m.getKey().toUpperCase(Locale.ROOT) + " " + p.getKey();
                JsonNode body = op.path("requestBody").path("content");
                if (body.isObject() && requireKeys(where + " requestBody", (ObjectNode) body, R33_JSON_KEYS, diffs)) {
                    JsonNode one = body.get(R33_JSON);
                    ((ObjectNode) body).removeAll().set(R33_JSON, one);
                }
                for (Map.Entry<String, JsonNode> r : op.path("responses").properties()) {
                    JsonNode content = r.getValue().path("content");
                    if (!content.isObject()) {
                        continue;
                    }
                    ObjectNode c = (ObjectNode) content;
                    String status = r.getKey();
                    String at = where + " " + status;
                    if (status.startsWith("2")) {
                        Set<String> required = R33_AGENT_JAR_PATH.equals(p.getKey())
                                ? Set.of("application/octet-stream") : R33_JSON_KEYS;
                        if (requireKeys(at, c, required, diffs)) {
                            JsonNode one = c.elements().next();
                            c.removeAll().set("*/*", one);
                        }
                    } else if ((R33_DECLARED_JSON_ERRORS.contains(errorSite(m.getKey(), p.getKey(), status))
                            || !keysOf(c).equals(Set.of("*/*"))) && requireKeys(at, c, R33_JSON_KEYS, diffs)) {
                        // [Phase R33] R33/AC-R33-01-2 재작업 1 — 와일드카드 하나만 있을 때 건너뛰는 것은 고정 목록 밖 자리뿐이다.
                        //   목록 안 자리는 와일드카드면 위 requireKeys 가 AL-R33-1 을 적는다 — 기준판 꼴로 되돌아가도 「차이 0」이 아니다.
                        JsonNode media = c.get(R33_JSON);
                        if (R33_ERROR_REF_STATUSES.contains(status)) {
                            JsonNode expMedia = expBody == null ? null : expBody.path("paths").path(p.getKey())
                                    .path(m.getKey()).path("responses").path(status).path("content").path("*/*");
                            if (!R33_ERROR_REF.equals(media.path("schema").path("$ref").asText(null))) {
                                diffs.add(at + " AL-R33-2 오류 응답 schema 가 공용 조각 참조가 아니다");
                                continue;
                            }
                            if (expMedia != null && !expMedia.isMissingNode()) {
                                JsonNode example = expMedia.path("schema").get("example");
                                if (!Objects.equals(media.get("example"), example)) {
                                    diffs.add(at + " AL-R33-2 옮긴 예시가 기준 schema 의 예시와 다르다");
                                    continue;
                                }
                                // [Phase R33] R33/AC-R33-01-2 재작업 1 — 기준 매체로 만든 기대 객체 「전체」와 같을 때만 접는다.
                                //   예시 · 참조만 보고 접으면 그 밖의 키(examples · 참조 옆 제약 키)가 접기와 함께 사라진다.
                                ObjectNode expected = JSON.createObjectNode();
                                expected.putObject("schema").put("$ref", R33_ERROR_REF);
                                if (example != null) {
                                    expected.set("example", example);
                                }
                                if (!media.equals(expected)) {
                                    diffs.add(at + " AL-R33-2 오류 응답 매체가 기대 객체(공용 조각 참조 하나 + 기준 예시)와 다르다 · 키 "
                                            + new TreeSet<>(keysOf(media)) + " · schema 키 " + new TreeSet<>(keysOf(media.path("schema"))));
                                    continue;
                                }
                                media = expMedia.deepCopy();
                            }
                        }
                        c.removeAll().set("*/*", media);
                    }
                }
            }
        }
        return act;
    }

    /** AL-R33-1 — 키 집합이 요구 집합과 같고 형제 키 값이 서로 같은가. 아니면 차이를 적고 false. */
    private static boolean requireKeys(String at, ObjectNode content, Set<String> required, List<String> diffs) {
        Set<String> keys = keysOf(content);
        if (!keys.equals(required)) {
            diffs.add(at + " AL-R33-1 매체 키 요구 " + new TreeSet<>(required) + " → 실제 " + new TreeSet<>(keys));
            return false;
        }
        JsonNode first = content.elements().next();
        for (Map.Entry<String, JsonNode> e : content.properties()) {
            if (!e.getValue().equals(first)) {
                diffs.add(at + " AL-R33-1 형제 키 값이 서로 다르다(" + e.getKey() + ")");
                return false;
            }
        }
        return true;
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.properties().forEach(e -> keys.add(e.getKey()));
        return keys;
    }

    private static JsonNode withPlaceholderVersion(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("info") || !body.get("info").isObject()) {
            return body;
        }
        ObjectNode copy = body.deepCopy();
        ((ObjectNode) copy.get("info")).set("version", TextNode.valueOf("<current-build-version>"));
        return copy;
    }

    private static void walk(String sn, String ptr, JsonNode exp, JsonNode act, Set<String> shapeOnly,
                             List<String> diffs) {
        if (exp == null || act == null || exp.isMissingNode() || act.isMissingNode()) {
            if ((exp == null || exp.isMissingNode()) != (act == null || act.isMissingNode())) {
                diffs.add(sn + ":" + ptr + " 있음/없음 다름");
            }
            return;
        }
        if (exp.getNodeType() != act.getNodeType()) {
            diffs.add(sn + ":" + ptr + " JSON 타입 " + exp.getNodeType() + " → " + act.getNodeType());
            return;
        }
        if (shapeOnly.contains(ptr)) {
            return; // 꼴만 보는 자리 — 존재 · JSON 타입까지만
        }
        if (exp.isObject()) {
            Set<String> keys = new TreeSet<>();
            exp.properties().forEach(e -> keys.add(e.getKey()));
            act.properties().forEach(e -> keys.add(e.getKey()));
            for (String k : keys) {
                walk(sn, ptr + "/" + escape(k), exp.get(k), act.get(k), shapeOnly, diffs);
            }
        } else if (exp.isArray()) {
            if (exp.size() != act.size()) {
                diffs.add(sn + ":" + ptr + " 배열 길이 " + exp.size() + " → " + act.size());
                return;
            }
            for (int i = 0; i < exp.size(); i++) {
                walk(sn, ptr + "/" + i, exp.get(i), act.get(i), shapeOnly, diffs);
            }
        } else if (exp.isNumber()) {
            if (exp.isIntegralNumber() != act.isIntegralNumber()
                    || exp.decimalValue().compareTo(act.decimalValue()) != 0) {
                diffs.add(sn + ":" + ptr + " 값 " + exp + " → " + act);
            }
        } else if (!exp.equals(act)) {
            diffs.add(sn + ":" + ptr + " 값 " + abbreviate(exp.toString()) + " → " + abbreviate(act.toString()));
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    // ─── 요청 · 고정 자료 ──────────────────────────────────────────────────────

    /** JDK {@code HttpClient}(판 무관)로 부른다. 응답 = {sn · method · path · status · bodyKind · body}. */
    private ObjectNode call(Target target) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + target.path()))
                .timeout(Duration.ofSeconds(30));
        if (target.withToken()) {
            req.header("Authorization", "Bearer " + API_KEY);
        }
        if (target.body() != null) {
            req.header("Content-Type", "application/json");
            req.method(target.method(), HttpRequest.BodyPublishers.ofString(target.body(), StandardCharsets.UTF_8));
        } else {
            req.method(target.method(), HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ObjectNode node = JSON.createObjectNode();
        node.put("sn", target.sn());
        node.put("method", target.method());
        node.put("path", target.path());
        node.put("status", res.statusCode());
        JsonNode parsed = null;
        String text = res.body() == null ? "" : res.body();
        if (!text.isBlank()) {
            try {
                parsed = JSON.readTree(text);
            } catch (IOException ignored) {
                parsed = null; // JSON 이 아니면 글자 그대로 담는다
            }
        }
        if (parsed != null && !parsed.isMissingNode()) {
            node.put("bodyKind", "json");
            node.set("body", parsed);
        } else {
            node.put("bodyKind", "text");
            node.put("body", text);
        }
        return node;
    }

    private static String traceId(int i) {
        return String.format("5a75%028x", i);
    }

    private static String spanId(int trace, int index) {
        return String.format("5a75%04x%08x", trace, index);
    }

    /**
     * 고정 자료 — trace 3 · span 9(trace 마다 뿌리 1 + 자식 2) · payload 2(그중 마스킹이 반응하는 것 1) · 서비스 1 ·
     * 시각 고정. 둘째 trace 는 오류다. 속성에는 문자열 · 정수 · 소수 · 참거짓 · SQL 원문이 섞여 있다.
     */
    static String fixtureBody() {
        StringBuilder sb = new StringBuilder("{\"spans\":[");
        for (int t = 1; t <= 3; t++) {
            long start = T0 + (t - 1) * 60_000L;
            String status = t == 2 ? "ERROR" : "OK";
            if (t > 1) {
                sb.append(',');
            }
            sb.append(span(t, 0, null, "GET /orders/" + t, "SERVER", start, start + 120, status,
                    "{\"http.method\":\"GET\",\"http.status_code\":" + (t == 2 ? 500 : 200)
                            + ",\"http.route\":\"/orders/{id}\",\"sampled\":true}",
                    t == 1 ? "[{\"direction\":\"IN\",\"contentType\":\"application/json\","
                            + "\"body\":\"{\\\"user\\\":\\\"kim\\\",\\\"password\\\":\\\"hunter2\\\"}\","
                            + "\"sizeBytes\":38,\"truncated\":false},"
                            + "{\"direction\":\"OUT\",\"contentType\":\"application/json\","
                            + "\"body\":\"{\\\"orderId\\\":1,\\\"total\\\":12.5}\","
                            + "\"sizeBytes\":27,\"truncated\":false}]"
                            : null));
            sb.append(',');
            sb.append(span(t, 1, spanId(t, 0), "OrderService.find", "INTERNAL", start + 10, start + 50, "OK",
                    "{\"code.function\":\"find\",\"ratio\":0.25}", null));
            sb.append(',');
            sb.append(span(t, 2, spanId(t, 1), "SELECT orders", "DB", start + 60, start + 100, status,
                    "{\"db.system\":\"sqlite\",\"db.statement\":\"SELECT * FROM orders WHERE id = ?\",\"rows\":" + t
                            + "}", null));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String span(int trace, int index, String parent, String op, String kind, long start, long end,
                               String status, String attributes, String payloads) {
        return "{\"spanId\":\"" + spanId(trace, index) + "\",\"traceId\":\"" + traceId(trace) + "\","
                + "\"parentSpanId\":" + (parent == null ? "null" : "\"" + parent + "\"") + ","
                + "\"serviceName\":\"" + SERVICE + "\",\"operationName\":\"" + op + "\",\"spanKind\":\"" + kind + "\","
                + "\"startTime\":" + start + ",\"endTime\":" + end + ",\"status\":\"" + status + "\","
                + "\"attributes\":" + attributes + ",\"payloads\":" + (payloads == null ? "[]" : payloads) + "}";
    }

    /** SN-R30-17 — 원시 필드 하나({@code startTime})에 {@code null}. 다른 trace 라 앞 대상 값에 안 닿는다(맨 끝 차례). */
    static String primitiveNullBody() {
        return "{\"spans\":[{\"spanId\":\"5a7500170000000a\",\"traceId\":\"" + traceId(17) + "\","
                + "\"parentSpanId\":null,\"serviceName\":\"snapshot-fail-svc\",\"operationName\":\"GET /null\","
                + "\"spanKind\":\"SERVER\",\"startTime\":null,\"endTime\":" + (T0 + 10) + ",\"status\":\"OK\","
                + "\"attributes\":{},\"payloads\":[]}]}";
    }

    /** SN-R30-18 — 올바른 본문 끝에 잉여 토큰 하나. */
    static String trailingTokenBody() {
        return "{\"spans\":[{\"spanId\":\"5a7500180000000a\",\"traceId\":\"" + traceId(18) + "\","
                + "\"parentSpanId\":null,\"serviceName\":\"snapshot-fail-svc\",\"operationName\":\"GET /trailing\","
                + "\"spanKind\":\"SERVER\",\"startTime\":" + T0 + ",\"endTime\":" + (T0 + 10) + ",\"status\":\"OK\","
                + "\"attributes\":{},\"payloads\":[]}]} 1";
    }
}
