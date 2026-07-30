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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * [Phase R19] ExcludeClassifier 5단 규칙 경계값 — 설계 §8.2 표(B-13~B-19b)를 그대로 옮긴 것.
 *
 * <p>비협상 AC verbatim 인용:
 * <ul>
 *   <li>AC-06-2: "제외 가능성은 3분류로 표기하고 <b>불확실을 '가능' 으로 반올림하지 않는다</b>" (비협상 D-12)</li>
 *   <li>AC-06-5: "표시용 이름을 그대로 옵션 값으로 쓰는 코드 경로가 존재하지 않는다"</li>
 * </ul>
 */
class ExcludeClassifierTest {

    // ─── B-13 — 클래스 이름 없음 (고정 합계 행) ──────────────────────────────

    @Test
    void returnsNoClassNameForEmptyClassName() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("");

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.NO_CLASS_NAME, verdict.reasonCode());
        assertNull(verdict.target());
    }

    @Test
    void returnsNoClassNameForNullClassName() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify(null);

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.NO_CLASS_NAME, verdict.reasonCode());
    }

    // ─── B-14 — 프레임워크 패키지는 안전 방향(UNKNOWN) ───────────────────────

    @Test
    void returnsUnknownForSpringDataFrameworkClass() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify(
                "org.springframework.data.jpa.repository.support.SimpleJpaRepository");

        assertEquals(ExcludeStatus.UNKNOWN, verdict.status());
        assertEquals(ExcludeReasonCode.UNVERIFIED_PATH, verdict.reasonCode());
        assertNull(verdict.target(), "framework names must never be offered as an option value");
    }

    @Test
    void returnsUnknownForEveryFrameworkPrefix() {
        assertEquals(ExcludeStatus.UNKNOWN,
                ExcludeClassifier.classify("org.apache.ibatis.binding.MapperProxy").status());
        assertEquals(ExcludeStatus.UNKNOWN,
                ExcludeClassifier.classify("java.util.concurrent.ThreadPoolExecutor").status());
        assertEquals(ExcludeStatus.UNKNOWN,
                ExcludeClassifier.classify("javax.sql.DataSource").status());
        assertEquals(ExcludeStatus.UNKNOWN,
                ExcludeClassifier.classify("jakarta.servlet.http.HttpServlet").status());
        assertEquals(ExcludeStatus.UNKNOWN,
                ExcludeClassifier.classify("sun.reflect.Reflection").status());
    }

    // ─── B-15/B-16/B-17 — 강한 mapper 신호 ──────────────────────────────────

    @Test
    void returnsProxyInstrumentedWhenBothMapperSignalsMatch() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.mapper.OrderMapper");

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.PROXY_INSTRUMENTED, verdict.reasonCode());
        assertNull(verdict.target());
    }

    @Test
    void returnsProxyInstrumentedWhenOnlySimpleNameEndsWithMapper() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.persistence.OrderMapper");

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.PROXY_INSTRUMENTED, verdict.reasonCode());
    }

    @Test
    void returnsProxyInstrumentedWhenOnlyPackageSegmentIsMapper() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.mapper.Helper");

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.PROXY_INSTRUMENTED, verdict.reasonCode());
    }

    @Test
    void returnsProxyInstrumentedForPluralMappersPackageSegment() {
        assertEquals(ExcludeStatus.NOT_EXCLUDABLE,
                ExcludeClassifier.classify("com.acme.mappers.Helper").status());
    }

    // ─── B-18 — 약한 신호는 UNKNOWN (양쪽 단정 회피) ─────────────────────────

    @Test
    void returnsUnknownForDaoPackageSegment() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.dao.OrderDao");

        assertEquals(ExcludeStatus.UNKNOWN, verdict.status());
        assertEquals(ExcludeReasonCode.UNVERIFIED_PATH, verdict.reasonCode());
    }

    @Test
    void returnsUnknownForWeakSimpleNameSuffixes() {
        assertEquals(ExcludeStatus.UNKNOWN, ExcludeClassifier.classify("com.acme.svc.OrderDAO").status());
        assertEquals(ExcludeStatus.UNKNOWN, ExcludeClassifier.classify("com.acme.svc.OrderRepository").status());
    }

    // ─── B-19 — 나머지는 EXCLUDABLE 이고 target 이 className 과 같다 ─────────

    @Test
    void returnsExcludableWithTargetForPlainServiceClass() {
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.service.OrderService");

        assertEquals(ExcludeStatus.EXCLUDABLE, verdict.status());
        assertNull(verdict.reasonCode(), "an excludable class carries no reason code");
        assertEquals("com.acme.service.OrderService", verdict.target());
    }

    // ─── B-19b — 부분 문자열 오탐 차단 ──────────────────────────────────────

    @Test
    void returnsExcludableWhenMapperIsOnlyAPrefixOfTheSimpleName() {
        // "MapperUtil" 은 Mapper 로 끝나지 않고 .mapper. 세그먼트도 없다 → 오탐 금지.
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.MapperUtil");

        assertEquals(ExcludeStatus.EXCLUDABLE, verdict.status());
        assertEquals("com.acme.MapperUtil", verdict.target());
    }

    @Test
    void returnsExcludableWhenDaoIsOnlyPartOfALongerWord() {
        // "Daoism" 류 — Dao 로 끝나지 않는다.
        assertEquals(ExcludeStatus.EXCLUDABLE, ExcludeClassifier.classify("com.acme.svc.Daoism").status());
    }

    // ─── 평가 순서 고정 — 위에서 아래로, 처음 걸리는 규칙이 이긴다 ───────────

    @Test
    void appliesFrameworkRuleBeforeMapperRule() {
        // org.apache.ibatis.binding.MapperProxy 는 두 규칙에 다 걸리지만 R-2 가 먼저다.
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("org.apache.ibatis.binding.MapperProxy");

        assertEquals(ExcludeStatus.UNKNOWN, verdict.status());
        assertEquals(ExcludeReasonCode.UNVERIFIED_PATH, verdict.reasonCode());
    }

    @Test
    void appliesMapperRuleBeforeWeakDaoRule() {
        // .mapper. 세그먼트(강한 신호) + Repository 접미사(약한 신호) → 강한 신호가 이긴다.
        ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify("com.acme.mapper.OrderRepository");

        assertEquals(ExcludeStatus.NOT_EXCLUDABLE, verdict.status());
        assertEquals(ExcludeReasonCode.PROXY_INSTRUMENTED, verdict.reasonCode());
    }
}
