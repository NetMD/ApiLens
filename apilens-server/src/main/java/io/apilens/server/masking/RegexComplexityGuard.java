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

import io.apilens.common.DeadlineCharSequence;
import io.apilens.common.RegexTimeoutException;

import java.util.regex.Pattern;

/**
 * ReDoS guard for masking-rule patterns — a 1st net that rejects regexes whose worst-case match
 * blows past a time budget before they are persisted (Design §3, R14-D05 server-only 중기안).
 *
 * <p>// [Phase K] AC-06-1/AC-06-4 — R14-D05 비협상: ReDoS = server-only 중기안(apilens-common
 * // MaskingEngine 소스 무변경). 가드는 MaskingRuleService 내부에서만 호출(공개 시그니처 불변).
 * // static 유틸이라 생성자 주입 0 — MaskingRuleService 생성자 시그니처 보존(NFR-03, agent fixture 회귀 차단).
 * // 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' (공유 엔진 일관성·agent 격리) 인용.
 *
 * <p>★중기안 한계 (NFR-B1, P0 비협상 — Design §3.4)★: 이 가드는 <b>신규 룰 저장 경로만</b> 보호한다.
 * (1) 이미 저장된 룰, (2) 무인증 ingest(R14-D04)로 들어온 임의 payload 가 IngestService 의 mask() 를
 * 치는 실행 경로는 보호하지 못한다. 즉 "무인증 ingest × 기저장/우회 룰" 조합은 막지 못한다 —
 * 근본 해소는 엔진 deadline(장기안)에서만 가능하다.
 *
 * <p>// [Phase R18] 위 장기안이 이번 라운드에 반영됨: {@link io.apilens.common.MaskingEngine} 의
 * // 실행 deadline(mask() 1회 누적 예산, 기본 1000ms)이 ingest·프리뷰 실행 경로를 보호한다. 본 가드는
 * // <b>저장 시점 probe(별개 경로, 무변경)</b>로 그대로 남고, 승격된 {@link io.apilens.common.DeadlineCharSequence}·
 * // {@link io.apilens.common.RegexTimeoutException}(apilens-common)을 세 경로가 공유한다.
 */
final class RegexComplexityGuard {

    /** [Phase K] AC-06-1 — ReDoS 시험 매칭 deadline (Design §3.2/§5 상수). 매직넘버 금지. */
    static final long REDOS_TIMEOUT_MS = 100;

    /**
     * [Phase K] AC-06-1 — catastrophic backtracking 유발 worst-case 합성 입력 (Design §3.3/§5 상수).
     * 비매칭 긴 입력: 'a'×40 + '!' — (a+)+$ 류 룰이 끝 '!' 에서 실패하며 지수 backtracking.
     */
    static final String REDOS_PROBE = "a".repeat(40) + "!";

    private RegexComplexityGuard() {
    }

    /**
     * Rejects the pattern if matching {@link #REDOS_PROBE} exceeds {@link #REDOS_TIMEOUT_MS}.
     *
     * <p>// [Phase K] AC-06-1 — timeout 시 IllegalArgumentException("pattern is too complex")
     * // → MaskingRuleController.handleBadRequest 가 400 {"error":"pattern is too complex"} 로 매핑.
     * // 정상 룰(seed 4종 등)은 선형 시간 → 즉시 통과(AC-06-2/06-3).
     *
     * @param compiled 이미 compileOrReject 로 구문 검증·컴파일된 Pattern (재컴파일 0)
     * @throws IllegalArgumentException 매칭이 deadline 을 초과(ReDoS 의심)할 때
     */
    static void rejectIfTooComplex(Pattern compiled) {
        rejectIfTooComplex(compiled, REDOS_TIMEOUT_MS, REDOS_PROBE);
    }

    /**
     * Deadline/probe 주입 오버로드 — production 호출은 항상 위 기본 메서드(REDOS_TIMEOUT_MS/REDOS_PROBE)만
     * 사용하므로 운영 동작은 불변이다.
     *
     * <p>// [Phase K hotfix] 단위 테스트가 catastrophic backtracking 의 <b>JDK·CPU 의존성</b>(특정 정규식이
     * // 실제로 폭발하는지) 없이 "deadline 초과 → reject" 로직 자체를 결정적으로 검증할 수 있게 한다.
     * // (.*a){N}$ 류의 폭발 여부는 java.util.regex 구현/플랫폼에 의존적이라 CI(리눅스 JDK21)에서 flaky 였음
     * // — Design §9.2 가 명문한 "java.util.regex 최적화 경로의 deadline 감지 한계" 의 실증.
     */
    static void rejectIfTooComplex(Pattern compiled, long timeoutMs, String probe) {
        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        CharSequence guarded = new DeadlineCharSequence(probe, deadlineNanos);
        try {
            // backtracking 중 charAt 마다 deadline 체크 → 초과 시 RegexTimeoutException 으로 탈출.
            // 현재 요청 스레드에서 동기 실행 — 별도 스레드 0 (데몬 스레드 누수 0, Design §3.2).
            compiled.matcher(guarded).find();
        } catch (RegexTimeoutException e) {
            throw new IllegalArgumentException("pattern is too complex");
        }
    }
}
