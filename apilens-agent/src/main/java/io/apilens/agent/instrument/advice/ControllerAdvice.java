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
 * Wraps every public instance method on a class annotated with
 * {@code @RestController} or {@code @Controller}. Produces the root SERVER span.
 *
 * <p>Note: helper methods on the advice class itself (private/package-private
 * statics) can fail to be inlined cleanly by ByteBuddy in some scenarios — keep
 * the advice body simple and call only into AdviceSupport, which is a normal
 * class loaded via the system classloader.
 */
public class ControllerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName) {
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] ControllerAdvice.enter " + typeName + "#" + methodName);
        }
        return AdviceSupport.enter(typeName + "#" + methodName, "SERVER");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter TraceContext.Frame frame,
                            @Advice.AllArguments Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
                            @Advice.Thrown Throwable thrown,
                            @Advice.Origin("#t") String typeName,
                            @Advice.Origin("#m") String methodName) {
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][RAW] ControllerAdvice.exit " + typeName + "#" + methodName
                    + " frame=" + (frame == null ? "null" : "ok")
                    + " argsLen=" + (args == null ? "null" : args.length)
                    + " ret=" + (returnValue == null ? "null" : returnValue.getClass().getSimpleName()));
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("code.namespace", typeName);
        attributes.put("code.function", methodName);
        // Reflectively probe args for HttpServletRequest/Response — best effort
        if (args != null) {
            for (Object a : args) {
                if (a == null) continue;
                String cn = a.getClass().getName();
                try {
                    if (cn.contains("HttpServletRequest")) {
                        Object m = a.getClass().getMethod("getMethod").invoke(a);
                        Object url = a.getClass().getMethod("getRequestURL").invoke(a);
                        if (m != null) attributes.put("http.method", m);
                        if (url != null) attributes.put("http.url", url.toString());
                    } else if (cn.contains("HttpServletResponse")) {
                        Object status = a.getClass().getMethod("getStatus").invoke(a);
                        if (status != null) attributes.put("http.status_code", status);
                    }
                } catch (Throwable ignore) {
                    // best-effort — never let reflection fail the request
                }
            }
        }
        String inBody = AdviceSupport.serializeArgs(args);
        String outBody = thrown == null ? AdviceSupport.serializeReturn(returnValue) : null;
        List<Payload> payloads = AdviceSupport.payloadsOf(inBody, outBody);
        AdviceSupport.exit(frame, thrown, attributes, payloads);
    }
}
