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
import io.apilens.agent.instrument.jdbc.JdbcSqlCache;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.sql.PreparedStatement;

/**
 * Wraps {@code Connection.prepareStatement(String sql)} so the SQL string is
 * paired with the resulting {@link PreparedStatement} instance. {@link JdbcAdvice}
 * later reads it back at execute-time — the {@link PreparedStatement} JDBC API
 * exposes no public way to recover the SQL.
 */
public class JdbcConnectionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterPrepare(@Advice.Argument(0) String sql,
                                    @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned) {
        try {
            if (returned instanceof PreparedStatement ps) {
                JdbcSqlCache.put(ps, sql);
            }
        } catch (Throwable t) {
            // silent
        }
        // Diagnostic — only emits when apilens.debug=true
        AdviceSupport.logPrepareReturn(returned, sql);
    }
}
