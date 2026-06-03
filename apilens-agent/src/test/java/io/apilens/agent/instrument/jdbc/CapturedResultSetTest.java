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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CapturedResultSet} backed by an in-memory SQLite database.
 * Uses real JDBC drivers so the wrapper's ORM-facing surface area (next /
 * getXxx / getMetaData / close / wasNull) is exercised against actual driver
 * output rather than mocks.
 */
class CapturedResultSetTest {

    @Test
    void capturesAllRowsWhenWithinLimits() throws Exception {
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 100, 65_536);

                assertEquals(2, captured.rowCount);
                assertFalse(captured.truncated);

                ResultSet wrap = captured.wrapper;
                assertTrue(wrap.next());
                assertEquals(1, wrap.getInt(1));
                assertEquals("alice", wrap.getString("name"));
                assertTrue(wrap.next());
                assertEquals(2, wrap.getInt("id"));
                assertEquals("bob", wrap.getString(2));
                assertFalse(wrap.next());
            }
        }
    }

    @Test
    void truncatesWhenMaxRowsExceeded() throws Exception {
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                // maxRows=1 → 2 rows present, only 1 captured
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 1, 65_536);

                assertEquals(1, captured.rowCount);
                assertTrue(captured.truncated);
            }
        }
    }

    @Test
    void wasNullTrackedAfterNullColumn() throws Exception {
        try (Connection conn = openWithNullableRow()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 100, 65_536);

                ResultSet wrap = captured.wrapper;
                assertTrue(wrap.next());
                assertEquals(7, wrap.getInt("id"));
                // null column read followed by wasNull() check — the typical ORM pattern
                wrap.getString("name");
                assertTrue(wrap.wasNull());
            }
        }
    }

    @Test
    void closeMarksClosed() throws Exception {
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 100, 65_536);
                ResultSet wrap = captured.wrapper;

                assertFalse(wrap.isClosed());
                wrap.close();
                assertTrue(wrap.isClosed());
            }
        }
    }

    @Test
    void lifecycleDelegatesToUnderlying() throws Exception {
        // [inter-pipeline] 옛 설계는 getStatement→null / unwrap→throw 로 stub 해 ORM 의 결과 정리·
        // 커넥션 lifecycle 을 깨 누수·롤백을 유발했음. 이제 lifecycle 은 원본 RS 에 위임한다.
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t");
                 ResultSet rs = ps.executeQuery()) {
                ResultSet wrap = CapturedResultSet.capture(rs, 100, 65_536).wrapper;

                // getStatement 가 원본 위임 — null stub 아님 (그 stub 이 누수/롤백 진원).
                assertNotNull(wrap.getStatement());
                assertTrue(wrap.isWrapperFor(ResultSet.class));
            }
        }
    }

    @Test
    void wrapperReturnsAllRowsEvenWhenCaptureBufferIsCapped() throws Exception {
        // [inter-pipeline] ★핵심★ result-set 캡처가 호스트 결과를 자르면 안 된다.
        // cap=2 로 5행을 캡처해도 호스트(wrapper)는 5행 전부를 받아야 한다
        // (앞 2행은 버퍼, 뒤 3행은 원본 위임). payload 버퍼만 cap, 호스트 데이터는 무손실.
        try (Connection conn = openWithN(5)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM t ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 2, 65_536);

                // payload 샘플 버퍼는 cap=2 로 잘림 (truncated 표시)
                assertEquals(2, captured.rowCount);
                assertTrue(captured.truncated);

                // 그러나 호스트는 5행 전부를 받아야 한다 (잘림 0)
                ResultSet wrap = captured.wrapper;
                int count = 0;
                int last = 0;
                while (wrap.next()) {
                    count++;
                    last = wrap.getInt("id");
                }
                assertEquals(5, count, "host must receive ALL rows, not the capped buffer");
                assertEquals(5, last, "delegated rows must continue correctly past the buffer");
            }
        }
    }

    @Test
    void columnLabelLookupResolvesIndex() throws Exception {
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id AS the_id, name FROM t");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 100, 65_536);

                ResultSet wrap = captured.wrapper;
                assertTrue(wrap.next());
                // Resolve by AS-aliased label
                assertEquals(1, wrap.getInt("the_id"));
            }
        }
    }

    // ─── convertValue (package-private) — JDBC 4.1+ getObject(col, Class<T>) 변환 검증 ───

    @Test
    void convertValueTimestampToLocalDateTime() {
        // VAMS 회귀 가드: MyBatis LocalDateTimeTypeHandler가 호출하는 케이스 직접 검증.
        // driver가 Timestamp을 반환했을 때 wrapper가 LocalDateTime으로 변환해야 함
        // (안 그러면 caller가 ClassCastException으로 깨짐 — 옵션 켰을 때 dogfooding 사례).
        Timestamp ts = Timestamp.valueOf("2026-05-13 20:56:11");
        Object converted = CapturedResultSet.convertValue(ts, LocalDateTime.class);
        assertInstanceOf(LocalDateTime.class, converted);
        assertEquals(ts.toLocalDateTime(), converted);
    }

    @Test
    void convertValueTimestampToLocalDate() {
        Timestamp ts = Timestamp.valueOf("2026-05-13 20:56:11");
        Object converted = CapturedResultSet.convertValue(ts, LocalDate.class);
        assertInstanceOf(LocalDate.class, converted);
        assertEquals(LocalDate.of(2026, 5, 13), converted);
    }

    @Test
    void convertValueNullStaysNull() {
        assertEquals(null, CapturedResultSet.convertValue(null, LocalDateTime.class));
    }

    @Test
    void convertValueNullPrimitiveReturnsMatchingWrapperType() {
        // [inter-pipeline NAS dogfooding] NULL → primitive 반환 타입과 정확히 일치하는 wrapper 여야
        // Proxy 언박싱이 안전하다. 기존엔 long/short/byte 에 Integer 0, float 에 Double 0.0 을 돌려
        // 호스트가 ClassCastException 으로 크래시했음 (int/double 은 우연히 일치해 안 터졌음).
        assertInstanceOf(Integer.class, CapturedResultSet.convertValue(null, int.class));
        assertInstanceOf(Long.class,    CapturedResultSet.convertValue(null, long.class));
        assertInstanceOf(Short.class,   CapturedResultSet.convertValue(null, short.class));
        assertInstanceOf(Byte.class,    CapturedResultSet.convertValue(null, byte.class));
        assertInstanceOf(Double.class,  CapturedResultSet.convertValue(null, double.class));
        assertInstanceOf(Float.class,   CapturedResultSet.convertValue(null, float.class));
        assertInstanceOf(Boolean.class, CapturedResultSet.convertValue(null, boolean.class));
        // wrapper/object 타입은 null 유지 — getObject(col, Long.class) on NULL → null (JDBC 정합).
        assertEquals(null, CapturedResultSet.convertValue(null, Long.class));
        assertEquals(null, CapturedResultSet.convertValue(null, Object.class));
    }

    @Test
    void primitiveGettersOnNullColumnReturnZeroWithoutClassCast() throws Exception {
        // [inter-pipeline NAS dogfooding] 실제 호스트 크래시 재현 — Proxy 를 통해 NULL BIGINT 컬럼에
        // getLong/getShort/getByte/getFloat 호출. 픽스 전엔 "Integer/Double cannot be cast to ..."
        // 으로 throw → vams restoreFromDb 기동 크래시. 아래 호출이 throw 없이 0 을 돌려줘야 한다.
        try (Connection conn = openWithNullNumericRow()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, verdict_id FROM t");
                 ResultSet rs = ps.executeQuery()) {
                ResultSet wrap = CapturedResultSet.capture(rs, 100, 65_536).wrapper;

                assertTrue(wrap.next());
                assertEquals(0L, wrap.getLong("verdict_id"));   // ← 핵심 회귀 가드 (Integer→Long)
                assertTrue(wrap.wasNull());
                assertEquals((short) 0, wrap.getShort("verdict_id"));
                assertEquals((byte) 0, wrap.getByte("verdict_id"));
                assertEquals(0.0f, wrap.getFloat("verdict_id"));
                assertEquals(0, wrap.getInt("verdict_id"));
                assertEquals(0.0d, wrap.getDouble("verdict_id"));
            }
        }
    }

    @Test
    void getLongOnNonNullBigintReturnsValue() throws Exception {
        // 정상 경로 회귀 가드 — 값이 있는 BIGINT 는 그대로 long 으로 반환.
        try (Connection conn = openWithNonNullBigint()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT verdict_id FROM t");
                 ResultSet rs = ps.executeQuery()) {
                ResultSet wrap = CapturedResultSet.capture(rs, 100, 65_536).wrapper;

                assertTrue(wrap.next());
                assertEquals(9_000_000_000L, wrap.getLong("verdict_id"));  // int 범위 초과 값
                assertFalse(wrap.wasNull());
            }
        }
    }

    @Test
    void getMetaDataExposesColumnCount() throws Exception {
        try (Connection conn = openWith2Rows()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM t");
                 ResultSet rs = ps.executeQuery()) {
                CapturedResultSet.Result captured = CapturedResultSet.capture(rs, 100, 65_536);

                assertEquals(2, captured.wrapper.getMetaData().getColumnCount());
            }
        }
    }

    // ─── fixtures ─────────────────────────────────────────────────────────────

    private static Connection openWith2Rows() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, name TEXT)");
            st.execute("INSERT INTO t VALUES (1, 'alice')");
            st.execute("INSERT INTO t VALUES (2, 'bob')");
        }
        return conn;
    }

    private static Connection openWithNullableRow() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, name TEXT)");
            st.execute("INSERT INTO t VALUES (7, NULL)");
        }
        return conn;
    }

    // NULL BIGINT 컬럼 — vams restoreFromDb 의 pending(verdict 미할당) 행 패턴.
    private static Connection openWithNullNumericRow() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, verdict_id BIGINT)");
            st.execute("INSERT INTO t VALUES (7, NULL)");
        }
        return conn;
    }

    // 값이 있는 BIGINT (int 범위 초과) — 정상 long 반환 확인용.
    private static Connection openWithNonNullBigint() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (verdict_id BIGINT)");
            st.execute("INSERT INTO t VALUES (9000000000)");
        }
        return conn;
    }

    // id 1..n 행 — capture cap 초과 시 호스트 무손실 위임 검증용.
    private static Connection openWithN(int n) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER)");
            for (int i = 1; i <= n; i++) {
                st.execute("INSERT INTO t VALUES (" + i + ")");
            }
        }
        return conn;
    }
}
