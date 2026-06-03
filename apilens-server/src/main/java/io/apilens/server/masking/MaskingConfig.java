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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.MaskingEngine;
import io.apilens.common.MaskingRule;
import io.apilens.common.MaskingRuleType;
import io.apilens.common.MaskingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

/**
 * Loads enabled masking rules from {@code masking_rules} once at startup and
 * registers a {@link MaskingEngine} bean. Rule edits via API (v0.2) will require
 * either a refresh endpoint or replacing the bean — out of scope for v0.1.
 */
@Configuration
public class MaskingConfig {

    @Bean
    public MaskingEngine maskingEngine(JdbcTemplate jdbc, ObjectMapper mapper) {
        List<MaskingRule> rules = jdbc.query(
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
        return new MaskingEngine(rules, mapper);
    }
}
