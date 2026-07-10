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

import io.apilens.server.ingest.IngestPauseState;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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
 *   <li>{@code POST /v1/maintenance/optimize} — online full VACUUM (디스크 조각 정리, 삭제 없음):
 *       {@link RetentionCleanupService#optimizeDatabase()}.</li>
 * </ul>
 *
 * <p>// 두 동작 모두 행 단위 DELETE + PRAGMA 만으로 공간 회수 (D-04 비협상 — 운영 DB 파일
 * // 삭제/이동/재생성 0). 공간 회수량 측정은 작업 전후 page_count × page_size 차로 계산한다.
 *
 * <p>에러 응답은 SettingsController 와 동형의 flat 표준 {@code { "error": "<message>" }}.
 * 인증 필요(키 설정 시) — R14 default-deny(/v1/** 보호) 자동 계승. AuthWhitelist 미등재.
 */
@RestController
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    private final RetentionCleanupService cleanupService;
    private final JdbcTemplate jdbc;
    // [Phase R15] AC-A3-1 — 수신 일시정지 상태(io.apilens.server.ingest.IngestPauseState) 추가 주입.
    // 사용자 명시 비협상 결정(D03). CLAUDE.md '데이터 모델' (in-memory, V4 금지) 인용.
    private final IngestPauseState pauseState;

    // 3-인자(기존 2-인자 + pauseState). cleanup/purge/optimize 본문 diff 0(D06).
    public MaintenanceController(RetentionCleanupService cleanupService, JdbcTemplate jdbc,
                                 IngestPauseState pauseState) {
        this.cleanupService = cleanupService;
        this.jdbc = jdbc;
        this.pauseState = pauseState;
    }

    /**
     * Apply the retention window now (manual "apply retention immediately" button).
     * Wraps {@link RetentionCleanupService#cleanup()} with before/after page measurement.
     */
    // [Phase R17] FR-05 (M-1) — maintenance @Operation 6종 신설(GT-12: @Operation 전무 → 부여).
    @Operation(summary = "보관 기간 즉시 적용 (행 DELETE) — 파일 삭제 0, freedBytes 는 삭제량보다 작을 수 있음(정상)")
    @PostMapping("/v1/maintenance/cleanup")
    public MaintenanceResult cleanup() {
        // 작업 전 page 수 측정 → cleanup → 작업 후 측정 (freedBytes 계산).
        long pageSize = readPageSize();
        long beforePages = readPageCount();

        RetentionCleanupService.CleanupResult result = cleanupService.cleanup();

        return measure(result.deletedTraces(), beforePages, pageSize, false);
    }

    /**
     * Delete everything now (manual "clear everything" button).
     * Wraps {@link RetentionCleanupService#purgeAll()} with before/after page measurement.
     */
    // [Phase R17] FR-05 (M-2) — maintenance @Operation 신설.
    @Operation(summary = "전체 삭제 (되돌릴 수 없음) — 파일 보존, 행만 제거")
    @PostMapping("/v1/maintenance/purge")
    public MaintenanceResult purge() {
        long pageSize = readPageSize();
        long beforePages = readPageCount();

        RetentionCleanupService.CleanupResult result = cleanupService.purgeAll();

        return measure(result.deletedTraces(), beforePages, pageSize, false);
    }

    /**
     * Online full VACUUM (manual "디스크 조각 정리(최적화)" button) — 삭제 없이 파일 조각만 회수.
     *
     * <p>// [Phase K] AC-07-1/AC-07-2/AC-07-3 — R14-D06 사용자 명시 비협상 결정: 온라인 전체 VACUUM
     * // (수동 버튼). deletedTraces=0(삭제 없음). busy 동반(디스크 부족 거부 / SQLITE_BUSY|FULL 부분 실패).
     * // 사용자 명시 비협상 결정. CLAUDE.md '데이터 모델' (행 재구성·파일 삭제 금지 D-04) 인용.
     */
    // [Phase R17] FR-05 (M-3) — maintenance @Operation 신설.
    @Operation(summary = "online 전체 VACUUM (행 재구성) — 원본 크기만큼 임시 공간 필요, 부족 시 busy=true 부분 실패(예외 안 던짐)")
    @PostMapping("/v1/maintenance/optimize")
    public MaintenanceResult optimize() {
        long pageSize = readPageSize();
        long beforePages = readPageCount();

        boolean busy = cleanupService.optimizeDatabase();

        // deletedTraces=0 — optimize 는 삭제 없음. busy 동반 (Design §4.1).
        return measure(0, beforePages, pageSize, busy);
    }

    // ── [Phase R15] 수신 일시정지 set 모델 — status/pause/resume (D03/D05/D08) ──

    /**
     * Current receive-pause state echo.
     *
     * <p>// [Phase R15] AC-A3-1 — set 모델 GET. 현재 일시정지 상태 echo. 사용자 명시 비협상 결정(D03).
     * // CLAUDE.md '데이터 모델' (in-memory 상태, 스키마 변경 0) 인용.
     * status() 도 isPaused() 를 호출 — 조회 시점 cap 경과면 자가 재개 echo(echo 일관성).
     */
    // [Phase R17] FR-05 (M-4) — maintenance @Operation 신설.
    @Operation(summary = "유지보수 상태 조회 — paused 여부·pausedAt echo (cap 경과 시 자가 재개 반영)")
    @GetMapping("/v1/maintenance/status")
    public MaintenanceStatusResponse status() {
        return new MaintenanceStatusResponse(pauseState.isPaused(), pauseState.pausedAt());
    }

    /**
     * Pause receiving (수신 일시정지). Idempotent.
     *
     * <p>// [Phase R15] AC-A3-3 — set 모델 POST. 멱등(2회 호출도 true 유지, 최초 시각 보존).
     * // 사용자 명시 비협상 결정(D03). CLAUDE.md '데이터 모델' (in-memory, 재시작 false) 인용.
     */
    // [Phase R17] FR-05 (M-5) — maintenance @Operation 신설.
    @Operation(summary = "수신 일시정지 — 이후 적재는 503+Retry-After:60, 저장 0. max-pause cap(30분) 자동 재개. 멱등")
    @PostMapping("/v1/maintenance/pause")
    public MaintenanceStatusResponse pause() {
        pauseState.pause();
        return new MaintenanceStatusResponse(pauseState.isPaused(), pauseState.pausedAt());
    }

    /**
     * Resume receiving (수신 재개). Idempotent.
     *
     * <p>// [Phase R15] AC-A3-3 — set 모델 POST. 멱등(2회 호출도 false 유지). D05 수동 재개.
     * // 사용자 명시 비협상 결정(D03). CLAUDE.md '데이터 모델' (in-memory, 재시작 false) 인용.
     */
    // [Phase R17] FR-05 (M-6) — maintenance @Operation 신설.
    @Operation(summary = "수신 재개 — paused=false 복귀. 멱등")
    @PostMapping("/v1/maintenance/resume")
    public MaintenanceStatusResponse resume() {
        pauseState.resume();
        return new MaintenanceStatusResponse(pauseState.isPaused(), pauseState.pausedAt());
    }

    /**
     * 작업 후 page 수를 다시 읽어 freedBytes / dbSizeBytes 를 계산해 결과로 묶는다.
     * freedBytes 는 음수가 나오지 않도록 0 으로 하한 (incremental_vacuum/VACUUM 후 회수가 정상이나
     * WAL 상태에 따라 미세 증가 가능성 방어 — GT-4 음수 방어가 빈 DB no-op freedBytes 0 자동 충족).
     *
     * <p>// [Phase K] AC-07-3/AC-07-4/AC-07-5 — busy 파라미터 추가(private — agent fixture 무관, NFR-03).
     * // cleanup/purge 는 busy=false 전달, optimize 만 optimizeDatabase() 결과를 전달.
     */
    private MaintenanceResult measure(int deletedTraces, long beforePages, long pageSize, boolean busy) {
        long afterPages = readPageCount();
        long freedBytes = Math.max(0L, (beforePages - afterPages) * pageSize);
        long dbSizeBytes = afterPages * pageSize;
        log.info("maintenance measured: deletedTraces={} freedBytes={} dbSizeBytes={} busy={}",
                deletedTraces, freedBytes, dbSizeBytes, busy);
        return new MaintenanceResult(deletedTraces, freedBytes, dbSizeBytes, busy);
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
