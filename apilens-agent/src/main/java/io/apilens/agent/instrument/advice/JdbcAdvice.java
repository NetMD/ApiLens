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
import io.apilens.agent.instrument.jdbc.JdbcParamCache;
import io.apilens.agent.instrument.jdbc.JdbcParamSerializer;
import io.apilens.agent.instrument.jdbc.JdbcResultSetCache;
import io.apilens.agent.instrument.jdbc.JdbcSqlCache;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps {@code PreparedStatement.execute / executeQuery / executeUpdate /
 * executeLargeUpdate}. Produces a DB span at the leaf of the trace tree.
 *
 * <p>SQL is recovered from {@link JdbcSqlCache}, populated at {@code prepareStatement}
 * time by {@link JdbcConnectionAdvice}.
 *
 * <p>Re-entrancy: pool proxies (HikariCP / driver) commonly stack 3 wrappers on a
 * single {@code execute*} call. {@link AdviceSupport#enterDbSpan} returns the
 * {@link TraceContext.Frame#SKIPPED} sentinel for inner layers so we record
 * exactly one DB span.
 */
public class JdbcAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceContext.Frame enter(@Advice.Origin("#t") String typeName) {
        return AdviceSupport.enterDbSpan(typeName, "jdbc.execute");
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void exit(@Advice.Enter TraceContext.Frame frame,
                            @Advice.This Object self,
                            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                            @Advice.Thrown Throwable thrown) {
        if (frame == TraceContext.Frame.SKIPPED) {
            // Inner wrapper: NO_OP via AdviceSupport.exit; ThreadLocal stays set
            // until the outer exit cleans it up.
            AdviceSupport.exit(frame, thrown, null, null);
            return;
        }
        Map<String, Object> attributes = new HashMap<>();
        List<Payload> payloads = null;
        try {
            if (self instanceof PreparedStatement ps) {
                String sql = JdbcSqlCache.get(ps);
                if (sql != null) {
                    attributes.put("db.statement", sql);
                }
                // ─── Phase E3 — JDBC parameter capture flush (사용자 비협상 D-01/D-04) ───
                // PreparedStatementParamAdvice 가 누적한 슬롯을 1회 PAYLOAD IN 으로 직렬화.
                // outer exit 가 owns ("set 한 측이 remove" 패턴) — clear 호출은 finally 블록에서.
                // CAPTURE_PARAMS=false 일 때 advice 등록 자체 skip 되므로 cache 가 비어있지만,
                // 옵션이 런타임 토글된 hot-reload 시나리오를 위해 defensive 한 분기.
                if (InstrumentationInstaller.CAPTURE_PARAMS) {
                    try {
                        List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
                        if (slots != null && !slots.isEmpty()) {
                            String paramJson = JdbcParamSerializer.serialize(slots);
                            if (paramJson != null) {
                                if (payloads == null) {
                                    payloads = new ArrayList<>(1);
                                }
                                payloads.add(AdviceSupport.maskedPayload(
                                        PayloadDirection.IN,
                                        AdviceSupport.CONTENT_TYPE_JSON,
                                        paramJson));
                                if (InstrumentationInstaller.DEBUG) {
                                    System.err.println("[ApiLens][JDBC-PARAM] exit FLUSH ps.cls="
                                            + ps.getClass().getName()
                                            + " ps.id=" + System.identityHashCode(ps)
                                            + " slots=" + slots.size()
                                            + " json.len=" + paramJson.length());
                                }
                            }
                            if (slots.size() > 1) {
                                attributes.put("db.batch_size", slots.size());
                            }
                        } else if (InstrumentationInstaller.DEBUG) {
                            System.err.println("[ApiLens][JDBC-PARAM] exit MISS  ps.cls="
                                    + ps.getClass().getName()
                                    + " ps.id=" + System.identityHashCode(ps)
                                    + " (no slots — setter advice 미발동 또는 instance mismatch)");
                        }
                    } catch (Throwable ignore) {
                        // best-effort param flush — host throw 0 (사용자 비협상 D-05)
                    } finally {
                        JdbcParamCache.clear(ps);
                    }
                }
            }
            if (returned instanceof Number n) {
                // executeUpdate / executeLargeUpdate return affected row count
                attributes.put("db.rows_affected", n.longValue());
            } else if (returned instanceof Boolean b) {
                // execute() returns whether the result is a ResultSet.
                attributes.put("db.execute.has_resultset", returned);
                // MyBatis pattern — execute() boolean true, then getResultSet() separately.
                // Pull the ResultSet here, wrap it, and stash the wrapper for
                // JdbcGetResultSetAdvice to substitute when the caller asks.
                if (b && self instanceof PreparedStatement ps && InstrumentationInstaller.CAPTURE_RESULT_SET) {
                    try {
                        ResultSet rs = ps.getResultSet();
                        if (rs != null) {
                            List<Payload> capturedPayloads = new ArrayList<>(1);
                            ResultSet wrapper = AdviceSupport.tryCaptureResultSet(rs, attributes, capturedPayloads);
                            if (wrapper != null) {
                                JdbcResultSetCache.put(ps, wrapper);
                                if (!capturedPayloads.isEmpty()) {
                                    // Phase E3 fix³ (2026-05-14 VAMS dogfooding R15) — 누적 패턴.
                                    // 직전 param flush 분기가 만든 PAYLOAD IN 을 덮어쓰지 않도록
                                    // List 대체 (`payloads = capturedPayloads`) → addAll 누적으로 전환.
                                    if (payloads == null) {
                                        payloads = capturedPayloads;
                                    } else {
                                        payloads.addAll(capturedPayloads);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignore) {
                        // best-effort — host app must keep running even if we can't get the RS
                    }
                }
            } else if (returned instanceof ResultSet rs && InstrumentationInstaller.CAPTURE_RESULT_SET) {
                // opt-in capture (apilens.jdbc.capture-result-set=true) — preread rows + return wrapper.
                // tryCaptureResultSet returns null on failure; in that case we leave `returned` alone,
                // which means the host app gets back the original (possibly partially-iterated) ResultSet.
                // Opt-in flag is the explicit acceptance of that risk.
                List<Payload> capturedPayloads = new ArrayList<>(1);
                ResultSet wrapper = AdviceSupport.tryCaptureResultSet(rs, attributes, capturedPayloads);
                if (wrapper != null) {
                    returned = wrapper;
                    if (!capturedPayloads.isEmpty()) {
                        // Phase E3 fix³ — 누적 패턴 (위 분기와 동일 사유).
                        if (payloads == null) {
                            payloads = capturedPayloads;
                        } else {
                            payloads.addAll(capturedPayloads);
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // best-effort attribute extraction
        }
        try {
            AdviceSupport.exit(frame, thrown, attributes, payloads);
        } finally {
            // outer exit owns the ThreadLocal cleanup ("set 한 측이 remove")
            AdviceSupport.markDbSpanExited(true);
        }
    }
}
