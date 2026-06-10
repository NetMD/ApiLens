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

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Whitelist + type/range validation for the {@code settings} KV store.
 * The server is the single source of truth for allowed keys and ranges.
 *
 * <p>// [Phase R12] AC-B1-2 — 키 화이트리스트 (v0.2 노출 키 = retention.days 단 1개,
 * // 과다 노출 금지 — cron 비노출 Design §2-A1/§2-B1).
 * // 범위 1..3650 의 4표면 동일값 의무 SSOT = 본 클래스 상수 (Design §2-B1:
 * // ① 서버 SettingsRegistry ② FE RETENTION_MAX ③ T-08 보간 ④ 경계 테스트 입력).
 */
@Component
public class SettingsRegistry {

    public static final String KEY_RETENTION_DAYS = "retention.days";
    public static final int RETENTION_DAYS_MIN = 1;
    public static final int RETENTION_DAYS_MAX = 3650;

    private static final Set<String> ALLOWED_KEYS = Set.of(KEY_RETENTION_DAYS);

    static final String RETENTION_DAYS_RANGE_MESSAGE =
            KEY_RETENTION_DAYS + " must be an integer between " + RETENTION_DAYS_MIN
                    + " and " + RETENTION_DAYS_MAX;

    /**
     * Validates the whole update map. Throws on the first violation —
     * callers must only persist after this returns (atomic rejection).
     *
     * <p>// [Phase R12] AC-B1-3 (BL-07 — PM 확정 400 원자 거부): ① unknown key → 400
     * // ② 비정수/범위 외 → 400 ③ 전체 유효 시에만 적용 (검증 통과 전 쓰기 0)
     * // ④ 400 본문에 허용 범위 포함 (Design §3.1.3).
     */
    public void validate(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException(
                    "settings body must contain at least one key (allowed: " + KEY_RETENTION_DAYS + ")");
        }
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "unknown settings key: " + entry.getKey() + " (allowed: " + KEY_RETENTION_DAYS + ")");
            }
            validateRetentionDays(entry.getValue());
        }
    }

    private static void validateRetentionDays(Object value) {
        // JSON 정수만 수용 — Jackson 은 1.5 → Double, "abc" → String, true → Boolean 으로
        // 역직렬화하므로 Integer/Long 외 전부 거부 (Design §7.1 경계: 1.5 / "abc" → 400).
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(RETENTION_DAYS_RANGE_MESSAGE);
        }
        long v = ((Number) value).longValue();
        if (v < RETENTION_DAYS_MIN || v > RETENTION_DAYS_MAX) {
            throw new IllegalArgumentException(RETENTION_DAYS_RANGE_MESSAGE);
        }
    }
}
