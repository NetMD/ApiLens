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
package io.apilens.server.masking;

import io.apilens.common.MaskingRule;
import io.apilens.common.MaskingRuleType;
import io.apilens.common.MaskingStrategy;
import io.apilens.server.masking.dto.MaskingRuleDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code masking_rules} SQL 단일 거주지 (Design §3.1.0) — 모든 파라미터는 바인딩
 * ({@code ?}), 동적 SQL 조립에 사용자 입력 직접 삽입 0 (W-B2, Design §8.4).
 */
@Repository
public class MaskingRuleRepository {

    private final JdbcTemplate jdbc;

    public MaskingRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MaskingRuleDto> DTO_MAPPER = (rs, rowNum) -> new MaskingRuleDto(
            rs.getLong("rule_id"),
            rs.getString("name"),
            rs.getString("rule_type"),
            rs.getString("pattern"),
            rs.getString("mask_strategy"),
            rs.getInt("enabled") == 1,
            rs.getInt("is_default") == 1
    );

    /** 정렬: is_default DESC, rule_id ASC — default 4종 상단 고정 (Design §5.3). */
    public List<MaskingRuleDto> findAll() {
        return jdbc.query(
                """
                        SELECT rule_id, name, rule_type, pattern, mask_strategy, enabled, is_default
                        FROM masking_rules
                        ORDER BY is_default DESC, rule_id ASC
                        """,
                DTO_MAPPER
        );
    }

    /** 엔진 구성용 — 활성 룰만, apilens-common {@link MaskingRule} 형태 (MaskingConfig v0.1 로딩과 동형). */
    public List<MaskingRule> findEnabled() {
        return jdbc.query(
                """
                        SELECT name, rule_type, pattern, mask_strategy, enabled
                        FROM masking_rules
                        WHERE enabled = 1
                        """,
                (rs, rowNum) -> new MaskingRule(
                        rs.getString("name"),
                        MaskingRuleType.valueOf(rs.getString("rule_type").toUpperCase(Locale.ROOT)),
                        rs.getString("pattern"),
                        MaskingStrategy.valueOf(rs.getString("mask_strategy").toUpperCase(Locale.ROOT)),
                        rs.getInt("enabled") == 1
                )
        );
    }

    public Optional<MaskingRuleDto> findById(long ruleId) {
        return jdbc.query(
                """
                        SELECT rule_id, name, rule_type, pattern, mask_strategy, enabled, is_default
                        FROM masking_rules
                        WHERE rule_id = ?
                        """,
                DTO_MAPPER, ruleId
        ).stream().findFirst();
    }

    /**
     * Custom 룰 생성 — {@code is_default} 는 SQL 리터럴 0 으로 서버 강제
     * (planner §5-2 비협상 — 요청 DTO 에 isDefault 필드 자체가 없음).
     *
     * @return 생성된 rule_id
     */
    public long insert(String name, String ruleType, String pattern, String maskStrategy, boolean enabled) {
        long now = System.currentTimeMillis();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                            INSERT INTO masking_rules (name, rule_type, pattern, mask_strategy, enabled, is_default, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, name);
            ps.setString(2, ruleType);
            ps.setString(3, pattern);
            ps.setString(4, maskStrategy);
            ps.setInt(5, enabled ? 1 : 0);
            ps.setLong(6, now);
            ps.setLong(7, now);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "generated rule_id").longValue();
    }

    /** @return 갱신된 행 수 (0 = 미존재 → 404 분기) */
    public int updateEnabled(long ruleId, boolean enabled) {
        return jdbc.update(
                "UPDATE masking_rules SET enabled = ?, updated_at = ? WHERE rule_id = ?",
                enabled ? 1 : 0, System.currentTimeMillis(), ruleId
        );
    }

    /** @return 삭제된 행 수 — default 보호 분기는 {@link MaskingRuleService} 책임 */
    public int delete(long ruleId) {
        return jdbc.update("DELETE FROM masking_rules WHERE rule_id = ?", ruleId);
    }
}
