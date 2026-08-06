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

import io.apilens.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Retention cleanup: deletes traces older than the resolved retention window,
 * in small batches, with explicit child-first deletes.
 *
 * <p>// [Phase R12] AC-A1-2/AC-A1-3/AC-A1-5 — D-04 비협상 verbatim: "기존 22GB DB 리셋은
 * // 사용자 수행 완료 (2026-06-10). 파이프라인이 운영 DB 파일을 추가로 삭제하지 말 것".
 * // 사용자 명시 비협상 결정. 본 서비스의 공간 회수는 전부 행 단위 DELETE + PRAGMA —
 * // 운영 DB **파일** 삭제/이동 경로 0. CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
 *
 * <p>// [Phase R12] A1 재해석 차단 (Design §0-2 비협상): cleanup 은 payloads → spans → traces
 * // **명시적 3단 DELETE**. V1 의 ON DELETE CASCADE 의존 코드 0 — PRAGMA foreign_keys 는
 * // OFF 유지 확정 (Design §2-A2-FK) 이므로 CASCADE 는 계속 장식이다.
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    /**
     * 배치당 trace 수 (Design §2-A1): SQLite 파라미터 바인딩 한계(구버전 999) 안전 마진 +
     * 실측 비율(traces:spans:payloads ≈ 1:2:3.1) 기준 배치당 ≈ 3,000행 — 배치 트랜잭션
     * 수십 ms 수준으로 장시간 writer 락 금지(NFR-04) 충족.
     *
     * <p>// [Phase R13] AC-D1-2 / E1 — 값 변경 없이 측정 조건 명문 (Design §7.3 / §8 D-E1):
     * // 배치 500 유지. 250 으로 낮추면 배치당 tx 는 짧아지나 배치 수가 2배가 되어 전체 cleanup
     * // 시간이 오히려 늘 수 있다. 성능 합격 기준은 단일 수치 단정이 아니라 cold(첫 기동 직후, page
     * // cache 비움) / warm(반복 실행) + DB 규모(1만/10만/100만 trace) 를 동반해 측정한다
     * // (R12 교훈: "cleanup <500ms" 단정이 cold 0.65~0.81s 에서 모호했음). 250 채택은 실측 후 backlog.
     */
    static final int RETENTION_DELETE_BATCH_SIZE = 500;

    /**
     * [Phase R20] R20/AC-08-1 — 배치 사이 양보 창(ms). 배치 tx 커밋으로 write lock 이 풀린 사이에
     * 다른 writer(ingest)/reader 가 끼어들 짬을 <b>보장</b>한다 — {@code Thread.yield()} 는 스케줄러
     * 힌트일 뿐이라 채택하지 않았다. cleanup 은 04:00 백그라운드 작업이라 배치당 +50ms 는 무해하고,
     * purge(수동)도 같은 경로라 두 호출 모두에 효과가 미친다. R17 ingest 청크 커밋과 동형 사상.
     */
    static final long BATCH_YIELD_MS = 50L;

    static final long DAY_MS = 86_400_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SettingsService settingsService;
    private final int batchSize;

    @Autowired
    public RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                   SettingsService settingsService) {
        this(jdbc, txManager, settingsService, RETENTION_DELETE_BATCH_SIZE);
    }

    /** 테스트 전용 — 배치 루프 종료 검증을 위해 배치 크기 주입 (Design §7.1 배치 종료 행). */
    RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            SettingsService settingsService, int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.settingsService = settingsService;
        this.batchSize = batchSize;
    }

    /** Cleanup 실행 결과 (관측/테스트용). */
    public record CleanupResult(int batches, int deletedTraces) {
    }

    public CleanupResult cleanup() {
        return cleanup(System.currentTimeMillis());
    }

    /**
     * 컷오프 = nowMs − resolveRetentionDays()×1일. 삭제 조건은 {@code received_at < cutoff}
     * (엄격 미만 — 경계값 cutoff 행은 보존, Design §7.1 확정).
     *
     * <p>배치 = 트랜잭션 경계: 배치 사이 트랜잭션을 닫아 ingest writer 에 양보 (락 분산).
     * 같은 트랜잭션 + SQLite 단일 writer 특성으로 3단 사이 집합 불변 — 고아 0 보장 (NFR-02).
     */
    CleanupResult cleanup(long nowMs) {
        // [Phase R12] AC-A1-6 — D-05 resolve 경유 (settings DB 값 > yml fallback)
        long cutoffMs = nowMs - settingsService.resolveRetentionDays() * DAY_MS;

        // 배치 3단 DELETE 루프 (cleanup/purgeAll 공통) — cutoff 지정 = 만료분만 삭제.
        CleanupResult result = deleteInBatches(OptionalLong.of(cutoffMs));

        finalizeMaintenance(nowMs);

        log.info("retention cleanup finished: deletedTraces={} batches={} cutoffMs={}",
                result.deletedTraces(), result.batches(), cutoffMs);
        return result;
    }

    /**
     * Purge ALL traces/spans/payloads (manual "clear everything" action).
     *
     * <p>// [수동 정리] D-04 비협상 verbatim: "운영 DB **파일** 삭제/이동/재생성 절대 금지.
     * // 행 단위 DELETE + PRAGMA만으로 공간 회수". 사용자 명시 비협상 결정.
     * // cleanup 의 배치/트랜잭션/3단 DELETE 패턴을 그대로 재사용 — cutoff 없는 전체 삭제만 차이.
     * // CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' + RetentionCleanupService 클래스 주석 인용.
     */
    public CleanupResult purgeAll() {
        long now = System.currentTimeMillis();

        // cutoff 미지정(empty) = 전체 trace 가 삭제 대상.
        CleanupResult result = deleteInBatches(OptionalLong.empty());

        finalizeMaintenance(now);

        log.info("manual purge-all finished: deletedTraces={} batches={}",
                result.deletedTraces(), result.batches());
        return result;
    }

    /**
     * 전체 VACUUM 으로 파일 조각을 회수한다 (삭제 없이 행 재구성). 신규 public 메서드 (Design §4.1).
     *
     * <p>// [Phase K] AC-07-1/AC-07-3/AC-07-4/AC-07-5 — R14-D06 사용자 명시 비협상 결정:
     * // 온라인 전체 VACUUM(수동 버튼). VACUUM 은 같은 경로/inode 의 행 재구성이라 파일 삭제 금지(D-04)와
     * // 충돌하지 않는다(NFR-05). 사용자 명시 비협상 결정. CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
     *
     * <p>// ★GT-5 비협상★: VACUUM 은 TransactionTemplate 안에서 실행 금지(SQLite: cannot VACUUM from
     * // within a transaction). cleanup/purge 의 배치 tx 패턴을 재사용하지 않고 jdbc.execute("VACUUM") 직접.
     *
     * @return busy — true 면 부분 실패(SQLITE_BUSY/FULL) 또는 디스크 부족 거부, false 면 정상 회수.
     */
    public boolean optimizeDatabase() {
        // 1. 디스크 여유 가드 — VACUUM 임시파일이 원본 크기만큼 추가 점유(2배)하므로 실행 전 거부 (BL-10, AC-07-4).
        if (!hasEnoughDiskForVacuum()) {
            log.warn("optimize skipped — insufficient disk (need >= DB size for VACUUM temp file)");
            return true; // busy 취급(거부) — FE 디스크 부족 토스트 (Design §4.5)
        }
        // 2. ★GT-5★ VACUUM 은 트랜잭션 밖에서 (TransactionTemplate 미사용).
        try {
            jdbc.execute("VACUUM");  // 전체 행 재구성 (같은 inode — NFR-05)
            checkpointWal();         // 기존 private 메서드 재사용 (wal_checkpoint TRUNCATE)
            return false;            // 정상
        } catch (Exception e) {
            // BL-11: SQLITE_BUSY / SQLITE_FULL 비전파 — 호스트로 던지지 않고 busy 상태에 반영 (AC-07-3/07-5).
            log.warn("VACUUM failed (busy or full) — host unaffected, state reflected", e);
            return true; // busy=true
        }
    }

    /**
     * VACUUM 실행 전 디스크 여유 가드 (FR-C3 — ★52GB 사고 직결★, BL-10).
     *
     * <p>// [Phase K] AC-07-4 — VACUUM 임시파일은 원본 DB 와 같은 디렉토리에 원본 크기만큼 생성되어
     * // 일시적으로 디스크를 약 2배 점유한다. 가용 디스크가 DB 크기보다 작으면 거부(SQLITE_FULL 2차 사고 차단).
     * // 임계는 비율 상수 아님 — 런타임 dbFile.length() 와 getUsableSpace() 동적 비교(>=, Design §4.2/§5).
     */
    private boolean hasEnoughDiskForVacuum() {
        File dbFile = resolveDbFile();
        if (dbFile == null) {
            // 경로 해석 실패(in-memory DB 등) — 가드를 통과시키되 VACUUM 실패는 catch 가 흡수.
            log.warn("optimize disk-guard skipped — DB file path unresolved (in-memory or driver limitation)");
            return true;
        }
        return hasEnoughDisk(dbFile.getUsableSpace(), dbFile.length());
    }

    /**
     * 디스크 여유 비교 핵심 (경계값 단위 테스트 진입점, [S-66] 임계 분기 봉인).
     *
     * <p>// [Phase K] AC-07-4 — VACUUM 임시파일이 원본 크기만큼 추가 점유(2배)하므로
     * // 가용 >= DB크기 일 때만 허용한다. 경계: 가용 == DB크기 → 허용(>=). 가용 < DB크기 → 거부 (Design §8.1 (C)).
     *
     * @param usableSpace DB 파일 디렉토리의 가용 디스크 byte
     * @param dbSize      현재 DB 파일 byte
     * @return 허용이면 true, 거부면 false
     */
    static boolean hasEnoughDisk(long usableSpace, long dbSize) {
        return usableSpace >= dbSize;
    }

    /**
     * 운영 DB 파일 경로 해석 — SQLite {@code PRAGMA database_list} 의 main DB file 경로(하드코딩 회피).
     *
     * <p>// [Phase K] AC-07-4 — datasource URL 의 apilens.db 경로를 드라이버에 직접 질의해 해석한다
     * // (런타임 해석 — Design §4.2). file 컬럼이 빈 문자열(in-memory) 이거나 매핑 불가면 null.
     */
    private File resolveDbFile() {
        try {
            // PRAGMA database_list → (seq, name, file). name='main' 행의 file 이 DB 파일 절대경로.
            List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA database_list");
            for (Map<String, Object> row : rows) {
                Object name = row.get("name");
                Object file = row.get("file");
                if ("main".equals(String.valueOf(name)) && file != null) {
                    String path = String.valueOf(file);
                    if (!path.isBlank()) {
                        return new File(path);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DB file path resolution failed — disk guard will be skipped", e);
        }
        return null;
    }

    /**
     * Batch loop shared by cleanup (cutoff present) and purgeAll (cutoff absent).
     * Each batch is its own transaction (writer 락 분산) + explicit 3-step child-first DELETE.
     *
     * <p>[Phase R20] R20/AC-08-1 — 2회차 배치부터 SELECT 직전에 {@link #BATCH_YIELD_MS} 만큼 정지
     * (배치 사이에만 — 첫 배치·종료 직전 빈 SELECT 앞엔 없음). 삭제 순서(payloads→spans→traces)·
     * SQL 문자열·배치 tx 경계 diff 0(AC-08-3). W-10 전건 준수(AC-08-2): setQueryTimeout 미사용 ·
     * 시간 상한 미도입(목적은 양보이지 상한이 아님) · org.sqlite.* main import 0 유지.
     *
     * @param cutoffMs present → {@code received_at < cutoff} 만 삭제. empty → 전체 삭제.
     */
    private CleanupResult deleteInBatches(OptionalLong cutoffMs) {
        int batches = 0;
        int deletedTraces = 0;
        while (true) {
            if (batches > 0) {
                sleepQuietly(BATCH_YIELD_MS);   // 배치 사이 양보 창 — 다른 writer/reader 가 끼어들 짬.
            }
            // cutoff 유무로 대상 선정만 분기 — cutoff 없으면 전체에서 오래된 순으로 배치.
            List<String> traceIds = cutoffMs.isPresent()
                    ? jdbc.queryForList(
                            """
                                    SELECT trace_id FROM traces
                                    WHERE received_at < ?
                                    ORDER BY received_at ASC
                                    LIMIT ?
                                    """,
                            String.class, cutoffMs.getAsLong(), batchSize)
                    : jdbc.queryForList(
                            """
                                    SELECT trace_id FROM traces
                                    ORDER BY received_at ASC
                                    LIMIT ?
                                    """,
                            String.class, batchSize);
            if (traceIds.isEmpty()) {
                break; // AC-A1-5: 대상 소진 = 종료 조건 (purgeAll 도 동일)
            }
            String in = String.join(",", Collections.nCopies(traceIds.size(), "?"));
            Object[] params = traceIds.toArray();
            tx.executeWithoutResult(status -> {
                // [Phase R12] AC-A1-3 — 명시적 3단 DELETE (CASCADE 의존 금지 — FK OFF, 비협상).
                // 1단: 자식의 자식(payloads) 먼저 → 2단: spans → 3단: traces.
                jdbc.update("DELETE FROM payloads WHERE span_id IN (SELECT span_id FROM spans WHERE trace_id IN ("
                        + in + "))", params);
                jdbc.update("DELETE FROM spans WHERE trace_id IN (" + in + ")", params);
                jdbc.update("DELETE FROM traces WHERE trace_id IN (" + in + ")", params);
            });
            batches++;
            deletedTraces += traceIds.size();
        }
        return new CleanupResult(batches, deletedTraces);
    }

    /**
     * [Phase R20] R20/AC-08-1 — 양보 sleep. {@code InterruptedException} 시 interrupt 플래그 복원 후
     * 즉시 진행한다(삭제 정확성 우선 — 배치 루프를 중단하지 않는다).
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Post-delete maintenance shared by cleanup and purgeAll:
     * stamp last_cleanup_at, reclaim free pages, truncate WAL, refresh stats.
     *
     * <p>// [Phase R13] AC-D1-2 — 디스크 회수의 구조적 한계 2가지 (D-04 파일 삭제 금지 전제):
     * // (i) incremental_vacuum 는 tail-only — free page 가 파일 끝에 모일 때만 회수한다.
     * //     중간 단편화는 즉시 안 줄 수 있다 (full VACUUM 은 파일 재생성이라 D-04 로 금지).
     * // (ii) wal_checkpoint(TRUNCATE) 는 reader 경합(SQLITE_BUSY)에 취약 — 수동 purge 는 운영자가
     * //      화면을 보는 중(reader 활성) 실행될 확률이 높아 busy 로 부분 실패할 수 있다.
     * //      그 경우 다음 cleanup(nightly/수동)에서 자연 재시도된다 (재시도 코드 불요).
     */
    private void finalizeMaintenance(long nowMs) {
        // AC-A1-7: 정상 종료 시 항상 갱신 (삭제 0건 포함) — "마지막 실행 시각" 의미 (T-10).
        jdbc.update("UPDATE retention_meta SET last_cleanup_at = ? WHERE id = 1", nowMs);

        // BL-03: incremental_vacuum — full VACUUM 경로 아님 (D-04: 파일 삭제/재생성 0). tail-only 한계 (i).
        jdbc.execute("PRAGMA incremental_vacuum");
        // [수동 정리] WAL truncate 미호출 시 -wal 파일이 안 줄어드는 문제 (디스크 회수 보강).
        // 순서: incremental_vacuum → wal_checkpoint(TRUNCATE) → ANALYZE. (순서 불변 봉인 — Design §2.D.1)
        // TRUNCATE 모드는 checkpoint 후 -wal 파일을 0 바이트로 잘라 디스크를 즉시 회수한다.
        checkpointWal();
        // AC-A4-3: cleanup 후 ANALYZE — 인덱스 통계 갱신.
        jdbc.execute("ANALYZE");
    }

    /**
     * [Phase R13] AC-D1-3 — wal_checkpoint(TRUNCATE) 결과 로깅 (재시도 안 함 — 한계 (ii)).
     *
     * <p>// PRAGMA wal_checkpoint(TRUNCATE) 는 단일 row 3컬럼 (busy, log, checkpointed) 을 반환한다.
     * // busy=1 이면 reader 경합으로 부분 실패 — 다음 cleanup 에서 자연 재시도되므로 로깅만 한다.
     * // queryForMap 매핑이 드라이버 사정으로 불가하면 execute fallback (busy 관측 생략, 가용성 우선).
     */
    private void checkpointWal() {
        try {
            java.util.Map<String, Object> ck = jdbc.queryForMap("PRAGMA wal_checkpoint(TRUNCATE)");
            Object busy = ck.get("busy"); // SQLite: 첫 컬럼명 "busy"
            if (busy instanceof Number n && n.intValue() != 0) {
                log.info("wal_checkpoint(TRUNCATE) busy — reader 경합, 부분 회수 (다음 cleanup 에서 재시도). result={}",
                        ck);
            }
        } catch (Exception e) {
            // queryForMap 매핑 실패(드라이버 차이) 또는 checkpoint 자체 실패 — execute fallback.
            // 디스크 회수 한계를 가용성보다 우선하지 않는다 (cleanup 종료를 막지 않음).
            log.warn("wal_checkpoint(TRUNCATE) result mapping unavailable — falling back to execute", e);
            try {
                jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Exception e2) {
                log.warn("wal_checkpoint(TRUNCATE) failed — continuing (디스크 회수 한계, 가용성 우선)", e2);
            }
        }
    }
}
