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

import io.apilens.server.setup.dto.SetupCompleteRequest;
import io.apilens.server.setup.dto.SetupCompleteResponse;
import io.apilens.server.setup.dto.SetupStateResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Setup wizard REST endpoints.
 *
 * <p>[Phase H] AC-06-1 / AC-06-2 — D-01 (첫 실행 wizard) / D-04 (skip 허용).
 * 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>[Phase H 후속 R10] AC-05-4: Design §0.1 D-H10-01 verbatim — "wizard 가 신규
 * endpoint `GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에 직접 박음"
 * (비협상). 신규 endpoint 추가 (3건).
 *
 * <ul>
 *   <li>{@code GET /v1/setup/state} — FirstRunGuard 라우팅 입력</li>
 *   <li>{@code POST /v1/setup/complete} — 멱등 완료 처리 (NFR-04)</li>
 *   <li>{@code GET /v1/setup/agent-jar-path} — [R10] 자동 추출된 agent jar 절대경로</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/setup")
public class SetupController {

    private final SetupService service;
    // [Phase H 후속 R10] AC-05-4 — AgentJarExtractor 주입 (Design §6.3 정합).
    private final AgentJarExtractor agentJarExtractor;

    public SetupController(SetupService service, AgentJarExtractor agentJarExtractor) {
        this.service = service;
        this.agentJarExtractor = agentJarExtractor;
    }

    @GetMapping("/state")
    public SetupStateResponse getState() {
        return service.getState();
    }

    @PostMapping("/complete")
    public SetupCompleteResponse complete(@RequestBody SetupCompleteRequest request) {
        return service.complete(request);
    }

    /**
     * Returns the absolute path of the auto-extracted agent jar, or
     * {@code { "path": null }} if extraction failed at server startup.
     *
     * <p>[Phase H 후속 R10] AC-05-4: Design §0.1 D-H10-01 verbatim — "wizard 가 신규
     * endpoint `GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에 직접 박음"
     * (비협상). NFR-02 정합 — path=null 도 HTTP 200 (404 아님).
     *
     * <p>[Phase H 후속 R10] 회귀 가드 grep (Design §9 F-R10-05-2):
     * <ul>
     *   <li>정방향: {@code grep -E "@GetMapping\(\"/agent-jar-path\"\)"} 정확 1 hit</li>
     *   <li>반대 (lock-in 금지): {@code grep "@ResponseStatus(HttpStatus.NOT_FOUND)"}
     *       의 path=null 분기 0 hit</li>
     *   <li>반대 (lock-in 금지): {@code grep "throw new.*Exception"} (getAgentJarPath 메서드 내) 0 hit</li>
     * </ul>
     *
     * <p>Response shape (Design §6.3.2 verbatim):
     * <pre>{@code
     * { "path": "/Users/foo/.apilens/apilens-agent.jar" }  // 정상
     * { "path": null }                                       // 추출 실패 (NFR-02 fallback)
     * }</pre>
     */
    @GetMapping("/agent-jar-path")
    public Map<String, Object> getAgentJarPath() {
        // Map.of 는 null 값 허용 안 함 → HashMap 사용 (Design §6.3 정합).
        Map<String, Object> result = new HashMap<>();
        result.put("path", agentJarExtractor.getExtractedPath());
        return result;
    }

    /**
     * Streams the embedded {@code apilens-agent.jar} as a browser download.
     *
     * <p>Complements {@code GET /v1/setup/agent-jar-path}: the path endpoint serves
     * the co-located case (server and target app share a filesystem), while this
     * download serves the <em>remote</em> case — e.g. ApiLens server runs on a NAS
     * and the operator opens the dashboard from a different machine, where an
     * absolute path on the server host is unusable. The operator downloads the jar,
     * copies it to the target app's host, and references that local path in
     * {@code -javaagent:}.
     *
     * <p>Reuses {@link AgentJarExtractor#openEmbeddedResource()} (the same embedded
     * source the startup extractor reads), so the resource location stays a single
     * source of truth ({@link AgentJarExtractor#EMBEDDED_RESOURCE}). No path
     * parameter is accepted — only this one fixed, known artifact is served, so
     * there is no path-traversal surface.
     *
     * <p>Returns {@code 404} when the jar is not embedded (agent shadowJar not built
     * into this server jar) — distinct from {@code agent-jar-path}, where the wizard
     * needs a {@code 200} to branch. A missing file to download is a genuine 404.
     *
     * <p>The whole jar (~12 MB) is buffered in memory per request via
     * {@link ByteArrayResource}; this is an occasional operator action, not a hot
     * path, and a known content length keeps the response clean.
     */
    @GetMapping("/agent-jar")
    public ResponseEntity<Resource> downloadAgentJar() throws IOException {
        byte[] jarBytes;
        try (InputStream in = agentJarExtractor.openEmbeddedResource()) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            jarBytes = in.readAllBytes();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"apilens-agent.jar\"")
                .contentLength(jarBytes.length)
                .body(new ByteArrayResource(jarBytes));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
