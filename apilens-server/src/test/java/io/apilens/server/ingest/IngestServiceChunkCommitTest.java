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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R17] FR-01/FR-03 — 청크 단위 커밋 재설계의 경계값(EXT-002) + 정합·멱등(OQ-A) +
 * 부분 적재·host-throw-0(EXT-005) lock-in 회귀 가드.
 *
 * <p>기존 {@code IngestServiceTest} 와 동일한 temp-file SQLite + Flyway V1~V3 하네스 재사용.
 * 정방향(EXT-005 lock-in) 단언 — 청크 실패는 host 로 던지지 않고(host-throw-0) 부분 적재를
 * 허용하며(통째 유실보다 나음), 유실 청크를 카운터로 정량 기록한다.
 */
class IngestServiceChunkCommitTest {

    // [Phase R17] SPAN_CHUNK_SIZE=500 과 동일(테스트 경계 명시 — production 상수는 IngestService.SPAN_CHUNK_SIZE).
    private static final int CHUNK = 500;

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private MaskingEngineHolder maskingHolder;
    private IngestService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-chunk-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        this.dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        this.service = new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── 경계값 (EXT-002) ────────────────────────────────────────────────

    @Test
    void commitsSingleChunkAtExactChunkSizeBoundary() {
        // 정확히 SPAN_CHUNK_SIZE(500) span → 청크 1개(나머지 0). span_count=500.
        service.ingest(traceOf("t500", CHUNK, false));
        assertEquals(CHUNK, spanRowCount("t500"));
        assertEquals(CHUNK, traceSpanCount("t500"));
    }

    @Test
    void splitsIntoTwoChunksJustOverBoundary() {
        // SPAN_CHUNK_SIZE + 1(501) → 청크 2개(500 + 1). span_count=501.
        service.ingest(traceOf("t501", CHUNK + 1, false));
        assertEquals(CHUNK + 1, spanRowCount("t501"));
        assertEquals(CHUNK + 1, traceSpanCount("t501"));
    }

    @Test
    void splitsIntoTwoFullChunks() {
        // 2 × SPAN_CHUNK_SIZE(1000) → 청크 2개(full). span_count=1000.
        service.ingest(traceOf("t1000", 2 * CHUNK, false));
        assertEquals(2 * CHUNK, spanRowCount("t1000"));
        assertEquals(2 * CHUNK, traceSpanCount("t1000"));
    }

    @Test
    void commitsSingleSpanTraceAsOneChunk() {
        // 1 span trace → 청크 1개.
        service.ingest(traceOf("t1", 1, false));
        assertEquals(1, spanRowCount("t1"));
        assertEquals(1, traceSpanCount("t1"));
    }

    @Test
    void summarizesOnceForHugeMultiChunkTrace() {
        // 거대 trace(3 × chunk = 1500) → 요약 span_count 가 전체 반영(청크마다 덮어쓰기 아닌 1회).
        service.ingest(traceOf("thuge", 3 * CHUNK, false));
        assertEquals(3 * CHUNK, spanRowCount("thuge"));
        assertEquals(3 * CHUNK, traceSpanCount("thuge"), "요약은 마지막에 1회 — 전체 span_count 반영");
    }

    // ─── 정합·멱등 회귀 (OQ-A, delete-then-insert) ────────────────────────

    @Test
    void reingestingSameSpansKeepsPayloadRowsIdempotent() {
        // 같은 span_id 배치를 2회 ingest → payloads 행 수가 1회 때와 동일(delete-then-insert 멱등). spans 도 REPLACE(중복 0).
        IngestRequest req = traceOf("tdup", 3, true);
        service.ingest(req);
        int payloadsAfterFirst = payloadRowCount("tdup");
        int spansAfterFirst = spanRowCount("tdup");

        service.ingest(req); // 재적재

        assertEquals(payloadsAfterFirst, payloadRowCount("tdup"), "payload 재적재 무중복(delete-then-insert)");
        assertEquals(spansAfterFirst, spanRowCount("tdup"), "spans REPLACE — 중복 0");
        assertEquals(3, spanRowCount("tdup"));
        assertEquals(3, payloadRowCount("tdup"), "3 span × 1 payload = 3 (재적재 후에도 3)");
    }

    @Test
    void handlesChunkWithNullPayloadSpans() {
        // payloads()==null span 청크 → deletePayloadsForChunk no-op(0행) + insertPayloads skip. 정상.
        Span nullPayloadRoot = new Span("tnull-s0", "tnull", null,
                "svc", "op", SpanKind.SERVER, 1000L, 1001L, SpanStatus.OK, null, null);
        Span withPayload = new Span("tnull-s1", "tnull", "tnull-s0",
                "svc", "op2", SpanKind.INTERNAL, 1001L, 1002L, SpanStatus.OK, null,
                List.of(new Payload(PayloadDirection.IN, "text/plain", "body", 4, false)));

        assertDoesNotThrow(() -> service.ingest(new IngestRequest(List.of(nullPayloadRoot, withPayload))));

        assertEquals(2, spanRowCount("tnull"));
        assertEquals(1, payloadRowCount("tnull"), "payload 있는 span 만 1행 — null payload span 은 skip");
    }

    // ─── 부분 적재·host-throw-0 (EXT-005 lock-in, 반대 방향 rethrow 금지) ────

    @Test
    void keepsEarlierChunksWhenLaterChunkBusyAndDoesNotThrow() throws Exception {
        // 2번째 청크 트랜잭션에 SQLITE_BUSY 를 주입 → 앞 청크(500 span) 는 커밋되고 조회됨,
        //   뒤 청크는 없음(부분 적재 허용), sqliteBusyDroppedCount()>0, ingest 는 host 로 예외를 던지지 않음(host-throw-0).
        SQLException busy = new SQLException("SQLITE_BUSY: database is locked", "", 5); // GT-2 실측 errorCode==5
        injectFailingChunkTx(2, new TransientDataAccessResourceException("busy on chunk 2", busy));

        assertDoesNotThrow(() -> service.ingest(traceOf("tpartial", 2 * CHUNK, false)),
                "host-throw-0 — 청크 실패가 controller 로 전파되지 않음");

        assertEquals(CHUNK, spanRowCount("tpartial"), "앞 청크(500)만 커밋 — 부분 적재");
        assertEquals(CHUNK, traceSpanCount("tpartial"), "요약은 커밋된 500 span 만 재집계(GT-10 self-heal)");
        assertTrue(service.sqliteBusyDroppedCount() > 0, "유실 청크가 카운터에 기록됨");
        assertEquals(1L, service.sqliteBusyDroppedCount(), "뒤 1청크 유실");
        assertEquals(1L, service.sqliteBusyEncounteredCount(), "BUSY 경합 이벤트 1회");
    }

    @Test
    void countsDroppedButNotEncounteredOnNonBusyWriteError() throws Exception {
        // 비-BUSY write 예외(예: CONSTRAINT) → dropped 는 증가하되 encountered 는 불변(원인 구분 신호).
        SQLException constraint = new SQLException("CHECK constraint failed", "", 19); // 비-BUSY
        injectFailingChunkTx(1, new TransientDataAccessResourceException("non-busy", constraint));

        assertDoesNotThrow(() -> service.ingest(traceOf("tnonbusy", 10, false)),
                "host-throw-0 — 비-BUSY write 예외도 밖으로 던지지 않음");

        assertEquals(0, spanRowCount("tnonbusy"), "첫 청크 실패 → 커밋 0");
        assertEquals(1L, service.sqliteBusyDroppedCount(), "유실 청크는 카운트");
        assertEquals(0L, service.sqliteBusyEncounteredCount(), "비-BUSY → encountered 불변(구분)");
    }

    @Test
    void rejectsEmptyBatchBeforeAnyWrite() {
        // 빈 span 목록 → validate 가 IllegalArgumentException(400, write 이전 — 불변). 청크 루프 진입 전.
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest(new IngestRequest(List.of())));
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private IngestRequest traceOf(String traceId, int n, boolean withPayload) {
        List<Span> spans = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String parent = (i == 0) ? null : traceId + "-s0";
            List<Payload> payloads = withPayload
                    ? List.of(new Payload(PayloadDirection.IN, "text/plain", "b" + i, 2, false))
                    : List.of();
            spans.add(new Span(
                    traceId + "-s" + i, traceId, parent,
                    "svc", "op-" + i,
                    (i == 0) ? SpanKind.SERVER : SpanKind.INTERNAL,
                    1000L + i, 1000L + i + 1, SpanStatus.OK,
                    null, payloads));
        }
        return new IngestRequest(spans);
    }

    private int spanRowCount(String traceId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM spans WHERE trace_id = ?", Integer.class, traceId);
        return c == null ? 0 : c;
    }

    private int traceSpanCount(String traceId) {
        Integer c = jdbc.queryForObject("SELECT span_count FROM traces WHERE trace_id = ?", Integer.class, traceId);
        return c == null ? 0 : c;
    }

    private int payloadRowCount(String traceId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads p JOIN spans s ON s.span_id = p.span_id WHERE s.trace_id = ?",
                Integer.class, traceId);
        return c == null ? 0 : c;
    }

    /**
     * chunkTx 필드를, N번째 executeWithoutResult 호출에서 주입 예외를 던지는 double 로 교체.
     * 그 외 호출은 실제 트랜잭션을 수행(앞 청크의 진짜 커밋 재현). production 코드 무변경 — 테스트 전용 주입.
     */
    private void injectFailingChunkTx(int failOnCall, RuntimeException toThrow) throws Exception {
        PlatformTransactionManager tm = new DataSourceTransactionManager(jdbc.getDataSource());
        TransactionTemplate failing = new FailingChunkTx(tm, failOnCall, toThrow);
        Field f = IngestService.class.getDeclaredField("chunkTx");
        f.setAccessible(true);
        f.set(service, failing);
    }

    /** N번째 호출에서만 지정 예외를 던지고 나머지는 실제 트랜잭션을 위임 수행하는 TransactionTemplate. */
    @SuppressWarnings("serial")
    private static final class FailingChunkTx extends TransactionTemplate {
        private final int failOnCall;
        private final RuntimeException toThrow;
        private int calls = 0;

        FailingChunkTx(PlatformTransactionManager tm, int failOnCall, RuntimeException toThrow) {
            super(tm);
            this.failOnCall = failOnCall;
            this.toThrow = toThrow;
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            if (++calls == failOnCall) {
                throw toThrow;
            }
            super.executeWithoutResult(action);
        }
    }
}
