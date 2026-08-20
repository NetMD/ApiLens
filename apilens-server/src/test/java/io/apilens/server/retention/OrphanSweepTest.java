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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R22] ③ 고아 span <b>2밤차 스윕</b> 전용 테스트 (Design §7.2).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>R22/AC-03-3: "<b>2밤차</b> — 삭제 대상 = <b>(어젯밤 후보) ∩ (오늘밤도 고아)</b>. 저장해 둔 후보
 *       목록을 그대로 지우지 않는다."</li>
 *   <li>R22/AC-03-4: "<b>삭제 순서는 {@code payloads} → {@code spans}</b> 다. (비협상)" — 사용자 명시
 *       비협상 결정</li>
 *   <li>R22/AC-03-7: "수동 <b>[지난 데이터 정리 / 보관 기간 즉시 적용]</b> 경로는 후보 상태를 <b>읽지도
 *       쓰지도 않는다.</b> ⇒ 이 버튼을 연달아 두 번 눌러도 고아 삭제는 0 이다." — 사용자 명시 비협상 결정</li>
 *   <li>R22/AC-03-8: "<b>[전체 삭제]({@code purgeAll})는 2밤차를 기다리지 않고 즉시 지운다.</b> 그리고
 *       후보 목록도 <b>비운다</b>."</li>
 * </ul>
 * CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용 — 스키마 변경 0 · 마이그레이션 0.
 *
 * <p><b>"밤" 의 뜻</b>: {@code sweepOrphanSpansNightly()} 실행 1회. 시각 비교를 쓰지 않으므로
 * (R22/AC-03-9) 테스트에서도 시계를 앞당기지 않고 <b>스윕을 한 번 더 부르는 것</b>이 "다음 밤" 이다.
 *
 * <p>기존 {@code RetentionCleanupServiceTest} 를 비대하게 만들지 않으려고 파일을 나눴다.
 */
class OrphanSweepTest {

    /** 후보 목록이 저장되는 내부 키 — production 리터럴은 {@code OrphanCandidateStore} 한 곳뿐이다. */
    private static final String CANDIDATE_KEY = OrphanCandidateStore.KEY_ORPHAN_CANDIDATES;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager txManager;
    private SettingsService settingsService;
    private RetentionCleanupService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-orphan-sweep-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.txManager = new DataSourceTransactionManager(dataSource);
        this.settingsService = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        this.service = new RetentionCleanupService(jdbc, txManager, settingsService);
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── T-01 · 1밤차는 기록만 ───────────────────────────────────────────────

    /**
     * R22/AC-03-2 verbatim: "<b>1밤차</b> — 고아로 판정된 span 을 후보로 <b>기록만</b> 한다. 삭제 0."
     */
    @Test
    void recordsCandidatesWithoutDeletingAnythingOnTheFirstNight() {
        seedOrphanSpans("o1", "o2", "o3");

        service.sweepOrphanSpansNightly();

        assertEquals(3, countOrphanSpans(), "1밤차에는 한 건도 안 지운다");
        assertEquals(3, storedCandidates().size(), "대신 오늘 본 고아를 후보로 기록한다");
    }

    // ─── T-02 · 2밤차에 삭제 (payload 도 함께) ───────────────────────────────

    /**
     * R22/AC-03-3 verbatim: "<b>2밤차</b> — 삭제 대상 = <b>(어젯밤 후보) ∩ (오늘밤도 고아)</b>."
     * R22/AC-03-4 (비협상): 삭제 순서가 {@code payloads} → {@code spans} 라야 payload 가 함께 사라진다.
     * 순서를 뒤집으면 payload 를 찾을 길이 없어져 <b>영구히</b> 남는다 — 그 회귀를 이 단언이 잡는다.
     */
    @Test
    void deletesOrphanSpansAndTheirPayloadsOnTheSecondNight() {
        seedOrphanSpans("o1", "o2", "o3");
        seedPayload("o1");
        seedPayload("o2");

        service.sweepOrphanSpansNightly();   // 1밤차 — 기록만
        service.sweepOrphanSpansNightly();   // 2밤차 — 교집합 삭제

        assertEquals(0, countOrphanSpans(), "2밤차에 고아 span 이 사라진다");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payloads", Integer.class),
                "payload 도 함께 사라진다 (삭제 순서 payloads → spans 의 직접 증거)");
        assertTrue(storedCandidates().isEmpty(), "지운 것은 다음 밤 후보로 나르지 않는다");
    }

    // ─── T-03 · 중간에 요약이 도착하면 살아남는다 ────────────────────────────

    /**
     * R22/AC-03-3 의 교집합이 지키는 것 — 1밤차 후보였더라도 <b>오늘 고아가 아니면</b> 안 지운다.
     * 요약(트리 머리)이 늦게 도착한 span 을 죽이지 않는 것이 2밤차 유예의 존재 이유다.
     *
     * <p>재실행 멱등(EXT-012) 이중 단언 (a): 같은 상태로 스윕을 여러 번 돌려도 {@code settings} 의
     * 후보 행은 <b>키가 PRIMARY KEY 라 언제나 1행</b>이다 — 중복이 쌓이지 않는다.
     */
    @Test
    void keepsSpansWhoseSummaryArrivesBeforeTheSecondNight() {
        seedOrphanSpans("o1", "o2");

        service.sweepOrphanSpansNightly();          // 1밤차 — o1·o2 가 후보
        insertTraceRow("trace-o1");                 // 요약이 뒤늦게 도착 → o1 은 더 이상 고아가 아니다

        service.sweepOrphanSpansNightly();          // 2밤차

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = 'o1'", Integer.class),
                "요약이 도착한 span 은 살아남는다");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = 'o2'", Integer.class),
                "끝내 고아인 span 만 지운다");
        assertEquals(1, countCandidateRows(), "후보 행은 언제나 1행 (재실행해도 중복이 안 쌓인다)");
    }

    // ─── T-04 · [전체 삭제]는 1회로 즉시 0 ──────────────────────────────────

    /**
     * R22/AC-03-8 verbatim: "<b>[전체 삭제]({@code purgeAll})는 2밤차를 기다리지 않고 즉시 지운다.</b>
     * 그리고 후보 목록도 <b>비운다</b> (전부 지운 뒤 이미 없는 span_id 를 후보로 들고 다음 밤을 시작하지
     * 않게 한다)."
     *
     * <p>{@code purgeAll} 의 배치 루프는 {@code traces} 를 기점으로 대상을 고르므로 고아 span 을
     * <b>구조적으로 못 잡는다</b> — 즉시 스윕이 없으면 "전체 삭제" 를 눌러도 고아가 남는다.
     */
    @Test
    void clearsOrphansImmediatelyOnPurgeAllAndEmptiesTheCandidateList() {
        seedOrphanSpans("o1", "o2", "o3");
        seedPayload("o1");
        service.sweepOrphanSpansNightly();   // 후보가 기록된 상태에서 전체 삭제를 누른다

        service.purgeAll();

        assertEquals(0, countOrphanSpans(), "전체 삭제는 1회로 고아까지 0 으로 만든다");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payloads", Integer.class));
        assertTrue(storedCandidates().isEmpty(), "이미 없는 span_id 를 다음 밤으로 나르지 않는다");
    }

    // ─── T-05 · 후보 행이 아예 없어도 정상 ──────────────────────────────────

    /**
     * R22/AC-03-12 verbatim: "후보 값이 깨져 있어도 정리 시각 갱신은 성공한다. … 예외를 밖으로 던지지
     * 않는다." — 행 자체가 없는 <b>첫 실행</b>이 그 극단이다.
     *
     * <p>재실행 멱등(EXT-012) 이중 단언 (b): 첫 실행의 결과가 "삭제 0 + 후보 기록" 으로, 같은 입력에
     * 대해 몇 번을 돌려도 같은 밤 안에서는 같은 결과를 준다.
     */
    @Test
    void recordsCandidatesWithoutFailingWhenNoCandidateRowExists() {
        seedOrphanSpans("o1");
        assertEquals(0, countCandidateRows(), "전제: 후보 행이 아직 없다 (첫 실행)");

        assertDoesNotThrow(() -> service.sweepOrphanSpansNightly());

        assertEquals(1, countOrphanSpans(), "첫 실행은 삭제 0");
        assertEquals(List.of("o1"), storedCandidates());
    }

    // ─── T-06 · 수동 정리 연속 2회 → 삭제 0 (C-1 비협상) ────────────────────

    /**
     * ★ R22/AC-03-7 verbatim: "수동 <b>[지난 데이터 정리 / 보관 기간 즉시 적용]</b> 경로는 후보 상태를
     * <b>읽지도 쓰지도 않는다.</b> ⇒ 이 버튼을 연달아 두 번 눌러도 고아 삭제는 0 이다.
     * <b>"밤" = 야간 스케줄 실행 1회</b>다." <b>사용자 명시 비협상 결정</b>(U-1).
     *
     * <p>이 단언이 지키는 것은 동작뿐이 아니다 — 후보 키가 <b>생성조차 되지 않는다</b>는 것이
     * "수동 경로에서 후보 상태로 가는 코드 경로가 존재하지 않는다" 의 실행 증거다.
     */
    @Test
    void keepsOrphansUntouchedWhenTheManualCleanupRunsTwice() {
        seedOrphanSpans("o1", "o2", "o3");
        long now = System.currentTimeMillis();

        service.cleanup(now);          // 수동 [보관 기간 즉시 적용] 1회
        service.cleanup(now + 1);      // 연달아 2회

        assertEquals(3, countOrphanSpans(), "수동 정리를 두 번 눌러도 고아 삭제는 0");
        assertEquals(0, countCandidateRows(), "수동 경로는 후보 키를 만들지도 않는다");
    }

    // ─── T-07 · 후보 값이 깨져 있어도 정리 시각은 갱신된다 (C-2) ────────────

    /**
     * ★ R22/AC-03-12 verbatim: "후보 값이 깨져 있어도 정리 시각 갱신은 성공한다. 후보 파싱 실패는
     * {@code SettingsService} 의 방어 관용구와 같은 모양으로 흡수한다 — <b>예외를 밖으로 던지지 않는다</b>."
     * 사용자 명시 결정(C-2).
     *
     * <p>정리 시각은 {@code cleanup()} 이 찍고, 스윕은 그 <b>뒤</b>에 돈다 — 구조적으로 "시각 기록보다 뒤" 다.
     */
    @Test
    void stampsTheCleanupTimestampEvenWhenTheCandidateValueIsCorrupt() {
        seedOrphanSpans("o1", "o2");
        // 한글·제어문자·과길이 조각이 섞인 값 (수동 DB 편집 등으로 유입될 수 있는 모양).
        writeCandidateRaw("한글,,,,,x".repeat(1) + ",," + "y".repeat(300));
        long now = System.currentTimeMillis();

        assertDoesNotThrow(() -> service.cleanup(now));
        assertDoesNotThrow(() -> service.sweepOrphanSpansNightly());

        assertEquals(now, jdbc.queryForObject(
                "SELECT last_cleanup_at FROM retention_meta WHERE id = 1", Long.class),
                "후보 값이 깨져 있어도 그 밤의 정리 시각은 남는다");
        assertEquals(2, countOrphanSpans(), "깨진 후보와 겹치는 것이 없으므로 그 밤 삭제는 0");
    }

    // ─── T-08 · 스윕 후 "span 없는 payload" 0 유지 ──────────────────────────

    /**
     * R22/AC-03-13 verbatim: "스윕 후 <b>"span 없는 payload" 가 0</b> 으로 유지된다."
     * {@code RetentionCleanupServiceTest} 의 역방향 단언식과 <b>같은 식</b>을 쓴다.
     */
    @Test
    void keepsZeroPayloadsWithoutASpanAfterTheSweep() {
        seedOrphanSpans("o1", "o2");
        seedPayload("o1");
        seedPayload("o2");
        // 살아 있는 trace 의 span·payload 도 섞어 둔다 (스윕이 남의 것을 안 건드리는지 함께 본다).
        insertTraceRow("trace-live");
        insertSpanRow("live-1", "trace-live");
        seedPayload("live-1");

        service.sweepOrphanSpansNightly();
        service.sweepOrphanSpansNightly();

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE span_id NOT IN (SELECT span_id FROM spans)",
                Integer.class), "span 없는 payload 0 — 3단 삭제 정합과 같은 기준");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE span_id = 'live-1'", Integer.class),
                "살아 있는 trace 의 span 은 그대로");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payloads WHERE span_id = 'live-1'", Integer.class),
                "살아 있는 span 의 payload 도 그대로");
    }

    // ─── 후보 목록 파싱·개수 경계 (B-11 · B-13 · B-14) ──────────────────────

    /**
     * B-11 — 저장 값에 빈 조각이 섞여 있어도({@code "a,,b,"}) 두 id 로 읽힌다.
     * 어젯밤 후보를 손으로 심어 2밤차 상태를 만든다.
     */
    @Test
    void readsCandidateIdsWhileSkippingEmptyPiecesInTheStoredValue() {
        seedOrphanSpans("o1", "o2");
        writeCandidateRaw("o1,,o2,");   // 빈 조각 3개 포함

        service.sweepOrphanSpansNightly();   // 어젯밤 후보가 이미 있으므로 이번이 2밤차

        assertEquals(0, countOrphanSpans(), "빈 조각을 건너뛰고 두 id 를 모두 읽는다");
    }

    /**
     * B-13/B-14 — 고아가 상한({@code ORPHAN_CANDIDATE_CAP})을 넘으면 그 밤에는 상한만큼만 후보가 되고,
     * 나머지는 <b>다음 밤에 다시 후보가 된다</b> (잃는 것이 없다 — R22/AC-03-10).
     */
    @Test
    void capsTheCandidateListAtTheLimitAndLeavesTheRestForTheNextNight() {
        int over = RetentionCleanupService.ORPHAN_CANDIDATE_CAP + 1;
        String[] ids = new String[over];
        for (int i = 0; i < over; i++) {
            ids[i] = "cap-" + i;
        }
        seedOrphanSpans(ids);

        service.sweepOrphanSpansNightly();   // 1밤차 — 상한만큼만 후보

        assertEquals(RetentionCleanupService.ORPHAN_CANDIDATE_CAP, storedCandidates().size(),
                "스윕 1회에 상한을 넘는 후보를 기록하지 않는다");
        assertEquals(over, countOrphanSpans(), "1밤차라 아직 삭제 0");

        service.sweepOrphanSpansNightly();   // 2밤차 — 상한만큼 삭제
        assertEquals(over - RetentionCleanupService.ORPHAN_CANDIDATE_CAP, countOrphanSpans(),
                "초과분은 사라지지 않고 남는다 (다음 밤에 다시 후보가 된다 — 안전한 방향)");
    }

    // ─── 후보 목록 **쓰기** 방어 (SEC-R22-02 · R23/AC-08-3) ──────────────────

    /**
     * [Phase R23] R23/AC-08-3 — 후보 목록 <b>쓰기</b> 방어에 테스트가 하나도 없던 자리를 메운다
     * (SEC-R22-02 이월). 읽기 쪽 방어는 위 두 테스트가 이미 잠그고 있었지만, 쓰기 쪽 거부 조건은
     * 지우더라도 아무 테스트도 안 깨지는 상태였다.
     *
     * <p><b>쉼표가 든 id 는 목록에서 버린다</b> — 저장 형식이 쉼표로 이은 문자열이라, 값 안에 쉼표가
     * 섞이면 <b>두 id 가 하나로 합쳐지거나 하나가 둘로 쪼개진다.</b> 그러면 다음 밤의 교집합이
     * 엉뚱한 span 을 가리킬 수 있다. 버리는 방향이라 <b>틀려도 안전한 쪽</b>이다(안 지우고 남는다).
     */
    @Test
    void keepsOnlyCommaFreeIdsWhenWritingTheCandidateList() {
        OrphanCandidateStore store = new OrphanCandidateStore(jdbc);

        store.write(List.of("clean-1", "bad,id", "clean-2"));

        assertEquals(List.of("clean-1", "clean-2"), storedCandidates(),
                "쉼표가 든 조각만 빠지고 나머지는 그대로 저장된다");
    }

    /**
     * [Phase R23] R23/AC-08-3 — 길이 상한(64자)을 넘는 id 도 <b>쓰기 시점에</b> 버린다.
     * span_id 는 W3C 규격 16자리 16진수라 64 는 넉넉한 상계다. 이 방어가 없으면 비정상 값 하나가
     * {@code settings} 한 행을 비대하게 만든 채 영구히 남는다.
     *
     * <p>⚠️ 이 거부가 만드는 그늘은 별건으로 남아 있다 — 64자를 넘는 span_id 를 가진 고아는 후보가
     * 되지 못해 영원히 안 지워진다(적재 입력 검증을 여는 라운드 몫). 여기서 잠그는 것은
     * <b>쓰기 방어가 살아 있다</b>는 사실뿐이다.
     */
    @Test
    void keepsOnlyIdsWithinTheLengthLimitWhenWritingTheCandidateList() {
        OrphanCandidateStore store = new OrphanCandidateStore(jdbc);
        String tooLong = "f".repeat(65);   // 상한 64 를 한 글자 넘긴다

        store.write(List.of("short-1", tooLong, "short-2"));

        assertEquals(List.of("short-1", "short-2"), storedCandidates(),
                "상한을 넘는 조각만 빠지고 나머지는 그대로 저장된다");
    }

    // ─── fixture helpers ────────────────────────────────────────────────────

    /** {@code traces} 에 짝이 없는 span 을 심는다 = 고아 span (기준 A). */
    private void seedOrphanSpans(String... spanIds) {
        List<Object[]> batch = new ArrayList<>();
        for (String spanId : spanIds) {
            batch.add(new Object[]{spanId, "trace-" + spanId});
        }
        jdbc.batchUpdate("""
                INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                   span_kind, start_time, end_time, status, attributes_json)
                VALUES (?, ?, NULL, 'svc', 'GET /x', 'SERVER', 1000, 1100, 'OK', NULL)
                """, batch);
    }

    private void insertTraceRow(String traceId) {
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'GET /x', 'svc', 1000, 100, 'OK', 1, 1, 0, 1000)
                        """,
                traceId);
    }

    private void insertSpanRow(String spanId, String traceId) {
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, 'svc', 'GET /x', 'SERVER', 1000, 1100, 'OK', NULL)
                        """,
                spanId, traceId);
    }

    private void seedPayload(String spanId) {
        jdbc.update("""
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'in', 'application/json', '{}', 2, 0)
                        """,
                spanId);
    }

    /** 후보 목록 값을 손으로 심는다 — "어젯밤" 상태를 만들거나 깨진 값을 재현할 때 쓴다. */
    private void writeCandidateRaw(String value) {
        jdbc.update("""
                        INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """,
                CANDIDATE_KEY, value, System.currentTimeMillis());
    }

    private List<String> storedCandidates() {
        List<String> rows = jdbc.queryForList("SELECT value FROM settings WHERE key = ?",
                String.class, CANDIDATE_KEY);
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return List.of();
        }
        return List.of(rows.get(0).split(","));
    }

    private int countCandidateRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE key = ?",
                Integer.class, CANDIDATE_KEY);
        return n == null ? 0 : n;
    }

    /** {@code RetentionCleanupServiceTest} 의 고아 판정 단언식과 <b>같은 뜻</b> (새 정의를 만들지 않는다). */
    private int countOrphanSpans() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spans WHERE trace_id NOT IN (SELECT trace_id FROM traces)",
                Integer.class);
        return n == null ? 0 : n;
    }
}
