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
package io.apilens.server.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.query.TraceQueryRepository;
import io.apilens.server.query.dto.SpanDto;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R25] AC-25-02-1 ~ AC-25-02-7 — SQL 원문을 별도 표에 <b>한 번만</b> 둔다.
 *
 * <p>AC-25-02-4 원문: "<b>밖으로 나가는 응답이 지금과 같은 모양이다.</b> 상세 조회의 span 속성에서
 * {@code db.statement} 는 여전히 SQL 문자열이다. 화면 · 문서 · 계측기는 한 줄도 안 바뀐다." (비협상 — UD-2)
 *
 * <p>AC-25-02-5 의 세 규칙(ⓐ 예약 접두 ⓑ 원문 우선 ⓒ 밖에서 차지했으면 원문 그대로 저장)이
 * 쓰기·읽기 양쪽에서 실제로 도는지 본다 — 적재 입구는 인증이 없고 속성 키를 거르지 않기 때문이다.
 */
class SqlStatementInterningTest {

    private static final String SQL = "SELECT * FROM orders WHERE id = ?";

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;
    private TraceQueryRepository repository;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-stmt-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        MaskingEngineHolder holder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        holder.reload();
        this.service = new IngestService(jdbc, holder, mapper, new IngestProperties(1_048_576L));
        this.repository = new TraceQueryRepository(jdbc, mapper);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    /** TS-R25-15 — 왕복: 넣고 읽으면 {@code db.statement} 가 <b>SQL 문자열 그대로</b>다. */
    @Test
    void returnsTheStatementAsAPlainStringAfterARoundTrip() {
        service.ingest(new IngestRequest(List.of(dbSpan("s1", "t1", Map.of(
                IngestService.DB_STATEMENT_ATTRIBUTE, SQL, "db.rows_affected", 1)))));

        // 저장 쪽 — 원문은 표에 한 벌, span 은 지문만 갖는다.
        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"));
        assertEquals(0, count("SELECT COUNT(*) FROM spans WHERE attributes_json LIKE '%db.statement%'"),
                "저장된 속성에는 원문이 없다(지문만 있다)");

        // 읽기 쪽 — 응답 모양이 오늘과 같다.
        Map<String, Object> attributes = attributesOf("t1", "s1");
        assertEquals(SQL, attributes.get(IngestService.DB_STATEMENT_ATTRIBUTE));
        assertFalse(attributes.containsKey(IngestService.STMT_REF_ATTRIBUTE),
                "예약 키는 응답에 안 나온다");
        assertEquals(1, ((Number) attributes.get("db.rows_affected")).intValue(),
                "다른 속성 키는 한 글자도 안 바뀐다");
    }

    /** TS-R25-16 — 같은 span 을 두 번 받아도 표는 안 늘고 참조도 같다. */
    @Test
    void keepsTheStatementTableUnchangedWhenTheSameSpanArrivesTwice() {
        IngestRequest req = new IngestRequest(List.of(dbSpan("s1", "t1",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, SQL))));
        service.ingest(req);
        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"),
                "전제: 첫 적재로 원문 행이 실제로 하나 생겨야 대조가 뜻을 가진다");
        String firstRef = storedRefOf("s1");

        service.ingest(req);

        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"), "표 무중복");
        assertEquals(firstRef, storedRefOf("s1"), "참조가 같다");
        assertEquals(SQL, attributesOf("t1", "s1").get(IngestService.DB_STATEMENT_ATTRIBUTE));
    }

    /**
     * TS-R25-17 — 옛 형태(원문을 속성에 그대로 담은) 행도 <b>같은 읽기 경로</b>가 다룬다.
     *
     * <p>두 상태를 <b>한 시험 안에서</b> 잰다. 갈래가 다르기 때문이다:
     * <ol>
     *   <li><b>옛 행만 있는 trace</b> — 되돌릴 지문이 하나도 없어 후처리가 <b>시작도 안 한다</b>
     *       (오늘과 바이트 동일). 이것이 대부분의 옛 trace 다.</li>
     *   <li><b>옛 행과 새 행이 섞인 trace</b> — 후처리가 <b>돌면서</b> 옛 행을 그냥 지나가야 한다.
     *       ★올린 뒤 약 이틀간 운영에 실제로 있는 상태이고, ①만 재면 이 갈래는 <b>무방비</b>다
     *       (①은 이른 반환에 걸려 행별 통과 갈래를 한 번도 안 밟는다 — 실측으로 확인했다).</li>
     * </ol>
     */
    @Test
    void readsOldFormSpanRowsThatStillCarryTheStatementInline() {
        // ① 옛 행만 있는 trace — 백필을 안 하기로 했으므로 실제로 생기는 상태다.
        insertRawSpan("s-old", "t-old", "{\"db.statement\":\"" + SQL + "\"}");
        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements"),
                "전제: 원문 표가 비어 있어야 '옛 행은 표 없이도 읽힌다' 를 잰다");

        Map<String, Object> attributes = attributesOf("t-old", "s-old");

        assertEquals(SQL, attributes.get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "옛 행은 그대로 읽힌다 — 이 갈래를 지우면 이틀치가 조용히 빈 값이 된다");

        // ② 옛 행과 새 행이 한 trace 에 섞인 상태 — 후처리가 실제로 도는 갈래다.
        String mixedSql = "SELECT 'mixed'";
        service.ingest(new IngestRequest(List.of(dbSpan("s-mix-new", "t-mix",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, mixedSql)))));
        insertRawSpan("s-mix-old", "t-mix", "{\"db.statement\":\"" + SQL + "\"}");
        // 전제: 새 행이 실제로 참조를 갖는다 — 안 그러면 후처리가 이른 반환으로 빠져 ①과 같은 갈래가 된다.
        assertNotNull(storedRefOf("s-mix-new"), "전제: 새 행이 참조를 실제로 들고 있어야 후처리가 돈다");

        assertEquals(SQL, attributesOf("t-mix", "s-mix-old").get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "섞인 trace 에서도 옛 행이 응답에서 사라지지 않고 원문 그대로 읽힌다");
        assertEquals(mixedSql, attributesOf("t-mix", "s-mix-new").get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "같은 trace 의 새 행은 표에서 원문으로 되돌아온다");
    }

    /** TS-R25-18 — 양쪽 키가 다 있으면 <b>원문 우선</b>이다(밖에서 온 값을 안 덮는다). */
    @Test
    void prefersTheInlineStatementWhenBothKeysArePresent() {
        // 표에는 다른 원문을 심어 둔다 — 지문을 따라가면 이 값이 나오게 해서 우선순위를 실제로 가른다.
        String other = "SELECT 'from-the-table'";
        service.ingest(new IngestRequest(List.of(dbSpan("s-seed", "t-seed",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, other)))));
        String seededRef = storedRefOf("s-seed");
        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"),
                "전제: 표에 풀 수 있는 원문이 있어야 '우선순위' 가 갈린다");

        insertRawSpan("s-both", "t-both",
                "{\"db.statement\":\"" + SQL + "\",\"apilens.stmt.ref\":\"" + seededRef + "\"}");

        Map<String, Object> attributes = attributesOf("t-both", "s-both");
        assertEquals(SQL, attributes.get(IngestService.DB_STATEMENT_ATTRIBUTE), "원문이 이긴다");
    }

    /** TS-R25-19 — 못 푼 참조는 <b>지우지 않고 통과</b>시키고 경고를 남긴다. */
    @Test
    void passesAnUnresolvedRefThroughAndWarns() {
        String orphanRef = "0".repeat(64);
        insertRawSpan("s-dangling", "t-dangling", "{\"apilens.stmt.ref\":\"" + orphanRef + "\"}");
        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements WHERE stmt_hash = '" + orphanRef + "'"),
                "전제: 그 지문이 표에 없어야 '못 푼 참조' 갈래를 밟는다");

        ch.qos.logback.classic.Logger logger = queryLogger();
        ListAppender<ILoggingEvent> appender = attach(logger);
        Map<String, Object> attributes;
        try {
            attributes = attributesOf("t-dangling", "s-dangling");
        } finally {
            detach(logger, appender);
        }

        assertEquals(orphanRef, attributes.get(IngestService.STMT_REF_ATTRIBUTE),
                "키를 지우지 않는다 — 밖에서 넣은 값일 수 있어 무손실이 우선이다");
        assertFalse(attributes.containsKey(IngestService.DB_STATEMENT_ATTRIBUTE),
                "자리표 문자열로 채우지 않는다");
        assertTrue(messages(appender, Level.WARN).stream()
                        .anyMatch(m -> m.startsWith("unresolved statement ref:")),
                "못 푼 사실이 경고 한 줄로 남는다");
    }

    /** TS-R25-20 — 밖에서 예약 키를 <b>이미 차지</b>했으면 그 span 은 인터닝하지 않는다. */
    @Test
    void skipsInterningWhenTheReservedKeyArrivesFromOutside() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put(IngestService.DB_STATEMENT_ATTRIBUTE, SQL);
        hostile.put(IngestService.STMT_REF_ATTRIBUTE, "attacker-supplied");

        service.ingest(new IngestRequest(List.of(dbSpan("s-hostile", "t-hostile", hostile))));

        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements"),
                "새 도피 규약을 만들지 않는다 — 그 span 은 원문 그대로 저장된다");
        assertEquals(1, count("SELECT COUNT(*) FROM spans WHERE attributes_json LIKE '%db.statement%'"),
                "원문이 속성에 그대로 남는다");
        Map<String, Object> attributes = attributesOf("t-hostile", "s-hostile");
        assertEquals(SQL, attributes.get(IngestService.DB_STATEMENT_ATTRIBUTE), "읽는 값도 원문이다");
    }

    /**
     * TS-R25-21 (BV-R25-08) — 되감긴 묶음의 참조가 <b>다음 묶음으로 새지 않는다</b>.
     *
     * <p>이 어긋남은 오류를 안 내고 SQL 만 조용히 사라지게 한다 — 참조를 프로세스 캐시에 두지 않고
     * 원문에서 바로 뽑기 때문에 <b>구조로</b> 불가능하다는 것을 실물로 확인한다.
     */
    @Test
    void keepsTheNextChunkResolvableAfterARolledBackChunk() throws Exception {
        injectRollingBackChunkTx(1);
        assertDoesNotThrow(() -> service.ingest(new IngestRequest(List.of(dbSpan("s-lost", "t-lost",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, SQL))))),
                "청크 실패는 호스트로 안 나간다");
        // 전제 — 첫 묶음이 실제로 되감겼는가.
        assertEquals(0, count("SELECT COUNT(*) FROM spans WHERE span_id = 's-lost'"),
                "전제: 첫 묶음이 진짜로 롤백돼야 '유령 참조' 를 잴 수 있다");
        assertEquals(0, count("SELECT COUNT(*) FROM sql_statements"),
                "표 행도 참조와 **함께** 사라졌다(같은 트랜잭션)");

        restoreChunkTx();
        service.ingest(new IngestRequest(List.of(dbSpan("s-next", "t-next",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, SQL)))));

        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"), "다음 묶음이 원문을 새로 넣는다");
        assertEquals(SQL, attributesOf("t-next", "s-next").get(IngestService.DB_STATEMENT_ATTRIBUTE),
                "다음 묶음의 SQL 이 정상으로 되돌아온다(유령 참조 0)");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * [v0.7.0 첫 밤 정정 · 2026-09-06] 청크 트랜잭션의 <b>첫 문장은 쓰기</b>여야 한다.
     *
     * <p>SQLite(WAL) 는 읽기로 시작한 트랜잭션이 처음 쓰려 할 때 그 사이 다른 writer 가 커밋했으면
     * {@code busy_timeout} 을 부르지 않고 <b>즉시</b> SQLITE_BUSY 를 돌려준다. 처음 구현은 원문이 있는지
     * {@code SELECT} 로 먼저 보았고, 그 문장이 청크 트랜잭션의 첫 문장이 되어 v0.6.3 에 없던 수신 유실이 났다
     * (첫 야간 정리 4분 동안 청크 108건 · 첫 유실이 정리 시작 0.43초 뒤 = 10초 대기 없음).
     *
     * <p>원문이 <b>이미 표에 있는</b> span 을 다시 받는 경우(운영의 대부분)가 바로 그 자리라 그 경우를 잰다.
     * 청크 트랜잭션 안에서 처음 실행되는 SQL 을 붙잡아 {@code INSERT} 로 시작하는지 본다.
     */
    @Test
    void chunkTransactionStartsWithAWriteStatement() throws Exception {
        IngestRequest req = new IngestRequest(List.of(dbSpan("s1", "t1",
                Map.of(IngestService.DB_STATEMENT_ATTRIBUTE, SQL))));
        service.ingest(req);
        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"), "전제: 원문이 이미 표에 있다");

        FirstStatementRecorder recorder = new FirstStatementRecorder(jdbc.getDataSource());
        setJdbc(recorder);
        setChunkTx(new FlaggingChunkTx(new DataSourceTransactionManager(jdbc.getDataSource()), recorder));
        try {
            service.ingest(req);
        } finally {
            setJdbc(jdbc);
            restoreChunkTx();
        }

        assertNotNull(recorder.firstSqlInChunkTx, "청크 트랜잭션 안에서 문장이 하나는 돌아야 한다");
        String head = recorder.firstSqlInChunkTx.stripLeading().toUpperCase(java.util.Locale.ROOT);
        assertTrue(head.startsWith("INSERT"),
                "청크 트랜잭션의 첫 문장은 쓰기(INSERT)여야 busy_timeout 이 산다 — 실제: "
                        + recorder.firstSqlInChunkTx.stripLeading());
        assertEquals(1, count("SELECT COUNT(*) FROM sql_statements"), "표는 여전히 한 벌(INSERT OR IGNORE)");
    }

    private static Span dbSpan(String spanId, String traceId, Map<String, Object> attributes) {
        return new Span(spanId, traceId, null, "svc", "com.example.OrderRepo#save", SpanKind.DB,
                1_000L, 1_002L, SpanStatus.OK, attributes, List.of());
    }

    private void insertRawSpan(String spanId, String traceId, String attributesJson) {
        jdbc.update("INSERT INTO spans (span_id, trace_id, service_name, operation_name, span_kind, "
                        + "start_time, end_time, status, attributes_json) "
                        + "VALUES (?, ?, 'svc', 'com.example.OrderRepo#save', 'DB', 1000, 1002, 'OK', ?)",
                spanId, traceId, attributesJson);
    }

    private Map<String, Object> attributesOf(String traceId, String spanId) {
        List<SpanDto> spans = repository.findSpans(traceId);
        return spans.stream()
                .filter(s -> s.spanId().equals(spanId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + spanId))
                .attributes();
    }

    private String storedRefOf(String spanId) {
        return jdbc.queryForObject(
                "SELECT json_extract(attributes_json, '$.\"apilens.stmt.ref\"') FROM spans WHERE span_id = ?",
                String.class, spanId);
    }

    private int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }

    /**
     * chunkTx 필드를, N번째 호출에서 <b>본문을 실제로 수행한 뒤</b> 던지는 double 로 교체한다.
     * 던지기 <b>전에</b> 문장이 다 돌아야 "되감긴 뒤" 를 재는 것이 된다(먼저 던지면 아무것도 안 쓴 상태다).
     * production 코드 무변경 — 테스트 전용 주입.
     */
    private void injectRollingBackChunkTx(int failOnCall) throws Exception {
        PlatformTransactionManager tm = new DataSourceTransactionManager(jdbc.getDataSource());
        setChunkTx(new RollingBackChunkTx(tm, failOnCall));
    }

    private void restoreChunkTx() throws Exception {
        setChunkTx(new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
    }

    private void setChunkTx(TransactionTemplate template) throws Exception {
        Field f = IngestService.class.getDeclaredField("chunkTx");
        f.setAccessible(true);
        f.set(service, template);
    }

    /** N번째 호출에서 본문 수행 후 예외를 던져 그 트랜잭션을 실제로 되감는다. */
    @SuppressWarnings("serial")
    private static final class RollingBackChunkTx extends TransactionTemplate {
        private final int failOnCall;
        private int calls = 0;

        RollingBackChunkTx(PlatformTransactionManager tm, int failOnCall) {
            super(tm);
            this.failOnCall = failOnCall;
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            boolean shouldFail = (++calls == failOnCall);
            super.executeWithoutResult(status -> {
                action.accept(status);
                if (shouldFail) {
                    throw new TransientDataAccessResourceException("rolled back on purpose");
                }
            });
        }
    }

    private void setJdbc(JdbcTemplate template) throws Exception {
        Field f = IngestService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        f.set(service, template);
    }

    /** 청크 트랜잭션 안에서 <b>처음</b> 실행된 SQL 한 줄만 기억한다. 같은 DataSource 라 Spring 트랜잭션에 함께 묶인다. */
    private static final class FirstStatementRecorder extends JdbcTemplate {
        volatile boolean inChunkTx = false;
        volatile String firstSqlInChunkTx = null;

        FirstStatementRecorder(DataSource ds) {
            super(ds);
        }

        private void note(String sql) {
            if (inChunkTx && firstSqlInChunkTx == null) {
                firstSqlInChunkTx = sql;
            }
        }

        @Override
        public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
            note(sql);
            return super.batchUpdate(sql, batchArgs);
        }

        @Override
        public int update(String sql, Object... args) {
            note(sql);
            return super.update(sql, args);
        }

        @Override
        public int update(String sql) {
            note(sql);
            return super.update(sql);
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            note(sql);
            return super.queryForList(sql, elementType, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            note(sql);
            return super.queryForObject(sql, requiredType, args);
        }

        @Override
        public void query(String sql, RowCallbackHandler rch, Object... args) {
            note(sql);
            super.query(sql, rch, args);
        }
    }

    /** 청크 트랜잭션의 시작·끝을 recorder 에 알린다. */
    @SuppressWarnings("serial")
    private static final class FlaggingChunkTx extends TransactionTemplate {
        private final FirstStatementRecorder recorder;

        FlaggingChunkTx(PlatformTransactionManager tm, FirstStatementRecorder recorder) {
            super(tm);
            this.recorder = recorder;
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            super.executeWithoutResult(status -> {
                recorder.inChunkTx = true;
                try {
                    action.accept(status);
                } finally {
                    recorder.inChunkTx = false;
                }
            });
        }
    }

    private static ch.qos.logback.classic.Logger queryLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TraceQueryRepository.class);
    }

    private static ListAppender<ILoggingEvent> attach(ch.qos.logback.classic.Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
