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
import io.apilens.common.Payload;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps public instance methods on {@code @Service} classes. Produces an
 * INTERNAL span. Same args/return capture as {@link ControllerAdvice} — operators
 * want to see what the service received and what it returned to the controller
 * (typical "service builds a Map, controller wraps in ResponseEntity" flow).
 *
 * <p>Servlet-type args are skipped by {@link AdviceSupport#serializeArgs};
 * everything else is run through Jackson + masking + truncate.
 */
public class ServiceAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] ServiceAdvice.enter " + typeName + "#" + methodName);
        }
        return AdviceSupport.enter(typeName + "#" + methodName, "INTERNAL");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter TraceContext.Frame frame,
                            @Advice.AllArguments Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
                            @Advice.Thrown Throwable thrown,
                            @Advice.Origin("#t") String typeName,
                            @Advice.Origin("#m") String methodName) {
        // [Phase R20] R20/AC-01-8 — 조기 단락(W-11 확정: JdbcAdvice 전례 복제, 4 advice 동일 형태).
        // 억제(SKIPPED)·enter 실패(null) 시 직렬화/마스킹/payload 생성 없이 즉시 반환.
        if (frame == null || frame == TraceContext.Frame.SKIPPED) {
            AdviceSupport.exit(frame, thrown, null, null);
            return;
        }
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] ServiceAdvice.exit " + typeName + "#" + methodName
                    + " frame=" + (frame == null ? "null" : "ok")
                    + " argsLen=" + (args == null ? "null" : args.length)
                    + " ret=" + (returnValue == null ? "null" : returnValue.getClass().getSimpleName()));
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("code.namespace", typeName);
        attributes.put("code.function", methodName);
        String inBody = AdviceSupport.serializeArgs(args);
        String outBody = thrown == null ? AdviceSupport.serializeReturn(returnValue) : null;
        List<Payload> payloads = AdviceSupport.payloadsOf(inBody, outBody);
        AdviceSupport.exit(frame, thrown, attributes, payloads);
    }
}
