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
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase H 후속 R10] BE-2 단위 테스트 — SetupController.getAgentJarPath (2 케이스).
 *
 * <p>Design §6.3 verbatim: "(1) 추출 성공 시 절대경로 반환,
 * (2) 추출 실패 시 path=null 반환 (200)" (비협상 — NFR-02 정합).
 *
 * <p>[Phase H 후속 R10] 단위 테스트명 정방향 동사 의무 (EXT-005 첫 발동):
 * <ul>
 *   <li>{@code returnsAbsolutePathWhenExtractionSucceeded} (정방향 — 추출됨 → path 반환)</li>
 *   <li>{@code returnsNullPathWhenExtractionFailed} (정방향 — 추출 실패 → path=null, 200)</li>
 * </ul>
 * 반대 방향 패턴 (BE-FAIL-01 회피): assertThrows on getAgentJarPath 0 hit, 404 expectation 0 hit.
 */
class SetupControllerTest {

    /**
     * Case 1: 추출 성공 — getExtractedPath != null → 응답 path 키에 절대경로.
     *
     * <p>[Phase H 후속 R10] AC-05-4 (Design §6.3 verbatim — "추출 성공 시 절대경로 반환") (비협상).
     */
    @Test
    void returnsAbsolutePathWhenExtractionSucceeded() {
        // Given — 추출이 성공한 stub extractor
        String expected = "/Users/foo/.apilens/apilens-agent.jar";
        AgentJarExtractor extractor = new StubExtractor(expected);
        SetupController controller = new SetupController(null, extractor);

        // When
        Map<String, Object> response = controller.getAgentJarPath();

        // Then
        assertTrue(response.containsKey("path"), "response must contain 'path' key");
        assertEquals(expected, response.get("path"), "path must equal extracted absolute path");
    }

    /**
     * Case 2: 추출 실패 — getExtractedPath == null → 응답 path 키 = null, HTTP 200.
     *
     * <p>[Phase H 후속 R10] AC-05-4 (Design §6.3 verbatim — "추출 실패 시 path=null 반환
     * (200)" — NFR-02 fallback) (비협상).
     */
    @Test
    void returnsNullPathWhenExtractionFailed() {
        // Given — 추출이 실패한 stub extractor (extractedPath=null)
        AgentJarExtractor extractor = new StubExtractor(null);
        SetupController controller = new SetupController(null, extractor);

        // When — getAgentJarPath 는 throw 하지 않음 (HTTP 200 정합)
        Map<String, Object> response = controller.getAgentJarPath();

        // Then — path 키 존재 + 값 null. 404/500 분기 없음
        assertTrue(response.containsKey("path"), "response must contain 'path' key even when null");
        assertNull(response.get("path"), "path must be null when extraction failed");
    }

    /**
     * Case 3: 임베드된 agent jar 다운로드 — openEmbeddedResource() 가 stream 반환 →
     * 200 + attachment Content-Disposition + octet-stream + 본문 = jar 바이트.
     *
     * <p>[inter-pipeline] 원격(NAS) 시나리오 대응 다운로드 엔드포인트. server 와 대상 앱이
     * 다른 장비일 때 운영자가 브라우저로 jar 를 받을 수 있어야 함.
     */
    @Test
    void streamsAgentJarAsAttachmentWhenEmbedded() throws Exception {
        // Given — 임베드 resource 가 존재하는 stub (PK.. = zip magic 흉내)
        byte[] fakeJar = {'P', 'K', 0x03, 0x04, 1, 2, 3, 4, 5};
        SetupController controller = new SetupController(null, new StubExtractor(null, fakeJar));

        // When
        ResponseEntity<Resource> response = controller.downloadAgentJar();

        // Then — 200 + 첨부 파일명 + octet-stream + 본문 바이트 일치
        assertEquals(200, response.getStatusCode().value(), "embedded jar must return 200");
        assertEquals("attachment; filename=\"apilens-agent.jar\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                "must be a named file attachment so the browser downloads it");
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        Resource body = response.getBody();
        assertNotNull(body, "body must carry the jar bytes");
        assertArrayEquals(fakeJar, body.getInputStream().readAllBytes(),
                "downloaded bytes must equal the embedded jar");
    }

    /**
     * Case 4: agent jar 미임베드 — openEmbeddedResource() == null → 404 (다운로드할
     * 파일이 실제로 없음). agent-jar-path 의 path=null/200 분기와 의도적으로 다름.
     */
    @Test
    void returnsNotFoundWhenAgentJarNotEmbedded() throws Exception {
        // Given — 임베드 resource 없음 (agent shadowJar 미빌드 시나리오)
        SetupController controller = new SetupController(null, new StubExtractor(null, null));

        // When
        ResponseEntity<Resource> response = controller.downloadAgentJar();

        // Then — 404 + 본문 없음
        assertEquals(404, response.getStatusCode().value(),
                "a genuinely missing download is a 404, unlike agent-jar-path's 200/null");
        assertNull(response.getBody());
    }

    /**
     * Stub extractor — production AgentJarExtractor 의 getExtractedPath() /
     * openEmbeddedResource() 만 override. run() 호출 없이 직접 fixed value 반환.
     */
    private static final class StubExtractor extends AgentJarExtractor {
        private final String fixedPath;
        private final byte[] embeddedBytes;

        StubExtractor(String fixedPath) {
            this(fixedPath, null);
        }

        StubExtractor(String fixedPath, byte[] embeddedBytes) {
            super(System.getProperty("java.io.tmpdir"));
            this.fixedPath = fixedPath;
            this.embeddedBytes = embeddedBytes;
        }

        @Override
        public String getExtractedPath() {
            return fixedPath;
        }

        @Override
        InputStream openEmbeddedResource() {
            return embeddedBytes == null ? null : new ByteArrayInputStream(embeddedBytes);
        }
    }
}
