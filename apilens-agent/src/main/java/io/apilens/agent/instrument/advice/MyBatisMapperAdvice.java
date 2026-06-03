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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps {@code org.apache.ibatis.binding.MapperProxy.invoke(Object, Method, Object[])}.
 *
 * <p>MyBatis mappers are user-declared {@code interface}s; at runtime MyBatis
 * instantiates a {@link java.lang.reflect.Proxy} backed by {@code MapperProxy}.
 * The user-facing classes are interfaces with no concrete implementation, so the
 * Spring-style {@code @Repository} / {@code JpaRepository} matchers used by
 * {@link RepositoryAdvice} do not see them.
 *
 * <p>All mapper calls (e.g. {@code dashboardMapper.selectRecentJobs(arg)}) flow
 * through {@code MapperProxy.invoke}. Intercepting that one method captures
 * every mapper invocation with a single instrumentation point, producing an
 * INTERNAL span named {@code <FQN>#<methodName>} so operators see the original
 * mapper interface (not the proxy) in the trace graph.
 *
 * <p>Object methods (toString/equals/hashCode) and methods coming from any
 * non-mapper interface are skipped via the {@code Object} declaring-class check.
 */
public class MyBatisMapperAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(@Advice.Argument(1) Method method) {
        if (method == null) {
            return null;
        }
        // Object methods routed through invoke — skip noise.
        if (method.getDeclaringClass() == Object.class) {
            return null;
        }
        String typeName = method.getDeclaringClass().getName();
        String methodName = method.getName();
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] MyBatisMapperAdvice.enter " + typeName + "#" + methodName);
        }
        return AdviceSupport.enter(typeName + "#" + methodName, "INTERNAL");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter TraceContext.Frame frame,
                            @Advice.Argument(1) Method method,
                            @Advice.Argument(2) Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
                            @Advice.Thrown Throwable thrown) {
        if (frame == null) {
            return;
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("code.namespace", method.getDeclaringClass().getName());
        attributes.put("code.function", method.getName());
        String inBody = AdviceSupport.serializeArgs(args);
        String outBody = thrown == null ? AdviceSupport.serializeReturn(returnValue) : null;
        List<Payload> payloads = AdviceSupport.payloadsOf(inBody, outBody);
        AdviceSupport.exit(frame, thrown, attributes, payloads);
    }
}
