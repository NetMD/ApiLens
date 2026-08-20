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

import io.apilens.server.db.SqlitePragmas;
import io.apilens.server.ingest.IngestPauseState;
import io.apilens.server.ingest.IngestService;
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
    // [Phase R20] R20/AC-10-1 — SQLITE_BUSY 카운터 노출용 IngestService 주입. controller 생성자는
    // 봉인 대상 아님(R15 의 2→3 전례 — IngestService 생성자 4-인자 봉인과 별개). actuator 기각 근거:
    // MeterRegistry 를 IngestService 에 주입하면 4-인자 봉인과 정면 충돌 + actuator/health 는 무토큰
    // 면제 표면이라 인증 경계가 어긋난다. 사용자 명시 비협상 결정(R17 확정 설계 불변).
    private final IngestService ingestService;

    // 3→4-인자(기존 3-인자 + ingestService). cleanup/purge/optimize 본문 diff 0(D06).
    public MaintenanceController(RetentionCleanupService cleanupService, JdbcTemplate jdbc,
                                 IngestPauseState pauseState, IngestService ingestService) {
        this.cleanupService = cleanupService;
        this.jdbc = jdbc;
        this.pauseState = pauseState;
        this.ingestService = ingestService;
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
    // [Phase R20] R20/AC-10-1 — SQLITE_BUSY 카운터 2필드 additive 확장(기존 두 필드 불변).
    // [Phase R23] R23/AC-06-1/R23/AC-07-1 — 3필드 additive 확장(4 → 7, 기존 4필드 불변).
    @Operation(summary = "유지보수 상태 조회 — paused 여부·pausedAt echo (cap 경과 시 자가 재개 반영) + SQLITE_BUSY 카운터·요약 실패 카운터(인메모리 — 재시작 시 0 복귀 정상) + DB 크기·회수 가능 빈 공간(바이트, 관측 실패 시 0)")
    @GetMapping("/v1/maintenance/status")
    public MaintenanceStatusResponse status() {
        return statusSnapshot();
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
        return statusSnapshot();
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
        return statusSnapshot();
    }

    /**
     * [Phase R20] R20/AC-10-1 — status/pause/resume 3 생성처 공통 스냅샷(4필드 단일 조립).
     * 카운터는 {@link IngestService} 인메모리 값 그대로 — DB 저장 금지·재시작 0 복귀 정상
     * (R17 확정 설계 불변, 기준선은 logs/apilens.log 누적 비교).
     */
    private MaintenanceStatusResponse statusSnapshot() {
        // [Phase R23] R23/AC-07-1 — 버튼을 누르지 않아도 보이는 DB 크기·회수 가능 공간.
        //   ★이 메서드의 성질이 바뀐다: 지금까지 status 는 DB 를 안 쳤는데, 이제 **헤더 상수 3개를 읽는다**
        //   (테이블 스캔·인덱스 탐색 0). 화면 폴링이 15초라 시간당 720회 헤더 읽기다.
        // [Phase R23] ★관측 실패가 제어 표면을 깨뜨리지 않게 한다 — pause()·resume() 은 **부작용을 이미
        //   적용한 뒤** 이 응답을 조립한다. 조립 중 DB 예외로 500 이 나가면 사용자는 "일시정지 실패" 로
        //   읽지만 실제로는 적용된 상태라 화면과 서버가 어긋난다. 그래서 관측값만 0 으로 접고 WARN 을 남긴다.
        //   같은 규율이 RetentionCleanupService 의 `cleanup continues` 로그에 이미 쓰이고 있다.
        //   ⚠️ 0 폴백은 화면에 `0 B` 로 뜬다 — `0 B` 는 곱셈 자리 오류일 수도, PRAGMA 실패일 수도 있다.
        //   둘을 가르는 것은 아래 WARN 로그다.
        long dbSizeBytes = 0L;
        long freePageBytes = 0L;
        try {
            long pageSize = SqlitePragmas.pageSize(jdbc);
            dbSizeBytes = SqlitePragmas.pageCount(jdbc) * pageSize;
            freePageBytes = SqlitePragmas.freelistCount(jdbc) * pageSize;
        } catch (Exception e) {
            dbSizeBytes = 0L;
            freePageBytes = 0L;
            log.warn("maintenance status size observation failed — reporting 0 bytes; pause/resume itself is unaffected", e);
        }
        return new MaintenanceStatusResponse(pauseState.isPaused(), pauseState.pausedAt(),
                ingestService.sqliteBusyEncounteredCount(), ingestService.sqliteBusyDroppedCount(),
                ingestService.traceSummaryDeferredCount(),
                dbSizeBytes, freePageBytes);
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

    // [Phase R23] R23/AC-07-3 — 아래 두 메서드는 **본문만** SqlitePragmas 위임으로 바뀌었다.
    //   이름·가시성·호출부는 그대로다(소비처 diff 0). PRAGMA 문자열은 이제 저장소에 한 곳뿐이다.
    private long readPageCount() {
        return SqlitePragmas.pageCount(jdbc);
    }

    private long readPageSize() {
        return SqlitePragmas.pageSize(jdbc);
    }

    /** SettingsController 와 동형 — IllegalArgumentException → 400 {@code { "error": ... }}. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
