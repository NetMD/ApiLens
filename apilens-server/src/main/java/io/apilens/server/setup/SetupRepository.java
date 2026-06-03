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
package io.apilens.server.setup;

import io.apilens.server.setup.dto.SetupStateResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JdbcTemplate-backed repository for the {@code setup_state} and wizard-side
 * {@code services} table inserts.
 *
 * <p>[Phase H] AC-06-1/AC-06-2 — D-01 / D-02 / D-04. 사용자 명시 비협상 결정.
 * CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
 */
@Repository
public class SetupRepository {

    private final JdbcTemplate jdbc;

    public SetupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Read the singleton {@code setup_state} row (id=1). V2 migration inserts
     * this row so the empty Optional path is defensive only.
     */
    public Optional<SetupStateResponse> findState() {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT completed, completed_at, server_url FROM setup_state WHERE id = 1",
                    (rs, n) -> new SetupStateResponse(
                            rs.getInt("completed") == 1,
                            // SQLite NULL → JDBC null. Long boxed type 명시.
                            (Long) rs.getObject("completed_at"),
                            rs.getString("server_url")
                    )
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Mark setup as completed and store the wizard-supplied server URL.
     * Idempotent (NFR-04): a repeated call overwrites completed_at + server_url.
     */
    public void updateSetupState(long now, String serverUrl) {
        jdbc.update(
                "UPDATE setup_state SET completed = 1, completed_at = ?, server_url = ? WHERE id = 1",
                now, serverUrl
        );
    }

    /**
     * Insert a wizard-registered service. If an auto-registered row already
     * exists for the same name, leave it as-is (ON CONFLICT DO NOTHING) — D-02
     * 정합: 처음 등록 시점 source 보존.
     */
    public void insertWizardService(String name, long registeredAt) {
        jdbc.update(
                """
                        INSERT INTO services (service_name, registered_at, last_seen_at, source)
                        VALUES (?, ?, NULL, 'wizard')
                        ON CONFLICT(service_name) DO NOTHING
                        """,
                name, registeredAt
        );
    }
}
