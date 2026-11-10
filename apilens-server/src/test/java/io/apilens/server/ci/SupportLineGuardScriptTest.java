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
package io.apilens.server.ci;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * [Phase R32] R32/AC-R32-04-3 — 지원 라인 가드 대본({@code scripts/check-support-line.sh})의 경계 세 날짜를 실제
 * {@code bash} 로 돌려 출력 한 줄을 글자 그대로 단언한다.
 *
 * <p>AC-R32-04-3(보충) 원문: "{@code scripts/check-support-line.sh} 경계 세 날짜(경계 앞 · 당일 · 뒤)마다 기대 출력을 단언하는
 * 자동 시험 · 세 경우가 실행됨 · 그 시험이 도는 자리({@code ./gw test} · CI 단계 중)는 기술 설계(STEP 8)가 적음".
 * 도는 자리 = 서버 시험 작업({@code ./gw test} · CI Backend 두 잡) — 워크플로에 단계를 더하지 않는다.
 *
 * <p>카탈로그는 시험이 만든 임시 파일({@code springBoot = "4.1.2"} 한 줄)이다 — 실제 카탈로그 판이 바뀌어도 경계가 안 흔들린다.
 * 대본의 비교는 엄격 큼이라 끝나는 날 당일은 지원 안이다.
 *
 * <p>★한계 그 자리에: 시험 작업 폴더가 모듈 폴더({@code apilens-server})라는 Gradle 기본값에 기댄다(대본 경로
 * {@code ../scripts/…}). 기본값이 바뀌면 전제 단언(대본 실재)이 먼저 빨개진다. 대본 안 지원 기간 표의 값이 맞는지는
 * 이 시험이 보지 않는다 — 표의 4.1 끝 날짜를 고치면 기대 글자도 함께 고친다.
 */
class SupportLineGuardScriptTest {

    /** 시험 작업 폴더(모듈 폴더) 기준 대본 경로. */
    private static final Path SCRIPT = Path.of("..", "scripts", "check-support-line.sh");

    private static final String NOTICE =
            "::notice title=support-line::spring-boot 4.1.2 within OSS support until 2027-07-31";

    private static final String WARNING =
            "::warning title=support-line::spring-boot 4.1.2 — OSS support ended 2027-07-31";

    @TempDir
    Path tempDir;

    /** 경계 앞 · 당일 · 뒤. */
    static Stream<Arguments> boundaryDates() {
        return Stream.of(
                Arguments.of("2027-07-30", NOTICE),
                Arguments.of("2027-07-31", NOTICE),
                Arguments.of("2027-08-01", WARNING));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("boundaryDates")
    void printsTheExpectedLineAroundTheEndDate(String today, String expectedLine) throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(SCRIPT),
                "전제: 시험 작업 폴더가 모듈 폴더라 대본이 ../scripts 에 있다 — 작업 폴더 " + Path.of("").toAbsolutePath());
        Path catalog = tempDir.resolve("libs.versions.toml");
        Files.writeString(catalog, "springBoot = \"4.1.2\"\n", StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("bash", SCRIPT.toString(), today, catalog.toString()).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("전제: bash 로 대본이 5 초 안에 끝난다 — " + today);
        }
        // 출력에 줄표(U+2014)가 있어 플랫폼 기본 문자셋이 아니라 UTF-8 로 읽는다.
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), "대본은 막지 않는다 — 종료 코드 0 · 실제 " + process.exitValue());
        assertEquals("", stderr, "표준 오류는 비어 있다 — 실제 " + stderr);
        List<String> lines = stdout.lines().toList();
        assertEquals(1, lines.size(), "표준 출력은 정확히 한 줄 — 실제 " + lines);
        assertEquals(expectedLine, lines.get(0), "경계 " + today + " 의 줄 글자 그대로");
    }
}
