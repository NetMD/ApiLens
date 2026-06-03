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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drop-in {@link ResultSet} replacement after the agent has eagerly preread
 * all rows for payload_out capture. Supports the subset of {@code ResultSet}
 * commonly used by ORM result-mapping (MyBatis {@code TypeHandler}, Spring JDBC
 * {@code RowMapper}, raw JDBC).
 *
 * <p>Returned to the caller via {@code @Advice.Return(readOnly = false)} only
 * when the operator opts in with {@code apilens.jdbc.capture-result-set=true}.
 * Driver-specific {@code unwrap} / scrollable navigation / row update APIs
 * throw {@link SQLFeatureNotSupportedException} — the opt-in flag is the
 * explicit acceptance of that risk.
 *
 * <p>Construction uses {@link Proxy} so we don't need to implement the
 * ~200-method {@code ResultSet} surface area; {@link #invoke} routes the
 * handful of methods we actually support.
 */
public final class CapturedResultSet implements InvocationHandler {

    /** Standard {@code getXxx(int|String)} family that should pull from the captured buffer. */
    private static final Set<String> GETTER_NAMES = Set.of(
            "getString", "getBoolean", "getByte", "getShort", "getInt", "getLong",
            "getFloat", "getDouble", "getBigDecimal", "getBytes",
            "getDate", "getTime", "getTimestamp",
            "getObject", "getNString");

    private final List<Object[]> rows;          // payload 샘플용으로 미리 읽은 앞부분 행 (버퍼)
    private final ResultSet underlying;          // 원본 RS — 버퍼 이후 행 + lifecycle 은 여기에 위임
    private final Map<String, Integer> labelIndex;
    private final int bufferedCount;             // 버퍼에 담긴 행 수
    private int cursor = -1;                      // 버퍼 위 가상 커서
    private boolean delegateMode;                // 버퍼 소진 후 true → 모든 호출 underlying 위임
    private boolean closed;
    private boolean wasNull;

    private CapturedResultSet(List<Object[]> rows,
                              ResultSet underlying,
                              Map<String, Integer> labelIndex) {
        this.rows = rows;
        this.underlying = underlying;
        this.labelIndex = labelIndex;
        this.bufferedCount = rows.size();
    }

    /**
     * Preread the first {@code maxRows} / {@code maxBytes} rows of {@code underlying}
     * into a buffer for the payload sample, then return a {@code ResultSet} proxy
     * that serves those buffered rows first and <strong>delegates every later row
     * (and all lifecycle/metadata calls) to {@code underlying}</strong>.
     *
     * <p>Crucially the host app still receives the <em>full</em> result set — the
     * buffer is only a capped sample for the captured payload, never a replacement
     * for the host's data. {@code underlying} is NOT closed here; the proxy delegates
     * {@code close()} so the driver/pool lifecycle stays intact (no connection leak,
     * no truncated host rows).
     *
     * <p>Throws if preread itself fails; callers must treat this as a capture
     * failure and skip wrapping (host app gets the original {@code ResultSet}).
     */
    public static Result capture(ResultSet underlying, int maxRows, int maxBytes) throws SQLException {
        ResultSetMetaData rawMd = underlying.getMetaData();
        int columnCount = rawMd.getColumnCount();
        SimpleResultSetMetaData snapshot = SimpleResultSetMetaData.from(rawMd);
        Map<String, Integer> labelIndex = new HashMap<>();
        for (int i = 1; i <= columnCount; i++) {
            labelIndex.putIfAbsent(rawMd.getColumnLabel(i), i);
            labelIndex.putIfAbsent(rawMd.getColumnName(i), i);
        }

        // payload 샘플용으로 앞부분만 버퍼에 읽는다. cap 까지만 읽고 멈추되, 멈춘 직후 원본 커서는
        // "마지막 버퍼 행 바로 다음"을 가리키므로, proxy 가 버퍼 소진 후 underlying.next() 를
        // 부르면 첫 미버퍼 행이 그대로 이어진다 (행 손실 0). 원본은 닫지 않는다.
        List<Object[]> rows = new ArrayList<>();
        int byteEstimate = 0;
        boolean truncated = false;
        while (rows.size() < maxRows) {
            if (!underlying.next()) {
                break;  // 결과 끝 — 전부 버퍼에 담김 (위임할 잔여 행 없음)
            }
            Object[] row = new Object[columnCount];
            int rowEstimate = 0;
            for (int i = 0; i < columnCount; i++) {
                Object v = underlying.getObject(i + 1);
                row[i] = v;
                if (v != null) {
                    rowEstimate += String.valueOf(v).length();
                }
            }
            if (!rows.isEmpty() && byteEstimate + rowEstimate > maxBytes) {
                // byte cap — 이 행까지만 버퍼에 담고 멈춘다 (이후 행은 proxy 가 원본에 위임).
                rows.add(row);
                truncated = true;
                break;
            }
            byteEstimate += rowEstimate;
            rows.add(row);
        }
        if (rows.size() == maxRows) {
            truncated = true;  // 행 cap 도달 — 더 있을 수 있음 (payload 표시; 호스트는 위임으로 전부 받음)
        }

        CapturedResultSet handler = new CapturedResultSet(rows, underlying, labelIndex);
        ResultSet proxy = (ResultSet) Proxy.newProxyInstance(
                CapturedResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class },
                handler);
        return new Result(proxy, rows, snapshot, rows.size(), truncated);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();

        // proxy 정체성 / close lifecycle.
        switch (name) {
            case "toString":
                return "CapturedResultSet[buffered=" + bufferedCount + ", cursor=" + cursor
                        + ", delegate=" + delegateMode + ", closed=" + closed + "]";
            case "equals":
                return proxy == (args == null ? null : args[0]);
            case "hashCode":
                return System.identityHashCode(this);
            case "close":
                closed = true;
                return invokeUnderlying(method, args);  // 원본 close 위임 — driver/pool lifecycle 보존
            case "isClosed":
                return closed || underlying.isClosed();
            default:
                break;
        }

        // next(): 버퍼 앞부분을 먼저 소비하고, 소진되면 원본에 위임한다.
        // capture() 가 원본을 닫지 않고 커서를 "마지막 버퍼 행 직후"에 둬서, 위임 next() 가
        // 첫 미버퍼 행부터 이어준다 → 호스트는 전체 결과를 받는다 (잘림 0).
        if ("next".equals(name) && (args == null || args.length == 0)) {
            if (closed) throw new SQLException("ResultSet is closed");
            if (!delegateMode && cursor + 1 < bufferedCount) {
                cursor++;
                return Boolean.TRUE;
            }
            delegateMode = true;
            return underlying.next();
        }

        // 버퍼 구간을 읽는 동안의 행 단위 read 는 버퍼에서 서빙 (이미 읽어둔 값 재사용).
        boolean inBuffer = !delegateMode && cursor >= 0 && cursor < bufferedCount;
        if (inBuffer) {
            if ("wasNull".equals(name)) {
                return wasNull;
            }
            if ("getRow".equals(name)) {
                return cursor + 1;
            }
            if (GETTER_NAMES.contains(name) && args != null && args.length >= 1) {
                // JDBC 4.1+ getObject(col, Class<T>) — MyBatis LocalDateTimeTypeHandler 등.
                if ("getObject".equals(name) && args.length >= 2 && args[1] instanceof Class<?> targetType) {
                    return getColumnValueWithTargetType(args, targetType);
                }
                return getColumnValue(name, args, method.getReturnType());
            }
            // 그 외(getMetaData / getWarnings / findColumn / navigation 등)는 아래에서 원본 위임.
        }

        // 그 외 전부 원본 RS 에 위임 — 호스트가 전체 데이터 + 정확한 metadata/lifecycle 을 받는다.
        // (옛 설계의 stub 들: getStatement→null / unwrap→throw / getMetaData→snapshot 이
        //  ORM 의 결과 매핑/정리 흐름을 깨 커넥션 누수·롤백을 유발했음 → 전면 위임으로 해소.)
        return invokeUnderlying(method, args);
    }

    /** 원본 RS 로 reflective 위임. InvocationTargetException 은 원래 예외로 풀어 던진다. */
    private Object invokeUnderlying(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(underlying, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private int resolveColumnIndex(Object locator) throws SQLException {
        if (locator instanceof Integer i) return i;
        if (locator instanceof String label) {
            Integer i = labelIndex.get(label);
            if (i == null) throw new SQLException("column not found: " + label);
            return i;
        }
        throw new SQLException("unsupported column locator: " + locator);
    }

    /**
     * Handle {@code getObject(int|String, Class<T>)} — JDBC 4.1+ API used by MyBatis
     * {@code LocalDateTimeTypeHandler} and friends. Variant of {@link #getColumnValue}
     * that uses the caller-supplied target {@link Class} rather than the method's
     * declared return type (which is just {@code Object}).
     */
    private Object getColumnValueWithTargetType(Object[] args, Class<?> targetType) throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("invalid cursor position: " + cursor);
        }
        int idx = resolveColumnIndex(args[0]);
        Object[] row = rows.get(cursor);
        if (idx < 1 || idx > row.length) {
            throw new SQLException("column index out of range: " + idx);
        }
        Object v = row[idx - 1];
        wasNull = (v == null);
        return convertValue(v, targetType);
    }

    private Object getColumnValue(String getter, Object[] args, Class<?> returnType) throws SQLException {
        if (closed) throw new SQLException("ResultSet is closed");
        if (cursor < 0 || cursor >= rows.size()) {
            throw new SQLException("invalid cursor position: " + cursor);
        }
        int idx = resolveColumnIndex(args[0]);
        Object[] row = rows.get(cursor);
        if (idx < 1 || idx > row.length) {
            throw new SQLException("column index out of range: " + idx);
        }
        Object v = row[idx - 1];
        wasNull = (v == null);
        return convertValue(v, returnType);
    }

    /**
     * Best-effort conversion to the method's declared return type. Driver-level
     * conversions are richer than this; if a caller needs an exotic conversion
     * (e.g. {@code getDate(int, Calendar)}) and we miss it, the operator can
     * turn off {@code apilens.jdbc.capture-result-set} to fall back to the
     * raw driver behaviour.
     */
    // package-private for unit test (CapturedResultSetTest) — driver returns vary
    // across JDBCs (e.g. SQLite stores TIMESTAMP as TEXT) so a hand-rolled
    // Timestamp input gives deterministic conversion checks.
    static Object convertValue(Object v, Class<?> returnType) {
        if (v == null) {
            // SQL NULL → primitive getter 는 typed zero 를 돌려준다 (JDBC: getLong → 0,
            // wasNull()==true). 단 반환 wrapper 가 primitive 반환 타입과 정확히 일치해야 한다 —
            // 이 값은 JDK Proxy 가 언박싱하므로, long 을 돌려주는 getLong 에 Integer 0 을 주면
            // "Integer cannot be cast to Long" 으로 호스트 앱이 크래시한다 (NAS dogfooding 실측:
            // NULL BIGINT verdict_id 에 getLong → vams restoreFromDb 기동 크래시 → 재시작 무한루프).
            // wrapper/object 타입 (getObject(col, Long.class) 등) 은 null 유지.
            if (returnType == int.class)     return 0;
            if (returnType == long.class)    return 0L;
            if (returnType == short.class)   return (short) 0;
            if (returnType == byte.class)    return (byte) 0;
            if (returnType == double.class)  return 0.0d;
            if (returnType == float.class)   return 0.0f;
            if (returnType == boolean.class) return false;
            return null;
        }
        if (returnType.isInstance(v)) return v;
        if (returnType == String.class) return v.toString();
        if (returnType == Object.class) return v;
        if (returnType == int.class || returnType == Integer.class) {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
        }
        if (returnType == long.class || returnType == Long.class) {
            return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
        }
        if (returnType == short.class || returnType == Short.class) {
            return v instanceof Number n ? n.shortValue() : Short.parseShort(v.toString());
        }
        if (returnType == byte.class || returnType == Byte.class) {
            return v instanceof Number n ? n.byteValue() : Byte.parseByte(v.toString());
        }
        if (returnType == double.class || returnType == Double.class) {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString());
        }
        if (returnType == float.class || returnType == Float.class) {
            return v instanceof Number n ? n.floatValue() : Float.parseFloat(v.toString());
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
        }
        if (returnType == BigDecimal.class) {
            if (v instanceof BigDecimal bd) return bd;
            if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return new BigDecimal(v.toString());
        }
        // JDBC 4.1+ java.time 변환 — MyBatis LocalDateTimeTypeHandler 등이
        // rs.getObject(col, LocalDateTime.class) 형태로 호출. driver는 preread
        // 시점에 java.sql.Timestamp/Date/Time을 반환했으므로 여기서 변환한다.
        if (returnType == LocalDateTime.class) {
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
            if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        }
        if (returnType == LocalDate.class) {
            if (v instanceof java.sql.Date d) return d.toLocalDate();
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        }
        if (returnType == LocalTime.class) {
            if (v instanceof java.sql.Time t) return t.toLocalTime();
            if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalTime();
        }
        if (returnType == Instant.class) {
            if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
            if (v instanceof java.util.Date d) return d.toInstant();
        }
        if (returnType == OffsetDateTime.class && v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (returnType == ZonedDateTime.class && v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atZone(ZoneId.systemDefault());
        }
        // 그 외 (Date/Time/Timestamp/byte[])는 underlying이 이미 적절한 타입을 채워줬을 가능성이 큼.
        // 안 맞으면 caller가 ClassCast를 받을 텐데, opt-in 위험 수용.
        return v;
    }

    /** Container for capture output — proxy + raw buffer for JSON serialisation. */
    public static final class Result {
        public final ResultSet wrapper;
        public final List<Object[]> rows;
        public final SimpleResultSetMetaData metaData;
        public final int rowCount;
        public final boolean truncated;

        Result(ResultSet wrapper, List<Object[]> rows, SimpleResultSetMetaData metaData,
               int rowCount, boolean truncated) {
            this.wrapper = wrapper;
            this.rows = rows;
            this.metaData = metaData;
            this.rowCount = rowCount;
            this.truncated = truncated;
        }
    }
}
