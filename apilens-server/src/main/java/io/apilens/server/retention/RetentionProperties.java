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
package io.apilens.server.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention configuration from {@code application.yml} ({@code apilens.retention.*}).
 *
 * <p>// Phase R12 (FR-A1, AC-A1-6): yml days 는 settings(DB) 미설정 시 fallback —
 * // D-05 "DB 저장 값이 yml 보다 우선" (사용자 명시 비협상 결정).
 *
 * @param days        보관 기간(일) fallback — 기본 30 (D-05: settings 값이 우선)
 * @param cleanupCron cleanup 실행 cron (Spring 6필드) — 기본 매일 04:00. settings 비노출 (Design §2-A1)
 */
@ConfigurationProperties(prefix = "apilens.retention")
public record RetentionProperties(
        @DefaultValue("30") int days,
        @DefaultValue("0 0 4 * * *") String cleanupCron
) {
}
