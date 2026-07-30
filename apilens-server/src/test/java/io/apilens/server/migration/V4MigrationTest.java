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
package io.apilens.server.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
 * [Phase R19] V4 Flyway migration test — {@code services.agent_version}.
 *
 * <p>D-1 / D-2 사용자 명시 비협상 결정 검증. CLAUDE.md '데이터 모델' 인용.
 *
 * <p><b>★ V2 회귀 가드에 대한 예외 명문(3면 봉인 중 한 면)</b>:
 * {@code V2__services_and_setup.sql:8} 의 가드 원문은 "services 에 trace_count / health_status
 * 컬럼 추가 절대 금지 (응답 시점 aggregation)" 이며 지금도 유효하다. 이번에 더한
 * {@code agent_version} 은 <b>집계로는 만들 수 없는 정체성 메타</b>이고 같은 테이블의
 * {@code source} 컬럼과 성격이 같다 — 집계 결과 컬럼은 이번에도 0개다.
 * ⚠️ V2 파일 자체는 <b>주석 한 글자도 고치지 않았다</b>. Flyway 는 파일 전체 내용의 체크섬을 쓰므로
 * 주석만 바꿔도 기존 운영 DB 가 부팅에 실패한다. 예외 명문의 단일 거주지는 V4 헤더이고
 * 이 javadoc 은 그것을 테스트 쪽에 다시 적어 둔 것이다.
 *
 * <p>검증 분기:
 * <ul>
 *   <li>V4 적용 후 {@code PRAGMA table_info(services)} 에 {@code agent_version} 이 있다</li>
 *   <li>V4 <b>적용 전</b>에 넣은 행이 적용 후에도 살아 있고 {@code agent_version IS NULL} 이다
 *       (= v0.5.0 collector 로 바꾼 뒤 아직 재시작하지 않았다는 뜻 — 유일한 해석)</li>
 *   <li>V1~V3 체크섬이 깨지지 않았다(= 두 단계 migrate 가 성공한다는 사실 자체가 증명)</li>
 *   <li>{@code services} 에 이번에 추가된 컬럼은 정확히 1개다(집계 결과 컬럼 0)</li>
 * </ul>
 */
class V4MigrationTest {

    /** V2 시점의 services 컬럼 4개 + V4 의 agent_version 1개 = 5개. */
    private static final List<String> EXPECTED_SERVICES_COLUMNS =
            List.of("service_name", "registered_at", "last_seen_at", "source", "agent_version");

    @TempDir
    Path tempDir;
    private Path dbFile;
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-v4-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private void migrateAll() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void addsAgentVersionColumnToServices() {
        migrateAll();

        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('services')", String.class);
        assertTrue(columns.contains("agent_version"),
                "services must expose agent_version after V4 (actual: " + columns + ")");
    }

    @Test
    void keepsExistingRowsWithNullAgentVersionAfterMigration() {
        // V3 까지만 올린 상태 = agent_version 컬럼이 아직 없는 기존 운영 DB.
        migrateTo("3");
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES (?, ?, ?, 'auto')", "legacy-api", 1_000L, 2_000L);

        // V4 적용.
        migrateAll();

        // 행이 살아 있고, 새 컬럼은 NULL 이며, 기존 값은 그대로다.
        assertEquals(1, count("SELECT COUNT(*) FROM services WHERE service_name = 'legacy-api'"));
        assertNull(jdbc.queryForObject(
                        "SELECT agent_version FROM services WHERE service_name = 'legacy-api'", String.class),
                "pre-V4 rows must have a NULL agent_version (agent not restarted yet)");
        assertEquals(1_000L, jdbc.queryForObject(
                "SELECT registered_at FROM services WHERE service_name = 'legacy-api'", Long.class));
        assertEquals("auto", jdbc.queryForObject(
                "SELECT source FROM services WHERE service_name = 'legacy-api'", String.class));
    }

    @Test
    void addsExactlyOneColumnAndNoAggregateColumns() {
        migrateAll();

        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('services')", String.class);
        assertEquals(EXPECTED_SERVICES_COLUMNS.size(), columns.size(),
                "services must gain exactly one column in R19 (actual: " + columns + ")");
        assertTrue(columns.containsAll(EXPECTED_SERVICES_COLUMNS),
                "services columns must be exactly " + EXPECTED_SERVICES_COLUMNS + " (actual: " + columns + ")");
        // V2:8 회귀 가드 — 집계 결과 컬럼은 이번에도 0개다.
        assertTrue(!columns.contains("trace_count") && !columns.contains("health_status"),
                "aggregate columns must never be added to services (V2 guard)");
    }

    @Test
    void recordsFourAppliedMigrations() {
        migrateAll();

        // V1~V4 가 모두 적용됐다는 것 자체가 "V1~V3 파일 내용 무변경(체크섬 유지)" 의 증명이다.
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL",
                Integer.class);
        assertNotNull(applied);
        assertEquals(4, applied.intValue(), "V1~V4 must all apply cleanly");
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(value);
        return value;
    }
}
