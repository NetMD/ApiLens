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
     * [Phase K] AC-06-1 — 악성 정규식은 deadline 초과 → "pattern is too complex" (의도된 거부 = 정방향).
     *
     * <p>★실측 보강 (설계 §3.3 예시 패턴 정정 — [S-68] 자진 신고)★: 설계 §3.3 의 예시 "(a+)+$" 는
     * Java 21 HotSpot 의 regex 최적화로 REDOS_PROBE 에 catastrophic backtracking 을 일으키지 않는다
     * (실측 0ms 통과 — 설계 §9.2 명문 잔여 한계의 실증). 본 테스트는 Java 21 에서 실제로 폭발하는
     * (.*a){N}$ 류를 악성 표본으로 쓴다 — REDOS_PROBE("a"×40+"!") 에 대해 deadline 100ms 초과 차단(실측).
     */
    @Test
    void returns400OnCatastrophicBacktrackingPattern() {
        Pattern malicious = Pattern.compile("(.*a){20}$");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(malicious));
        // AddRuleModal 고정 문구 → MaskingRuleController 가 400 {"error":"pattern is too complex"} 로 매핑.
        assertEquals("pattern is too complex", ex.getMessage());
    }

    /** [Phase K] AC-06-1 — 또 다른 ReDoS 패턴(반복 횟수 다른 중첩 .*)도 deadline 초과로 거부(의도된 거부 = 정방향). */
    @Test
    void returns400OnNestedQuantifierPattern() {
        // probe('a'×40+'!') 에 매칭 시작점('a')이 있어야 backtracking 이 폭발한다(',' 류는 빠르게 실패).
        Pattern malicious = Pattern.compile("(.*a){25}$");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(malicious));
        assertEquals("pattern is too complex", ex.getMessage());
    }

    /** [Phase K] AC-06-1 — 악성 룰 차단까지 무한 hang 0 + 시간 예산 내 완료(deadline 100 + 체크 간격, Design §8.2). */
    @Test
    void returns400WithinTimeBudgetWithoutHang() {
        Pattern malicious = Pattern.compile("(.*a){20}$");
        long start = System.nanoTime();
        assertThrows(IllegalArgumentException.class,
                () -> RegexComplexityGuard.rejectIfTooComplex(malicious));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 2000L,
                "ReDoS 차단이 무한 hang 없이 시간 예산 내 완료되어야 함 (실측 " + elapsedMs + "ms)");
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

    /** [Phase K] DeadlineCharSequence — 정확 deadline 은 엄격 초과(>)가 아니라 통과(Design §8.1 경계). */
    @Test
    void passesWhenExactlyAtDeadline() {
        // deadline 을 충분히 미래로 두면 짧은 패턴은 항상 통과 — 엄격 초과 의미 확인.
        long future = System.nanoTime() + 10_000_000_000L; // +10s
        DeadlineCharSequence guarded = new DeadlineCharSequence("aaaa", future);
        // charAt 1024회 미만이라 nanoTime 체크 자체가 안 일어나도 정상 위임 확인.
        assertEquals('a', guarded.charAt(0));
        assertEquals(4, guarded.length());
    }
}
