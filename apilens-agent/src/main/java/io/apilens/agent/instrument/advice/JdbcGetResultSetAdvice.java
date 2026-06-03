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
import io.apilens.agent.instrument.jdbc.JdbcResultSetCache;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Wraps {@code PreparedStatement.getResultSet()}. Pairs with {@link JdbcAdvice} for
 * the MyBatis / raw-JDBC pattern where {@code execute()} is called first (returning
 * {@code boolean}) and then {@code getResultSet()} retrieves the actual rows.
 *
 * <p>{@link JdbcAdvice} stashes the {@link io.apilens.agent.instrument.jdbc.CapturedResultSet}
 * wrapper at {@code execute()} time when {@code apilens.jdbc.capture-result-set=true};
 * this advice consumes that stash and returns the wrapper to the caller so ORM
 * code gets the captured rows.
 *
 * <p>If the operator hasn't opted in, or no stash exists (e.g. {@code execute()}
 * was not used), {@code returned} is left untouched and the caller sees the
 * original driver ResultSet.
 */
public class JdbcGetResultSetAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self,
                            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned) {
        if (!InstrumentationInstaller.CAPTURE_RESULT_SET) {
            return;
        }
        if (!(self instanceof PreparedStatement ps)) {
            return;
        }
        ResultSet wrapper = JdbcResultSetCache.poll(ps);
        if (wrapper != null) {
            returned = wrapper;
        }
    }
}
