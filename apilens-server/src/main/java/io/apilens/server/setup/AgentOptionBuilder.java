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

import java.util.regex.Pattern;

/**
 * Pure helper for building the JVM {@code -javaagent:} argument shown in the
 * setup wizard's Step 4.
 *
 * <p>[Phase H] AC-06-4 — Q-08 cross-stack parity. 사용자 명시 결정.
 * CLAUDE.md 'docs/agent-options.md' 와 정합 (옵션 키 명세 단일 출처).
 *
 * <p>[Phase H 후속 R10] AC-05-5 / AC-05-6: Design §0.1 D-H10-01 verbatim — "wizard 가
 * 신규 endpoint `GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에
 * 직접 박음" (비협상). 시그니처에 5번째 파라미터 {@code agentJarPath} 추가.
 * null/blank 시 {@link #FALLBACK_JAR_PATH} 사용 (NFR-02 정합).
 *
 * <p>This helper MUST produce a string token-for-token identical to
 * {@code apilens-ui/src/lib/agent-option-builder.ts} {@code buildAgentOption()}.
 * Both helpers share the same fixture cases (golden output) — see
 * {@code AgentOptionBuilderTest} on the BE side and {@code agent-option-builder.test.ts}
 * on the FE side. 양측 상수명 통일: {@code FALLBACK_JAR_PATH}.
 *
 * <p>Token order (fixed):
 * <ol>
 *   <li>{@code -javaagent:<jarPath>}</li>
 *   <li>{@code -Dapilens.service.name=<serviceName>}</li>
 *   <li>{@code -Dapilens.server=<serverUrl>}</li>
 *   <li>{@code -Dapilens.jdbc.capture-params=<true|false>}</li>
 *   <li>{@code -Dapilens.jdbc.capture-result-set=<true|false>}</li>
 * </ol>
 *
 * <p>Joined with a single ASCII space.
 *
 * <p>[Phase H 후속 R10] 회귀 가드 grep (Design §9 F-R10-05-3 + §10.2):
 * <ul>
 *   <li>정방향: {@code grep -E "String\s+agentJarPath\)"} ≥ 1 hit</li>
 *   <li>정방향: {@code grep -E "FALLBACK_JAR_PATH\s*=\s*\"/path/to/apilens-agent\.jar\""} 정확 1 hit</li>
 *   <li>정방향: {@code grep -E "agentJarPath\s*==\s*null"} ≥ 1 hit (fallback 분기)</li>
 *   <li>반대 (lock-in 금지): {@code grep -E "AGENT_JAR_PATH\s*=\s*\"/path/to"} 0 hit (BE-FAIL-01 회피)</li>
 * </ul>
 */
public final class AgentOptionBuilder {

    /**
     * Fallback placeholder when agentJarPath is null/blank (NFR-02 정합).
     *
     * <p>[Phase H 후속 R10] AC-05-6 — FE FALLBACK_JAR_PATH 와 token-for-token 동일
     * (Q-08 cross-stack parity 의무).
     */
    static final String FALLBACK_JAR_PATH = "/path/to/apilens-agent.jar";

    /** 영문/숫자/하이픈/언더스코어 only — SetupService 와 동일 규약. */
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private AgentOptionBuilder() {
        // util class
    }

    /**
     * Build the agent option string from the 5 wizard inputs.
     *
     * <p>[Phase H 후속 R10] AC-05-5 (Design §0.1 D-H10-01 verbatim — "agentJarPath
     * 5번째 파라미터 추가") (비협상). null/blank 시 {@link #FALLBACK_JAR_PATH} 사용.
     *
     * <p>Q-08 cross-stack parity — FE {@code buildAgentOption(input)} 와
     * token-for-token 동일 (Design §10 row 2).
     *
     * @param serviceName  service name (영문/숫자/하이픈/언더스코어)
     * @param serverUrl    server URL (http:// or https:// prefix 필수)
     * @param captureParams  capture JDBC bound parameters flag
     * @param captureResultSet  capture ResultSet rows flag
     * @param agentJarPath  자동 추출된 agent jar 절대경로 (null/blank → fallback)
     * @throws IllegalArgumentException if serviceName / serverUrl 형식 위반
     */
    public static String build(String serviceName, String serverUrl,
                                boolean captureParams, boolean captureResultSet,
                                String agentJarPath) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("serviceName format invalid");
        }
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("serverUrl is required");
        }
        if (!(serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("serverUrl must start with http:// or https://");
        }

        // [Phase H 후속 R10] AC-05-6: null/blank fallback (NFR-02 정합).
        // FE 도 동일 분기 (Q-08 parity 의무) — FALLBACK_JAR_PATH token 양측 동일.
        String jarPath = (agentJarPath == null || agentJarPath.isBlank())
                ? FALLBACK_JAR_PATH
                : agentJarPath;

        // ⚠️ -D 키는 agent 가 실제로 읽는 키와 반드시 일치해야 한다 (SSOT):
        //    apilens-agent AgentConfig.java (PROP_SERVER / PROP_CAPTURE_PARAMS /
        //    PROP_CAPTURE_RESULT_SET) + docs/agent-options.md. 키가 틀리면 agent 가
        //    옵션을 조용히 무시하고 default 로 떨어진다 (예: server URL → localhost).
        //    Q-08 parity(FE==BE)만으로는 이 불일치를 못 잡는다 — 양쪽이 똑같이 틀릴 수 있음.
        return String.join(" ",
                "-javaagent:" + jarPath,
                "-Dapilens.service.name=" + serviceName,
                "-Dapilens.server=" + serverUrl,
                "-Dapilens.jdbc.capture-params=" + captureParams,
                "-Dapilens.jdbc.capture-result-set=" + captureResultSet
        );
    }
}
