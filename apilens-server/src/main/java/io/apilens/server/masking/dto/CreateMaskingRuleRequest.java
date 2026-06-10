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
 * Request body of {@code POST /v1/masking-rules}.
 *
 * <p>// [Phase R12] planner §5-2 비협상: isDefault 필드 자체가 DTO 에 없음 —
 * // 서버가 is_default=0 강제 (custom 룰만 생성 가능). Design §5.3.
 *
 * @param name         룰 이름 (trim 비공백, ≤ 100자)
 * @param ruleType     'field_name' | 'regex'
 * @param pattern      정규식 (Pattern.compile 사전 검증 — invalid 시 400)
 * @param maskStrategy 'full' | 'partial' | 'hash' | 'length_only'
 * @param enabled      생략(null) 시 true (Design §5.3)
 */
public record CreateMaskingRuleRequest(
        String name,
        String ruleType,
        String pattern,
        String maskStrategy,
        Boolean enabled
) {
}
