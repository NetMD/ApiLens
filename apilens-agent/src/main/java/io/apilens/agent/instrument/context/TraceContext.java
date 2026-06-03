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
package io.apilens.agent.instrument.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Per-thread span stack. Advice methods push a {@link Frame} on enter and pop
 * on exit, building parent-child relationships via {@code parentSpanId} = the
 * previous top of stack.
 *
 * <p>v0.1 단순화: WebFlux/{@code @Async} (스레드 경계 횡단) 미지원 — Phase F+에서.
 *
 * <p>Static-only because ByteBuddy advice methods are inlined into the target
 * class; instance state isn't transferable. ThreadLocal is the cleanest carrier.
 */
public final class TraceContext {

    private static final ThreadLocal<Deque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private TraceContext() {
    }

    /** Push a new span frame. Returns the same frame for fluent use in advice. */
    public static Frame push(String operationName, String spanKind) {
        Deque<Frame> stack = STACK.get();
        Frame parent = stack.peek();
        Frame frame = new Frame(
                parent != null ? parent.traceId : newTraceId(),
                newSpanId(),
                parent != null ? parent.spanId : null,
                operationName,
                spanKind,
                System.currentTimeMillis()
        );
        stack.push(frame);
        return frame;
    }

    /** Pop the topmost frame. Returns null if the stack was empty (defensive). */
    public static Frame pop() {
        Deque<Frame> stack = STACK.get();
        Frame frame = stack.poll();
        if (frame == null) {
            return null;
        }
        if (stack.isEmpty()) {
            // free the ThreadLocal map slot when this thread has no more frames
            STACK.remove();
        }
        return frame;
    }

    public static Frame peek() {
        return STACK.get().peek();
    }

    public static int depth() {
        return STACK.get().size();
    }

    /** For tests / shutdown — clear any leaked frames. */
    public static void clear() {
        STACK.remove();
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Mutable carrier for per-span state during advice enter→exit window.
     *
     * <p>Not a record: advice may need to mutate {@code attributes} after enter.
     */
    public static final class Frame {

        /**
         * Sentinel returned by enter helpers when a span must be skipped (re-entrancy
         * guard). Exit helpers must short-circuit when they see this instance — never
         * pop the live stack, never enqueue. {@code traceId}/{@code spanId} use a
         * placeholder that is impossible to collide with a real generated id.
         */
        public static final Frame SKIPPED = new Frame(
                "__SKIPPED__", "__SKIPPED__", null, "__SKIPPED__", "__SKIPPED__", 0L);

        public final String traceId;
        public final String spanId;
        public final String parentSpanId;
        public final String operationName;
        public final String spanKind;
        public final long startMillis;

        Frame(String traceId, String spanId, String parentSpanId,
              String operationName, String spanKind, long startMillis) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.operationName = operationName;
            this.spanKind = spanKind;
            this.startMillis = startMillis;
        }
    }
}
