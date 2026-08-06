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

import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.common.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R20] R20/AC-07-1/AC-07-2 — {@code exception.stacktrace} 채움 + 상한 절단 단위 테스트.
 *
 * <p>R20/AC-07-2 verbatim (비협상): "<b>상한 필수 — 상한 존재 자체가 봉인</b>(W-3). 상한 없는 구현은
 * 수용 불가(용량 절감 라운드의 자기 배반 + 무인증 ingest 표면에 큰 문자열 칸 추가)."
 * 확정값(OQ-7): 4,096자 + 후미 절단 + {@code "... (truncated)"}.
 *
 * <p>경계값(4,095/4,096/4,097)은 절단 helper 직접 검증([S-66] 임계 분기 경계값 동반 출고),
 * 전체 경로(exit → attributes)는 실제 예외로 검증한다.
 */
class AdviceSupportStacktraceTest {

    private SpanQueue queue;

    @BeforeEach
    void setup() {
        queue = new SpanQueue(16);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.PAYLOAD_MAX_BYTES = 65_536;
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        InstrumentationInstaller.QUEUE = null;
    }

    // ─── B-11 경계값: 4,095 / 4,096 / 4,097 — truncateStackTrace 직접 ─────────

    /** 4,095자(상한 미만) — 무절단 원본 그대로. */
    @Test
    void keepsRawBelowLimit() {
        String raw = "x".repeat(4_095);
        assertEquals(raw, AdviceSupport.truncateStackTrace(raw, AdviceSupport.STACKTRACE_MAX_CHARS));
    }

    /** 4,096자(정확히 상한) — 무절단(≤ 경계 판정). 접미 미부착. */
    @Test
    void keepsRawAtExactLimit() {
        String raw = "x".repeat(4_096);
        String result = AdviceSupport.truncateStackTrace(raw, AdviceSupport.STACKTRACE_MAX_CHARS);
        assertEquals(raw, result);
        assertFalse(result.endsWith(AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX),
                "정확히 상한이면 절단 아님 — 접미 부착 금지");
    }

    /** 4,097자(상한 초과) — 앞 4,096자 + "... (truncated)" 접미(후미 절단). */
    @Test
    void truncatesTailBeyondLimitWithSuffix() {
        String raw = "x".repeat(4_097);
        String result = AdviceSupport.truncateStackTrace(raw, AdviceSupport.STACKTRACE_MAX_CHARS);
        assertEquals(4_096 + AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX.length(), result.length());
        assertTrue(result.endsWith(AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX));
        assertEquals("x".repeat(4_096), result.substring(0, 4_096), "앞부분(원인 지점) 보존 — 후미 절단");
    }

    /** null 입력 방어 — null 그대로(키 부재 경로). */
    @Test
    void returnsNullForNullRaw() {
        assertNull(AdviceSupport.truncateStackTrace(null, AdviceSupport.STACKTRACE_MAX_CHARS));
    }

    // ─── B-11 전체 경로: exit → attributes ───────────────────────────────────

    /**
     * thrown != null — exception.stacktrace 가 기존 두 키 옆에 채워지고 원인 사슬을 포함한다.
     * 합성 얕은 스택 사용 — 테스트 러너의 깊은 스택(JUnit/Gradle 프레임 100+)에서는 Caused by 절이
     * 4,096자 상한 밖으로 밀릴 수 있고, 그것은 설계된 후미 절단 동작이라 별도 케이스
     * ({@link #truncatesHugeStacktraceOnFullPath})가 잠근다.
     */
    @Test
    void fillsStacktraceWithCauseChainOnError() {
        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        Exception cause = new IllegalStateException("root cause here");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.Dao", "query", "Dao.java", 42)});
        Exception thrown = new RuntimeException("wrapper", cause);
        thrown.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.acme.Svc", "run", "Svc.java", 10)});

        AdviceSupport.exit(frame, thrown, null, null);

        Span span = drainSingle();
        assertNotNull(span.attributes());
        // 기존 두 키 불변(AC-07-1)
        assertEquals("RuntimeException", span.attributes().get("exception.type"));
        assertEquals("wrapper", span.attributes().get("exception.message"));
        // 신규 키 — printStackTrace 전체 문자열(원인 사슬 자동 포함)
        String stacktrace = (String) span.attributes().get("exception.stacktrace");
        assertNotNull(stacktrace, "에러 span 에 exception.stacktrace 채움");
        assertTrue(stacktrace.contains("java.lang.RuntimeException: wrapper"));
        assertTrue(stacktrace.contains("Caused by: java.lang.IllegalStateException: root cause here"),
                "원인 사슬(Caused by:) 포함 — 운영 가치의 핵심");
        assertTrue(stacktrace.length() <= AdviceSupport.STACKTRACE_MAX_CHARS
                        + AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX.length(),
                "상한 초과 불가(무인증 ingest 표면 부피 가드)");
    }

    /** thrown == null — exception.* 키 자체가 없다(기존 두 키 전례와 동일). */
    @Test
    void omitsStacktraceKeyWhenNoThrown() {
        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Api#get", "SERVER");

        AdviceSupport.exit(frame, null, null, null);

        Span span = drainSingle();
        // attributes 자체가 null(빈 맵 → null 직렬화 전례) — stacktrace 키 부재의 강한 형태.
        assertNull(span.attributes(), "thrown 없으면 exception.* 키 자체가 없다");
    }

    /** 거대 메시지 예외 — 전체 경로에서도 절단 + 접미가 적용된다. */
    @Test
    void truncatesHugeStacktraceOnFullPath() {
        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        Exception thrown = new RuntimeException("m".repeat(10_000));

        AdviceSupport.exit(frame, thrown, null, null);

        Span span = drainSingle();
        String stacktrace = (String) span.attributes().get("exception.stacktrace");
        assertNotNull(stacktrace);
        assertTrue(stacktrace.endsWith(AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX),
                "10,000자 메시지 → 4,096자 후미 절단 + 접미");
        assertEquals(4_096 + AdviceSupport.STACKTRACE_TRUNCATED_SUFFIX.length(), stacktrace.length());
    }

    private Span drainSingle() {
        List<Span> out = new ArrayList<>();
        queue.drainTo(out);
        assertEquals(1, out.size(), "정확히 1건 enqueue");
        Span span = out.get(0);
        assertSame(Span.class, span.getClass());
        return span;
    }
}
