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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        if (expStatus != actStatus && !volatilePointers.contains(sn + ":#status")) {
            if (ingestFailureTarget && INGEST_FAILURE_MODE_CHANGE_ALLOWED
                    && actStatus >= 400 && actStatus < 500) {
                return diffs; // AL-R30-2 · 3 — 실패 모드 변경(4xx) 허용 · 본문은 Boot 오류 본문이라 맞추지 않는다
            }
            diffs.add(sn + ":#status " + expStatus + " → " + actStatus);
            return diffs;
        }
        JsonNode expBody = expected.get("body");
        JsonNode actBody = actual.get("body");
        if ("SN-R30-13".equals(sn)) {
            expBody = withPlaceholderVersion(expBody);
            actBody = withPlaceholderVersion(actBody);
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
