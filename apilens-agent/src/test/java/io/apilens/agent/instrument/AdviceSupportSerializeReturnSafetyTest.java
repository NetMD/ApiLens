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
package io.apilens.agent.instrument;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [R11] AC-F-R11-04 + AC-F-R11-06 (D-P0-02 비협상 — verbatim 인용)
 *   본질: agent file truncate side effect P0 회귀 영구 봉인.
 *         (1) mock Resource OutputStream getter 호출 0 hit 검증
 *         (2) 실제 임시 파일 + FileSystemResource → 파일 사이즈 보존 assertion
 *   회귀 가드 grep: 정방향 = 정방향 동사 (keepsZero* / returnsPlaceholder* / preserves*) /
 *                    반대방향 = `assertThrows(IOException` 0 hit + `assertThrows(RuntimeException` 0 hit +
 *                    반대 방향 동사 (throws* / rejects* / denies* / failsOn*) 0 hit
 *   CLAUDE.md 인용: "Agent 자체 장애가 호스트 앱에 영향 0 — 모든 agent 코드는
 *                    try-catch 로 감싸고 실패 시 silent drop"
 *
 * <p>D-P0-02 비협상 의무: CI 에 fs_usage 류 통합 테스트는 무리이므로 단위 테스트 2건으로
 * 회귀 영구 봉인. (1) mock Resource OutputStream getter 호출 0 hit 검증 +
 * (2) 실제 임시 파일 사이즈 보존 assertion. dev-backend.ApiLens EXT-005 lock-in
 * 가드: 정방향 동사 (preserves* / keepsZeroInvocations* / returnsPlaceholder*) 사용,
 * 반대 방향 동사 (throws* / rejects*) 0 hit 의무.
 */
class AdviceSupportSerializeReturnSafetyTest {

    @BeforeEach
    void setup() {
        // MAPPER 는 InstrumentationInstaller 의 static field — 본 테스트가 직접 초기화.
        // (production install() 과 동일 옵션 + ResourceMixIn 등록 — Layer 3 정합 검증)
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Class<?> resourceCls = Class.forName(
                    "org.springframework.core.io.Resource", false,
                    InstrumentationInstaller.class.getClassLoader());
            mapper.addMixIn(resourceCls, ResourceMixIn.class);
        } catch (ClassNotFoundException ignore) {
            // Spring 미존재 환경 — Layer 3 skip 정상 (test 자체는 진행 가능,
            // RT-P0-01/02 는 Spring 가용 가정 — apilens-agent testRuntime 에 spring-boot-starter-test 포함)
        }
        InstrumentationInstaller.MAPPER = mapper;
        InstrumentationInstaller.DEBUG = false;
    }

    @AfterEach
    void teardown() {
        InstrumentationInstaller.MAPPER = null;
    }

    // ─── RT-P0-01: Resource 영역 — Layer 1 / Layer 2 차단 ───────────────────

    /**
     * RT-P0-01 — FileSystemResource 직접 반환 (Layer 1 단독 차단 시나리오).
     * Layer 1 placeholder 반환 → Jackson writeValueAsString 호출 0 → 파일 사이즈 보존.
     *
     * <p>정방향 동사: keepsZeroOutputStreamInvocations* — EXT-005 lock-in 가드.
     */
    @Test
    void keepsZeroOutputStreamInvocationsForFileSystemResourceReturn(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("video.mp4");
        Files.write(tempFile, new byte[1024]);

        Object fileResource = newFileSystemResource(tempFile);
        String json = AdviceSupport.serializeReturn(fileResource);

        // Layer 1 placeholder 반환 (Jackson writeValueAsString 호출 0)
        assertNotNull(json);
        assertTrue(json.contains("streaming-body-skipped"),
                "Layer 1 must return placeholder for FileSystemResource");
        // 파일 사이즈 보존 — getOutputStream() 호출되었다면 0 byte 가 되었을 것
        assertEquals(1024, Files.size(tempFile),
                "host app file size must be preserved (Layer 1 차단 검증)");
    }

    /**
     * RT-P0-01 보강 — ResponseEntity<FileSystemResource> 시나리오 (Layer 2 차단).
     *
     * <p>정방향 동사: returnsPlaceholderForResponseEntity* — EXT-005 lock-in 가드.
     */
    @Test
    void returnsPlaceholderForResponseEntityWithFileSystemResourceBody(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("video.mp4");
        Files.write(tempFile, new byte[1024]);

        Object fileResource = newFileSystemResource(tempFile);
        Object responseEntity = newResponseEntity(fileResource);
        String json = AdviceSupport.serializeReturn(responseEntity);

        assertNotNull(json);
        assertTrue(json.contains("streaming-body-skipped"),
                "Layer 2 must replace FileSystemResource body with placeholder");
        assertEquals(1024, Files.size(tempFile),
                "host app file size must be preserved (Layer 2 차단 검증)");
    }

    /**
     * RT-P0-01-정상 — DTO record 직렬화 정상 케이스 (placeholder 안 박힘 회귀 검증).
     *
     * <p>정방향 동사: preservesNormalDtoSerialization* — EXT-005 lock-in 가드.
     */
    @Test
    void preservesNormalDtoSerializationWithoutPlaceholder() {
        Map<String, Object> dto = Map.of("id", 42, "name", "Alice");
        String json = AdviceSupport.serializeReturn(dto);

        assertNotNull(json);
        assertFalse(json.contains("streaming-body-skipped"),
                "normal DTO must serialize without placeholder (회귀 0)");
        assertTrue(json.contains("\"id\":42") || json.contains("\"id\" : 42"));
        assertTrue(json.contains("Alice"));
    }

    // ─── RT-P0-02: 실제 임시 파일 + FileSystemResource — 파일 사이즈 보존 ────

    /**
     * RT-P0-02 — 임시 파일 + FileSystemResource — serializeReturn 후 파일 사이즈 보존.
     *
     * <p>입력값 경계 [EXT-005 부분 적용]: 1024 byte (정상 보존) + 파일 내용 byte 단위 정합.
     * 회귀 검출 가설: P0 BUG 재발 시 파일 사이즈가 0 byte 로 잘림 → assertEquals(1024) 실패.
     *
     * <p>정방향 동사: preservesFileSizeAfterSerializeReturn* — EXT-005 lock-in 가드.
     */
    @Test
    void preservesFileSizeAfterSerializeReturnWithFileSystemResource(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("video.mp4");
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }
        Files.write(tempFile, payload);
        long sizeBefore = Files.size(tempFile);
        assertEquals(1024L, sizeBefore, "precondition: file written 1024 bytes");

        Object fileResource = newFileSystemResource(tempFile);
        AdviceSupport.serializeReturn(fileResource);

        long sizeAfter = Files.size(tempFile);
        assertEquals(sizeBefore, sizeAfter,
                "RT-P0-02: host app file size must equal input size after serializeReturn");
        byte[] readBack = Files.readAllBytes(tempFile);
        assertEquals(1024, readBack.length, "byte-level content length preserved");
        for (int i = 0; i < readBack.length; i++) {
            assertEquals(payload[i], readBack[i],
                    "byte-level content equality at index " + i);
        }
    }

    /**
     * RT-P0-02-arg — serializeArgs 의 FileSystemResource 인자 시나리오.
     *
     * <p>정방향 동사: preservesFileSizeAfterSerializeArgs* — EXT-005 lock-in 가드.
     */
    @Test
    void preservesFileSizeAfterSerializeArgsWithFileSystemResource(@TempDir Path tempDir) throws IOException {
        Path tempFile = tempDir.resolve("upload.bin");
        Files.write(tempFile, new byte[1024]);

        Object fileResource = newFileSystemResource(tempFile);
        String json = AdviceSupport.serializeArgs(new Object[]{ fileResource });

        assertNotNull(json);
        assertTrue(json.contains("[skipped:"),
                "serializeArgs must use [skipped:...] placeholder for FileSystemResource");
        assertEquals(1024, Files.size(tempFile),
                "host app file size must be preserved (serializeArgs Layer 1 차단 검증)");
    }

    // ─── 헬퍼: Spring Resource / ResponseEntity 생성 (reflection 미사용, direct API) ───

    private static Object newFileSystemResource(Path path) {
        // org.springframework.core.io.FileSystemResource 는 testRuntime 의존성으로 가용.
        // (apilens-agent build.gradle.kts testImplementation = spring-boot-starter-test 포함)
        return new org.springframework.core.io.FileSystemResource(path.toFile());
    }

    private static Object newResponseEntity(Object body) {
        return new org.springframework.http.ResponseEntity<>(body, org.springframework.http.HttpStatus.OK);
    }
}
