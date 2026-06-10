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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for retention cleanup.
 *
 * <p>// [Phase R12] AC-A1-1/AC-A1-8 — cron 기본 매일 04:00 (yml `apilens.retention.cleanup-cron`
 * // 으로 변경 가능 — settings(DB) 비노출, Design §2-A1). 예외 격리: cleanup 실패가 서버·ingest 에
 * // 영향 0 — 다음 주기 자연 재시도 (E-06). 스케줄 스레드는 Spring 기본 1개 — 중첩 실행 없음.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final RetentionCleanupService service;

    public RetentionCleanupJob(RetentionCleanupService service) {
        this.service = service;
    }

    @Scheduled(cron = "${apilens.retention.cleanup-cron:0 0 4 * * *}")
    public void runScheduled() {
        try {
            service.cleanup();
        } catch (Exception e) {
            // AC-A1-8: 예외 격리 — throw 전파 0. 다음 cron 주기가 자연 재시도.
            log.error("retention cleanup failed — will retry at next schedule", e);
        }
    }
}
