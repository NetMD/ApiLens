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
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.query.dto.ServiceInfo;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase H] BT-12 — GET /v1/services 응답 신규 필드 노출 검증 (W-01 breaking).
 *
 * <p>D-03 / Q-03 / Q-04 / W-01. 사용자 명시 비협상 결정.
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>검증 분기:
 * <ul>
 *   <li>응답에 registeredAt / lastSeenAt / source / healthStatus 모두 포함</li>
 *   <li>wizard 등록 후 trace 미수신 → lastSeenAt=null, healthStatus="never"</li>
 *   <li>auto 등록 + 방금 trace 도착 → healthStatus="active"</li>
 *   <li>name ASC 정렬 보존</li>
 *   <li>LEFT JOIN GROUP BY 단일 쿼리 정합 — traceCount 정확</li>
 * </ul>
 */
class TraceQueryServicesHealthTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService ingestService;
    private TraceQueryService queryService;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-svc-health-test-", ".db");
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
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload(); // V1 시드 룰 로드 — v0.1 buildEngineFromSeededRules 와 동등 (R12 holder 전환)
        this.ingestService = new IngestService(jdbc, maskingHolder, mapper);
        this.queryService = new TraceQueryService(new TraceQueryRepository(jdbc, mapper));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }


    // ─── BT-12 — 응답에 6 필드 모두 노출 ─────────────────────────────────

    @Test
    void shouldExposeNewServiceInfoFields() {
        ingestService.ingest(new IngestRequest(List.of(makeSpan("span-1", "trace-1", "my-api"))));

        List<ServiceInfo> services = queryService.listServices().services();
        assertEquals(1, services.size());

        ServiceInfo info = services.get(0);
        assertEquals("my-api", info.name());
        assertTrue(info.registeredAt() > 0L);
        assertNotNull(info.lastSeenAt());
        assertEquals("auto", info.source());
        assertEquals(1L, info.traceCount());
        // 방금 INSERT 했으므로 active
        assertEquals("active", info.healthStatus());
    }

    // ─── wizard 등록 후 trace 미수신 → lastSeenAt=null / never ───────────

    @Test
    void wizardOnlyServiceShowsNeverHealthStatus() {
        jdbc.update(
                "INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                        + "VALUES (?, ?, NULL, 'wizard')",
                "pre-registered", 1_000L
        );

        List<ServiceInfo> services = queryService.listServices().services();
        Optional<ServiceInfo> match = services.stream()
                .filter(s -> "pre-registered".equals(s.name()))
                .findFirst();
        assertTrue(match.isPresent());

        ServiceInfo info = match.get();
        assertEquals(1_000L, info.registeredAt());
        assertNull(info.lastSeenAt());
        assertEquals("wizard", info.source());
        assertEquals(0L, info.traceCount(), "trace 미수신 → 0");
        assertEquals("never", info.healthStatus());
    }

    // ─── 정렬 + traceCount aggregation 정합 (Q-04 LEFT JOIN GROUP BY) ────

    @Test
    void servicesOrderedByNameWithCorrectTraceCount() {
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s1", "t1", "zeta-api"))));
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s2", "t2", "alpha-api"))));
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s3", "t3", "alpha-api"))));
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s4", "t4", "beta-api"))));

        List<ServiceInfo> services = queryService.listServices().services();
        assertEquals(3, services.size());

        // ORDER BY name ASC
        assertEquals("alpha-api", services.get(0).name());
        assertEquals(2L, services.get(0).traceCount());

        assertEquals("beta-api", services.get(1).name());
        assertEquals(1L, services.get(1).traceCount());

        assertEquals("zeta-api", services.get(2).name());
        assertEquals(1L, services.get(2).traceCount());
    }

    // ─── DELETE 후 trace 도착 시 재등록 정합 ─────────────────────────────

    @Test
    void deletedServiceCanBeReRegisteredViaTrace() {
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s1", "t1", "my-api"))));

        jdbc.update("DELETE FROM services WHERE service_name = ?", "my-api");

        // DELETE 후 즉시 조회 → services 에서 사라짐 (D-05 운영자 명시 삭제)
        List<ServiceInfo> afterDelete = queryService.listServices().services();
        boolean stillPresent = afterDelete.stream().anyMatch(s -> "my-api".equals(s.name()));
        org.junit.jupiter.api.Assertions.assertFalse(stillPresent);

        // 다음 trace 도착 → 자동 재등록 (D-02 경로 B)
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s2", "t2", "my-api"))));

        List<ServiceInfo> afterReRegister = queryService.listServices().services();
        Optional<ServiceInfo> match = afterReRegister.stream()
                .filter(s -> "my-api".equals(s.name()))
                .findFirst();
        assertTrue(match.isPresent());
        // 재등록 시 source='auto'
        assertEquals("auto", match.get().source());
    }

    // ─── Q-04: LEFT JOIN GROUP BY 단일 쿼리 — traceCount 정합 (services-only row) ─

    @Test
    void wizardServiceWithoutTracesReturnsZeroTraceCount() {
        // wizard 등록만, traces 없음
        jdbc.update(
                "INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                        + "VALUES (?, ?, NULL, 'wizard')",
                "lonely", 2_000L
        );

        // 다른 service 는 traces 있음
        ingestService.ingest(new IngestRequest(List.of(makeSpan("s1", "t1", "busy"))));

        List<ServiceInfo> services = queryService.listServices().services();

        Map<String, ServiceInfo> byName = new java.util.HashMap<>();
        for (ServiceInfo info : services) byName.put(info.name(), info);

        assertEquals(0L, byName.get("lonely").traceCount());
        assertEquals(1L, byName.get("busy").traceCount());
    }

    private static Span makeSpan(String spanId, String traceId, String serviceName) {
        // [Phase R12] AC-A3-1: traceCount 가 최근 24h 윈도우(start_time 기준)로 변경 —
        // 픽스처 start_time 을 현재 시각 기반으로 사용 (기존 100L 은 1970년 = 윈도우 밖 0 카운트).
        // 윈도우 경계 자체의 검증은 TraceQueryRepositoryTest (T-A3) 가 전담.
        long now = System.currentTimeMillis();
        return new Span(
                spanId, traceId, null,
                serviceName, "GET /probe", SpanKind.SERVER,
                now - 100L, now, SpanStatus.OK,
                null, List.of()
        );
    }
}
