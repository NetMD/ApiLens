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
package io.apilens.server.instrument.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R21] R21/AC-01-1/AC-01-2/AC-01-3 — R20 원격 계측 config <b>기동 순서 봉인</b> 회귀 테스트.
 *
 * <p>R21/AC-01-1 verbatim: "값 = {@code AgentMain.java} 소스 텍스트에서 {@code RemoteConfigGate.init(}
 * 호출이 {@code senderThread.start()} 보다 앞. 성립 시점 = 기준점(-D 기동값 3필드)은 그 값을 읽는
 * 첫 소비자(202 응답 적용 경로를 여는 전송 스레드 시작)보다 먼저 설정된다. 검증 2축 = ①설정된 상태:
 * 테스트 GREEN ②설정 전 상태: 순서가 뒤집힌 입력에서 테스트가 RED 판정을 내는지 실증."
 *
 * <p>순서가 역전되면 무력화되는 것: 기준점이 static 기본값(전부 false)인 창이 생기고, requireEntryRoot
 * 축(true 가 줄이는 쪽)에서 server 의 false 지시가 "기동값 복귀"로 오판 적용된다 — 운영자가 {@code -D} 로
 * 켠 억제가 조용히 꺼지고, 기준점이 뒤늦게 잡힌 뒤에는 같은 지시가 정상 폐기되므로 self-healing
 * 재적용으로도 복구되지 않는다(JVM 수명 내내 — {@code AgentMain} 의 순서 봉인 주석 verbatim).
 *
 * <p><b>창 ① 만 단언한다</b> (R21/AC-01-3). 창 ②(install 의 기동값 대입이 전송 시작보다 뒤 — 안전한
 * 방향, 다음 202 에서 자동 복구)는 <b>여기는 일부러 단언하지 않는다</b> — 나중에 창 ②를 고칠 때
 * 이 테스트가 막지 않게.
 *
 * <p>판정은 <b>주석 제거 후</b> 수행한다 — {@code AgentMain} 의 순서 봉인 주석 안에
 * {@code senderThread.start()} 리터럴이 실제 호출보다 앞서 등장하므로, 소박한 indexOf 비교는 지금의
 * 올바른 코드를 RED 로 오판한다. RED 실증(검증 ②축)은 <b>워킹트리 무변형 방식</b> — 순서 반전 fixture
 * 문자열을 판정 함수에 입력한다. agent {@code src/main} 무접촉·원복 실수 위험 0 — 사용자 명시 비협상
 * 결정(agent 소스 diff 0)과 무충돌. 본 테스트 자리가 server 모듈인 것도 같은 봉인의 결과다
 * ("agent 테스트 개수 불변" 이 곧 agent 무변경 증거 — 212→213 이 아니다).
 *
 * <p>전례 계승: {@code AgentOptionSsotParityTest} — Gradle test workingDir = 모듈 디렉토리 기준
 * 형제 모듈 상대 경로로 소스를 읽고, 파일 부재 = 즉시 실패(참조를 끊으면 깨지는 구조).
 */
class RemoteConfigInitOrderSealTest {

    /**
     * Gradle test workingDir = apilens-server 모듈 디렉토리 — 모노레포 형제 모듈 상대 경로.
     * 파일 이동/rename 시 본 테스트가 즉시 실패하는 것이 의도된 동작이다.
     */
    static final Path AGENT_MAIN_SOURCE =
            Path.of("../apilens-agent/src/main/java/io/apilens/agent/AgentMain.java");

    /** 기준점 설정 호출 — {@code RemoteConfigGate.init(config)} (import 문에는 이 형태가 등장하지 않는다). */
    static final String INIT_TOKEN = "RemoteConfigGate.init(";

    /** 첫 소비자를 여는 호출 — 전송 스레드 시작 ({@code senderThread.join(...)} 과는 다른 토큰). */
    static final String START_TOKEN = "senderThread.start()";

    // ─── @Test 1 — 창 ① GREEN (검증 ①축: 실물 소스) ─────────────────────────

    /** 실물 AgentMain.java: 주석 제거 소스에서 init 이 전송 스레드 시작보다 앞이다. */
    @Test
    void initPrecedesSenderStartInAgentMainSource() throws IOException {
        assertTrue(Files.exists(AGENT_MAIN_SOURCE),
                "seal source missing: " + AGENT_MAIN_SOURCE.toAbsolutePath().normalize()
                        + " — agent 모듈 이동/rename 시 본 테스트를 함께 갱신할 것");
        String source = Files.readString(AGENT_MAIN_SOURCE);

        assertTrue(initPrecedesSenderStart(source),
                "순서 봉인 위반: RemoteConfigGate.init( 이 senderThread.start() 보다 뒤에 있다 — "
                        + "기준점 없는 창이 열리며 requireEntryRoot 오판 적용은 JVM 수명 내내 복구 불가");
    }

    // ─── @Test 2 — 검증 ②축: RED 실증 (워킹트리 무변형 — 반전 fixture) ────────

    /** 순서 반전 fixture(전송 시작이 init 보다 앞) → 판정이 RED(false)를 낸다 — 장치의 실효 증명. */
    @Test
    void reversedFixtureIsJudgedRed() {
        String reversed = """
                public final class AgentMain {
                    private static void init(Instrumentation instrumentation) {
                        Thread senderThread = new Thread(sender, "apilens-sender");
                        senderThread.start();
                        RemoteConfigGate.init(config);
                    }
                }
                """;

        assertFalse(initPrecedesSenderStart(reversed),
                "순서가 뒤집힌 입력을 GREEN 으로 판정하면 이 봉인 장치는 무력하다 (RED 실증 실패)");
    }

    // ─── @Test 3 — 주석 내성 (실물 순서 봉인 주석 함정의 고정 회귀 가드) ──────

    /** 주석에만 위장 토큰이 init 보다 앞서 등장하고 코드 순서는 올바른 fixture → GREEN 유지. */
    @Test
    void commentDecoyDoesNotFoolTheJudgement() {
        String decoy = """
                public final class AgentMain {
                    private static void init(Instrumentation instrumentation) {
                        // 주의: 이 호출이 senderThread.start() 뒤로 가면 기준점 없는 창이 생긴다 — 위장 토큰.
                        RemoteConfigGate.init(config);
                        Thread senderThread = new Thread(sender, "apilens-sender");
                        senderThread.start();
                    }
                }
                """;

        assertTrue(initPrecedesSenderStart(decoy),
                "주석 속 위장 토큰(실물 AgentMain 의 순서 봉인 주석과 동형)에 속으면 소박한 indexOf 오판 재발");
    }

    // ─── @Test 4 — 장치 자기 진단 (토큰 소멸 = 침묵 PASS 가 아니라 소리 나는 실패) ──

    /** 토큰이 없거나(0회) 중복되면(2회+) IllegalStateException — 리팩터링으로 토큰이 사라져도 침묵하지 않는다. */
    @Test
    void missingTokenFailsLoudly() {
        assertThrows(IllegalStateException.class,
                () -> initPrecedesSenderStart("public final class AgentMain { }"),
                "토큰 0회 등장은 판정 불가로 소리 나게 실패해야 한다");

        String duplicated = """
                class AgentMain {
                    void a() { RemoteConfigGate.init(config); senderThread.start(); }
                    void b() { senderThread.start(); }
                }
                """;
        assertThrows(IllegalStateException.class,
                () -> initPrecedesSenderStart(duplicated),
                "토큰 2회 이상 등장은 판정 모호로 소리 나게 실패해야 한다");
    }

    // ─── 판정 로직 (package-private 순수 함수 — 반전 fixture 입력의 기반) ─────

    /**
     * 창 ① 판정: 주석 제거 소스에서 {@code RemoteConfigGate.init(} 이 {@code senderThread.start()} 보다
     * 앞인가. 주석 제거 = 줄 단위 필터(trim 이 "//", "*", "/*" 로 시작하는 줄 제거) — AgentMain 순서 봉인
     * 주석 속 위장 토큰을 걸러낸다. 꼬리 주석은 제거하지 않는 대신, 각 토큰이 코드에서 정확히 1회
     * 등장함을 {@link #requireSingleIndex} 가 강제한다(2회 이상/0회 = IllegalStateException —
     * 침묵 오판 대신 소리 나는 실패).
     */
    static boolean initPrecedesSenderStart(String javaSource) {
        String code = stripCommentLines(javaSource);
        int init = requireSingleIndex(code, INIT_TOKEN);
        int start = requireSingleIndex(code, START_TOKEN);
        return init < start;
    }

    /** 줄 단위 주석 제거 — 라이선스 블록·javadoc(`*`)·행 주석(`//`) 줄을 걸러낸 소스를 돌려준다. */
    static String stripCommentLines(String source) {
        StringBuilder code = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            code.append(line).append('\n');
        }
        return code.toString();
    }

    /** 토큰이 정확히 1회 등장할 때만 그 index 를 돌려준다 — 0회/2회 이상은 IllegalStateException. */
    static int requireSingleIndex(String code, String token) {
        int first = code.indexOf(token);
        if (first < 0) {
            throw new IllegalStateException("seal token not found (0 occurrences): " + token
                    + " — AgentMain 리팩터링으로 토큰이 사라졌다면 본 테스트의 토큰을 함께 갱신할 것");
        }
        if (code.indexOf(token, first + token.length()) >= 0) {
            throw new IllegalStateException("seal token appears more than once: " + token
                    + " — 판정이 모호해지므로 봉인 대상 호출을 특정할 수 있게 테스트를 갱신할 것");
        }
        return first;
    }
}
