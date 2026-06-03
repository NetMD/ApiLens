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
package io.apilens.common;

/**
 * Single masking rule. Persisted in {@code masking_rules} table; default rules
 * are seeded by Flyway migration ({@code is_default=1}, deletable=false).
 *
 * @param name     human-readable name (e.g. "주민번호")
 * @param ruleType FIELD_NAME or REGEX
 * @param pattern  regex pattern; for FIELD_NAME applied with full-match against keys,
 *                 for REGEX applied with substring search within string values
 * @param strategy how to mask the matched value
 * @param enabled  inactive rules are skipped by the engine
 */
public record MaskingRule(
        String name,
        MaskingRuleType ruleType,
        String pattern,
        MaskingStrategy strategy,
        boolean enabled
) {
}
