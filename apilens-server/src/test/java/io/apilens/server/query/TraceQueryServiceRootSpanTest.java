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
package io.apilens.server.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.MaskingEngine;
import io.apilens.common.MaskingRule;
import io.apilens.common.MaskingRuleType;
import io.apilens.common.MaskingStrategy;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.query.dto.TraceDetailResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UT-20 ~ UT-23: validates {@link TraceDetailResponse#rootSpanId} convenience
 * field — populated from the unique {@code parentSpanId == null} span returned
 * by the repository.
 */
class TraceQueryServiceRootSpanTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private IngestService ingestService;
    private TraceQueryService queryService;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-rootspan-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        MaskingEngine engine = buildEngineFromSeededRules(jdbc);
        this.ingestService = new IngestService(jdbc, engine, mapper);
        this.queryService = new TraceQueryService(new TraceQueryRepository(jdbc, mapper));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private MaskingEngine buildEngineFromSeededRules(JdbcTemplate jdbc) {
        List<MaskingRule> rules = jdbc.query(
                "SELECT name, rule_type, pattern, mask_strategy, enabled FROM masking_rules WHERE enabled = 1",
                (rs, rowNum) -> new MaskingRule(
                        rs.getString("name"),
                        MaskingRuleType.valueOf(rs.getString("rule_type").toUpperCase()),
                        rs.getString("pattern"),
                        MaskingStrategy.valueOf(rs.getString("mask_strategy").toUpperCase()),
                        rs.getInt("enabled") == 1
                )
        );
        return new MaskingEngine(rules, mapper);
    }

    /** UT-20: a single root span — rootSpanId is its spanId. */
    @Test
    void rootSpanIdIsExtractedFromTheUniqueRoot() {
        Span root = new Span(
                "root-1", "trace-root-1", null,
                "checkout", "POST /api/orders", SpanKind.SERVER,
                1_000L, 1_120L, SpanStatus.OK,
                null, List.of()
        );
        Span child = new Span(
                "child-1", "trace-root-1", "root-1",
                "checkout", "INSERT orders", SpanKind.DB,
                1_010L, 1_080L, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(root, child)));

        TraceDetailResponse detail = queryService.getTrace("trace-root-1");

        assertEquals("root-1", detail.rootSpanId(),
                "rootSpanId must equal the spanId of the span whose parentSpanId is null");
        assertEquals(2, detail.spans().size());
    }

    /** UT-21: response field exposes the root via the public accessor. */
    @Test
    void rootSpanIdAccessorIsExposedOnTheRecord() {
        Span root = new Span(
                "abc", "trace-acc", null,
                "svc", "GET /probe", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(root)));

        TraceDetailResponse detail = queryService.getTrace("trace-acc");
        assertNotNull(detail.rootSpanId());
        assertEquals("abc", detail.rootSpanId());
    }

    /** UT-22: rootSpanId is null when no root span is present in spans list. */
    @Test
    void rootSpanIdIsNullWhenNoRootIsPresent() {
        // Synthetic situation: a single span with a non-null parentSpanId. This is
        // unusual for normal traces but the field must degrade gracefully.
        Span orphan = new Span(
                "orphan-1", "trace-orphan", "missing-parent",
                "svc", "internal.op", SpanKind.INTERNAL,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(orphan)));

        TraceDetailResponse detail = queryService.getTrace("trace-orphan");
        assertNull(detail.rootSpanId(),
                "rootSpanId must be null when no span has parentSpanId == null");
        assertEquals(1, detail.spans().size());
    }

    /** UT-23: ordering — when several roots exist, the one ordered first wins. */
    @Test
    void rootSpanIdPicksFirstWhenMultipleRootsExist() {
        // start_time ASC ordering means the smaller-start_time root is first.
        Span rootEarly = new Span(
                "root-early", "trace-multi", null,
                "svc", "op-early", SpanKind.SERVER,
                100L, 150L, SpanStatus.OK,
                null, List.of()
        );
        Span rootLate = new Span(
                "root-late", "trace-multi", null,
                "svc", "op-late", SpanKind.SERVER,
                200L, 300L, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(rootEarly, rootLate)));

        TraceDetailResponse detail = queryService.getTrace("trace-multi");

        // both spans must be in the response (order start_time ASC)
        assertEquals(2, detail.spans().size());
        assertEquals("root-early", detail.spans().get(0).spanId());
        // rootSpanId picks the first parentSpanId == null span, which is start_time-earliest
        assertEquals("root-early", detail.rootSpanId(),
                "first root in start_time ASC ordering must win");
        assertTrue(detail.spans().stream().anyMatch(s -> s.spanId().equals("root-late")));
    }
}
