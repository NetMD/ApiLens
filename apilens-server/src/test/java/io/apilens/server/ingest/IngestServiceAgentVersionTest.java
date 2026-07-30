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
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * [Phase R19] IngestService — agent 시작 알림(hello) 에서 읽은 버전을 services 에 남긴다.
 *
 * <p>비협상 AC verbatim 인용:
 * <ul>
 *   <li>AC-01-4: "시작 알림 span 에서 서비스 이름을 키로 짝지어 저장한다"</li>
 *   <li>AC-01-5: "시작 알림이 없는 일반 trace 는 <b>마지막 확인 값을 그대로 둔다</b>"</li>
 *   <li>AC-01-2: "값이 없으면 비어 있는 것이 정상이다 (기본값을 만들지 않는다)"</li>
 * </ul>
 *
 * <p>테스트 이름은 모두 <b>정방향</b>이다 — "거절한다/던진다" 로 이름 붙이면 반대 방향 동작을
 * 잠가 버리기 때문이다. 이 기능에서 옳은 동작은 "값이 이상하면 <b>기존 값을 지킨다</b>" 이지
 * "예외를 던진다" 가 아니다(호스트 throw 0 비협상).
 */
class IngestServiceAgentVersionTest {

    /** agent 가 보내는 시작 알림 span 의 operationName (원본: AgentMain.java:141). */
    private static final String HELLO_OPERATION = "agent.startup";

    /** 그 span 이 버전을 싣는 attribute 키 (원본: AgentMain.java:147). */
    private static final String VERSION_KEY = "apilens.agent.version";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-agent-version-test-", ".db");
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
        maskingHolder.reload();
        this.service = new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── B-35 — hello 있는 trace 적재 → 그 값이 저장된다 ──────────────────────

    @Test
    void storesAgentVersionFromHelloSpan() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0"))));

        assertEquals("0.4.0", agentVersionOf("my-api"));
    }

    // ─── B-36 — hello 없는 일반 trace → 기존 값 유지 (AC-01-5 verbatim) ────────

    @Test
    void keepsLastKnownVersionWhenTraceHasNoHelloSpan() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0"))));

        service.ingest(new IngestRequest(List.of(
                plain("span-1", "trace-1", "my-api", 200L))));

        assertEquals("0.4.0", agentVersionOf("my-api"),
                "a trace without a hello span must leave the last known version untouched");
    }

    // ─── B-41 계열 — hello 를 한 번도 못 본 서비스는 비어 있는 것이 정상 ───────

    @Test
    void leavesAgentVersionEmptyUntilFirstHelloArrives() {
        service.ingest(new IngestRequest(List.of(
                plain("span-1", "trace-1", "fresh-api", 100L))));

        assertNull(agentVersionOf("fresh-api"),
                "no default value is invented — empty means 'agent has not restarted yet'");
    }

    // ─── B-37 — attribute 에 버전 키가 없으면 기존 값 유지, 예외 0 ────────────

    @Test
    void keepsLastKnownVersionWhenHelloHasNoVersionAttribute() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0"))));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("apilens.agent.mode", "premain");
        assertDoesNotThrow(() -> service.ingest(new IngestRequest(List.of(
                new Span("hello-2", "agent-startup-def", null, "my-api", HELLO_OPERATION,
                        SpanKind.INTERNAL, 200L, 200L, SpanStatus.OK, attributes, List.of())))));

        assertEquals("0.4.0", agentVersionOf("my-api"));
    }

    // ─── B-38 — 버전 값이 공백 문자열이면 기존 값 유지 ────────────────────────

    @Test
    void keepsLastKnownVersionWhenReportedVersionIsBlank() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0"))));

        service.ingest(new IngestRequest(List.of(
                hello("hello-2", "agent-startup-def", "my-api", 200L, "   "))));

        assertEquals("0.4.0", agentVersionOf("my-api"));
    }

    // ─── B-39 — 한 trace 에 서비스가 둘 섞여도 각자 자기 값 ───────────────────

    @Test
    void storesEachServiceOwnVersionWhenOneTraceCarriesTwoServices() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-a", "mixed-trace", "api-a", 100L, "0.4.0"),
                hello("hello-b", "mixed-trace", "api-b", 100L, "0.5.0"))));

        assertEquals("0.4.0", agentVersionOf("api-a"));
        assertEquals("0.5.0", agentVersionOf("api-b"));
    }

    // ─── BL-03 — 같은 서비스에 hello 가 둘이면 startTime 이 큰 쪽이 이긴다 ────

    @Test
    void keepsTheLatestHelloWhenOneTraceCarriesTwoHellosForSameService() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-old", "same-trace", "my-api", 100L, "0.4.0"),
                hello("hello-new", "same-trace", "my-api", 900L, "0.5.0"))));

        assertEquals("0.5.0", agentVersionOf("my-api"), "the hello with the greatest startTime wins");
    }

    // ─── S-99 정당한 예외 경로 — 낮은 버전으로 되돌리면 값도 낮아진다 ─────────

    @Test
    void reflectsDowngradeWhenAnOlderAgentRestarts() {
        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.5.0"))));

        service.ingest(new IngestRequest(List.of(
                hello("hello-2", "agent-startup-def", "my-api", 200L, "0.4.0"))));

        // 값이 낮아지는 것은 정직한 반영이지 결함이 아니다 — 실제로 낮은 버전 agent 가 돌고 있다.
        assertEquals("0.4.0", agentVersionOf("my-api"));
    }

    // ─── 기존 계약 보존 — registered_at / source 는 그대로 ────────────────────

    @Test
    void preservesRegisteredAtAndSourceWhileUpdatingAgentVersion() {
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES (?, ?, NULL, 'wizard')", "my-api", 1_000L);

        service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0"))));

        assertEquals(1_000L, jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = ?", Long.class, "my-api"));
        assertEquals("wizard", jdbc.queryForObject(
                "SELECT source FROM services WHERE service_name = ?", String.class, "my-api"));
        assertEquals("0.4.0", agentVersionOf("my-api"));
    }

    // ─── R6 계승 — services UPSERT 가 실패해도 host throw 0 ───────────────────

    @Test
    void keepsIngestSucceedingEvenIfServicesUpsertFails() {
        jdbc.execute("DROP TABLE services");

        assertDoesNotThrow(() -> service.ingest(new IngestRequest(List.of(
                hello("hello-1", "agent-startup-abc", "my-api", 100L, "0.4.0")))));

        Integer spanCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = ?", Integer.class, "hello-1");
        assertNotNull(spanCount);
        assertEquals(1, spanCount.intValue());
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    private String agentVersionOf(String serviceName) {
        return jdbc.queryForObject(
                "SELECT agent_version FROM services WHERE service_name = ?", String.class, serviceName);
    }

    /** agent 의 시작 알림 span — 자기 trace 를 새로 만드는 span 1개짜리 독립 trace 모양. */
    private static Span hello(String spanId, String traceId, String serviceName, long startTime, String version) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(VERSION_KEY, version);
        attributes.put("apilens.agent.mode", "premain");
        return new Span(spanId, traceId, null, serviceName, HELLO_OPERATION,
                SpanKind.INTERNAL, startTime, startTime, SpanStatus.OK, attributes, List.of());
    }

    private static Span plain(String spanId, String traceId, String serviceName, long startTime) {
        return new Span(spanId, traceId, null, serviceName, "GET /probe",
                SpanKind.SERVER, startTime, startTime + 10L, SpanStatus.OK, null, List.of());
    }
}
