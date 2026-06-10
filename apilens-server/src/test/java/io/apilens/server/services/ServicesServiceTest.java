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
package io.apilens.server.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * [Phase H] BT-8 — ServicesService DELETE 멱등 + cascade 0 검증.
 *
 * <p>D-05 / Q-02. 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <ul>
 *   <li>존재하지 않는 service 이름 → throw 0 (호출자가 204 응답)</li>
 *   <li>존재하는 service 삭제 → services row 만 제거. traces / spans / payloads 보존</li>
 * </ul>
 */
class ServicesServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private ServicesService service;
    private IngestService ingestService;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-services-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.service = new ServicesService(jdbc);
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload(); // V1 시드 룰 로드 — v0.1 buildEngineFromSeededRules 와 동등 (R12 holder 전환)
        this.ingestService = new IngestService(jdbc, maskingHolder, mapper);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }


    @Test
    void shouldReturnSuccessOnNonExistentService() {
        // Q-02: 멱등 — 존재하지 않는 service 도 throw 0
        assertDoesNotThrow(() -> service.delete("nonexistent-service"));
    }

    @Test
    void shouldDeleteOnlyServicesRowAndPreserveTraces() {
        // 자동 등록 + traces 누적
        ingestService.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));
        ingestService.ingest(new IngestRequest(List.of(makeSpan("span-2", "trace-2", "my-api"))));

        Integer beforeServices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(beforeServices);
        assertEquals(1, beforeServices.intValue());

        Integer beforeTraces = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(beforeTraces);
        assertEquals(2, beforeTraces.intValue());

        Integer beforeSpans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(beforeSpans);
        assertEquals(2, beforeSpans.intValue());

        // DELETE
        service.delete("my-api");

        // D-05: services row 만 제거
        Integer afterServices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(afterServices);
        assertEquals(0, afterServices.intValue());

        // traces / spans 보존
        Integer afterTraces = jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(afterTraces);
        assertEquals(2, afterTraces.intValue(), "traces must be preserved (D-05)");

        Integer afterSpans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(afterSpans);
        assertEquals(2, afterSpans.intValue(), "spans must be preserved (D-05)");
    }

    @Test
    void shouldBeIdempotentAcrossRepeatedDeletes() {
        // 같은 이름 두 번 삭제 — Q-02
        ingestService.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        service.delete("my-api");
        assertDoesNotThrow(() -> service.delete("my-api"));

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services WHERE service_name = ?", Integer.class, "my-api");
        assertNotNull(rowCount);
        assertEquals(0, rowCount.intValue());
    }

    private static Span makeSpan(String spanId, String traceId, String serviceName) {
        return new Span(
                spanId, traceId, null,
                serviceName, "GET /probe", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.of()
        );
    }
}
