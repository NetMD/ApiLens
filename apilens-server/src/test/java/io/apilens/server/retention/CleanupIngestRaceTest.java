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
package io.apilens.server.retention;

import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestProperties;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import io.apilens.server.support.TestMappers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R32] R32/AC-R32-04-4 — 밤 정리가 쓰기 잠금을 쥔 사이에 수신이 들어와도 수신 쓰기는 잠금을 기다렸다가 이어 쓴다 —
 * 유실 0 · 행 1.
 *
 * <p>AC-R32-04-4 원문: "정리 중 수신 두 연결 경주 시험이 결정적으로 돎 · 설계 표에 「빨강 입력이 만드는 값」 칸 · 빨강 실증은
 * main 소스를 안 바꾸는 반전 입력으로".
 *
 * <p>차례는 시각이 아니라 신호로 맞춘다({@code Thread.sleep} 0): ① 정리 갈래가 첫 쓰기 트랜잭션 안(삭제 문장 뒤 · 커밋 전)에서
 * {@code lockHeld} 를 알린다 ② 수신 갈래가 그 신호를 받고 {@link IngestService#ingest} 를 부른다 ③ 수신 연결이 첫 쓰기 문장을
 * 내는 순간(데이터 소스 감싸개) {@code ingestEntered} 를 알린다 ④ 정리 갈래는 그 신호를 받은 뒤 {@link #HOLD_MS} 를 쥐고
 * 커밋한다. 쥠은 대기 객체의 시한 대기다.
 *
 * <p>★겹침의 직접 증거: 수신의 첫 쓰기 문장이 정리 커밋 <b>전에 시작</b>해 커밋 <b>뒤에 끝났다</b> — 그 문장이 잠금에서
 * 기다렸다는 뜻이다. 겹침 없이 통과하는 길(쓰기가 커밋 뒤에야 시작)은 전제 단언이 막는다.
 *
 * <p>★한계 그 자리에: 한 청크의 첫 쓰기 문장만 잰다 — 청크가 여럿인 큰 trace 의 뒤 청크 · 요약 · 서비스 등록 쓰기가 정리의 뒤
 * 단계와 겹치는 갈래는 직접 재지 않는다(그 쓰기도 같은 대기 상한을 탄다). 단언이 빨강을 실제로 내는지는 시험 DB URL 의
 * {@code busy_timeout} 글자만 0 으로 바꾼 사본으로 실증한다(커밋하지 않음 · 상수 {@link #BUSY_TIMEOUT_MS} 는 그대로).
 * 생성자 · 진입점 무변 — 수신은 4 인자 공개 생성자 그대로 짓는다.
 */
class CleanupIngestRaceTest {

    /** 운영 {@code application.yml} datasource url 의 대기 상한(ms) — 아래 시험 DB URL 글자와 같은 값이다. */
    private static final long BUSY_TIMEOUT_MS = 10_000L;

    /**
     * 시험 DB URL — 운영 datasource url 의 질의 글자 그대로(파일 자리만 임시). 드라이버로 직접 연다 — {@code SQLiteDataSource}
     * 는 기본 설정이 대기 상한 3000 을 먼저 실어 URL 의 {@code busy_timeout} 을 무시한다(이 라운드 실측 · 운영 연결 풀은 URL 값을
     * 쓴다).
     */
    private static final String DB_PARAMS = "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=10000";

    /** 정리가 쓰기 잠금을 쥐고 버티는 시간(ms) — 대기 상한보다 짧아야 수신이 기다리다 이긴다. */
    private static final long HOLD_MS = 300L;

    /** 두 갈래를 기다리는 시험 상한(s) — 합격선이 아니라 멈춤 방지다. */
    private static final long JOIN_SECONDS = 60L;

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;

    @Test
    void ingestDuringTheCleanupWriteLockWaitsAndLosesNothing() throws Exception {
        assertTrue(HOLD_MS < BUSY_TIMEOUT_MS,
                "precondition-hold: 쥠(" + HOLD_MS + " ms)이 대기 상한(" + BUSY_TIMEOUT_MS + " ms)보다 짧다");

        Path db = tempDir.resolve("race.db").toAbsolutePath();
        DataSource source = new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + db + DB_PARAMS);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        long now = System.currentTimeMillis();
        seedExpiredTrace(jdbc, now - 100 * DAY_MS);
        Integer expired = jdbc.queryForObject("SELECT COUNT(*) FROM traces WHERE received_at < ?", Integer.class,
                now - 30 * DAY_MS);
        assertTrue(expired != null && expired >= 1, "precondition-seed: 만료 trace ≥ 1 — 실제 " + expired);

        RaceClock clock = new RaceClock();

        // 정리 갈래 — 운영 생성자 그대로 짓고, 트랜잭션 틀만 「첫 커밋 앞에서 쥐는」 시험 틀로 바꾼다.
        SettingsService settings = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        RetentionCleanupService cleanup =
                new RetentionCleanupService(jdbc, new DataSourceTransactionManager(source), settings);
        setTx(cleanup, new HoldFirstCommitTx(new DataSourceTransactionManager(source), clock::holdBeforeFirstCommit));

        // 수신 갈래 — 4 인자 공개 생성자 그대로 · 데이터 소스만 첫 쓰기를 알리는 감싸개로.
        JdbcTemplate ingestJdbc = new JdbcTemplate(clock.wrap(source));
        MaskingEngineHolder masking = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), TestMappers.forMasking());
        masking.reload();
        IngestService ingest = new IngestService(ingestJdbc, masking, TestMappers.forServices(),
                new IngestProperties(1_048_576L));
        IngestRequest request = new IngestRequest(List.of(new Span("race-span-1", "race-trace-1", null,
                "race-svc", "GET /race", SpanKind.SERVER, now, now + 10, SpanStatus.OK, null, List.of())));

        Callable<Void> cleanupBranch = () -> {
            cleanup.cleanup(now);
            return null;
        };
        Callable<Void> ingestBranch = () -> {
            if (clock.lockHeld.await(BUSY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                ingest.ingest(request);
                clock.ingestDoneAt.set(System.nanoTime());
            }
            return null;
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Void> cleanupDone = pool.submit(cleanupBranch);
            Future<Void> ingestDone = pool.submit(ingestBranch);
            assertDoesNotThrow(() -> cleanupDone.get(JOIN_SECONDS, TimeUnit.SECONDS), "precondition-done: 정리 갈래가 끝났다");
            assertDoesNotThrow(() -> ingestDone.get(JOIN_SECONDS, TimeUnit.SECONDS), "precondition-done: 수신 갈래가 끝났다");
        }

        long lockHeldAt = clock.lockHeldAt.get();
        long writeStartAt = clock.writeStartAt.get();
        long commitAt = clock.commitAt.get();
        String order = clock.describe();
        assertTrue(lockHeldAt != 0 && writeStartAt != 0 && commitAt != 0,
                "precondition-overlap: 세 신호가 다 찍혔다 — " + order);
        assertTrue(lockHeldAt <= writeStartAt && writeStartAt < commitAt,
                "precondition-overlap: 수신의 첫 쓰기는 잠금을 쥔 뒤 · 정리 커밋 전에 시작했다 — " + order);

        assertTrue(clock.writeDoneAt.get() > commitAt && clock.ingestDoneAt.get() > commitAt,
                "assert-ingest-after-commit: 수신의 첫 쓰기와 수신 전체가 정리 커밋 뒤에 끝났다(잠금에서 기다림) — " + order);
        assertEquals(0L, ingest.sqliteBusyDroppedCount(), "assert-no-busy-drop: 유실 청크 0 — " + order);
        assertEquals(0L, ingest.sqliteBusyEncounteredCount(), "assert-no-busy-drop: BUSY 만남 0 — " + order);
        Integer row = jdbc.queryForObject("SELECT COUNT(*) FROM spans WHERE span_id = 'race-span-1'", Integer.class);
        assertEquals(1, row == null ? 0 : row, "assert-span-row: 수신한 span 행 1");
        assertEquals(now, jdbc.queryForObject("SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class),
                "assert-cleanup-stamped: 정리 시각이 그대로 기록됐다");
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /** 만료 trace 하나(trace 1 · span 1 · payload 1) — 정리의 첫 쓰기 트랜잭션이 실제로 지울 대상이다. */
    private static void seedExpiredTrace(JdbcTemplate jdbc, long receivedAt) {
        jdbc.update("""
                INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                    status, span_count, service_count, has_error, received_at)
                VALUES ('race-expired', 'GET /old', 'race-svc', ?, 10, 'OK', 1, 1, 0, ?)
                """, receivedAt, receivedAt);
        jdbc.update("""
                INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                   span_kind, start_time, end_time, status, attributes_json)
                VALUES ('race-expired-s1', 'race-expired', NULL, 'race-svc', 'GET /old', 'SERVER', ?, ?, 'OK', NULL)
                """, receivedAt, receivedAt + 10);
        jdbc.update("""
                INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                VALUES ('race-expired-s1', 'in', 'application/json', '{}', 2, 0)
                """);
    }

    private static void setTx(RetentionCleanupService target, TransactionTemplate replacement) throws Exception {
        Field f = RetentionCleanupService.class.getDeclaredField("tx");
        f.setAccessible(true);
        f.set(target, replacement);
    }

    /**
     * 첫 트랜잭션 하나만 콜백이 끝난 뒤 · 커밋 전에 주어진 일을 한다 — 정리의 첫 쓰기 트랜잭션(배치 삭제)이 그 자리다.
     * 그 뒤 트랜잭션은 그대로 지나간다.
     */
    private static final class HoldFirstCommitTx extends TransactionTemplate {

        private static final long serialVersionUID = 1L;

        private final transient AtomicBoolean pending = new AtomicBoolean(true);

        private final transient Runnable beforeFirstCommit;

        HoldFirstCommitTx(PlatformTransactionManager tm, Runnable beforeFirstCommit) {
            super(tm);
            this.beforeFirstCommit = beforeFirstCommit;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            if (!pending.compareAndSet(true, false)) {
                return super.execute(action);
            }
            return super.execute(status -> {
                T out = action.doInTransaction(status);
                beforeFirstCommit.run();
                return out;
            });
        }
    }

    /** 두 갈래의 신호와 시각(나노초 · 같은 JVM 단조 시계). */
    private static final class RaceClock {
        final CountDownLatch lockHeld = new CountDownLatch(1);
        final CountDownLatch ingestEntered = new CountDownLatch(1);
        final AtomicLong lockHeldAt = new AtomicLong();
        final AtomicLong writeStartAt = new AtomicLong();
        final AtomicLong writeDoneAt = new AtomicLong();
        final AtomicLong commitAt = new AtomicLong();
        final AtomicLong ingestDoneAt = new AtomicLong();
        final AtomicReference<String> firstWrite = new AtomicReference<>();
        private final AtomicBoolean firstWritePending = new AtomicBoolean(true);

        /** 정리 갈래 — 첫 쓰기 트랜잭션의 커밋 앞. 수신이 첫 쓰기를 낼 때까지 기다린 뒤 쥔다. */
        void holdBeforeFirstCommit() {
            lockHeldAt.set(System.nanoTime());
            lockHeld.countDown();
            try {
                ingestEntered.await(BUSY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                new CountDownLatch(1).await(HOLD_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while holding the cleanup write lock", e);
            }
            commitAt.set(System.nanoTime());
        }

        /** 수신 데이터 소스 감싸개 — 첫 쓰기 문장의 실행 시작 · 끝을 찍는다(시작 때 신호). */
        DataSource wrap(DataSource target) {
            return proxy(DataSource.class, target, (p, m, a) -> {
                Object out = invoke(target, m, a);
                if (m.getName().equals("getConnection") && out instanceof Connection c) {
                    return wrapConnection(c);
                }
                return out;
            });
        }

        private Connection wrapConnection(Connection target) {
            return proxy(Connection.class, target, (p, m, a) -> {
                Object out = invoke(target, m, a);
                if (m.getName().equals("prepareStatement") && a != null && a[0] instanceof String sql
                        && out instanceof PreparedStatement ps && isWrite(sql)) {
                    return wrapWrite(ps, sql);
                }
                return out;
            });
        }

        private PreparedStatement wrapWrite(PreparedStatement target, String sql) {
            return proxy(PreparedStatement.class, target, (p, m, a) -> {
                boolean first = m.getName().startsWith("execute") && firstWritePending.compareAndSet(true, false);
                if (first) {
                    firstWrite.set(sql.strip().lines().findFirst().orElse(sql));
                    writeStartAt.set(System.nanoTime());
                    ingestEntered.countDown();
                }
                try {
                    return invoke(target, m, a);
                } finally {
                    if (first) {
                        writeDoneAt.set(System.nanoTime());
                    }
                }
            });
        }

        private static boolean isWrite(String sql) {
            String head = sql.strip().toUpperCase(Locale.ROOT);
            return head.startsWith("INSERT") || head.startsWith("UPDATE") || head.startsWith("DELETE")
                    || head.startsWith("REPLACE");
        }

        /** 실패 메시지용 — 기준점(잠금을 쥔 때)에서 잰 밀리초. */
        String describe() {
            long base = lockHeldAt.get();
            Map<String, Long> at = new java.util.LinkedHashMap<>();
            at.put("writeStart", writeStartAt.get());
            at.put("commit", commitAt.get());
            at.put("writeDone", writeDoneAt.get());
            at.put("ingestDone", ingestDoneAt.get());
            StringBuilder sb = new StringBuilder("lockHeld=0ms");
            at.forEach((k, v) -> sb.append(' ').append(k).append('=')
                    .append(v == 0 ? "-" : ((v - base) / 1_000_000L) + "ms"));
            return sb.append(" firstWrite=").append(firstWrite.get()).toString();
        }

        /** 형 변환은 {@code Class#cast} 로 한다 — 검사 안 된 변환이 없어 억제 표기가 필요 없다. */
        private static <T> T proxy(Class<T> type, Object target, InvocationHandler h) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (p, m, a) -> switch (m.getName()) {
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        case "toString" -> "Probed(" + target + ")";
                        default -> h.invoke(p, m, a);
                    }));
        }

        private static Object invoke(Object target, Method m, Object[] a) throws Throwable {
            try {
                return m.invoke(target, a);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
