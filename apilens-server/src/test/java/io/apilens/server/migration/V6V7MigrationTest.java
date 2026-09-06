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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R25] AC-25-01-1/AC-25-02-1/AC-25-03-1 — V6({@code payload_bodies}) · V7({@code sql_statements})
 * 마이그레이션 검증. 두 표가 한 라운드의 산물이라 한 파일에서 함께 본다.
 *
 * <p>⚠️ V1~V5 파일은 <b>주석 한 글자도 고치지 않았다</b>. Flyway 는 파일 전체 내용의 체크섬을 쓰므로
 * 주석만 바꿔도 기존 운영 DB 가 부팅에 실패한다. 두 단계 migrate 가 성공한다는 사실 자체가 그 증명이다.
 *
 * <p>검증 분기:
 * <ul>
 *   <li>두 표가 생기고 열 구성이 맞다</li>
 *   <li>V6 <b>적용 전</b>에 넣은 payload 행이 적용 후에도 <b>본문 그대로</b> 살아 있고 지문은 NULL 이다
 *       (= 백필을 안 한다는 결정의 실물 — 옛 행 판별식이 성립하는 상태)</li>
 *   <li>정리가 참조를 훑는 인덱스가 실재한다</li>
 *   <li>새 파일 두 개에 트랜잭션 안에서 못 도는 명령이 없다</li>
 * </ul>
 */
class V6V7MigrationTest {

    /** V1 시점의 payloads 컬럼 6개 + V6 의 body_hash 1개 = 7개. */
    private static final List<String> EXPECTED_PAYLOAD_COLUMNS = List.of(
            "payload_id", "span_id", "direction", "content_type", "body", "size_bytes", "truncated", "body_hash");

    @TempDir
    Path tempDir;
    private Path dbFile;
    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-v6v7-test-", ".db");
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

    /** TS-R25-01 — 두 표가 생기고 열 구성이 맞다. */
    @Test
    void createsBothTablesWithTheExpectedColumns() {
        migrateAll();

        List<String> bodyColumns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('payload_bodies')", String.class);
        assertEquals(List.of("body_hash", "body", "body_bytes", "first_seen_at"), bodyColumns,
                "payload_bodies columns must be exactly the four declared in V6 (actual: " + bodyColumns + ")");

        List<String> stmtColumns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('sql_statements')", String.class);
        assertEquals(List.of("stmt_hash", "statement", "first_seen_at"), stmtColumns,
                "sql_statements columns must be exactly the three declared in V7 (actual: " + stmtColumns + ")");

        // 열쇠가 실제로 PRIMARY KEY 인가 — 멱등(INSERT OR IGNORE)이 여기에 기댄다.
        assertEquals(1, jdbc.queryForObject(
                "SELECT pk FROM pragma_table_info('payload_bodies') WHERE name = 'body_hash'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT pk FROM pragma_table_info('sql_statements') WHERE name = 'stmt_hash'", Integer.class));
    }

    /** TS-R25-02 — payloads 에 body_hash 가 생기고 <b>기존 행은 NULL · body 값 그대로</b>. */
    @Test
    void keepsExistingPayloadRowsIntactWithNullHashAfterMigration() {
        // V5 까지만 올린 상태 = body_hash 컬럼이 아직 없는 기존 운영 DB.
        migrateTo("5");
        jdbc.update("INSERT INTO spans (span_id, trace_id, service_name, operation_name, span_kind, "
                + "start_time, end_time, status) VALUES ('legacy-s1', 'legacy-t1', 'svc', 'op', 'SERVER', 1, 2, 'OK')");
        jdbc.update("INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated) "
                + "VALUES ('legacy-s1', 'in', 'application/json', ?, ?, 0)", "{\"a\":1}", 8L);

        migrateAll();

        List<String> columns = jdbc.queryForList(
                "SELECT name FROM pragma_table_info('payloads')", String.class);
        assertEquals(EXPECTED_PAYLOAD_COLUMNS, columns,
                "payloads must gain exactly one column in R25 (actual: " + columns + ")");

        // 행이 살아 있고, 본문은 그대로이며, 새 컬럼은 NULL 이다(백필 0).
        assertEquals("{\"a\":1}", jdbc.queryForObject(
                "SELECT body FROM payloads WHERE span_id = 'legacy-s1'", String.class),
                "옛 행의 본문은 한 글자도 안 바뀐다 — 백필을 하지 않는다");
        assertNull(jdbc.queryForObject(
                "SELECT body_hash FROM payloads WHERE span_id = 'legacy-s1'", String.class),
                "옛 행의 지문은 NULL 이다(기본값을 주지 않는 이유)");
        assertEquals(8L, jdbc.queryForObject(
                "SELECT size_bytes FROM payloads WHERE span_id = 'legacy-s1'", Long.class));

        // 옛 행 판별식이 이 상태에서 실제로 1 을 센다 — "지문 없음" 만으로 세면 안 되는 이유의 반대편.
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE body_hash IS NULL AND body IS NOT NULL", Integer.class));
    }

    /** TS-R25-03 — 정리가 참조를 훑는 인덱스가 실재한다. */
    @Test
    void createsTheIndexThatTheBodyCleanupWalks() {
        migrateAll();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_payloads_body_hash'",
                Integer.class);
        assertEquals(1, count, "본문 정리가 payloads 를 NOT EXISTS 로 훑는다 — 이 인덱스가 없으면 본문 한 행마다 전수 스캔이다");

        List<String> indexed = jdbc.queryForList(
                "SELECT name FROM pragma_index_info('idx_payloads_body_hash')", String.class);
        assertEquals(List.of("body_hash"), indexed);
    }

    /**
     * TS-R25-04 — 새 파일 두 개에 <b>트랜잭션 안에서 못 도는 명령</b>이 없다.
     *
     * <p>Flyway 는 마이그레이션을 트랜잭션으로 감싸는데 SQLite 의 공간 회수·통계 갱신 명령은 그 안에서
     * 실행되지 않는다. V3·V4 헤더가 같은 방침을 이미 적어 뒀고, 이 시험은 그 방침이 새 파일에도
     * 지켜졌는지를 <b>파일 내용으로</b> 본다.
     *
     * <p>★<b>주석을 먼저 걷어낸다</b>: 방침을 <b>적어 둔 헤더 주석</b>이 그 방침을 세는 검사에 잡힌다
     * (실측으로 한 번 빨개졌다 — 가드가 자기 선언문을 문 것이다). 이 시험이 재려는 것은
     * <b>실행되는 문장</b>이므로 {@code --} 주석 줄을 지운 뒤에 센다.
     */
    @Test
    void keepsTheNewMigrationFilesFreeOfNonTransactionalCommands() throws Exception {
        for (String file : List.of("V6__payload_bodies.sql", "V7__sql_statements.sql")) {
            String sql = executableSqlOf(readMigration(file)).toUpperCase(Locale.ROOT);
            assertFalse(sql.contains("VACUUM"), file + " must not contain a reclaim command");
            assertFalse(sql.contains("ANALYZE"), file + " must not contain a statistics command");
            // 새 파일이 기존 표의 데이터를 손대지 않는다(백필 0)는 것도 같은 자리에서 본다.
            assertFalse(sql.contains("UPDATE "), file + " must not rewrite existing rows (no backfill)");
            assertTrue(sql.contains("CREATE TABLE"), file + " must actually create its table");
        }
    }

    /** {@code --} 주석을 걷어내고 실행되는 문장만 남긴다(줄 끝 주석 포함). */
    private static String executableSqlOf(String raw) {
        StringBuilder out = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            int marker = line.indexOf("--");
            out.append(marker >= 0 ? line.substring(0, marker) : line).append('\n');
        }
        return out.toString();
    }

    private String readMigration(String fileName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/migration/" + fileName)) {
            assertNotNull(in, fileName + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
