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

import io.apilens.server.retention.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * KV store backed settings service. Write entry point is {@link #put(Map)} only
 * (controller delegates) — read of the resolved retention value goes through
 * {@link #resolveRetentionDays()} only (Design §6.4 단일 진입점).
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final JdbcTemplate jdbc;
    private final SettingsRegistry registry;
    private final RetentionProperties retentionProperties;

    public SettingsService(JdbcTemplate jdbc, SettingsRegistry registry,
                           RetentionProperties retentionProperties) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.retentionProperties = retentionProperties;
    }

    /**
     * GET 응답 — settings 값은 resolve 된 유효값 (DB 없으면 yml fallback 값이 그대로 내려감,
     * FE prefill 단순화 — Design §5.2). lastCleanupAt = retention_meta.last_cleanup_at
     * (0 = 이력 없음 → FE T-11 분기).
     */
    public SettingsResponse getSettings() {
        return new SettingsResponse(
                Map.of(SettingsRegistry.KEY_RETENTION_DAYS, resolveRetentionDays()),
                readLastCleanupAt()
        );
    }

    /**
     * PUT — 원자 적용. 검증 전체 통과 후에만 쓰기 (부분 적용 0 — BL-07).
     *
     * <p>// [Phase R12] AC-B1-3: "범위 외 400, 전체 유효 시에만 적용" (비협상 — PM 확정 400 원자 거부).
     */
    @Transactional
    public SettingsResponse put(Map<String, Object> updates) {
        registry.validate(updates); // 첫 위반에서 throw — 이 줄을 통과하기 전 쓰기 0
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            jdbc.update(
                    """
                            INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
                            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                            """,
                    entry.getKey(), String.valueOf(entry.getValue()), now
            );
        }
        return getSettings();
    }

    /**
     * Resolve the effective retention days.
     *
     * <p>// [Phase R12] AC-B1-1 — D-05 비협상 verbatim: "retention 기본 30일 유지 +
     * // 설정 페이지에서 변경 가능 (DB 저장 값이 yml 보다 우선)". 사용자 명시 비협상 결정.
     * // settings(DB) 저장 값 > yml fallback (apilens.retention.days, 기본 30).
     * // CLAUDE.md '절대 변경하지 말아야 할 결정 사항 §2' (SQLite+Flyway) 인용 — 저장소는 settings 테이블 (V3).
     */
    public int resolveRetentionDays() {
        return findValue(SettingsRegistry.KEY_RETENTION_DAYS)
                .map(this::parseIntOrNull) // 파싱 실패 → null (Optional.map 이 empty 로 전환) — 방어, PUT 검증이 1차 차단
                .filter(v -> v >= SettingsRegistry.RETENTION_DAYS_MIN
                        && v <= SettingsRegistry.RETENTION_DAYS_MAX)
                .orElse(retentionProperties.days());
    }

    private Optional<String> findValue(String key) {
        return jdbc.query("SELECT value FROM settings WHERE key = ?",
                        (rs, rowNum) -> rs.getString(1), key)
                .stream().findFirst();
    }

    private Integer parseIntOrNull(String raw) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            // 수동 DB 편집 등으로 비정수 유입 시 yml fallback (방어 분기)
            log.warn("invalid stored settings value for {} — falling back to yml default",
                    SettingsRegistry.KEY_RETENTION_DAYS);
            return null;
        }
    }

    /**
     * [Phase R22] R22/AC-04-2/R22/AC-04-3/R22/AC-04-4 — R22/AC-04-3 verbatim: "<b>반환값은 기존과 같은
     * {@code 0L} 유지 · {@code SettingsResponse} DTO 무변경 · FE 무변경 · FE 테스트 무변경.</b>
     * 값으로 구분하거나 새 필드를 만드는 안은 채택하지 않는다." 사용자 명시 결정(OQ-8·9).
     *
     * <p>운영에서 {@code retention_meta} 의 행(id=1)이 사라져 정리 시각이 계속 "이력 없음" 으로 보인 일이
     * 있었다. 원인은 <b>끝내 규명하지 못했다</b> — 추측으로 채우지 않는다. 이 로그가 <b>재발 감지의 유일한
     * 표면</b>이다("고쳤다" 가 아니라 "다시 생기면 알 수 있게 했다"). 행 자체는 다음 정리의 upsert 가
     * 다시 만든다.
     *
     * <p>★ 기존 {@code jdbc.query} 의 throw 의미론은 <b>일부러 그대로 둔다.</b> 여기에 포괄 try-catch 를
     * 씌우면 DB 장애가 "이력 없음" 으로 위장되어, 위와 같은 무음 실패를 새로 심는 셈이다.
     * R22/AC-04-4 가 요구하는 것은 <b>이 라운드가 새로 넣는 코드가 예외를 만들지 않는다</b>는 것이고,
     * {@code log.warn} 은 던지지 않는다.
     */
    private long readLastCleanupAt() {
        List<Long> rows = jdbc.query("SELECT last_cleanup_at FROM retention_meta WHERE id = 1",
                (rs, rowNum) -> rs.getLong(1)); // NULL → 0 (이력 없음과 동일 취급)
        if (rows.isEmpty()) {
            log.warn("retention_meta row (id=1) is missing — returning 0 (no cleanup history). "
                    + "the row is re-created by the next cleanup upsert");
            return 0L;
        }
        return rows.get(0);
    }
}
