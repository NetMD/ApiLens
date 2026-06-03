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
package io.apilens.agent.instrument.jdbc;

import io.apilens.agent.instrument.InstrumentationInstaller;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Maps a {@link PreparedStatement} instance to a list of "batch slots", each a
 * {@code Map<parameterIndex, value>}. Mirrors the design of {@link JdbcSqlCache}
 * but captures {@code setXxx} parameter values instead of the SQL string.
 *
 * <p><b>Phase ID:</b> Phase E3 (JDBC parameter capture).
 *
 * <p><b>AC mapping:</b> AC-01-1, AC-01-2 (planner §2 / design §3.1).
 *
 * <p><b>User-prescribed (non-negotiable) decisions:</b>
 * <ul>
 *   <li>D-02 — internal shape is fixed at
 *       {@code WeakHashMap<PreparedStatement, List<Map<Integer, Object>>>} —
 *       identity-weak key reclaims closed/GC'd statements automatically.</li>
 *   <li>D-02 — 4-method public API: {@code put} / {@code commitBatchSlot} /
 *       {@code get} / {@code clear}. Signatures are pinned by user prompt;
 *       extending them re-opens the phase decision.</li>
 *   <li>D-05 — host throw 0: every public method wraps its body in
 *       {@code try-catch(Throwable)} + silent drop.</li>
 * </ul>
 *
 * <p><b>CLAUDE.md rules in force:</b>
 * <ul>
 *   <li>"Agent 자체 장애가 host 앱에 영향 0" — silent drop pattern.</li>
 *   <li>"모든 advice 코드는 try-catch 로 감싸고 실패 시 silent drop".</li>
 *   <li>No bootstrap classloader injection — JDK-standard data structures only.</li>
 * </ul>
 *
 * <p>Re-entrancy / multi-thread access is mitigated by
 * {@link Collections#synchronizedMap}; the per-statement list is also
 * synchronized on the {@code slots} instance so batch slot ordering is stable
 * even when multiple threads share a pooled statement.
 *
 * <p>{@link #MAX_BATCH_SLOTS} / {@link #MAX_PARAMS_PER_SLOT} are soft caps —
 * exceeded values are silently dropped, never thrown. This protects heap from a
 * runaway producer (e.g. a JDBC batch loop with no flush).
 */
public final class JdbcParamCache {

    /** Soft cap on batch slots per statement — protects against runaway addBatch() in user code. */
    static final int MAX_BATCH_SLOTS = 1_000;

    /** Soft cap on parameters per slot — defensive against unusual JDBC drivers. */
    static final int MAX_PARAMS_PER_SLOT = 1_024;

    // PreparedStatement instance 키 + 약한 참조 (자연 GC 의존, leak 0 보장).
    // 사용자 비협상 D-02 직접 인용 — 자료구조 변경 권한 없음.
    private static final Map<PreparedStatement, List<Map<Integer, Object>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private JdbcParamCache() {
    }

    /**
     * Store {@code value} at {@code parameterIndex} in the current (last) batch slot.
     *
     * <p>Silent no-op when {@code ps} is null, {@code parameterIndex} is outside
     * {@code [1, MAX_PARAMS_PER_SLOT]}, or any internal error occurs (D-05).
     */
    public static void put(PreparedStatement ps, int parameterIndex, Object value) {
        if (ps == null || parameterIndex < 1 || parameterIndex > MAX_PARAMS_PER_SLOT) {
            return;
        }
        try {
            // 약한 참조 회수 race 가드 — computeIfAbsent 가 null 키를 거부하므로
            // WeakHashMap 의 entry 회수 타이밍과 충돌 안 함.
            List<Map<Integer, Object>> slots = CACHE.computeIfAbsent(ps, k -> {
                List<Map<Integer, Object>> initial = new ArrayList<>(1);
                initial.add(new HashMap<>());
                return initial;
            });
            synchronized (slots) {
                if (slots.isEmpty()) {
                    slots.add(new HashMap<>());
                }
                Map<Integer, Object> current = slots.get(slots.size() - 1);
                current.put(parameterIndex, value);
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][JDBC-PARAM] put idx=" + parameterIndex
                            + " slot=" + (slots.size() - 1) + " cacheSize=" + CACHE.size());
                }
            }
        } catch (Throwable t) {
            // silent drop — host throw 0 (사용자 비협상 D-05)
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] put FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Close the current batch slot and open a new empty one. Called on
     * {@code addBatch()} so subsequent {@code setXxx} calls land in a fresh slot.
     *
     * <p>Soft-caps at {@link #MAX_BATCH_SLOTS} — beyond that the cache stops
     * accumulating slots to protect heap from a runaway producer, but never throws.
     */
    public static void commitBatchSlot(PreparedStatement ps) {
        if (ps == null) {
            return;
        }
        try {
            List<Map<Integer, Object>> slots = CACHE.get(ps);
            if (slots == null) {
                // setXxx 가 한 번도 호출되지 않은 PS 에서 addBatch 호출된 비정상 경로 —
                // 빈 slot 을 새로 만들 가치 없음. 다음 setXxx 가 자체적으로 entry 생성.
                return;
            }
            synchronized (slots) {
                if (slots.size() >= MAX_BATCH_SLOTS) {
                    if (InstrumentationInstaller.DEBUG) {
                        System.err.println("[ApiLens][JDBC-PARAM] commitBatchSlot CAP "
                                + slots.size() + " >= MAX " + MAX_BATCH_SLOTS);
                    }
                    return;
                }
                slots.add(new HashMap<>());
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][JDBC-PARAM] commitBatchSlot opened slot="
                            + (slots.size() - 1));
                }
            }
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] commitBatchSlot FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Return the captured slots for {@code ps}, or {@code null} if none.
     * Returned list is a defensive copy so callers can safely iterate after
     * {@link #clear(PreparedStatement)}.
     */
    public static List<Map<Integer, Object>> get(PreparedStatement ps) {
        if (ps == null) {
            return null;
        }
        try {
            List<Map<Integer, Object>> slots = CACHE.get(ps);
            if (slots == null) {
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][JDBC-PARAM] get MISS cls="
                            + ps.getClass().getName());
                }
                return null;
            }
            synchronized (slots) {
                List<Map<Integer, Object>> copy = new ArrayList<>(slots.size());
                for (Map<Integer, Object> slot : slots) {
                    copy.add(new HashMap<>(slot));
                }
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][JDBC-PARAM] get cls="
                            + ps.getClass().getName() + " slots=" + copy.size());
                }
                return copy;
            }
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] get FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /** Drop all captured slots for {@code ps}. Called from {@link io.apilens.agent.instrument.advice.JdbcAdvice}. */
    public static void clear(PreparedStatement ps) {
        if (ps == null) {
            return;
        }
        try {
            CACHE.remove(ps);
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] clear FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /** Test-only diagnostic — current number of tracked statements. */
    static int currentSize() {
        return CACHE.size();
    }
}
