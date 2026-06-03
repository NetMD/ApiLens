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
 * Hooks {@link PreparedStatement#addBatch()} (no-arg) to mark a batch boundary
 * in {@link JdbcParamCache}. Subsequent {@code setXxx} calls land in a fresh
 * slot so {@link JdbcAdvice} can render one PAYLOAD IN per batch slot at
 * execute time.
 *
 * <p><b>Phase ID:</b> Phase E3 (JDBC parameter capture).
 *
 * <p><b>AC mapping:</b> AC-01-4, AC-01-5, AC-01-6 (design §3.3).
 *
 * <p><b>User-prescribed (non-negotiable) decisions:</b>
 * <ul>
 *   <li>D-01 — no-arg {@code addBatch()} only. {@code Statement.addBatch(String)}
 *       (1-arg) is excluded at the matcher layer
 *       ({@code SpringMatchers.preparedStatementAddBatchMethod()}).</li>
 *   <li>D-05 — host throw 0: try-catch(Throwable) + silent drop.</li>
 * </ul>
 *
 * <p><b>CLAUDE.md rules in force:</b>
 * <ul>
 *   <li>"Agent 자체 장애가 host 앱에 영향 0" — silent drop pattern.</li>
 *   <li>"@Advice helper 내부 private static 금지" — delegates to
 *       {@link JdbcParamCache}.</li>
 *   <li>"@Advice.Origin String 만 사용, Method 객체 금지".</li>
 * </ul>
 */
public class PreparedStatementAddBatchAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onAddBatch(@Advice.This Object self,
                                  @Advice.Origin("#t") String typeName) {
        if (!InstrumentationInstaller.CAPTURE_PARAMS) {
            return;
        }
        try {
            if (self instanceof PreparedStatement ps) {
                JdbcParamCache.commitBatchSlot(ps);
            }
        } catch (Throwable t) {
            // silent drop — host throw 0 (사용자 비협상 D-05)
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] advice.onAddBatch FAILED in "
                        + typeName + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
