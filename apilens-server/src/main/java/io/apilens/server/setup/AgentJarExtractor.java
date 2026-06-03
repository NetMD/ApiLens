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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the embedded {@code apilens-agent.jar} resource to a stable absolute
 * path under the user's home directory at server startup.
 *
 * <p>[Phase H 후속 R10] AC-05-1 / AC-05-2 / AC-05-3: Design §0.1 D-H10-01 verbatim
 * — "ApiLens server 가 startup 시 임베드된 `resources/main/agent/apilens-agent.jar` 를
 * 사용자 home 의 `~/.apilens/apilens-agent.jar` 로 자동 추출 → wizard 가 신규 endpoint
 * `GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에 직접 박음"
 * (사용자 명시 비협상 결정). CLAUDE.md "Agent 자체 장애가 호스트 앱에 영향 0" 인용.
 *
 * <p>Resource location (apilens-server/build.gradle.kts:35-40 정합):
 * {@code classpath:/agent/apilens-agent.jar}. Target location:
 * {@code ${user.home}/.apilens/apilens-agent.jar} (override via property
 * {@code apilens.agent.jar.target-dir}).
 *
 * <p>[Phase H 후속 R10] 회귀 가드 grep (Design §9 F-R10-05-1):
 * <ul>
 *   <li>정방향: {@code grep -E "class\s+AgentJarExtractor" } 정확 1 hit</li>
 *   <li>정방향: {@code grep "implements\s+ApplicationRunner" } 정확 1 hit</li>
 *   <li>정방향: {@code grep "getResourceAsStream" } ≥ 1 hit</li>
 *   <li>정방향: {@code grep "extractedPath\s*=\s*null" } ≥ 2 hit (silent fallback 2 경로)</li>
 *   <li>반대 (lock-in 금지): {@code grep "throw new RuntimeException" } 0 hit
 *       (NFR-02 정합 — 추출 실패 시 server boot abort 금지)</li>
 * </ul>
 *
 * <p>NFR-02 — silent log warning + extractedPath=null on any failure. Server boot
 * MUST NOT abort. Wizard fallback path covers the null case.
 */
@Component
public class AgentJarExtractor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentJarExtractor.class);

    /** Embedded resource path (build.gradle.kts:38-39 정합). */
    static final String EMBEDDED_RESOURCE = "/agent/apilens-agent.jar";

    /** Target jar filename (build.gradle.kts:39 rename 결과 정합). */
    private static final String TARGET_FILENAME = "apilens-agent.jar";

    private final String targetDir;

    /** Holds the absolute path of the extracted jar, or {@code null} on failure. */
    private volatile String extractedPath = null;

    public AgentJarExtractor(
            @Value("${apilens.agent.jar.target-dir:#{systemProperties['user.home'] + '/.apilens'}}")
            String targetDir) {
        this.targetDir = targetDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        // [Phase H 후속 R10] AC-05-1 / AC-05-2: silent failure + extractedPath=null
        // on any IOException (NFR-02 정합 — server boot abort 금지).
        try {
            Path targetDirPath = Paths.get(targetDir);
            Files.createDirectories(targetDirPath);
            Path targetFile = targetDirPath.resolve(TARGET_FILENAME);

            // [Phase H 후속 R10] AC-05-3: getResourceAsStream 사용
            // (CLAUDE.md "Build 설정 lessons §1 shadow jar relocate 함정 회피").
            // project(":apilens-agent") 의존성 직접 참조 0 — classpath resource 만 읽음.
            try (InputStream in = openEmbeddedResource()) {
                if (in == null) {
                    log.warn("ApiLens agent jar not embedded (classpath:{}). "
                            + "Run ':apilens-agent:shadowJar' then ':apilens-server:processResources' "
                            + "before bootRun. Wizard Step 4 will show fallback placeholder.",
                            EMBEDDED_RESOURCE);
                    this.extractedPath = null;
                    return;
                }
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            this.extractedPath = targetFile.toAbsolutePath().toString();
            log.info("ApiLens agent jar extracted: {}", this.extractedPath);
        } catch (IOException e) {
            // [Phase H 후속 R10] AC-05-2: server 기동 정상 진행, wizard fallback 분기 진입.
            log.warn("Failed to extract ApiLens agent jar (target={}): {}. "
                    + "Wizard Step 4 will show fallback placeholder.",
                    targetDir, e.getMessage());
            this.extractedPath = null;
        }
    }

    /**
     * Returns the extracted jar's absolute path, or {@code null} on failure.
     *
     * <p>[Phase H 후속 R10] AC-05-4: Design §6.3 verbatim — "path=null 도 HTTP 200
     * (404 아님). 추출 실패는 silent log warning + extractedPath=null." (비협상).
     */
    public String getExtractedPath() {
        return this.extractedPath;
    }

    /**
     * Opens an {@link InputStream} to the embedded agent jar resource.
     * Package-private + non-final so that unit tests may override to simulate
     * missing-resource scenarios (silent fallback verification).
     *
     * <p>Returns {@code null} when the resource is absent (default
     * {@link Class#getResourceAsStream(String)} contract).
     */
    InputStream openEmbeddedResource() {
        return AgentJarExtractor.class.getResourceAsStream(EMBEDDED_RESOURCE);
    }
}
