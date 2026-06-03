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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.apilens.agent.instrument.InstrumentationInstaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E3 — JdbcParamSerializer contract tests.
 *
 * <p>AC mapping: AC-03-1 ~ AC-03-5 (planner §2 US-03 / design §3.8).
 */
class JdbcParamSerializerTest {

    private ObjectMapper previousMapper;

    @BeforeEach
    void wireMapper() {
        previousMapper = InstrumentationInstaller.MAPPER;
        InstrumentationInstaller.MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @AfterEach
    void restoreMapper() {
        InstrumentationInstaller.MAPPER = previousMapper;
    }

    /** UT-SER-01: String value. */
    @Test
    void serializeStringValue() throws Exception {
        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, "hello")));

        assertNotNull(json);
        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("hello", shape.get("1"));
    }

    /** UT-SER-02: int value. */
    @Test
    void serializeIntValue() throws Exception {
        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, 42)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("42", shape.get("1"));
    }

    /** UT-SER-03: BigDecimal — no exponent / scientific notation. */
    @Test
    void serializeBigDecimalValue() throws Exception {
        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, new BigDecimal("123.456"))));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("123.456", shape.get("1"));
    }

    /**
     * UT-SER-04: byte[] longer than HEX_PREFIX_BYTES — truncated to exactly
     * {@link JdbcParamSerializer#HEX_PREFIX_BYTES} bytes (32 hex chars).
     */
    @Test
    void serializeByteArrayWithHexPrefix() throws Exception {
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) {
            data[i] = (byte) i; // 00 01 02 ... 1f
        }

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, data)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        String value = (String) shape.get("1");
        assertNotNull(value);
        assertTrue(value.startsWith("[B@"), "must start with [B@ marker: " + value);
        assertTrue(value.endsWith("]"));
        // 16 bytes * 2 hex chars = 32 chars between [B@ and ]
        String hex = value.substring(3, value.length() - 1);
        assertEquals(JdbcParamSerializer.HEX_PREFIX_BYTES * 2, hex.length());
        assertEquals("000102030405060708090a0b0c0d0e0f", hex);
    }

    /** UT-SER-05: byte[] shorter than prefix — full content, no truncation. */
    @Test
    void serializeByteArrayShorterThanPrefix() throws Exception {
        byte[] data = new byte[]{0x01, 0x02};

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, data)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("[B@0102]", shape.get("1"));
    }

    /** UT-SER-06: null value → literal "NULL". */
    @Test
    void serializeNullAsLiteralNull() throws Exception {
        Map<Integer, Object> slot = new HashMap<>();
        slot.put(1, null);

        String json = JdbcParamSerializer.serialize(slotsOf(slot));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("NULL", shape.get("1"));
    }

    /** UT-SER-07: java.sql.Date → ISO_LOCAL_DATE. */
    @Test
    void serializeSqlDateIso8601() throws Exception {
        Date d = Date.valueOf("2026-05-14");

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, d)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("2026-05-14", shape.get("1"));
    }

    /** UT-SER-08: java.sql.Time → ISO_LOCAL_TIME. */
    @Test
    void serializeSqlTimeIso8601() throws Exception {
        Time t = Time.valueOf("20:20:00");

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, t)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("20:20:00", shape.get("1"));
    }

    /** UT-SER-09: java.sql.Timestamp → ISO_LOCAL_DATE_TIME. */
    @Test
    void serializeSqlTimestampIso8601() throws Exception {
        Timestamp ts = Timestamp.valueOf("2026-05-14 20:20:00");

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, ts)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("2026-05-14T20:20:00", shape.get("1"));
    }

    /** UT-SER-10: Boolean → toString. */
    @Test
    void serializeBooleanValue() throws Exception {
        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, true)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("true", shape.get("1"));
    }

    /** UT-SER-11: index keys sorted ascending (deterministic JSON order). */
    @Test
    void serializeMultipleIndicesSorted() {
        Map<Integer, Object> slot = new HashMap<>();
        slot.put(3, "c");
        slot.put(1, "a");
        slot.put(2, "b");

        String json = JdbcParamSerializer.serialize(slotsOf(slot));

        // Order is "1":"a","2":"b","3":"c" by string parse — assert positional.
        int p1 = json.indexOf("\"1\"");
        int p2 = json.indexOf("\"2\"");
        int p3 = json.indexOf("\"3\"");
        assertTrue(p1 < p2 && p2 < p3, "keys must appear in ascending index order: " + json);
    }

    /** UT-SER-12: batch shape — batch_size + batch array. */
    @Test
    void serializeBatchSlotsShape() throws Exception {
        List<Map<Integer, Object>> slots = new ArrayList<>();
        slots.add(Map.of(1, "a"));
        slots.add(Map.of(1, "b"));
        slots.add(Map.of(1, "c"));

        String json = JdbcParamSerializer.serialize(slots);

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals(3, shape.get("batch_size"));
        List<?> batch = (List<?>) shape.get("batch");
        assertEquals(3, batch.size());
        assertEquals("a", ((Map<?, ?>) batch.get(0)).get("1"));
        assertEquals("b", ((Map<?, ?>) batch.get(1)).get("1"));
        assertEquals("c", ((Map<?, ?>) batch.get(2)).get("1"));
    }

    /** UT-SER-13: empty slots list returns null (caller skips PAYLOAD IN). */
    @Test
    void serializeEmptySlotsReturnsNull() {
        assertNull(JdbcParamSerializer.serialize(new ArrayList<>()));
    }

    /** UT-SER-14: unknown type → defensive "<unknown:...>" fall-through. */
    @Test
    void serializeUnknownTypeFallback() throws Exception {
        UUID id = new UUID(0L, 0L);

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, id)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("<unknown:UUID>", shape.get("1"));
    }

    /** UT-SER-15: null slots input returns null. */
    @Test
    void serializeNullSlotsReturnsNull() {
        assertNull(JdbcParamSerializer.serialize(null));
    }

    /** Bonus: java.time.LocalDateTime (defensive ISO-8601 branch). */
    @Test
    void serializeLocalDateTimeIso8601() throws Exception {
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 14, 20, 20, 0);

        String json = JdbcParamSerializer.serialize(slotsOf(Map.of(1, ldt)));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("2026-05-14T20:20:00", shape.get("1"));
    }

    /** Bonus: java.time.LocalDate / LocalTime / Instant defensive branches. */
    @Test
    void serializeOtherJavaTimeIso8601() throws Exception {
        Map<Integer, Object> slot = new HashMap<>();
        slot.put(1, LocalDate.of(2026, 5, 14));
        slot.put(2, LocalTime.of(20, 20));
        slot.put(3, Instant.parse("2026-05-14T11:20:00Z"));

        String json = JdbcParamSerializer.serialize(slotsOf(slot));

        Map<?, ?> shape = new ObjectMapper().readValue(json, Map.class);
        assertEquals("2026-05-14", shape.get("1"));
        assertEquals("20:20:00", shape.get("2"));
        assertEquals("2026-05-14T11:20:00Z", shape.get("3"));
    }

    private static List<Map<Integer, Object>> slotsOf(Map<Integer, Object> single) {
        List<Map<Integer, Object>> out = new ArrayList<>(1);
        out.add(new HashMap<>(single));
        return out;
    }
}
