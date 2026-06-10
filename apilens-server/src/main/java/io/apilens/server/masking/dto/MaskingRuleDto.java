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
package io.apilens.server.masking.dto;

/**
 * Masking rule as exposed by {@code /v1/masking-rules} (Design §5.3).
 *
 * <p>// [Phase R12] 식별자 단일명 (Design §5.6 — planner §9 전건 채택):
 * // ruleId · name · ruleType · pattern · maskStrategy · enabled · isDefault.
 * // ruleType/maskStrategy 는 DB 저장 소문자 형태 그대로 노출 ('field_name'/'regex',
 * // 'full'/'partial'/'hash'/'length_only').
 *
 * @param ruleId       masking_rules.rule_id
 * @param name         사람이 읽는 이름
 * @param ruleType     'field_name' | 'regex'
 * @param pattern      정규식 패턴
 * @param maskStrategy 'full' | 'partial' | 'hash' | 'length_only'
 * @param enabled      비활성 룰은 엔진이 skip
 * @param isDefault    빌트인 여부 — true 면 삭제 불가 (비활성만 가능, E-02 409)
 */
public record MaskingRuleDto(
        long ruleId,
        String name,
        String ruleType,
        String pattern,
        String maskStrategy,
        boolean enabled,
        boolean isDefault
) {
}
