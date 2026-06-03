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
import io.apilens.common.SpanStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates that {@link AdviceSupport#exit} marks a span ERROR when the
 * {@code http.status_code} attribute is 4xx/5xx, even when no exception was
 * thrown (운영자 가치: 빨간 점이 진짜 빨간 점이어야 함).
 */
class AdviceSupportHttpStatusTest {

    private SpanQueue queue;

    @BeforeEach
    void setup() {
        queue = new SpanQueue(64);
        InstrumentationInstaller.QUEUE = queue;
        InstrumentationInstaller.SERVICE_NAME = "test-service";
        InstrumentationInstaller.PAYLOAD_MAX_BYTES = 65_536;
        InstrumentationInstaller.MASKING = null;
        InstrumentationInstaller.DEBUG = false;
    }

    @AfterEach
    void teardown() {
        TraceContext.clear();
        InstrumentationInstaller.QUEUE = null;
    }

    private SpanStatus runExitWithStatusCode(Object statusCode) {
        TraceContext.Frame frame = TraceContext.push("GET /users/{id}", "SERVER");
        Map<String, Object> attrs = new HashMap<>();
        if (statusCode != null) {
            attrs.put("http.status_code", statusCode);
        }
        AdviceSupport.exit(frame, null, attrs, null);
        List<Span> drained = new ArrayList<>();
        queue.drainTo(drained);
        assertEquals(1, drained.size(), "exactly one span enqueued");
        return drained.get(0).status();
    }

    @Test
    void status200IsOk() {
        assertEquals(SpanStatus.OK, runExitWithStatusCode(200));
    }

    @Test
    void status404IsError() {
        assertEquals(SpanStatus.ERROR, runExitWithStatusCode(404));
    }

    @Test
    void status500IsError() {
        assertEquals(SpanStatus.ERROR, runExitWithStatusCode(500));
    }

    @Test
    void status399IsOkBoundary() {
        // 경계 검증: 399는 redirect 류, ERROR로 간주하지 않는다.
        assertEquals(SpanStatus.OK, runExitWithStatusCode(399));
    }

    @Test
    void noStatusCodeIsOkWhenNoThrown() {
        // http.status_code 자체가 없으면 thrown 기준만 적용 — 기존 동작 유지.
        assertEquals(SpanStatus.OK, runExitWithStatusCode(null));
    }

    @Test
    void thrownTakesPrecedenceOverStatusCode() {
        // thrown != null 이면 status_code 200이어도 ERROR (예외가 우선).
        TraceContext.Frame frame = TraceContext.push("POST /users", "SERVER");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("http.status_code", 200);
        AdviceSupport.exit(frame, new RuntimeException("boom"), attrs, null);
        List<Span> drained = new ArrayList<>();
        queue.drainTo(drained);
        assertEquals(1, drained.size());
        assertEquals(SpanStatus.ERROR, drained.get(0).status());
    }
}
