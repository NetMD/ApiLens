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
import io.apilens.agent.instrument.context.TraceContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Wraps Spring's {@code AbstractClientHttpRequest#execute()} — the single point every
 * built-in Spring request implementation (RestClient / RestTemplate) passes through.
 * Produces one CLIENT span per outbound call.
 *
 * <p>Both advice methods are one-line delegations: all logic lives in
 * {@link AdviceSupport} (advice bodies are inlined into the host class, so no helper
 * methods here and no Spring types in signatures).
 */
public class ClientHttpRequestAdvice {

    // [Phase R31] R31/AC-R31-01/AC-R31-03/AC-R31-09 — 외부 호출 구간(CLIENT span). 사용자 명시 비협상 결정
    //   (UD-R31-2 지점 · 속성 / UD-R31-9 갈래 ⓐ — 응답 머리까지). 두 advice 모두 suppress = Throwable —
    //   어떤 실패도 호스트 스레드로 새지 않는다. CLAUDE.md '아키텍처 핵심 원칙'(Agent 자체 장애가 호스트 앱에
    //   영향 0 · 절대 호스트 thread block 안 함) 인용.

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(@Advice.This Object request) {
        return AdviceSupport.enterClientSpan(request);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter TraceContext.Frame frame,
                            @Advice.This Object request,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object response,
                            @Advice.Thrown Throwable thrown) {
        AdviceSupport.exitClientSpan(frame, request, response, thrown);
    }
}
