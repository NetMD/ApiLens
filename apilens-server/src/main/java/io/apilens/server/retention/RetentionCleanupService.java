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

import java.util.Collections;
import java.util.List;

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
     */
    static final int RETENTION_DELETE_BATCH_SIZE = 500;

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

        int batches = 0;
        int deletedTraces = 0;
        while (true) {
            List<String> traceIds = jdbc.queryForList(
                    """
                            SELECT trace_id FROM traces
                            WHERE received_at < ?
                            ORDER BY received_at ASC
                            LIMIT ?
                            """,
                    String.class, cutoffMs, batchSize
            );
            if (traceIds.isEmpty()) {
                break; // AC-A1-5: 대상 소진 = 종료 조건
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

        // AC-A1-7: 정상 종료 시 항상 갱신 (삭제 0건 포함) — "마지막 실행 시각" 의미 (T-10).
        jdbc.update("UPDATE retention_meta SET last_cleanup_at = ? WHERE id = 1", nowMs);

        // BL-03: incremental_vacuum — full VACUUM 경로 아님 (D-04: 파일 삭제/재생성 0).
        jdbc.execute("PRAGMA incremental_vacuum");
        // AC-A4-3: cleanup 후 ANALYZE — 인덱스 통계 갱신.
        jdbc.execute("ANALYZE");

        log.info("retention cleanup finished: deletedTraces={} batches={} cutoffMs={}",
                deletedTraces, batches, cutoffMs);
        return new CleanupResult(batches, deletedTraces);
    }
}
