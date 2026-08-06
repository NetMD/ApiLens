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
package io.apilens.agent.instrument.advice;

import io.apilens.agent.instrument.AdviceSupport;
import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.common.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase R20] R20/AC-01-8 — advice 4개 조기 단락(W-11 확정: diff 발생 채택 — "직렬화 후 폐기" 제거)
 * 단위 테스트. exit 가 SKIPPED/null frame 에서 "스택 pop 안 함 + enqueue 안 함" 을 지키고 예외 없이
 * 반환하는지 확인한다 — {@code JdbcAdvice} 전례 복제 4개 동일 형태.
 *
 * <p>advice 메서드는 weaving 전에는 평범한 public static 메서드라 직접 호출로 단위 검증 가능
 * (ByteBuddy inline 은 프로덕션 경로 — 여기서는 조기 단락 분기 자체를 잠근다).
 */
class AdviceEarlyExitTest {

    private SpanQueue queue;

    @BeforeEach
    void setup() {
        queue = new SpanQueue(16);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.PAYLOAD_MAX_BYTES = 65_536;
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        AdviceSupport.markDbSpanExited(true);
        InstrumentationInstaller.QUEUE = null;
    }

    /** ControllerAdvice.exit(SKIPPED) — enqueue 0 + 스택 불변 + 예외 0. */
    @Test
    void controllerExitShortCircuitsOnSkipped() {
        ControllerAdvice.exit(TraceContext.Frame.SKIPPED, new Object[]{"arg"}, "ret", null, "T", "m");
        assertEquals(0, drainCount());
        assertEquals(0, TraceContext.depth());
    }

    /** ControllerAdvice.exit(null) — 기존 null 처리와 동작 동등(enqueue 0). */
    @Test
    void controllerExitShortCircuitsOnNullFrame() {
        ControllerAdvice.exit(null, new Object[]{"arg"}, "ret", null, "T", "m");
        assertEquals(0, drainCount());
    }

    /** ServiceAdvice.exit(SKIPPED) — enqueue 0. */
    @Test
    void serviceExitShortCircuitsOnSkipped() {
        ServiceAdvice.exit(TraceContext.Frame.SKIPPED, new Object[]{"arg"}, "ret", null, "T", "m");
        assertEquals(0, drainCount());
        assertEquals(0, TraceContext.depth());
    }

    /** RepositoryAdvice.exit(SKIPPED) — enqueue 0. */
    @Test
    void repositoryExitShortCircuitsOnSkipped() {
        RepositoryAdvice.exit(TraceContext.Frame.SKIPPED, new Object[]{"arg"}, "ret", null, "T", "m");
        assertEquals(0, drainCount());
        assertEquals(0, TraceContext.depth());
    }

    /** MyBatisMapperAdvice.exit(SKIPPED) — 통일 형태 대체 후에도 enqueue 0(기존 null 단락과 동작 동등). */
    @Test
    void mapperExitShortCircuitsOnSkippedAndNull() throws Exception {
        Method method = String.class.getMethod("length");

        MyBatisMapperAdvice.exit(TraceContext.Frame.SKIPPED, method, new Object[]{}, "ret", null);
        MyBatisMapperAdvice.exit(null, method, new Object[]{}, "ret", null);

        assertEquals(0, drainCount());
        assertEquals(0, TraceContext.depth());
    }

    /** 조기 단락이 정상 frame 경로를 건드리지 않는다 — 정상 push→exit 은 여전히 1건 enqueue. */
    @Test
    void keepsNormalPathEnqueueingAfterShortCircuitAdded() {
        TraceContext.Frame frame = AdviceSupport.enter("com.acme.Svc#run", "INTERNAL");
        ServiceAdvice.exit(frame, new Object[]{}, null, null, "com.acme.Svc", "run");
        assertEquals(1, drainCount(), "정상 경로 회귀 0 — 조기 단락은 SKIPPED/null 에만 작동");
    }

    private int drainCount() {
        List<Span> out = new ArrayList<>();
        queue.drainTo(out);
        return out.size();
    }
}
