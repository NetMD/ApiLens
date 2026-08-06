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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * [Phase R12] T-D1 — wizard 옵션 키 골든테스트의 SSOT 실참조 (FR-D1 / V-04, Design §9.1).
 *
 * <p>// [Phase R12] FR-D1 (DG-02, 사용자 명시 결정): SSOT = {@code AgentConfig.java} **소스 텍스트**
 * // (PROP_* 상수 리터럴). docs/agent-options.md 는 untrack(.gitignore:47)이라 공개 CI checkout 에
 * // 부재 → docs 를 1차 SSOT 로 잡으면 CI 영구 실패 (Design §9.1 근거 verbatim).
 *
 * <p>구조 요건: <b>참조를 끊으면 테스트가 깨진다</b> — AgentConfig.java 파일 부재/이동 시 본 테스트가
 * 즉시 실패한다. FE==BE parity 단독 비교는 금지 (양쪽이 똑같이 틀릴 수 있음 — R5 교훈):
 * 본 테스트는 검증의 한쪽 끝을 agent 코드 진실({@code System.getProperty} 가 실제로 읽는 키)에 닿게 한다.
 *
 * <p>FE 짝: {@code apilens-ui/src/test/agent-option-builder.test.ts} (FT-D1) 가 동일 패턴으로
 * 같은 소스 파일을 읽는다 — 양끝 모두 SSOT 직접 대조.
 */
class AgentOptionSsotParityTest {

    /**
     * Gradle test workingDir = apilens-server 모듈 디렉토리 — 모노레포 형제 모듈 상대 경로.
     * 이 경로가 깨지면 (파일 이동/rename) 본 테스트가 즉시 실패하는 것이 의도된 동작이다 (V-04).
     */
    private static final Path AGENT_CONFIG_SOURCE =
            Path.of("../apilens-agent/src/main/java/io/apilens/agent/config/AgentConfig.java");

    /** 로컬 보조 대조 대상 — untrack 이라 CI 에선 skip (assumeTrue), 1차 SSOT 강제는 위 소스가 항상 수행. */
    private static final Path AGENT_OPTIONS_DOCS = Path.of("../docs/agent-options.md");

    /** AgentConfig 의 {@code public static final String PROP_X = "apilens...";} 리터럴 추출. */
    private static final Pattern PROP_LITERAL = Pattern.compile("PROP_\\w+\\s*=\\s*\"([^\"]+)\"");

    /** wizard 출력 토큰 {@code -D<key>=<value>} 의 key 추출. */
    private static final Pattern DASH_D_KEY = Pattern.compile("-D([^=\\s]+)=");

    // ─── 1차 SSOT: AgentConfig.java 소스 (CI 포함 항상 수행) ────────────────

    @Test
    void readsPropKeysFromAgentConfigSourceAsSsot() throws IOException {
        Set<String> ssotKeys = extractSsotKeys();

        // agent 가 실제 읽는 키 4종이 소스에서 추출돼야 한다 (Design §9.1 :61-77 실측 키 그대로)
        assertTrue(ssotKeys.contains("apilens.server"), "SSOT missing apilens.server: " + ssotKeys);
        assertTrue(ssotKeys.contains("apilens.service.name"), "SSOT missing apilens.service.name: " + ssotKeys);
        assertTrue(ssotKeys.contains("apilens.jdbc.capture-params"), "SSOT missing capture-params: " + ssotKeys);
        assertTrue(ssotKeys.contains("apilens.jdbc.capture-result-set"), "SSOT missing capture-result-set: " + ssotKeys);
        // [Phase R20] R20/AC-01-1 — 신규 키 SSOT 편입(docs 미갱신 자동 검출의 한쪽 끝).
        assertTrue(ssotKeys.contains("apilens.instrument.require-entry-root"),
                "SSOT missing require-entry-root: " + ssotKeys);
    }

    @Test
    void buildsDashDKeysThatAllExistInAgentConfigSsot() throws IOException {
        Set<String> ssotKeys = extractSsotKeys();

        String option = AgentOptionBuilder.build(
                "my-api", "http://apilens-host:8765", true, false, "/opt/apilens/apilens-agent.jar");

        Matcher m = DASH_D_KEY.matcher(option);
        int dashDCount = 0;
        while (m.find()) {
            dashDCount++;
            String key = m.group(1);
            // 핵심 단언: wizard 가 출력하는 -D 키는 agent 가 실제 읽는 키 집합의 부분집합.
            // 키가 틀리면 agent 가 옵션을 조용히 무시하고 default 로 떨어진다 (R5 trace 0건 버그 재발 방지).
            assertTrue(ssotKeys.contains(key),
                    "wizard emits -D" + key + " but AgentConfig.java does not read it. SSOT keys=" + ssotKeys);
        }
        assertTrue(dashDCount == 4, "wizard 옵션 -D 키는 4종 고정 (실제 " + dashDCount + ") — " + option);
    }

    // ─── 로컬 보조: docs/agent-options.md 표기 동기 (untrack — CI skip) ──────

    @Test
    void matchesDocsAgentOptionsKeySpellingWhenDocsPresent() throws IOException {
        assumeTrue(Files.exists(AGENT_OPTIONS_DOCS),
                "docs/agent-options.md untracked — CI checkout 에 없으면 skip (Design §9.1 명문)");

        String docs = Files.readString(AGENT_OPTIONS_DOCS);
        // [Phase R20] R20/AC-01-1 — 신규 키(require-entry-root) docs 표기 대조 편입(NFR-07 SSOT 대조).
        for (String key : Set.of("apilens.server", "apilens.service.name",
                "apilens.jdbc.capture-params", "apilens.jdbc.capture-result-set",
                "apilens.instrument.require-entry-root")) {
            assertTrue(docs.contains("`" + key + "`"),
                    "docs/agent-options.md 에 키 표기 누락/오기: " + key);
        }
        // AC-D1-2 짝: 옛 오기 키 변형(apilens.server.url / apilens.capture.*)이 문서에 재유입되지 않아야 한다
        assertFalse(docs.contains("apilens.server.url="), "옛 오기 키 apilens.server.url 재유입");
        assertFalse(docs.contains("apilens.capture.params"), "옛 오기 키 apilens.capture.params 재유입");
        assertFalse(docs.contains("apilens.capture.resultset"), "옛 오기 키 apilens.capture.resultset 재유입");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Set<String> extractSsotKeys() throws IOException {
        // 파일 부재 = 즉시 실패 (참조를 끊으면 테스트가 깨지는 구조 — Design §9.1 비협상 요건)
        assertTrue(Files.exists(AGENT_CONFIG_SOURCE),
                "SSOT source missing: " + AGENT_CONFIG_SOURCE.toAbsolutePath().normalize()
                        + " — agent 모듈 이동/rename 시 본 테스트와 FE FT-D1 을 함께 갱신할 것");
        String source = Files.readString(AGENT_CONFIG_SOURCE);
        Matcher m = PROP_LITERAL.matcher(source);
        Set<String> keys = new HashSet<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertFalse(keys.isEmpty(), "PROP_* 리터럴 추출 0건 — AgentConfig 상수 선언 형식 변경 여부 확인");
        return keys;
    }
}
