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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * [Phase H] BT-1 — V2 Flyway migration smoke test.
 *
 * <p>D-01 / D-02 / D-04 / D-05 사용자 명시 비협상 결정 검증.
 * CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
 *
 * <p>검증 분기:
 * <ul>
 *   <li>V1 + V2 마이그레이션이 fresh SQLite DB 에 깨끗하게 적용됨 (Flyway checksum)</li>
 *   <li>services / setup_state 테이블 존재</li>
 *   <li>setup_state 초기 row (id=1, completed=0) 존재</li>
 * </ul>
 */
class V2MigrationTest {

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setupSchema() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-v2-test-", ".db");
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
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void servicesTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='services'",
                Integer.class
        );
        assertNotNull(count);
        assertEquals(1, count.intValue());
    }

    @Test
    void setupStateTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='setup_state'",
                Integer.class
        );
        assertNotNull(count);
        assertEquals(1, count.intValue());
    }

    @Test
    void setupStateInitialRowInserted() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM setup_state",
                Integer.class
        );
        assertNotNull(count);
        assertEquals(1, count.intValue());

        Integer completed = jdbc.queryForObject(
                "SELECT completed FROM setup_state WHERE id = 1",
                Integer.class
        );
        assertNotNull(completed);
        assertEquals(0, completed.intValue());
    }

    @Test
    void servicesLastSeenIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_services_last_seen'",
                Integer.class
        );
        assertNotNull(count);
        assertEquals(1, count.intValue());
    }
}
