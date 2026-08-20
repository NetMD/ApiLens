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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.server.ingest.IngestPauseState;
import io.apilens.server.ingest.IngestPauseStateTestFactory;
import io.apilens.server.ingest.IngestProperties;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.settings.SettingsRegistry;
import io.apilens.server.settings.SettingsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [수동 정리] MaintenanceController MockMvc 단위 테스트 — POST /v1/maintenance/{cleanup,purge}.
 *
 * <p>실제 SQLite 임시 파일 DB + Flyway 마이그레이션으로 page_count/page_size PRAGMA 가
 * 실제 동작하는 환경에서 응답 계약(deletedTraces / freedBytes / dbSizeBytes)을 검증한다.
 * 운영 DB 파일(/Users/netmd/project/ApiLens/apilens.db*)은 건드리지 않는다 (D-04).
 */
class MaintenanceControllerTest {

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-maintenance-test-", ".db");
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
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        SettingsService settingsService = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        RetentionCleanupService cleanupService =
                new RetentionCleanupService(jdbc, txManager, settingsService);

        // [Phase R15] IngestPauseState 추가 주입. 기본 시간 소스(System) 인스턴스.
        // [Phase R20] R20/AC-10-1 — 4-인자(IngestService 추가 — SQLITE_BUSY 카운터 노출).
        MaintenanceController controller =
                new MaintenanceController(cleanupService, jdbc, new IngestPauseState(), newIngestService());
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** [Phase R20] R20/AC-10-1 — 카운터 원천 실 IngestService (IngestServiceChunkCommitTest 구성 동형). */
    private IngestService newIngestService() {
        ObjectMapper mapper = new ObjectMapper();
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        return new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void cleanupReturns200WithMaintenanceResultContract() throws Exception {
        long now = System.currentTimeMillis();
        insertTraceTree("t-expired", now - 100 * DAY_MS); // 만료 (30일 초과)
        insertTraceTree("t-live", now - DAY_MS);          // 보존

        mockMvc.perform(post("/v1/maintenance/cleanup"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 만료분 1건만 삭제 (cleanup = retention window 적용).
                .andExpect(jsonPath("$.deletedTraces").value(1))
                // 세 필드 모두 응답에 존재 (프론트 계약 1:1).
                .andExpect(jsonPath("$.freedBytes").exists())
                .andExpect(jsonPath("$.dbSizeBytes").exists());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = 't-live'", Integer.class),
                "최근 trace 는 보존");
    }

    @Test
    void purgeReturns200AndDeletesEverything() throws Exception {
        long now = System.currentTimeMillis();
        insertTraceTree("t-a", now - 100 * DAY_MS);
        insertTraceTree("t-b", now - DAY_MS); // 최근분도 전체 비우기 대상

        mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.deletedTraces").value(2))
                .andExpect(jsonPath("$.freedBytes").exists())
                .andExpect(jsonPath("$.dbSizeBytes").exists());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM traces", Integer.class),
                "purge 후 traces 0건");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM spans", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payloads", Integer.class));
    }

    /**
     * [Phase R22] V-11 — 수동 [지난 데이터 정리] 토스트의 "약 X 확보" 숫자가 <b>실제로 커진다</b>.
     *
     * <p>{@code freedBytes} 는 {@code MaintenanceController} 가 작업 전후 {@code page_count} 차로 계산한다.
     * ① 이전에는 {@code incremental_vacuum} 이 호출당 한 페이지만 진행돼 사실상 0 이었다. 예산 루프가
     * 들어온 뒤에는 그 밤에 회수한 만큼이 이 숫자로 나온다 — <b>화면 코드는 한 줄도 안 바꿨는데 화면에
     * 보이는 숫자가 바뀌는</b> 자리라, 릴리스 노트가 미리 알려야 하는 변화다.
     *
     * <p>★ 단위 테스트 DB 는 Flyway 만 돌아 {@code auto_vacuum = NONE} 이다 — 그대로 두면
     * {@code incremental_vacuum} 이 no-op 이라 이 테스트가 <b>통과하면서 아무것도 검증하지 않는다</b>.
     * 그래서 전환을 먼저 걸고, free page 가 실제로 생겼는지 단언으로 확인한 뒤 잰다.
     */
    @Test
    void cleanupReturnsFreedBytesAboveZeroWhenFreePagesWereReclaimed() throws Exception {
        enableIncrementalAutoVacuum();
        createFreePages(120, 40_000);
        Long freeBefore = jdbc.queryForObject("PRAGMA freelist_count", Long.class);
        assertEquals(true, freeBefore != null && freeBefore > 0,
                "전제: 회수할 free page 가 실제로 생겨야 이 단언이 의미를 가진다");

        String body = mockMvc.perform(post("/v1/maintenance/cleanup"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long freedBytes = new ObjectMapper().readTree(body).get("freedBytes").asLong();
        assertEquals(true, freedBytes > 0,
                "예산 루프가 회수한 만큼이 freedBytes 로 나온다 (실측 freedBytes=" + freedBytes + ")");
    }

    /** {@code auto_vacuum} 전환 — PRAGMA 와 VACUUM 은 <b>같은 connection</b> 이어야 반영된다. */
    private void enableIncrementalAutoVacuum() {
        jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                st.execute("VACUUM");
            }
            return null;
        });
    }

    /** payload 를 대량으로 넣었다 지워 free page 를 만든다. */
    private void createFreePages(int rows, int bodyBytes) {
        insertTraceTree("t-bulk", System.currentTimeMillis());
        String body = "x".repeat(bodyBytes);
        java.util.List<Object[]> batch = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            batch.add(new Object[]{"bulk-" + i, body, (long) bodyBytes});
        }
        jdbc.batchUpdate("INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated) "
                + "VALUES (?, 'out', 'application/json', ?, ?, 0)", batch);
        jdbc.update("DELETE FROM payloads WHERE span_id LIKE 'bulk-%'");
    }

    /**
     * [Phase R23] R23/AC-08-5 — [전체 삭제] 도 회수한 만큼이 {@code freedBytes} 로 나온다.
     *
     * <p>★ 왜 <b>신설</b>인가: 위 {@code purgeReturns200AndDeletesEverything} 의
     * {@code .freedBytes").exists()} 는 <b>그대로 둔다.</b> 그 테스트의 DB 는 Flyway 만 돌아
     * {@code auto_vacuum = NONE} 이라 {@code incremental_vacuum} 이 no-op 이고, 같은 파일의
     * {@code optimizeOnEmptyDbReturns200NoOpWithZeroFreed} 는 <b>{@code freedBytes == 0} 을 단언</b>한다 —
     * 세 자리를 일괄로 {@code > 0} 으로 바꾸면 그 단언과 정면으로 부딪힌다.
     * 그래서 <b>전제를 갖춘 전용 테스트를 따로</b> 만든다({@code cleanup} 쪽 본보기와 같은 모양).
     */
    @Test
    void purgeReturnsFreedBytesAboveZeroWhenFreePagesWereReclaimed() throws Exception {
        enableIncrementalAutoVacuum();
        createFreePages(120, 40_000);
        Long freeBefore = jdbc.queryForObject("PRAGMA freelist_count", Long.class);
        assertEquals(true, freeBefore != null && freeBefore > 0,
                "전제: 회수할 free page 가 실제로 생겨야 이 단언이 의미를 가진다");

        String body = mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long freedBytes = new ObjectMapper().readTree(body).get("freedBytes").asLong();
        assertEquals(true, freedBytes > 0,
                "회수한 만큼이 freedBytes 로 나온다 (실측 freedBytes=" + freedBytes + ")");
    }

    @Test
    void purgeOnEmptyDbReturns200WithZeroDeleted() throws Exception {
        mockMvc.perform(post("/v1/maintenance/purge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedTraces").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").exists());
    }

    // ── [Phase K] optimize (online full VACUUM) — V-C01~C04, AC-07 ───────────

    /**
     * [Phase K] AC-07-1 verbatim: ""최적화" 실행 시 전체 VACUUM 으로 파일 크기가 감소하고
     * freedBytes 가 응답된다" — deletedTraces=0(삭제 없음) + busy=false(정상 회수). 정방향.
     *
     * <p>// R14-D06 비협상 — 충분한 디스크의 임시파일 DB 라 디스크 가드 통과 → 실제 VACUUM 수행.
     */
    @Test
    void optimizeReturns200WithFreedBytesAndDeletesNothing() throws Exception {
        long now = System.currentTimeMillis();
        // 행을 넣었다 만료분 cleanup 으로 비워 free page 를 만든 뒤 VACUUM 이 회수하도록 구성.
        for (int i = 0; i < 30; i++) {
            insertTraceTree("t-" + i, now - 100 * DAY_MS);
        }
        // 만료분 전부 삭제 → free page 생성.
        // [Phase R22] R22/AC-01-10 — ★오진단 정정: 이전 주석의 "incremental_vacuum tail-only 한계" 는 **틀렸다**.
        //   incremental_vacuum 은 빈 페이지를 전량 회수할 수 있고, 회수가 안 보였던 이유는
        //   호출당 한 페이지만 진행됐기 때문이다. optimize(전체 VACUUM)는 그와 별개로 **중간 단편까지 재배치**
        //   한다 — 이 테스트가 재는 것은 그 재배치다.
        jdbc.update("DELETE FROM payloads");
        jdbc.update("DELETE FROM spans");
        jdbc.update("DELETE FROM traces");

        mockMvc.perform(post("/v1/maintenance/optimize"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // optimize 는 삭제 없음 → deletedTraces 0.
                .andExpect(jsonPath("$.deletedTraces").value(0))
                .andExpect(jsonPath("$.freedBytes").exists())
                .andExpect(jsonPath("$.dbSizeBytes").exists())
                // 정상 회수 → busy=false (디스크 충분 + 단일 connection, 경합 없음).
                .andExpect(jsonPath("$.busy").value(false));
    }

    /**
     * [Phase K] AC-07-2 verbatim: "빈 DB(또는 회수 불가) 에서는 no-op 으로 freedBytes 0
     * (또는 음수 방어로 0)이 반환된다" — VACUUM tx 밖 실행(GT-5)이 SQLITE_ERROR 없이 성공. 정방향.
     */
    @Test
    void optimizeOnEmptyDbReturns200NoOpWithZeroFreed() throws Exception {
        mockMvc.perform(post("/v1/maintenance/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedTraces").value(0))
                // 빈 DB VACUUM = no-op → measure 음수 방어로 freedBytes 0 (GT-4).
                .andExpect(jsonPath("$.freedBytes").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").exists())
                .andExpect(jsonPath("$.busy").value(false));
    }

    /**
     * [Phase K] AC-07-3/GT-5 — VACUUM 이 트랜잭션 밖에서 실행되어 "cannot VACUUM from within a
     * transaction" 예외가 발생하지 않음을 직접 검증(busy=false 면 VACUUM 이 성공한 것). 정방향.
     */
    @Test
    void optimizeRunsVacuumOutsideTransactionWithoutSqliteError() throws Exception {
        // optimizeDatabase() 직접 호출 — busy=false 면 jdbc.execute("VACUUM") 가 예외 없이 완료.
        RetentionCleanupService cleanupService = newCleanupService();
        boolean busy = cleanupService.optimizeDatabase();
        org.junit.jupiter.api.Assertions.assertFalse(busy,
                "충분한 디스크에서 VACUUM 은 tx 밖 실행으로 SQLITE_ERROR 없이 성공해야 함 (GT-5)");
    }

    // ── [Phase R15] 수신 일시정지 set 모델 — status/pause/resume (D03/D05/D08) ──

    /**
     * standaloneSetup 으로 MaintenanceController + 주입한 IngestPauseState 를 묶어 새 MockMvc 를 만든다.
     * cap 결정적 주입을 위해 시간 소스를 외부에서 제어할 수 있게 한다.
     */
    private MockMvc mockMvcWith(IngestPauseState pauseState) {
        // [Phase R20] R20/AC-10-1 — 4-인자 전환(카운터 원천 실 IngestService).
        MaintenanceController controller =
                new MaintenanceController(newCleanupService(), jdbc, pauseState, newIngestService());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * [Phase R15] AC-A3-2 verbatim: "pause 후 status 가 paused=true, pausedAt != null 을 echo 한다".
     * 정방향: reports paused true after pause.
     */
    @Test
    void reportsPausedTrueAfterPause() throws Exception {
        IngestPauseState pauseState = IngestPauseStateTestFactory.withClock(() -> 1_000L);
        MockMvc mvc = mockMvcWith(pauseState);

        mvc.perform(post("/v1/maintenance/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pausedAt").value(1000));

        mvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pausedAt").value(1000));
    }

    /**
     * [Phase R15] AC-A3-2 — resume 후 status 가 paused=false, pausedAt=null 을 echo 한다.
     * 정방향: reports resumed false after resume.
     */
    @Test
    void reportsResumedFalseAfterResume() throws Exception {
        IngestPauseState pauseState = IngestPauseStateTestFactory.withClock(() -> 1_000L);
        MockMvc mvc = mockMvcWith(pauseState);
        pauseState.pause();

        mvc.perform(post("/v1/maintenance/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist());

        mvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist());
    }

    /**
     * [Phase R15] AC-A1-3 verbatim: "재시작 시 in-memory 상태가 false 로 복귀한다" — 새 bean status = false.
     * 정방향: reports false on fresh bean.
     */
    @Test
    void reportsFalseOnFreshBean() throws Exception {
        MockMvc mvc = mockMvcWith(new IngestPauseState());

        mvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist());
    }

    // ── [Phase R20] R20/AC-10-1/AC-10-2 — SQLITE_BUSY 카운터 노출 (B-19) ──

    /**
     * R20/AC-10-1 verbatim (비협상): "기존 카운터의 <b>표면화만</b>. 기확정 설계 불변: 카운터 이름 유지 ·
     * 인메모리(DB 저장 금지·스키마 무변경) · 재시작 시 0 복귀 정상". 정방향: status 응답에 4필드 —
     * 기존 두 필드(paused·pausedAt) 불변 + 카운터 2필드(fresh 인스턴스라 0 = 재시작 0 복귀와 동형).
     */
    @Test
    void reportsBusyCountersOnStatusWithExistingFieldsIntact() throws Exception {
        mockMvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist())
                .andExpect(jsonPath("$.sqliteBusyEncountered").value(0))
                .andExpect(jsonPath("$.sqliteBusyDropped").value(0));
    }

    /** R20/AC-10-1 — pause/resume echo 에도 카운터 2필드 동반(3 생성처 단일 조립 검증). */
    @Test
    void reportsBusyCountersOnPauseAndResumeEcho() throws Exception {
        IngestPauseState pauseState = IngestPauseStateTestFactory.withClock(() -> 1_000L);
        MockMvc mvc = mockMvcWith(pauseState);

        mvc.perform(post("/v1/maintenance/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.sqliteBusyEncountered").value(0))
                .andExpect(jsonPath("$.sqliteBusyDropped").value(0));

        mvc.perform(post("/v1/maintenance/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.sqliteBusyEncountered").value(0))
                .andExpect(jsonPath("$.sqliteBusyDropped").value(0));
    }

    // ── [Phase R23] R23/AC-06-1/R23/AC-07-1 — 요약 실패 카운터 + 디스크 크기 3필드 ──

    /**
     * R23/AC-06-1 verbatim: "요약 저장 실패 횟수가 상태 응답에 실린다. 기존 4필드는 <b>이름·타입·순서·의미
     * 무변경</b>, 뒤에만 더한다."
     * R23/AC-07-1 verbatim: "DB 크기와 회수 가능 공간이 <b>버튼을 누르지 않아도</b> 상태 응답에 실린다."
     *
     * <p>★ 이 단언이 <b>화면 테스트로는 원리적으로 못 잡는 축</b>을 맡는다 — 화면 테스트는 픽스처로 값을
     * 직접 고정해 넣으므로 "서버가 틀린 숫자를 보내는가" 를 알 수 없다. 그래서 여기서 PRAGMA 를 직접 읽어
     * <b>곱셈 결과와 값 대조</b>를 한다(단위: 페이지 수 × 바이트/페이지 = 바이트).
     */
    @Test
    void reportsDeferredSummaryCountAndDiskSizesOnStatusWithExistingFieldsIntact() throws Exception {
        long pageSize = requirePragma("PRAGMA page_size");
        long pageCount = requirePragma("PRAGMA page_count");
        long freelist = requirePragma("PRAGMA freelist_count");

        mockMvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                // 기존 4필드 — 이름·의미 그대로.
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist())
                .andExpect(jsonPath("$.sqliteBusyEncountered").value(0))
                .andExpect(jsonPath("$.sqliteBusyDropped").value(0))
                // 뒤에 더한 3필드.
                .andExpect(jsonPath("$.traceSummaryDeferred").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").value(pageCount * pageSize))
                .andExpect(jsonPath("$.freePageBytes").value(freelist * pageSize));
    }

    /** R23/AC-07-1 — pause/resume echo 에도 세 필드가 동반한다(3 표면 단일 조립 검증). */
    @Test
    void reportsTheThreeNewFieldsOnPauseAndResumeEcho() throws Exception {
        IngestPauseState pauseState = IngestPauseStateTestFactory.withClock(() -> 1_000L);
        MockMvc mvc = mockMvcWith(pauseState);
        long expectedDbSize = requirePragma("PRAGMA page_count") * requirePragma("PRAGMA page_size");

        mvc.perform(post("/v1/maintenance/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.traceSummaryDeferred").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").value(expectedDbSize))
                .andExpect(jsonPath("$.freePageBytes").exists());

        mvc.perform(post("/v1/maintenance/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.traceSummaryDeferred").value(0))
                .andExpect(jsonPath("$.dbSizeBytes").value(expectedDbSize))
                .andExpect(jsonPath("$.freePageBytes").exists());
    }

    /** PRAGMA 값을 직접 읽는다 — 응답의 숫자를 <b>서버가 계산한 값</b>과 대조하기 위해서다. */
    private long requirePragma(String pragma) {
        Long v = jdbc.queryForObject(pragma, Long.class);
        org.junit.jupiter.api.Assertions.assertNotNull(v, pragma + " 결과 없음 — 전제 붕괴");
        return v;
    }

    /**
     * [Phase R15] AC-A1-4 — cap 경과(결정적 주입) 후 status 가 paused=false echo(status() 가 isPaused() 호출 →
     * 자가 재개). 정방향: reports false after cap elapsed.
     */
    @Test
    void reportsFalseAfterCapElapsed() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        IngestPauseState pauseState = IngestPauseStateTestFactory.withClock(clock::get);
        MockMvc mvc = mockMvcWith(pauseState);
        pauseState.pause(); // pausedAt = 0

        // cap 초과 시각으로 진행 → status 조회 시점 자가 재개.
        clock.set(30L * 60L * 1000L + 1L); // MAX_PAUSE_MS + 1

        mvc.perform(get("/v1/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.pausedAt").doesNotExist());
    }

    private RetentionCleanupService newCleanupService() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        PlatformTransactionManager txManager = new DataSourceTransactionManager(ds);
        SettingsService settingsService = new SettingsService(new JdbcTemplate(ds), new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        return new RetentionCleanupService(new JdbcTemplate(ds), txManager, settingsService);
    }

    // ─── fixture helper (RetentionCleanupServiceTest 와 동형) ─────────────────

    private void insertTraceTree(String traceId, long receivedAt) {
        jdbc.update(
                """
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'GET /x', 'svc', ?, 100, 'OK', 2, 1, 0, ?)
                        """,
                traceId, receivedAt, receivedAt
        );
        jdbc.update(
                """
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, 'svc', 'GET /x', 'SERVER', ?, ?, 'OK', NULL)
                        """,
                traceId + "-span-1", traceId, receivedAt, receivedAt + 100
        );
        jdbc.update(
                """
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated)
                        VALUES (?, 'in', 'application/json', '{}', 2, 0)
                        """,
                traceId + "-span-1"
        );
    }
}
