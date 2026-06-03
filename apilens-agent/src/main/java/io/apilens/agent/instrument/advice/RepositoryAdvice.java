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
 * Wraps public business methods on Spring Data repositories (and {@code @Repository}
 * classes). Produces an INTERNAL span. Same args/return capture as
 * {@link ControllerAdvice} / {@link ServiceAdvice} — operators want to see the
 * query parameters and the mapped result rows (typical {@code findByXxx(arg) →
 * List<Entity>} flow).
 *
 * <p>{@link AdviceSupport#serializeReturn} truncates per
 * {@code apilens.payload.max-bytes} so large result lists won't bloat trace
 * memory.
 */
public class RepositoryAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] RepositoryAdvice.enter " + typeName + "#" + methodName);
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
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] RepositoryAdvice.exit " + typeName + "#" + methodName
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
