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
package io.apilens.server.masking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * [Phase K] RegexComplexityGuard 단위 테스트 — ReDoS 1차 그물 (V-B01~B04, AC-06).
 *
 * <p>// [Phase K] AC-06-1 verbatim: "악성 정규식(예: (a+)+$ + 비매칭 긴 입력) 저장 시
 * // 400 {"error":"pattern is too complex"} 가 반환된다" (R14-D05 비협상 — server-only 중기안).
 * // 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' (공유 엔진 일관성) 인용.
 *
 * <p>★EXT-005 정방향 동사 lock-in 가드★: 대부분 테스트는 정방향 동사(passes/accepts/returns).
 * 단 AC-06-1 의 "too complex 거부" 는 <b>의미상 의도된 거부가 정상 동작</b>이므로 정방향이다 —
 * {@code rejectsComplexPattern} 류 메서드명은 BE-FAIL-01(잘못된 lock-in)이 아니라 AC 의도 자체.
 * dev 자기증명 grep: 정상 룰을 거부하는 반대방향 테스트(rejectsValid / throwsOnValid 류) 0 hit.
 */
class RegexComplexityGuardTest {

    /**
     * [Phase K] AC-06-1 — deadline 초과 매칭은 "pattern is too complex" 로 거부(의도된 거부 = 정방향).
     *
     * <p>★hotfix (CI 환경 의존 제거)★: 기존엔 (.*a){20}$ 의 catastrophic backtracking 이 100ms 를 넘김에
     * 의존했으나, java.util.regex 의 폭발 여부는 JDK 버전·CPU 에 의존적이라 CI(리눅스 JDK21)에서 flaky 였다
     * (설계 §9.2 명문 한계). 이제 deadline 을 0(이미 지난 시각)으로 주입하고 charAt 가
     * DEADLINE_CHECK_MASK(1024) 회 이상 호출되는 긴 입력을 써서, <b>패턴의 실제 폭발 여부와 무관하게</b>
     * "deadline 초과 → reject" 로직 자체를 결정적으로 검증한다.
     */
    @Test
    void rejectsWhenMatchingExceedsDeadline() {
        Pattern p = Pattern.compile("a+$");            // 긴 입력을 charAt 로 순회(greedy + backtrack)
        String longProbe = "a".repeat(5000) + "!";     // charAt 1024 회 훨씬 초과 → deadline 체크 발동
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(p, 0L, longProbe));
        // MaskingRuleController 가 400 {"error":"pattern is too complex"} 로 매핑하는 고정 문구.
        assertEquals("pattern is too complex", ex.getMessage());
    }

    /** [Phase K] AC-06-1 — 중첩 quantifier 패턴도 deadline 감지로 거부(결정적 — timeout 0 주입, 폭발 의존 없음). */
    @Test
    void rejectsNestedQuantifierPatternViaDeadline() {
        Pattern p = Pattern.compile("(a+)+$");
        String longProbe = "a".repeat(5000) + "!";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(p, 0L, longProbe));
        assertEquals("pattern is too complex", ex.getMessage());
    }

    /** [Phase K] AC-06-1 — 거부까지 무한 hang 0 + 시간 예산 내 완료(deadline 감지 후 즉시 자기 탈출). */
    @Test
    void rejectsWithinTimeBudgetWithoutHang() {
        Pattern p = Pattern.compile("a+$");
        long start = System.nanoTime();
        assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(p, 0L, "a".repeat(5000) + "!"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 2000L,
                "deadline 감지 후 무한 hang 없이 즉시 완료되어야 함 (실측 " + elapsedMs + "ms)");
    }

    /**
     * [Phase K] AC-06-2 verbatim: "정상 정규식은 시간 내 통과되어 저장된다" — 통과(정방향).
     */
    @Test
    void passesSimpleLinearPattern() {
        assertDoesNotThrow(() -> RegexComplexityGuard.rejectIfTooComplex(Pattern.compile("\\d{6}")));
    }

    /**
     * [Phase K] AC-06-3 verbatim: "기존 V1 seed 룰(default 4종)은 모두 통과한다(회귀 0)" — 통과(정방향).
     * V1__initial_schema.sql:75-78 의 default 4종 패턴 (주민번호/카드/password/token).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\\d{6}-?\\d{7}",                          // 주민번호
            "\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}",          // 카드번호
            "password|passwd|pwd",                     // password
            "token|secret|authorization|api[_-]?key"   // token / secret
    })
    void passesAllV1SeedRules(String seedPattern) {
        assertDoesNotThrow(() -> RegexComplexityGuard.rejectIfTooComplex(Pattern.compile(seedPattern)),
                "V1 seed 룰은 ReDoS 가드를 통과해야 함 (회귀 0): " + seedPattern);
    }

    /**
     * [Phase K] AC-06-2 / EXT-002 경계 — 적당히 무거우나 deadline 미초과인 패턴은 통과(정방향).
     * REDOS_PROBE 에 선형으로 매칭되는 패턴이라 100ms deadline 안에 끝난다.
     */
    @Test
    void passesModeratePatternWithinDeadline() {
        assertDoesNotThrow(() -> RegexComplexityGuard.rejectIfTooComplex(Pattern.compile("a+!")));
    }

    /** [Phase K] 상수 명세 고정 — REDOS_TIMEOUT_MS=100, REDOS_PROBE 형태(Design §5). 매직넘버 회귀 가드. */
    @Test
    void exposesConstantsAsDesigned() {
        assertEquals(100L, RegexComplexityGuard.REDOS_TIMEOUT_MS);
        assertEquals("a".repeat(40) + "!", RegexComplexityGuard.REDOS_PROBE);
    }

    // [Phase R18] DeadlineCharSequence 경계(정확 deadline 통과 등) 단위 테스트는 승격에 따라
    //   apilens-common 의 DeadlineCharSequenceTest 로 이동(EXT-006 시간소스 주입 결정적 테스트 신설).
}
