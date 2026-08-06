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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * [Phase R20] (Q-1) 진입점 게이트 + 런타임 게이트 exclude 단위 테스트.
 *
 * <p>R20/AC-01-2 verbatim (비협상): "억제 의미는 Q-U1 verbatim — <b>옵션 ON && root 후보(스택 깊이 0)
 * && kind 가 진입점(SERVER)이 아니면 trace 생성 억제</b>. ON 상태에서 SERVER root(Controller 진입)는
 * 억제되지 않음을 테스트로 확인." — 요구사항 문자 그대로 구현(모든 root 억제)의 반대 방향 lock-in 을
 * 정방향 테스트(pushes/keeps)로 차단한다.
 *
 * <p>R20/AC-01-4 verbatim (비협상): "enterDbSpan 의 억제 판정은 IN_DB_SPAN set 이전에 수행(W-4 — 늦으면
 * 그 스레드의 이후 JDBC span 전부 SKIP 되는 영구 누수). 검증: ON 상태로 JDBC root 후보를 억제한 뒤,
 * 같은 스레드의 후속 SERVER trace 안 JDBC span 이 정상 기록된다."
 *
 * <p>[Phase R21] R21/AC-01-4 (R-04) — 대표 수락 기준: R20/AC-01-2(진입점 게이트 — SERVER root 비억제) ·
 * R20/AC-01-4(enterDbSpan 억제 판정 순서 — 영구 누수 차단). src/test 주석만 추가 — agent src/main
 * diff 0 봉인과 무충돌.
 */
class AdviceSupportEntryRootGateTest {

    private SpanQueue queue;

    @BeforeEach
    void setup() {
        queue = new SpanQueue(64);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.PAYLOAD_MAX_BYTES = 65_536;
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
        // 각 테스트가 기본 상태(게이트 OFF + exclude 없음)에서 시작 — 기본값 반드시 꺼짐(Q-D3)과 동형.
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of();
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        AdviceSupport.markDbSpanExited(true);
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of();
        InstrumentationInstaller.QUEUE = null;
    }

    // ─── B-01: OFF(기본) — 기존 동작 완전 동일 (AC-01-6) ─────────────────────

    /** B-01 — OFF + INTERNAL root: push 정상(억제 없음 — 기본값 꺼짐이 곧 현 동작 보존). */
    @Test
    void pushesInternalRootWhenGateOff() {
        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Svc#run", "INTERNAL");

        assertNotSame(TraceContext.Frame.SKIPPED, frame, "OFF 기본값에서 INTERNAL root 는 억제되지 않는다");
        assertEquals(1, TraceContext.depth(), "정상 push — 스택 깊이 1");
        AdviceSupport.exit(frame, null, null, null);
        assertEquals(1, drainCount(), "OFF 에서 span 1건 정상 enqueue");
    }

    // ─── B-02: ON + SERVER root — 억제 안 됨 (AC-01-2 정방향) ────────────────

    /** B-02 — ON + SERVER root(depth 0): push 정상. "모든 root 억제" 잘못된 문자 해석 차단 핵심 케이스. */
    @Test
    void pushesServerRootWhenGateOn() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Api#get", "SERVER");

        assertNotSame(TraceContext.Frame.SKIPPED, frame, "SERVER root(Controller 진입)는 ON 이어도 억제 금지");
        assertEquals(1, TraceContext.depth());
        AdviceSupport.exit(frame, null, null, null);
        assertEquals(1, drainCount());
    }

    // ─── B-03: ON + INTERNAL root — SKIPPED ─────────────────────────────────

    /** B-03 — ON + INTERNAL root(depth 0): SKIPPED(억제 — 이 옵션의 목적, 의도된 동작). */
    @Test
    void skipsInternalRootWhenGateOn() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Batch#poll", "INTERNAL");

        assertSame(TraceContext.Frame.SKIPPED, frame);
        assertEquals(0, TraceContext.depth(), "억제 — 스택 push 없음");
        AdviceSupport.exit(frame, null, null, null);
        assertEquals(0, drainCount(), "억제된 root 는 enqueue 없음");
    }

    // ─── B-04: ON + SERVER 하위 INTERNAL(depth 1) — push 정상 ────────────────

    /** B-04 — ON + INTERNAL depth 1(SERVER 하위): 정상 push(게이트는 root 후보에만 작동). */
    @Test
    void pushesInternalChildUnderServerWhenGateOn() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        TraceContext.Frame root = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        TraceContext.Frame child = AdviceSupport.enter("com.acme.Svc#run", "INTERNAL");

        assertNotSame(TraceContext.Frame.SKIPPED, child, "SERVER 하위 INTERNAL 은 depth>0 이라 게이트 미발동");
        assertEquals(2, TraceContext.depth());
        AdviceSupport.exit(child, null, null, null);
        AdviceSupport.exit(root, null, null, null);
        assertEquals(2, drainCount(), "root+child 2건 정상 enqueue");
    }

    // ─── B-05: W-4 — enterDbSpan 억제 후 IN_DB_SPAN 누수 0 (AC-01-4) ─────────

    /**
     * B-05 — ON + JDBC root 후보 억제(SKIPPED) 후, <b>같은 스레드</b>에서 SERVER trace 안 JDBC span 이
     * 정상 기록된다(IN_DB_SPAN 영구 누수 0 — 게이트가 set 이전이라 ThreadLocal 무접촉).
     */
    @Test
    void keepsJdbcAliveAfterSuppressedDbRootOnSameThread() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        // 1) JDBC root 후보(depth 0) — 억제. JdbcAdvice 조기 단락과 동형으로 markDbSpanExited 미호출.
        TraceContext.Frame suppressed = AdviceSupport.enterDbSpan("HikariProxyPreparedStatement", "jdbc.execute");
        assertSame(TraceContext.Frame.SKIPPED, suppressed);
        AdviceSupport.exit(suppressed, null, null, null);   // no-op (스택 pop 없음·enqueue 없음)

        // 2) 같은 스레드 — 후속 SERVER trace 안 JDBC span 정상 기록(누수 있었다면 여기가 SKIPPED 가 된다).
        TraceContext.Frame server = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        TraceContext.Frame db = AdviceSupport.enterDbSpan("HikariProxyPreparedStatement", "jdbc.execute");
        assertNotSame(TraceContext.Frame.SKIPPED, db,
                "억제 경로가 IN_DB_SPAN 을 건드렸다면 영구 누수로 여기가 SKIPPED — W-4 위반 검출");
        AdviceSupport.exit(db, null, null, null);
        AdviceSupport.markDbSpanExited(true);
        AdviceSupport.exit(server, null, null, null);
        assertEquals(2, drainCount(), "SERVER + DB 2건 정상 enqueue");
    }

    // ─── B-06: ON + SERVER 하위 enterDbSpan — 기존 inner dedup 불변 ──────────

    /** B-06 — ON + SERVER 하위(depth≥1) enterDbSpan: 정상 push + 기존 inner dedup(SKIPPED) 동작 불변. */
    @Test
    void keepsInnerDedupUnderServerWhenGateOn() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        TraceContext.Frame server = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        TraceContext.Frame outer = AdviceSupport.enterDbSpan("wrapper-outer", "jdbc.execute");
        TraceContext.Frame inner = AdviceSupport.enterDbSpan("wrapper-inner", "jdbc.execute");

        assertNotSame(TraceContext.Frame.SKIPPED, outer, "outer 는 정상 push(depth>0 — 게이트 미발동)");
        assertSame(TraceContext.Frame.SKIPPED, inner, "inner 는 기존 re-entrancy dedup 그대로 SKIPPED");
        AdviceSupport.exit(inner, null, null, null);
        AdviceSupport.exit(outer, null, null, null);
        AdviceSupport.markDbSpanExited(true);
        AdviceSupport.exit(server, null, null, null);
        assertEquals(2, drainCount(), "SERVER + DB(outer 1건만) — dedup 불변");
    }

    // ─── B-07: 억제 root 아래 연쇄 호출 — 일관 억제 (AC-01-5) ─────────────────

    /** B-07 — 억제된 root 아래 후속 호출(스택 계속 0): 전부 SKIPPED — 일관 억제(반쪽 흐름 0). */
    @Test
    void skipsWholeChainUnderSuppressedRoot() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        TraceContext.Frame first = AdviceSupport.enter("com.acme.Batch#poll", "INTERNAL");
        TraceContext.Frame second = AdviceSupport.enter("com.acme.Svc#load", "INTERNAL");
        TraceContext.Frame third = AdviceSupport.enterDbSpan("stmt", "jdbc.execute");

        assertSame(TraceContext.Frame.SKIPPED, first);
        assertSame(TraceContext.Frame.SKIPPED, second, "push 가 없어 depth 가 계속 0 → 같은 게이트로 일관 억제");
        assertSame(TraceContext.Frame.SKIPPED, third);
        AdviceSupport.exit(third, null, null, null);
        AdviceSupport.exit(second, null, null, null);
        AdviceSupport.exit(first, null, null, null);
        assertEquals(0, drainCount(), "연쇄 전체 enqueue 0 — 파편 trace 원천 미생성");
    }

    // ─── B-12: 게이트 exclude — FQN 정확 일치만 (AC-06-1) ────────────────────

    /** B-12 — exclude "com.foo.Bar": 정확 일치만 SKIPPED. prefix 오매칭 0. */
    @Test
    void skipsExactFqnMatchOnlyForGateExclude() {
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of("com.foo.Bar");

        // depth 0 게이트와 분리해 exclude 축만 검증(OFF 상태 — exclude 는 옵션과 독립).
        TraceContext.Frame excluded = AdviceSupport.enter("com.foo.Bar#m", "INTERNAL");
        assertSame(TraceContext.Frame.SKIPPED, excluded, "FQN 정확 일치 → SKIPPED (재시작 불요 개별 제외)");
        AdviceSupport.exit(excluded, null, null, null);

        TraceContext.Frame notExcluded = AdviceSupport.enter("com.foo.BarMapper#m", "INTERNAL");
        assertNotSame(TraceContext.Frame.SKIPPED, notExcluded,
                "com.foo.Bar exclude 가 com.foo.BarMapper 에 오매칭되면 안 된다(prefix 매칭 금지)");
        AdviceSupport.exit(notExcluded, null, null, null);

        TraceContext.Frame noHash = AdviceSupport.enter("plain-operation-name", "INTERNAL");
        assertNotSame(TraceContext.Frame.SKIPPED, noHash, "'#' 없는 이름은 exclude 판정 대상 아님");
        AdviceSupport.exit(noHash, null, null, null);

        assertEquals(2, drainCount(), "exclude 1건 제외, 나머지 2건 정상 enqueue");
    }

    /** B-12 부속 — exclude 된 마디의 자식은 살아 있는 스택 상위(조부모)에 붙는다(그 마디만 지우기). */
    @Test
    void keepsChildrenAliveUnderGateExcludedNode() {
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of("com.foo.Bar");

        TraceContext.Frame server = AdviceSupport.enter("com.acme.Api#get", "SERVER");
        TraceContext.Frame excluded = AdviceSupport.enter("com.foo.Bar#m", "INTERNAL");
        assertSame(TraceContext.Frame.SKIPPED, excluded);
        TraceContext.Frame child = AdviceSupport.enter("com.acme.Svc#run", "INTERNAL");

        assertNotSame(TraceContext.Frame.SKIPPED, child, "exclude 는 그 마디만 — 자식은 계속 기록");
        assertEquals(child.parentSpanId, server.spanId, "자식의 부모 = 살아 있는 스택 상위(SERVER)");
        AdviceSupport.exit(child, null, null, null);
        AdviceSupport.exit(excluded, null, null, null);
        AdviceSupport.exit(server, null, null, null);
        assertEquals(2, drainCount());
    }

    private int drainCount() {
        java.util.List<io.apilens.common.Span> out = new java.util.ArrayList<>();
        queue.drainTo(out);
        return out.size();
    }
}
