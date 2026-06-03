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

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link JdbcParamCache} contents (a list of batch slots) into the
 * PAYLOAD IN body JSON string attached to a DB span.
 *
 * <p><b>Phase ID:</b> Phase E3 (JDBC parameter capture).
 *
 * <p><b>AC mapping:</b> AC-03-1, AC-03-2, AC-03-3, AC-03-4, AC-03-5 (design §3.8).
 *
 * <p><b>User-prescribed (non-negotiable) D-04 serialization rules:</b>
 * <ul>
 *   <li>{@code byte[]} → {@code "[B@<hex-prefix>]"} where the prefix is the first
 *       {@link #HEX_PREFIX_BYTES} bytes encoded as lowercase hex. Truncation marker
 *       is implicit via length (when {@code value.length > HEX_PREFIX_BYTES}).</li>
 *   <li>{@code null} (typically from {@code setNull}) → literal {@code "NULL"}.
 *       The {@code java.sql.Types} integer arrives at args[1] for setNull(int, int)
 *       and is captured as an {@link Integer} rather than as a true null — see the
 *       {@code Number} branch below — so the dedicated "NULL(typeName)" formatting
 *       happens only when the value really is {@code null}.</li>
 *   <li>{@link Date} / {@link Time} / {@link Timestamp} → ISO-8601 via the JDK
 *       {@link DateTimeFormatter} constants.</li>
 *   <li>{@link LocalDate} / {@link LocalTime} / {@link LocalDateTime} / {@link Instant}
 *       → ISO-8601 — defensive; the agent's matcher whitelists java.sql types only,
 *       but downstream MyBatis TypeHandlers occasionally hand off java.time values
 *       and we render them consistently rather than as {@code <unknown:...>}.</li>
 *   <li>String / Number / Boolean / BigDecimal → {@link Object#toString()}.</li>
 *   <li>Anything else → {@code "<unknown:" + simpleClassName + ">"} (defensive;
 *       D-04 "그 외 직렬화 정책 추정 금지" — silent fall-through, not throw).</li>
 * </ul>
 *
 * <p><b>JSON shape:</b>
 * <ul>
 *   <li>Single slot (no addBatch): {@code {"1":"value1","2":"value2",...}}.</li>
 *   <li>Batch (size &gt; 1): {@code {"batch_size":N,"batch":[{...},{...},...]}}.
 *       The caller also stamps {@code db.batch_size=N} as a span attribute.</li>
 * </ul>
 *
 * <p>Uses Jackson via {@link InstrumentationInstaller#MAPPER} which is shaded
 * (relocated to {@code io.apilens.agent.shaded.jackson}); no new external
 * dependency is introduced.
 *
 * <p><b>CLAUDE.md rules in force:</b>
 * <ul>
 *   <li>"Agent 자체 장애가 host 앱에 영향 0" — every method body has try-catch
 *       silent drop; serialization failures return {@code null} (caller skips
 *       PAYLOAD IN), they do not throw.</li>
 *   <li>External class — invoked by advice but lives outside the {@code advice}
 *       package, satisfying the "@Advice helper 내부 private static 금지" rule.</li>
 * </ul>
 */
public final class JdbcParamSerializer {

    /**
     * CL-01 confirmed: bytes shown before truncation. 16 bytes = 32 hex chars,
     * roughly the visual length of a UUID — readable in narrow trace cells
     * without overflowing. Aligned with {@code apilens.payload.max-bytes}
     * default (65,536) so each parameter cell is a negligible fraction of the
     * overall body budget.
     */
    static final int HEX_PREFIX_BYTES = 16;

    private JdbcParamSerializer() {
    }

    /**
     * Serialize the captured slots to a JSON string suitable for the PAYLOAD IN
     * body. Returns {@code null} when there is nothing to render or on any
     * internal error — callers should skip emitting a payload in that case.
     */
    public static String serialize(List<Map<Integer, Object>> slots) {
        if (slots == null || slots.isEmpty()) {
            return null;
        }
        try {
            if (InstrumentationInstaller.MAPPER == null) {
                return null;
            }
            if (slots.size() == 1) {
                Map<String, Object> shape = formatSlot(slots.get(0));
                return InstrumentationInstaller.MAPPER.writeValueAsString(shape);
            }
            Map<String, Object> outer = new LinkedHashMap<>(2);
            outer.put("batch_size", slots.size());
            List<Map<String, Object>> batch = new ArrayList<>(slots.size());
            for (Map<Integer, Object> slot : slots) {
                batch.add(formatSlot(slot));
            }
            outer.put("batch", batch);
            return InstrumentationInstaller.MAPPER.writeValueAsString(outer);
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC-PARAM] serialize FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /**
     * Convert a single slot's {@code Map<Integer, Object>} into a
     * {@code Map<String, Object>} where keys are the parameterIndex as decimal
     * strings, sorted ascending so the JSON output is deterministic.
     */
    static Map<String, Object> formatSlot(Map<Integer, Object> slot) {
        Map<String, Object> out = new LinkedHashMap<>(slot.size());
        slot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(String.valueOf(e.getKey()), formatValue(e.getValue())));
        return out;
    }

    /**
     * D-04 per-type formatter. Never throws; on unexpected error returns
     * {@code "<format-error>"} so the surrounding JSON still renders.
     */
    static String formatValue(Object value) {
        try {
            if (value == null) {
                return "NULL";
            }
            if (value instanceof byte[] bytes) {
                int len = Math.min(bytes.length, HEX_PREFIX_BYTES);
                StringBuilder sb = new StringBuilder(8 + len * 2);
                sb.append("[B@");
                for (int i = 0; i < len; i++) {
                    sb.append(String.format("%02x", bytes[i] & 0xff));
                }
                sb.append(']');
                return sb.toString();
            }
            if (value instanceof Timestamp ts) {
                return ts.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (value instanceof Date d) {
                return d.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            if (value instanceof Time t) {
                return t.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
            }
            if (value instanceof LocalDateTime ldt) {
                return ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (value instanceof LocalDate ld) {
                return ld.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            if (value instanceof LocalTime lt) {
                return lt.format(DateTimeFormatter.ISO_LOCAL_TIME);
            }
            if (value instanceof Instant inst) {
                return DateTimeFormatter.ISO_INSTANT.format(inst);
            }
            if (value instanceof String s) {
                return s;
            }
            if (value instanceof BigDecimal bd) {
                return bd.toPlainString();
            }
            if (value instanceof Number || value instanceof Boolean) {
                return value.toString();
            }
            return "<unknown:" + value.getClass().getSimpleName() + ">";
        } catch (Throwable t) {
            return "<format-error>";
        }
    }
}
