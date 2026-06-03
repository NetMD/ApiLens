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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase H] BT-11 — AgentOptionBuilder 단위 테스트 (Q-08 cross-stack parity).
 *
 * <p>사용자 명시 결정. CLAUDE.md 'docs/agent-options.md' 인용 (옵션 키 명세 단일 출처).
 *
 * <p>BE helper 와 FE helper ({@code apilens-ui/src/lib/agent-option-builder.ts}) 는 동일 입력
 * → 동일 출력 문자열 (golden output) 을 만들어야 한다. 본 테스트의 golden output 은
 * FE {@code agent-option-builder.test.ts} 와 정확 일치해야 한다.
 *
 * <p>[Phase H 후속 R10] AC-05-5 / AC-05-7: Design §0.1 D-H10-01 verbatim — "wizard 가
 * 신규 endpoint `GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에
 * 직접 박음" (비협상). 시그니처 5번째 파라미터 {@code agentJarPath} 추가 — 기존 7
 * 메서드의 caller 5 인자로 갱신 + 신규 3 메서드 추가 (Q-08 parity row 2).
 *
 * <p>[Phase H 후속 R10] 단위 테스트명 정방향 동사 의무 (EXT-005 첫 발동):
 * <ul>
 *   <li>{@code buildsCorrectStringWith*} (정방향 — 정상 입력 → 정확 golden 문자열)</li>
 *   <li>{@code buildsFallbackString*} (정방향 — null/blank → fallback 동일 출력)</li>
 * </ul>
 * 기존 {@code throwsOn*} 메서드는 유효성 검사 (validation) 영역으로 R9 본체 보존
 * (BE-FAIL-01 패턴 아님 — 사용자 명시 throw 의무 영역).
 *
 * <p>케이스 수: 기존 7 + 신규 3 = 10 메서드.
 * 신규 3 케이스 (Design §6.4.3 + §10 row 2):
 * <ol>
 *   <li>agentJarPath 절대경로 주입 → -javaagent: 토큰에 박힘</li>
 *   <li>agentJarPath = null → FALLBACK_JAR_PATH 사용 (BE/FE 동일 분기)</li>
 *   <li>agentJarPath = blank ("   ") → FALLBACK_JAR_PATH 사용</li>
 * </ol>
 */
class AgentOptionBuilderTest {

    // golden output — FE agent-option-builder.test.ts 와 token-for-token 일치 의무
    private static final String GOLDEN_DEFAULT =
            "-javaagent:/path/to/apilens-agent.jar"
                    + " -Dapilens.service.name=my-api"
                    + " -Dapilens.server=http://localhost:8765"
                    + " -Dapilens.jdbc.capture-params=true"
                    + " -Dapilens.jdbc.capture-result-set=false";

    private static final String GOLDEN_PARAMS_OFF_RESULTSET_ON =
            "-javaagent:/path/to/apilens-agent.jar"
                    + " -Dapilens.service.name=payment-svc"
                    + " -Dapilens.server=https://apilens.example.com:8765"
                    + " -Dapilens.jdbc.capture-params=false"
                    + " -Dapilens.jdbc.capture-result-set=true";

    // [Phase H 후속 R10] AC-05-7 — Q-08 parity row 2 신규 golden output (agentJarPath 절대경로 주입).
    private static final String GOLDEN_WITH_EXTRACTED_PATH =
            "-javaagent:/Users/foo/.apilens/apilens-agent.jar"
                    + " -Dapilens.service.name=my-api"
                    + " -Dapilens.server=http://localhost:8765"
                    + " -Dapilens.jdbc.capture-params=true"
                    + " -Dapilens.jdbc.capture-result-set=false";

    // ─── Case 1: 정상 입력 (params ON, resultSet OFF) ───────────────────────

    @Test
    void buildsCorrectStringForDefaultOptions() {
        // [Phase H 후속 R10] AC-05-6: agentJarPath=null → FALLBACK_JAR_PATH 사용,
        // golden output 보존 (R9 본체 동일 결과).
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", true, false, null);
        assertEquals(GOLDEN_DEFAULT, result);
    }

    // ─── Case 2: serviceName 빈 문자열 → throw ────────────────────────────

    @Test
    void throwsOnBlankServiceName() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("", "http://x:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("   ", "http://x:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build(null, "http://x:8765", true, false, null));
    }

    @Test
    void throwsOnInvalidServiceNameFormat() {
        // 공백 / 한글 / 특수문자 등 정규식 위반
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my api", "http://x:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my!api", "http://x:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("서비스", "http://x:8765", true, false, null));
    }

    // ─── Case 3: serverUrl http(s):// 없음 → throw ────────────────────────

    @Test
    void throwsOnInvalidServerUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my-api", "localhost:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my-api", "ftp://x:8765", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my-api", "", true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptionBuilder.build("my-api", null, true, false, null));
    }

    // ─── Case 4: captureParams=false, captureResultSet=true 토글 표기 ─────

    @Test
    void buildsCorrectStringForParamsOffResultSetOn() {
        String result = AgentOptionBuilder.build(
                "payment-svc", "https://apilens.example.com:8765", false, true, null);
        assertEquals(GOLDEN_PARAMS_OFF_RESULTSET_ON, result);
    }

    // ─── 추가: 모든 boolean 조합 표기 정확 ─────────────────────────────────

    @Test
    void bothFlagsTrue() {
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", true, true, null);
        // 동일 토큰 순서 + true/true 표기
        assertEquals(
                "-javaagent:/path/to/apilens-agent.jar"
                        + " -Dapilens.service.name=my-api"
                        + " -Dapilens.server=http://localhost:8765"
                        + " -Dapilens.jdbc.capture-params=true"
                        + " -Dapilens.jdbc.capture-result-set=true",
                result);
    }

    @Test
    void bothFlagsFalse() {
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", false, false, null);
        assertEquals(
                "-javaagent:/path/to/apilens-agent.jar"
                        + " -Dapilens.service.name=my-api"
                        + " -Dapilens.server=http://localhost:8765"
                        + " -Dapilens.jdbc.capture-params=false"
                        + " -Dapilens.jdbc.capture-result-set=false",
                result);
    }

    // ─── [R10] 신규 Case A: agentJarPath 절대경로 주입 → 토큰에 박힘 ─────────

    /**
     * [Phase H 후속 R10] AC-05-7 (Design §6.4.3 verbatim — "agentJarPath 절대경로
     * 주입 시 -javaagent: 토큰에 박힘") (비협상). Q-08 parity row 2 첫 케이스.
     */
    @Test
    void buildsCorrectStringWithExtractedAgentJarPath() {
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", true, false,
                "/Users/foo/.apilens/apilens-agent.jar");
        assertEquals(GOLDEN_WITH_EXTRACTED_PATH, result);
    }

    // ─── [R10] 신규 Case B: agentJarPath=null → FALLBACK 사용 ───────────────

    /**
     * [Phase H 후속 R10] AC-05-6 (Design §6.4.3 verbatim — "null fallback. golden
     * output = 기존 GOLDEN_DEFAULT") (비협상). BE/FE 동일 분기 (Q-08 parity).
     */
    @Test
    void buildsFallbackStringWhenAgentJarPathIsNull() {
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", true, false, null);
        assertEquals(GOLDEN_DEFAULT, result);
        assertTrue(result.startsWith("-javaagent:/path/to/apilens-agent.jar"),
                "fallback path token must be FALLBACK_JAR_PATH");
    }

    // ─── [R10] 신규 Case C: agentJarPath=blank → FALLBACK 사용 ──────────────

    /**
     * [Phase H 후속 R10] AC-05-6 (Design §6.4.3 verbatim — "blank fallback. golden
     * output = 기존 GOLDEN_DEFAULT") (비협상). BE/FE 동일 분기 (Q-08 parity).
     */
    @Test
    void buildsFallbackStringWhenAgentJarPathIsBlank() {
        String result = AgentOptionBuilder.build(
                "my-api", "http://localhost:8765", true, false, "   ");
        assertEquals(GOLDEN_DEFAULT, result);
        assertTrue(result.startsWith("-javaagent:/path/to/apilens-agent.jar"),
                "fallback path token must be FALLBACK_JAR_PATH");
    }
}
