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
package io.apilens.server.settings;

import java.util.Map;

/**
 * Response of {@code GET/PUT /v1/settings}.
 *
 * <p>// [Phase R12] 식별자 단일명 (Design §5.6): 응답 필드 {@code settings}(객체) /
 * // {@code lastCleanupAt}(number) — retentionMeta 류 중첩명 금지.
 *
 * @param settings      resolve 된 유효 설정 맵 (v0.2: {@code retention.days} 1키)
 * @param lastCleanupAt retention_meta.last_cleanup_at (epoch ms). 0 = cleanup 이력 없음 (FE T-11 분기)
 */
public record SettingsResponse(
        Map<String, Object> settings,
        long lastCleanupAt
) {
}
