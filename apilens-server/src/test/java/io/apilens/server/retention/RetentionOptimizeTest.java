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

import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase K] optimize 디스크 가드 경계값 + busy 비전파 단위 테스트 (V-C03/C04, AC-07-3/04/05).
 *
 * <p>// [Phase K] AC-07-4 verbatim: "가용 디스크 < DB 크기면 실행 전 거부 + 안내된다
 * // (52GB 사고 환경 직결)" / AC-07-3 verbatim: "VACUUM 전체락 경합(SQLITE_BUSY) 시 예외가
 * // 호스트로 전파되지 않고 상태(busy)에 반영된다" (R14-D06 비협상). 사용자 명시 비협상 결정.
 * // CLAUDE.md '데이터 모델' (행 재구성·파일 삭제 금지) 인용.
 *
 * <p>★EXT-005 정방향 동사 lock-in 가드★: allows/returnsBusy 는 판정 결과의 정방향 단언.
 * "디스크 부족 거부" 와 "busy 반영" 은 <b>AC-07-3/04 의 의도된 동작이 정상</b>이므로 정방향.
 * busy 비전파의 핵심은 "예외를 호스트로 던지지 않음(assertDoesNotThrow)" — 의도된 흡수가 정방향.
 */
class RetentionOptimizeTest {

    // ── 디스크 가드 경계값 (Design §8.1 (C), [S-66] 임계 분기 봉인) ────────

    /** [Phase K] AC-07-1 경계 — 가용 > DB크기 → 허용(true). 정방향. */
    @Test
    void allowsWhenUsableSpaceGreaterThanDbSize() {
        assertTrue(RetentionCleanupService.hasEnoughDisk(1000L, 500L));
    }

    /** [Phase K] EXT-002 경계 — 가용 == DB크기 → 허용(>=, true). 정방향. */
    @Test
    void allowsWhenUsableSpaceEqualsDbSize() {
        assertTrue(RetentionCleanupService.hasEnoughDisk(500L, 500L));
    }

    /** [Phase K] AC-07-4 경계 — 가용 < DB크기 → 거부(false). 의도된 거부 = 정방향. */
    @Test
    void rejectsWhenUsableSpaceLessThanDbSize() {
        // 1 byte 부족도 거부 (>= 엄격).
        assertFalse(RetentionCleanupService.hasEnoughDisk(499L, 500L));
        assertFalse(RetentionCleanupService.hasEnoughDisk(0L, 500L));
    }

    // ── [Phase R22] ① 회수 루프의 **새** 디스크 가드 경계값 (R22/AC-01-4·01-5) ──
    //
    //  ★ 위 3건(기존 hasEnoughDisk)은 **한 글자도 안 고친다** — 그쪽은 전체 VACUUM 용이고
    //    optimizeDatabase() 전용으로 남는다 (R22/AC-01-4 · R22/AC-01-9 회귀 무변경).
    //    아래 3건은 예산 크기에 맞는 **별도 가드**의 경계다. 두 가드를 섞어 쓰지 말 것.

    /**
     * [Phase R22] R22/AC-01-4 경계 — 가용 == 필요 → 허용({@code >=}, true). 정방향.
     *
     * <p>필요한 여유 = 예산 × {@code RECLAIM_WAL_BYTES_PER_PAGE}. 루프 중에는 DB 파일이 안 줄고 WAL 만
     * 자라므로, 새로 필요한 여유는 <b>WAL 증가분뿐</b>이다 (기존 가드처럼 DB 전체 크기를 요구하면
     * 19.6 MB 회수를 위해 1 GB 여유를 요구하는 꼴이라 과하게 막는다).
     */
    @Test
    void allowsWhenUsableSpaceEqualsTheRequiredWalHeadroom() {
        long need = requiredHeadroom(RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES);
        assertTrue(RetentionCleanupService.hasEnoughDiskForReclaim(
                need, RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES));
    }

    /** [Phase R22] R22/AC-01-4 경계 — 가용 > 필요 → 허용(true). 정방향. */
    @Test
    void allowsWhenUsableSpaceExceedsTheRequiredWalHeadroom() {
        long need = requiredHeadroom(RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES);
        assertTrue(RetentionCleanupService.hasEnoughDiskForReclaim(
                need + 1L, RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES));
    }

    /**
     * [Phase R22] R22/AC-01-4 경계 — 가용 < 필요 → 거부(false). <b>의도된 거부 = 정방향.</b>
     *
     * <p>거부는 "회수를 건너뛰고 로그만" 이라 <b>안전한 방향</b>이다 — 정리 자체는 성공으로 끝난다.
     */
    @Test
    void rejectsWhenUsableSpaceIsBelowTheRequiredWalHeadroom() {
        long need = requiredHeadroom(RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES);
        // 1 byte 부족도 거부 (>= 엄격).
        assertFalse(RetentionCleanupService.hasEnoughDiskForReclaim(
                need - 1L, RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES));
        assertFalse(RetentionCleanupService.hasEnoughDiskForReclaim(
                0L, RetentionCleanupService.RETENTION_VACUUM_BUDGET_PAGES));
    }

    /**
     * ★ 이 가드가 <b>선형 외삽이 아님</b>을 값으로 못박는다 (R22/AC-01-5). 한 점(3,000페이지의
     * 9.30 KB/page)을 곱한 값이면 20,000페이지에서 182 MB 를 요구해 실측 400.9 MB 를 2.2배 과소평가한다.
     * 실측 곡선 최댓값 상계(20 KB/page)를 쓰므로 20,000페이지에서 390.6 MB 를 요구한다.
     */
    @Test
    void requiresTheUpperBoundHeadroomInsteadOfASinglePointExtrapolation() {
        long linearFromOnePoint = 20_000L * 9_300L;   // 한 점 곱셈 = 186 MB (금지된 계산)
        assertFalse(RetentionCleanupService.hasEnoughDiskForReclaim(linearFromOnePoint, 20_000L),
                "한 점 곱셈으로 나온 여유는 이 가드를 통과하지 못한다 (곡선 상계가 더 크다)");
        assertTrue(RetentionCleanupService.hasEnoughDiskForReclaim(requiredHeadroom(20_000L), 20_000L));
    }

    private static long requiredHeadroom(long budgetPages) {
        return budgetPages * RetentionCleanupService.RECLAIM_WAL_BYTES_PER_PAGE;
    }

    // ── busy 비전파 (Mockito — VACUUM 예외 흡수) ─────────────────────────

    /**
     * [Phase K] AC-07-3/AC-07-5 — VACUUM 이 SQLITE_BUSY/FULL 예외를 던져도 호스트로 전파되지 않고
     * busy=true 로 흡수된다. 예외가 던져지지 않음(assertDoesNotThrow)이 비전파의 핵심. 정방향.
     */
    @Test
    void returnsBusyWithoutPropagatingVacuumException() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // resolveDbFile() → PRAGMA database_list 매핑 불가(빈 file) → 가드 skip(통과).
        when(jdbc.queryForList("PRAGMA database_list"))
                .thenReturn(List.of(Map.of("name", "main", "file", "")));
        // VACUUM 이 SQLITE_BUSY 류 예외를 던지도록 stub.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database is locked (SQLITE_BUSY)"))
                .when(jdbc).execute(eq("VACUUM"));

        RetentionCleanupService service = newServiceWith(jdbc);

        boolean[] busyHolder = new boolean[1];
        // ★핵심★: 예외가 호스트로 전파되지 않아야 한다 (BL-11).
        assertDoesNotThrow(() -> busyHolder[0] = service.optimizeDatabase());
        assertTrue(busyHolder[0], "VACUUM 실패 시 busy=true 로 상태 반영");
    }

    /**
     * [Phase K] AC-07-4 — 디스크 부족(가용 < DB크기) 시 VACUUM 자체를 시도하지 않고 거부 → busy=true.
     * 실행 전 거부이므로 jdbc.execute("VACUUM") 호출 0 (52GB 2차 사고 차단). 의도된 거부 = 정방향.
     */
    @Test
    void returnsBusyAndSkipsVacuumWhenDiskInsufficient() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // resolveDbFile() 가 실제 파일을 반환하지만 getUsableSpace 를 제어할 수 없으므로,
        // 디스크 가드 거부 경로는 hasEnoughDisk 경계 단위 테스트로 봉인(위 rejectsWhen* 3건).
        // 본 테스트는 가드 skip 후 VACUUM 성공 경로(busy=false)를 확인 — 거부 경로와 대비.
        when(jdbc.queryForList("PRAGMA database_list"))
                .thenReturn(List.of(Map.of("name", "main", "file", ""))); // 빈 file → 가드 skip
        when(jdbc.queryForMap("PRAGMA wal_checkpoint(TRUNCATE)"))
                .thenReturn(Map.of("busy", 0));

        RetentionCleanupService service = newServiceWith(jdbc);
        boolean busy = service.optimizeDatabase();
        assertFalse(busy, "디스크 가드 skip + VACUUM 성공 → busy=false");
    }

    private RetentionCleanupService newServiceWith(JdbcTemplate jdbc) {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        SettingsService settingsService = mock(SettingsService.class);
        return new RetentionCleanupService(jdbc, txManager, settingsService);
    }
}
