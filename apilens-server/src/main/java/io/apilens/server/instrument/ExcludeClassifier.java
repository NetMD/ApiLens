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
package io.apilens.server.instrument;

import java.util.List;

/**
 * Decides whether a class name shown in the ranking can be excluded by the agent option.
 *
 * <p>순수 함수 유틸이다 — DB·시간·설정에 의존하지 않으므로 단위 테스트가 결정적이다.
 *
 * <p><b>판별의 뿌리</b>: 계측 제외 옵션은 weaving 시점의 <b>타입 이름</b>에 prefix 매칭을 건다.
 * 그러니 물어야 할 것은 하나뿐이다 — "화면에 보이는 span 이름 == weaving 대상 이름 인가?"
 * 원리적 불일치는 MyBatis mapper 하나뿐이다(계측은 {@code org.apache.ibatis.binding.MapperProxy}
 * 한 점에 걸리는데 span 이름은 리플렉션이 읽은 사용자 인터페이스로 찍힌다).
 *
 * <p>// [Phase R19] AC-06-1/AC-06-2/AC-06-5 — 5단 규칙. 사용자 명시 비협상 결정(D-12:
 * // "제외 가능성은 3분류로 표기하고 불확실을 '가능' 으로 반올림하지 않는다").
 * // CLAUDE.md '아키텍처 핵심 원칙'(Agent 는 가볍게 — 서버가 agent 동작을 추정할 때는 안전 방향) 인용.
 *
 * <p><b>평가 순서를 고정한다. 위에서 아래로, 처음 걸리는 규칙이 이긴다.</b>
 * <ol>
 *   <li>R-1 클래스 이름 없음 → {@code NOT_EXCLUDABLE} / {@code NO_CLASS_NAME}</li>
 *   <li>R-2 프레임워크 패키지 → {@code UNKNOWN} / {@code UNVERIFIED_PATH}</li>
 *   <li>R-3 강한 mapper 신호 → {@code NOT_EXCLUDABLE} / {@code PROXY_INSTRUMENTED}</li>
 *   <li>R-4 약한 dao/repository 신호 → {@code UNKNOWN} / {@code UNVERIFIED_PATH}</li>
 *   <li>R-5 나머지 → {@code EXCLUDABLE}, {@code excludeTarget = className}</li>
 * </ol>
 *
 * <p><b>정직한 자기 평가</b>: 이름이 {@code Mapper}/{@code Dao} 로 끝나지 않는 MyBatis mapper
 * 인터페이스는 {@code EXCLUDABLE} 로 잘못 표시된다 — 이 방식의 실제 오탐 표면이고 규칙으로는
 * 더 줄일 수 없다. 근본 해소는 agent 가 계측 경로를 attribute 로 붙이는 것이며 agent 변경이라
 * 이번 라운드 범위 밖이다.
 */
public final class ExcludeClassifier {

    /**
     * R-2 — 프레임워크 패키지. 이 이름들로 제외 옵션을 걸면 실제로 빠지긴 하지만
     * 그 프레임워크를 쓰는 <b>모든 계층이 통째로 사라진다</b>. 화면에 "뺄 수 있어요" 만 뜨면
     * 운영자가 {@code org.springframework} 를 옵션에 넣는 대참사가 난다 → 안전 방향은 UNKNOWN.
     */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "org.springframework.",
            "org.apache.",
            "java.",
            "javax.",
            "jakarta.",
            "sun."
    );

    /** R-3 — 강한 mapper 신호(패키지 세그먼트). */
    private static final List<String> MAPPER_PACKAGE_SEGMENTS = List.of(".mapper.", ".mappers.");

    /** R-3 — 강한 mapper 신호(단순 이름 접미사). */
    private static final String MAPPER_SIMPLE_NAME_SUFFIX = "Mapper";

    /** R-4 — 약한 신호(패키지 세그먼트). */
    private static final String DAO_PACKAGE_SEGMENT = ".dao.";

    /** R-4 — 약한 신호(단순 이름 접미사). */
    private static final List<String> WEAK_SIMPLE_NAME_SUFFIXES = List.of("Dao", "DAO", "Repository");

    private ExcludeClassifier() {
    }

    /**
     * Classification result for one class name.
     *
     * <p>{@code target} 을 {@code status} 와 따로 둔 이유: "표시용 이름을 그대로 옵션 값으로 쓰는
     * 코드 경로가 존재하지 않는다" 를 계약으로 강제하기 위해서다. {@code EXCLUDABLE} 일 때만
     * 값이 있고, 그 값은 {@code className} 과 같다.
     *
     * @param status     3분류
     * @param reasonCode 불가·불확실 사유 코드({@code EXCLUDABLE} 이면 {@code null})
     * @param target     옵션에 넣을 값({@code EXCLUDABLE} 이 아니면 {@code null})
     */
    public record Verdict(ExcludeStatus status, ExcludeReasonCode reasonCode, String target) {
    }

    /**
     * Apply the five rules in order; the first match wins.
     *
     * @param className span 이름에서 잘라 낸 클래스 부분({@code #} 앞). 없으면 빈 문자열
     * @return 판별 결과 (never {@code null})
     */
    public static Verdict classify(String className) {
        // R-1: 클래스 이름 자체가 없다 (jdbc.execute 등 — 고정 합계 행).
        if (className == null || className.isBlank()) {
            return new Verdict(ExcludeStatus.NOT_EXCLUDABLE, ExcludeReasonCode.NO_CLASS_NAME, null);
        }

        // R-2: 프레임워크 패키지 — 뺄 수는 있지만 대참사 방향이라 "가능" 으로 말하지 않는다.
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return new Verdict(ExcludeStatus.UNKNOWN, ExcludeReasonCode.UNVERIFIED_PATH, null);
            }
        }

        String simpleName = simpleName(className);

        // R-3: 강한 mapper 신호 — 계측이 라이브러리 프록시에 걸려 이 이름으로는 못 뺀다.
        for (String segment : MAPPER_PACKAGE_SEGMENTS) {
            if (className.contains(segment)) {
                return new Verdict(ExcludeStatus.NOT_EXCLUDABLE, ExcludeReasonCode.PROXY_INSTRUMENTED, null);
            }
        }
        if (simpleName.endsWith(MAPPER_SIMPLE_NAME_SUFFIX)) {
            return new Verdict(ExcludeStatus.NOT_EXCLUDABLE, ExcludeReasonCode.PROXY_INSTRUMENTED, null);
        }

        // R-4: 약한 신호 — @Repository 구체 클래스일 수도 있어 NOT_EXCLUDABLE 로 단정하지 않고,
        //      반대로 EXCLUDABLE 로 단정하지도 않는다(그쪽이 "조용히 무효" 사고를 자동화하는 방향).
        if (className.contains(DAO_PACKAGE_SEGMENT)) {
            return new Verdict(ExcludeStatus.UNKNOWN, ExcludeReasonCode.UNVERIFIED_PATH, null);
        }
        for (String suffix : WEAK_SIMPLE_NAME_SUFFIXES) {
            if (simpleName.endsWith(suffix)) {
                return new Verdict(ExcludeStatus.UNKNOWN, ExcludeReasonCode.UNVERIFIED_PATH, null);
            }
        }

        // R-5: 나머지 — 화면 이름 == weaving 대상 이름.
        return new Verdict(ExcludeStatus.EXCLUDABLE, null, className);
    }

    /** FQCN 의 마지막 마침표 뒤 부분. 마침표가 없으면 입력 그대로. */
    private static String simpleName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(lastDot + 1);
    }
}
