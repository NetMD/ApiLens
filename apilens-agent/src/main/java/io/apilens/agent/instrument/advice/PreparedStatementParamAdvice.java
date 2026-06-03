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

import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.agent.instrument.jdbc.JdbcParamCache;
import net.bytebuddy.asm.Advice;

import java.sql.PreparedStatement;

/**
 * Hooks {@link PreparedStatement}'s 12 standard setters and stashes the captured
 * parameter into {@link JdbcParamCache} keyed by the {@link PreparedStatement}
 * instance. {@link JdbcAdvice} flushes the stash on {@code execute*} exit and
 * renders the body of the PAYLOAD IN attached to the DB span.
 *
 * <p><b>Phase ID:</b> Phase E3 (JDBC parameter capture).
 *
 * <p><b>AC mapping:</b> AC-01-3, AC-01-5, AC-01-6, AC-05-1, AC-05-2 (design §3.2).
 *
 * <p><b>User-prescribed (non-negotiable) decisions:</b>
 * <ul>
 *   <li>D-01 — exactly 12 setters: setString / setInt / setLong / setDouble /
 *       setFloat / setBoolean / setBigDecimal / setDate / setTime / setTimestamp /
 *       setBytes / setNull. setObject / setArray / setBlob / setClob / setRef /
 *       driver-specific setters are out of scope for this phase.</li>
 *   <li>D-04 — serialization lives in
 *       {@link io.apilens.agent.instrument.jdbc.JdbcParamSerializer}; this advice
 *       only captures the raw value.</li>
 *   <li>D-05 — host throw 0: the body is wrapped in {@code try-catch(Throwable)}
 *       and any failure is silently dropped. ClassCastException /
 *       NoSuchMethodError / VerifyError at advice-weaving time are also caught.</li>
 * </ul>
 *
 * <p><b>CLAUDE.md rules in force:</b>
 * <ul>
 *   <li>"Agent 자체 장애가 host 앱에 영향 0" — silent drop pattern.</li>
 *   <li>"모든 advice 코드는 try-catch 로 감싸고 실패 시 silent drop".</li>
 *   <li>"@Advice helper 내부 private static 금지" — cache / serializer are
 *       delegated to external classes (JdbcParamCache / JdbcParamSerializer).</li>
 *   <li>"@Advice.Origin String 만 사용, Method 객체 금지" — only the {@code "#t"}
 *       string is requested below; Method objects trigger E2-era
 *       NoSuchMethodError during weaving.</li>
 * </ul>
 */
public class PreparedStatementParamAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onSetter(@Advice.This Object self,
                                @Advice.AllArguments Object[] args,
                                @Advice.Origin("#t") String typeName) {
        // Second-line defensive guard — primary guard is matcher non-registration
        // when CAPTURE_PARAMS=false. Hot-reload scenarios may leave compiled advice
        // bytecode in place; this check keeps the body a no-op in that case too.
        if (!InstrumentationInstaller.CAPTURE_PARAMS) {
            return;
        }
        try {
            // Entry debug — Phase E3 fix² (VAMS dogfooding R14). advice 가 실제로
            // weaving 됐는지 + self 클래스 + idHash 를 stderr 로 노출해서 끊긴 지점을
            // 한 번에 식별. Production 노이즈는 apilens.debug=true 명시 시에만 발생.
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] advice.entry type=" + typeName
                        + " self.cls=" + (self == null ? "null" : self.getClass().getName())
                        + " idHash=" + (self == null ? 0 : System.identityHashCode(self))
                        + " argsLen=" + (args == null ? -1 : args.length));
            }
            if (!(self instanceof PreparedStatement ps)) {
                return;
            }
            if (args == null || args.length < 2) {
                return;
            }
            if (!(args[0] instanceof Integer idxBoxed)) {
                return;
            }
            int parameterIndex = idxBoxed;
            // args[1] is the value for 11 of the 12 setters. For setNull(int, int)
            // and setNull(int, int, String) the int at args[1] is java.sql.Types —
            // JdbcParamSerializer renders that as a Number rather than as a true
            // null. The dedicated "NULL" literal is reserved for a value that is
            // actually {@code null}, which only happens for setNull(int) overloads
            // that are not part of D-01 anyway.
            Object value = args[1];
            JdbcParamCache.put(ps, parameterIndex, value);
        } catch (Throwable t) {
            // silent drop — host throw 0 (사용자 비협상 D-05)
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] advice.onSetter FAILED in "
                        + typeName + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
