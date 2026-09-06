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
import io.apilens.common.MaskingEngine;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.RegexTimeoutException;
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
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase R18] AC-02-1/NFR-04 — ingest 호출부의 ReDoS degrade 흡수 lock-in 회귀 가드.
 *
 * <p>정방향(EXT-005 lock-in) 단언 — {@code mask()} 가 {@link RegexTimeoutException} 을 던지면
 * IngestService 가 청크 tx 람다 안에서 이를 흡수해 body 를 상수 {@code "***"} 로 degrade 하고(부분결과·
 * 원문 저장 금지 — PII), <b>host 로 예외를 던지지 않으며</b>(host-throw-0) 청크는 정상 commit 된다(롤백 0).
 *
 * <p>결정적: 실제 catastrophic backtracking 에 의존하지 않고 {@code mask()} 가 확정적으로 throw 하도록
 * MaskingEngine 을 mock 해 주입한다(R14 CI JDK21 flaky 계보 회피).
 */
class IngestServiceReDoSDegradeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-redos-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        this.jdbc = new JdbcTemplate(dataSource);

        // mask() 가 확정적으로 RegexTimeoutException 을 던지는 엔진을 holder 로 주입.
        MaskingEngine throwingEngine = Mockito.mock(MaskingEngine.class);
        Mockito.when(throwingEngine.mask(Mockito.any(), Mockito.any()))
                .thenThrow(RegexTimeoutException.INSTANCE);
        MaskingEngineHolder throwingHolder =
                new ThrowingMaskingHolder(new MaskingRuleRepository(jdbc), mapper, throwingEngine);

        this.service = new IngestService(jdbc, throwingHolder, mapper, new IngestProperties(1_048_576L));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void degradesPayloadToFullMaskAndCommitsWithoutThrowing() {
        Span span = new Span("rs-s0", "rtrace", null, "svc", "op",
                SpanKind.SERVER, 1000L, 1001L, SpanStatus.OK, null,
                List.of(new Payload(PayloadDirection.IN, "application/json",
                        "{\"note\":\"evil-redos-input\"}", 27, false)));

        assertDoesNotThrow(() -> service.ingest(new IngestRequest(List.of(span))),
                "host-throw-0 — ReDoS deadline 초과가 controller 로 전파되면 안 됨");

        // span 은 커밋됨(청크 롤백 0)
        assertEquals(1, spanRowCount("rtrace"), "청크 commit — span 적재됨(롤백 0)");
        // payload body 는 상수 "***" 로 degrade (부분결과·원문 저장 0)
        assertEquals("***", payloadBody("rs-s0"), "ReDoS degrade — body 전체 상수 마스킹");
        assertEquals(0, payloadTruncated("rs-s0"), "'***' 는 3바이트 → truncate 미발동");
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private int spanRowCount(String traceId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM spans WHERE trace_id = ?", Integer.class, traceId);
        return c == null ? 0 : c;
    }

    /**
     * [Phase R25] AC-25-03-4 — 저장된 본문을 읽는 단일 자리. R25 부터 새 행은 {@code payloads.body} 가
     * 비어 있고 실물은 {@code payload_bodies} 에 있다. 읽기 SQL 은
     * {@code TraceQueryRepository.findPayloads} 와 같은 모양이라 옛 행도 그대로 읽힌다.
     */
    private String payloadBody(String spanId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(pb.body, p.body) FROM payloads p "
                        + "LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash "
                        + "WHERE p.span_id = ?",
                String.class, spanId);
    }

    private int payloadTruncated(String spanId) {
        Integer c = jdbc.queryForObject("SELECT truncated FROM payloads WHERE span_id = ?", Integer.class, spanId);
        return c == null ? 0 : c;
    }

    /** current() 가 확정 throw 엔진을 반환하는 테스트 전용 holder. */
    private static final class ThrowingMaskingHolder extends MaskingEngineHolder {
        private final MaskingEngine engine;

        ThrowingMaskingHolder(MaskingRuleRepository repository, ObjectMapper mapper, MaskingEngine engine) {
            super(repository, mapper);
            this.engine = engine;
        }

        @Override
        public MaskingEngine current() {
            return engine;
        }
    }
}
