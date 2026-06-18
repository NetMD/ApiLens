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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /v1/maintenance/*} — settings 페이지의 manual disk-reclaim buttons.
 *
 * <ul>
 *   <li>{@code POST /v1/maintenance/cleanup} — apply retention window immediately
 *       (보관기간 즉시 적용): {@link RetentionCleanupService#cleanup()}.</li>
 *   <li>{@code POST /v1/maintenance/purge} — delete everything (전체 비우기):
 *       {@link RetentionCleanupService#purgeAll()}.</li>
 * </ul>
 *
 * <p>// 두 동작 모두 행 단위 DELETE + PRAGMA 만으로 공간 회수 (D-04 비협상 — 운영 DB 파일
 * // 삭제/이동/재생성 0). 공간 회수량 측정은 작업 전후 page_count × page_size 차로 계산한다.
 *
 * <p>에러 응답은 SettingsController 와 동형의 flat 표준 {@code { "error": "<message>" }}.
 * 인증 없음 — 신뢰 네트워크 전제 (기존과 동일).
 */
@RestController
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    private final RetentionCleanupService cleanupService;
    private final JdbcTemplate jdbc;

    public MaintenanceController(RetentionCleanupService cleanupService, JdbcTemplate jdbc) {
        this.cleanupService = cleanupService;
        this.jdbc = jdbc;
    }

    /**
     * Apply the retention window now (manual "apply retention immediately" button).
     * Wraps {@link RetentionCleanupService#cleanup()} with before/after page measurement.
     */
    @PostMapping("/v1/maintenance/cleanup")
    public MaintenanceResult cleanup() {
        // 작업 전 page 수 측정 → cleanup → 작업 후 측정 (freedBytes 계산).
        long pageSize = readPageSize();
        long beforePages = readPageCount();

        RetentionCleanupService.CleanupResult result = cleanupService.cleanup();

        return measure(result.deletedTraces(), beforePages, pageSize);
    }

    /**
     * Delete everything now (manual "clear everything" button).
     * Wraps {@link RetentionCleanupService#purgeAll()} with before/after page measurement.
     */
    @PostMapping("/v1/maintenance/purge")
    public MaintenanceResult purge() {
        long pageSize = readPageSize();
        long beforePages = readPageCount();

        RetentionCleanupService.CleanupResult result = cleanupService.purgeAll();

        return measure(result.deletedTraces(), beforePages, pageSize);
    }

    /**
     * 작업 후 page 수를 다시 읽어 freedBytes / dbSizeBytes 를 계산해 결과로 묶는다.
     * freedBytes 는 음수가 나오지 않도록 0 으로 하한 (incremental_vacuum 후 회수가 정상이나
     * WAL 상태에 따라 미세 증가 가능성 방어).
     */
    private MaintenanceResult measure(int deletedTraces, long beforePages, long pageSize) {
        long afterPages = readPageCount();
        long freedBytes = Math.max(0L, (beforePages - afterPages) * pageSize);
        long dbSizeBytes = afterPages * pageSize;
        log.info("maintenance measured: deletedTraces={} freedBytes={} dbSizeBytes={}",
                deletedTraces, freedBytes, dbSizeBytes);
        return new MaintenanceResult(deletedTraces, freedBytes, dbSizeBytes);
    }

    private long readPageCount() {
        Long v = jdbc.queryForObject("PRAGMA page_count", Long.class);
        return v == null ? 0L : v;
    }

    private long readPageSize() {
        Long v = jdbc.queryForObject("PRAGMA page_size", Long.class);
        return v == null ? 0L : v;
    }

    /** SettingsController 와 동형 — IllegalArgumentException → 400 {@code { "error": ... }}. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
