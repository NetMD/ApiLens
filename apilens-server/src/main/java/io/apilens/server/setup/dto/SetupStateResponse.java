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
package io.apilens.server.setup.dto;

/**
 * Body of {@code GET /v1/setup/state}.
 *
 * <p>[Phase H] AC-06-1 — FirstRunGuard 초기 라우팅 분기 입력. 사용자 명시 비협상 결정 (D-01).
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * @param completed    setup wizard 완료 여부
 * @param completedAt  완료 시각 epoch millis (미완료 시 null)
 * @param serverUrl    wizard 에서 입력한 server URL (미완료 시 null)
 */
public record SetupStateResponse(
        boolean completed,
        Long completedAt,
        String serverUrl
) {
}
