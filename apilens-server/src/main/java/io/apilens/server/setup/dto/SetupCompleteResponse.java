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
 * Response body of {@code POST /v1/setup/complete}.
 *
 * <p>[Phase H] AC-06-2 — NFR-04 멱등. 사용자 명시 결정 (재호출 시에도 200).
 *
 * @param completed    항상 true (멱등 — 이미 완료된 상태에서도 갱신 후 true)
 * @param completedAt  이번 호출 시각 epoch millis
 */
public record SetupCompleteResponse(
        boolean completed,
        long completedAt
) {
}
