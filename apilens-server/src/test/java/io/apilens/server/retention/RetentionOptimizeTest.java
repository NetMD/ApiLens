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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.apilens.server.settings.SettingsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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
    //    아래 **4건**은 예산 크기에 맞는 **별도 가드**의 경계다. 두 가드를 섞어 쓰지 말 것.
    // [Phase R26] R26/AC-R26-36 — 개수를 3 에서 4 로 고쳤다(실물을 세어 맞춤). 종전 표기는
    //   네 번째 시험(한 점 외삽 대신 상계를 요구하는 경계)이 나중에 붙으면서 낡은 값이 됐다.

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

    // ── [Phase R28] [최적화] 권고 — 밤 판정 · 거둠 · 유지 (AC-28-05-1/02/03/05/12) ─────────────────
    //
    //  [Phase R28] AC-28-05-2 원문 요지: 「권고는 밤 정리의 <b>삭제 전</b> 빈 페이지가 전체의 25% 이상 <b>그리고</b>
    //  100 MiB 이상인 밤에 켜지고, 임계 아래 밤 · [최적화] 성공 · 재기동에 꺼진다. busy · 실패 · 수동 정리 · 전체
    //  삭제는 권고를 그대로 둔다」. 사용자 명시 결정(UD-3). CLAUDE.md '데이터 모델 (변경 신중히)' 인용 — 인메모리.
    //  ★실 100 MiB 파일을 만들지 않는다: 실 임시 SQLite 위 spy 로 PRAGMA 세 읽기만 꾸민다. 서비스는 기존 생성
    //  도우미를 거친다(생성자 호출 줄 0 추가 — 부록 E G-R28-08).

    @TempDir
    Path tempDir;

    /** A01 — 밤 진입점(Job)이 삭제 전 25% 그리고 100 MiB 를 보면 권고를 켠다. */
    @Test
    void advisesWhenTheNightlyJobSeesAQuarterAndOneHundredMiBFree() throws Exception {
        JdbcTemplate jdbc = realDbSpy();
        stubPragmas(jdbc, 30_000L, 100_000L, 4_096L);
        assertTrue(30_000L * 100L >= 100_000L * RetentionCleanupService.OPTIMIZE_ADVICE_MIN_FREE_PERCENT,
                "전제: 꾸민 값이 비율 임계 위다");
        assertTrue(30_000L * 4_096L >= RetentionCleanupService.OPTIMIZE_ADVICE_MIN_FREE_BYTES,
                "전제: 꾸민 값이 바이트 임계 위다");
        RetentionCleanupService service = newServiceWith(jdbc);
        assertFalse(service.isOptimizeAdvised(), "전제: 새 인스턴스는 권고 없음");

        List<String> info = infoLines(() -> new RetentionCleanupJob(service).runScheduled());

        assertTrue(info.contains("cleanup start: freelistPagesBeforeDelete=30000 pageCount=100000 pageSize=4096"),
                "전제: 밤 경로가 꾸민 삭제 전 값을 읽었다 — " + info);
        assertTrue(service.isOptimizeAdvised(), "밤 정리가 권고를 켰다");
    }

    /** A02 — 비율 경계: 정확히 25% → 참 · 한 페이지 모자람 → 거짓(바이트 조건은 참으로 고정). */
    @ParameterizedTest
    @CsvSource({"100000,true", "99999,false"})
    void decidesTheRatioBoundary(long freePages, boolean expected) {
        long pageCount = 400_000L;
        long pageSize = 4_096L;
        assertTrue(freePages * pageSize >= RetentionCleanupService.OPTIMIZE_ADVICE_MIN_FREE_BYTES, "전제: 바이트 조건은 참");
        RetentionCleanupService service = newServiceWith(mock(JdbcTemplate.class));

        service.judgeOptimizeAdvice(new RetentionCleanupService.FreelistSnapshot(freePages, pageCount, pageSize));

        assertEquals(expected, service.isOptimizeAdvised());
    }

    /** A03 — 바이트 경계: 104,857,600 B → 참 · 한 페이지 모자람 → 거짓(비율 조건은 참으로 고정). */
    @ParameterizedTest
    @CsvSource({"25600,true", "25599,false"})
    void decidesTheBytesBoundary(long freePages, boolean expected) {
        long pageCount = 50_000L;
        long pageSize = 4_096L;
        assertTrue(freePages * 100L >= pageCount * RetentionCleanupService.OPTIMIZE_ADVICE_MIN_FREE_PERCENT, "전제: 비율 조건은 참");
        RetentionCleanupService service = newServiceWith(mock(JdbcTemplate.class));

        service.judgeOptimizeAdvice(new RetentionCleanupService.FreelistSnapshot(freePages, pageCount, pageSize));

        assertEquals(expected, service.isOptimizeAdvised());
    }

    /** A04 — 같은 인스턴스에서 권고가 켜진 뒤 임계 아래 밤이 오면 꺼진다. */
    @Test
    void dropsTheAdviceWhenALaterNightIsBelowTheThreshold() {
        RetentionCleanupService service = newServiceWith(mock(JdbcTemplate.class));
        service.judgeOptimizeAdvice(above());
        assertTrue(service.isOptimizeAdvised(), "전제: 첫 밤에 권고가 켜졌다");

        service.judgeOptimizeAdvice(below());

        assertFalse(service.isOptimizeAdvised());
    }

    /** A05 — 판정은 삭제 <b>전</b> 값으로 한다. 삭제 후 값이 임계 위여도 삭제 전이 아래면 거짓. */
    @Test
    void judgesOnTheBeforeDeleteValueNotTheAfterDeleteValue() throws Exception {
        JdbcTemplate jdbc = realDbSpy();
        doReturn(1_000L, 30_000L).when(jdbc).queryForObject("PRAGMA freelist_count", Long.class);
        doReturn(100_000L).when(jdbc).queryForObject("PRAGMA page_count", Long.class);
        doReturn(4_096L).when(jdbc).queryForObject("PRAGMA page_size", Long.class);
        RetentionCleanupService service = newServiceWith(jdbc);

        List<String> info = infoLines(() -> new RetentionCleanupJob(service).runScheduled());

        assertTrue(info.contains("cleanup start: freelistPagesBeforeDelete=1000 pageCount=100000 pageSize=4096"),
                "전제: 삭제 전 값 = 1,000 — " + info);
        assertTrue(info.contains("reclaim start: freelistPages=30000 budgetPages=5000"),
                "전제: 삭제 후 값 = 30,000(이 값으로 판정했다면 참이었다) — " + info);
        assertFalse(service.isOptimizeAdvised(), "삭제 전 값으로 판정 → 거짓");
    }

    /** A06 — 새 인스턴스(= 재기동)는 권고 없음. */
    @Test
    void startsWithoutAdviceOnANewInstance() {
        assertFalse(newServiceWith(mock(JdbcTemplate.class)).isOptimizeAdvised());
    }

    /** A07 — 순서 ① 판정 참 → [최적화] 성공 ⇒ 거짓 · 성공 줄 한 줄(거둔 사실을 싣는다). */
    @Test
    void optimizeSuccessAfterAdviceClearsIt() {
        JdbcTemplate jdbc = successfulOptimizeJdbc();
        RetentionCleanupService service = newServiceWith(jdbc);
        service.judgeOptimizeAdvice(above());
        assertTrue(service.isOptimizeAdvised(), "전제: 권고가 켜져 있다");

        boolean[] busy = new boolean[1];
        List<String> info = infoLines(() -> busy[0] = service.optimizeDatabase());

        assertFalse(busy[0], "전제: [최적화] 성공 갈래");
        assertFalse(service.isOptimizeAdvised(), "성공이 권고를 거뒀다");
        assertEquals(List.of("optimize succeeded: adviceCleared=true"),
                info.stream().filter(l -> l.startsWith("optimize succeeded:")).toList());
    }

    /** A08 — 순서 ② [최적화] 성공 → 판정 참 ⇒ 참(마지막에 쓴 쪽이 남는다). */
    @Test
    void adviceAfterOptimizeSuccessStands() {
        JdbcTemplate jdbc = successfulOptimizeJdbc();
        RetentionCleanupService service = newServiceWith(jdbc);

        List<String> info = infoLines(() -> assertFalse(service.optimizeDatabase(), "전제: [최적화] 성공 갈래"));
        assertEquals(List.of("optimize succeeded: adviceCleared=false"),
                info.stream().filter(l -> l.startsWith("optimize succeeded:")).toList(), "전제: 거둘 권고가 없었다");

        service.judgeOptimizeAdvice(above());

        assertTrue(service.isOptimizeAdvised());
    }

    /**
     * A09 — [최적화]가 busy 로 끝나면 권고를 그대로 둔다. 판 ① 디스크 부족 거부(성긴 파일 — 할당 블록 0 이라 실제
     * 디스크를 안 쓴다) · 판 ② VACUUM 예외.
     */
    @ParameterizedTest
    @ValueSource(strings = {"diskShortage", "vacuumFails"})
    void keepsTheAdviceWhenOptimizeIsBusyOrFails(String branch) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        if ("diskShortage".equals(branch)) {
            File sparse = tempDir.resolve("sparse-larger-than-the-disk.db").toFile();
            try (RandomAccessFile raf = new RandomAccessFile(sparse, "rw")) {
                raf.setLength(tempDir.toFile().getUsableSpace() + (1L << 30));
            }
            assertFalse(RetentionCleanupService.hasEnoughDisk(sparse.getUsableSpace(), sparse.length()),
                    "전제: 디스크 가드가 거부할 크기다");
            when(jdbc.queryForList("PRAGMA database_list"))
                    .thenReturn(List.of(Map.of("name", "main", "file", sparse.getAbsolutePath())));
        } else {
            when(jdbc.queryForList("PRAGMA database_list"))
                    .thenReturn(List.of(Map.of("name", "main", "file", "")));
            doThrow(new DataAccessResourceFailureException("database is locked (SQLITE_BUSY)"))
                    .when(jdbc).execute(eq("VACUUM"));
        }
        RetentionCleanupService service = newServiceWith(jdbc);
        service.judgeOptimizeAdvice(above());
        assertTrue(service.isOptimizeAdvised(), "전제: 권고가 켜져 있다");

        assertTrue(service.optimizeDatabase(), "busy 갈래");

        if ("diskShortage".equals(branch)) {
            verify(jdbc, never()).execute("VACUUM");
        }
        assertTrue(service.isOptimizeAdvised(), "busy · 실패는 권고를 그대로 둔다");
    }

    /** A10 — 밤마다 판정 줄 한 줄 · INFO · 필드 이름은 {@code cleanup start:} 줄과 같은 freelistPagesBeforeDelete. */
    @ParameterizedTest
    @CsvSource({
            "1000,false,4096000",
            "30000,true,122880000"})
    void logsOneInfoAdviceLinePerNight(long freePages, boolean advised, long freeBytes) throws Exception {
        JdbcTemplate jdbc = realDbSpy();
        stubPragmas(jdbc, freePages, 100_000L, 4_096L);
        RetentionCleanupService service = newServiceWith(jdbc);

        List<ILoggingEvent> events = events(() -> new RetentionCleanupJob(service).runScheduled());

        List<ILoggingEvent> advice = events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("optimize advice:")).toList();
        assertEquals(1, advice.size(), "밤마다 한 줄 — " + advice);
        assertEquals(Level.INFO, advice.get(0).getLevel(), "권고가 뜨는 밤에도 INFO");
        assertEquals("optimize advice: advised=" + advised + " freelistPagesBeforeDelete=" + freePages
                        + " pageCount=100000 freeBytesBeforeDelete=" + freeBytes + " minFreePercent=25 minFreeBytes=104857600",
                advice.get(0).getFormattedMessage());
        assertEquals(advised, service.isOptimizeAdvised());
    }

    /** A11 — 관측이 실패한 밤은 skipped 줄만 남기고 상태를 그대로 둔다(근거 없이 바꾸지 않는다). */
    @Test
    void logsSkippedAndKeepsTheStateWhenObservationFails() throws Exception {
        JdbcTemplate jdbc = realDbSpy();
        doReturn(30_000L).when(jdbc).queryForObject("PRAGMA freelist_count", Long.class);
        doThrow(new DataAccessResourceFailureException("page_count unavailable"))
                .when(jdbc).queryForObject("PRAGMA page_count", Long.class);
        RetentionCleanupService service = newServiceWith(jdbc);
        service.judgeOptimizeAdvice(above());
        assertTrue(service.isOptimizeAdvised(), "전제: 권고가 켜져 있다");

        List<ILoggingEvent> events = events(() -> new RetentionCleanupJob(service).runScheduled());

        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().startsWith("freelist observation before delete failed")),
                "전제: 삭제 전 관측이 실제로 실패했다");
        List<String> advice = events.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("optimize advice:")).toList();
        assertEquals(List.of("optimize advice: skipped (observation failed) advised=true"), advice);
        assertTrue(service.isOptimizeAdvised(), "상태 그대로");
    }

    private static RetentionCleanupService.FreelistSnapshot above() {
        return new RetentionCleanupService.FreelistSnapshot(30_000L, 100_000L, 4_096L);
    }

    private static RetentionCleanupService.FreelistSnapshot below() {
        return new RetentionCleanupService.FreelistSnapshot(1_000L, 100_000L, 4_096L);
    }

    /** 실 임시 SQLite(Flyway · 비워 둠) 위의 spy — 지울 행이 0 이라 삭제 배치 트랜잭션이 안 열린다. */
    private JdbcTemplate realDbSpy() throws Exception {
        Path dbFile = Files.createTempFile(tempDir, "apilens-advice-", ".db");
        Files.deleteIfExists(dbFile);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return spy(new JdbcTemplate(ds));
    }

    private static void stubPragmas(JdbcTemplate spyJdbc, long freePages, long pageCount, long pageSize) {
        doReturn(freePages).when(spyJdbc).queryForObject("PRAGMA freelist_count", Long.class);
        doReturn(pageCount).when(spyJdbc).queryForObject("PRAGMA page_count", Long.class);
        doReturn(pageSize).when(spyJdbc).queryForObject("PRAGMA page_size", Long.class);
    }

    /** 기존 성공 갈래 꼴 — 디스크 가드 건너뜀(빈 파일 경로) + 체크포인트 결과 매핑. */
    private static JdbcTemplate successfulOptimizeJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("PRAGMA database_list"))
                .thenReturn(List.of(Map.of("name", "main", "file", "")));
        when(jdbc.queryForMap("PRAGMA wal_checkpoint(TRUNCATE)"))
                .thenReturn(Map.of("busy", 0));
        return jdbc;
    }

    /** INFO 를 켠 채 action 을 돌리고 정리 서비스 로그 이벤트를 돌려준다. 레벨은 원래대로 되돌린다. */
    private static List<ILoggingEvent> events(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RetentionCleanupService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static List<String> infoLines(Runnable action) {
        return events(action).stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private RetentionCleanupService newServiceWith(JdbcTemplate jdbc) {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        SettingsService settingsService = mock(SettingsService.class);
        return new RetentionCleanupService(jdbc, txManager, settingsService);
    }
}
