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
package io.apilens.server.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R23] FR-04 — 요약 저장의 <b>쓰기 승격 실패</b> 회귀 가드 (Design §6.1 G-R23-01 ~ G-R23-04).
 *
 * <p>비협상 anchor (Plan AC verbatim 인용):
 * <ul>
 *   <li>R23/AC-04-1: "<b>재현 축</b> — 승격 실패가 이 저장소에서 재현되고, <b>대기 상한을 기다리지 않고
 *       즉시</b> 실패한다는 것이 값으로 단언된다."</li>
 *   <li>R23/AC-04-2: "<b>트리거 축</b> — 외부 커밋이 요약의 <b>읽기→쓰기 창 안에서</b> 일어나게 하는
 *       실행 가능한 훅이 있다. <b>밖에서 경합을 거는 형태는 안 된다</b>(실패가 재현되지 않아 되감아도
 *       초록불)."</li>
 *   <li>R23/AC-04-4: "<b>수정 축</b> — 고친 코드에서 흐름 요약 행 수와 실패 카운터가 <b>반대 방향으로
 *       함께 움직인다.</b> "예외가 안 난다" 단독 단언 금지."</li>
 *   <li>R23/AC-04-6: "테스트의 DB 주소가 <b>운영과 같은 저널 방식</b>을 쓴다. 손으로 옵션을 적지 말고
 *       <b>설정 파일에서 주소를 읽어 경로만 바꿔치기하는 기존 선례</b>를 따른다."</li>
 * </ul>
 * CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용 — 스키마 변경 0 · 마이그레이션 0.
 *
 * <p><b>★ 왜 DB 주소를 손으로 안 적는가</b> (R23/AC-04-6): 이 파일을 뺀 {@code apilens-server/src/test} 의
 * SQLite 주소는 <b>전수가 맨 URL</b>(PRAGMA 0)이다. 롤백 저널 모드에서는 읽기 잠금이 다른 커넥션의 쓰기를
 * <b>막아 버려</b> 승격 실패가 <b>일어나지도 않는다</b> — 운영과 다른 실패를 재현하게 된다. 그래서 아래
 * {@code readDatasourceUrlFromYml()} 이 {@code application.yml} 의 주소를 읽고 <b>경로만</b> 임시 파일로
 *바꿔치기한다. 그러면 PRAGMA 문자열이 운영과 영원히 같이 움직인다.
 * <b>정본은 {@code io.apilens.server.db.DbPragmaTest} 의 같은 이름 헬퍼</b>이고(그쪽은 {@code private static}
 * 이라 다른 패키지에서 못 부른다), 이 파일은 <b>동형 복제</b>다. 두 곳이 갈라지지 않게 회귀 가드가 본다.
 */
class IngestServiceSummaryPromotionTest {

    /** {@code DbPragmaTest} 의 같은 이름 상수와 동형 — 정본은 그쪽이다. */
    private static final Pattern YML_URL = Pattern.compile("url:\\s*(jdbc:sqlite:\\S+)");

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private String testUrl;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-summary-promotion-", ".db");
        Files.deleteIfExists(dbFile);

        String ymlUrl = readDatasourceUrlFromYml();
        testUrl = ymlUrl.replace("jdbc:sqlite:apilens.db", "jdbc:sqlite:" + dbFile.toAbsolutePath());
        assertNotEquals(ymlUrl, testUrl,
                "yml URL 에서 운영 파일명 치환 실패 — URL 형식 변경 여부 확인: " + ymlUrl);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(testUrl);
        this.dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        this.service = new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── G-R23-01 · 재현 축 (이 축이 깨지면 나머지 전제가 무너진다) ────────────

    /**
     * R23/AC-04-1 verbatim: "<b>재현 축</b> — 승격 실패가 이 저장소에서 재현되고, <b>대기 상한을
     * 기다리지 않고 즉시</b> 실패한다는 것이 값으로 단언된다."
     *
     * <p>먼저 읽고 나중에 쓰는 트랜잭션(A)이 첫 읽기에서 스냅샷을 잡은 뒤, 다른 커넥션(B)이 커밋하면,
     * A 의 쓰기 승격은 <b>설정 파일의 대기 상한({@code busy_timeout}, 현재 10초)을 안 기다리고</b> 즉시 실패한다.
     * 기다렸다 실패하는 평범한 잠금 경합과 <b>다른 실패</b>라는 것이 이 단언의 요점이다 — 그래서
     * 상한을 늘리는 처방으로는 안 고쳐지고, 트랜잭션을 걷어내는 처방이 필요했다.
     *
     * <p>이 축이 깨지면 드라이버 거동이 바뀐 것이라 아래 세 테스트의 전제가 무너진다 — 먼저 실패해서 알려 준다.
     */
    @Test
    void failsWriteUpgradeImmediatelyWhenAnotherConnectionCommitsAfterTheReadSnapshot() throws Exception {
        seedTraceRow("t-existing");

        try (Connection a = DriverManager.getConnection(testUrl)) {
            a.setAutoCommit(false);
            // ① A 의 첫 읽기 — 여기서 WAL 스냅샷이 잡힌다.
            try (Statement st = a.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM spans")) {
                assertTrue(rs.next(), "전제: 읽기가 실제로 수행돼 스냅샷이 잡혀야 한다");
            }
            // ② 그 사이 B 가 커밋 — WAL 이 한 칸 진전돼 A 의 스냅샷이 낡는다.
            commitFromAnotherConnection();

            // ③ A 가 쓰기로 승격 시도 → 즉시 실패.
            long startedAt = System.nanoTime();
            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (Statement st = a.createStatement()) {
                    st.executeUpdate("UPDATE traces SET span_count = span_count + 1 WHERE trace_id = 't-existing'");
                }
            });
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(elapsedMs < 1_000L,
                    "대기 상한(10초)을 기다리지 않고 즉시 실패해야 한다 (실측 " + elapsedMs + "ms)");
            String message = String.valueOf(thrown.getMessage());
            assertTrue(message.contains("SQLITE_BUSY_SNAPSHOT"),
                    "스냅샷 충돌 표식이 메시지에 들어 있어야 한다 (실측: " + message + ")");
            a.rollback();
        }
    }

    // ─── G-R23-02 · 수정 축 (라운드 최위험 단일 회귀경로) ──────────────────────

    /**
     * R23/AC-04-4 verbatim: "<b>수정 축</b> — 고친 코드에서 흐름 요약 행 수와 실패 카운터가 <b>반대 방향으로
     * 함께 움직인다.</b> "예외가 안 난다" 단독 단언 금지."
     *
     * <p>★ 이 테스트가 거는 훅은 <b>요약의 「첫 읽기 ~ 쓰기」 창 안에서</b> 외부 커밋을 일으킨다
     * (R23/AC-04-2). {@code ingest()} 를 부르기 <b>전에</b> 경합을 걸면 그 커밋은 창보다 앞이라 요약이
     * 최신 상태를 보고 그냥 성공한다 — 실패가 재현되지 않아 <b>되감아도 초록불</b>이 된다.
     *
     * <p>★ RED 선확인(R23/AC-04-3): 요약을 되감은(트랜잭션으로 감싼) 코드에서 이 테스트가
     * {@code traces} <b>0</b> · {@code traceSummaryDeferredCount()} <b>1</b> 로 <b>실제로 RED</b> 를 내는 것을
     * 확인한 뒤 고친 코드로 넘어왔다. 두 값이 <b>반대로 함께</b> 움직이므로 우연한 통과가 안 된다.
     */
    @Test
    void storesTheTraceSummaryEvenWhenAnotherConnectionCommitsInsideTheSummaryWindow() throws Exception {
        injectJdbc(new SummaryWindowContentionJdbc(dataSource, testUrl));

        assertDoesNotThrow(() -> service.ingest(traceOf("t-window", 3)),
                "host-throw-0 — 창 안 외부 커밋이 적재를 500 으로 만들지 않는다");

        assertEquals(1, traceRowCount("t-window"),
                "요약 행이 남는다 (읽기가 먼저인 트랜잭션이 없어져 쓰기 승격 자체가 없다)");
        assertEquals(0L, service.traceSummaryDeferredCount(),
                "요약 실패 카운터는 안 오른다 — 위 행 수와 반대 방향으로 함께 움직인다");
        assertEquals(3, spanRowCount("t-window"), "span 은 그대로 커밋돼 있다");
    }

    // ─── G-R23-03 · 계약 축 (요약이 실패했을 때 남는 상태) ────────────────────

    /**
     * R23/AC-02-4 verbatim: "청크는 이미 저장된 상태가 유지된다 — <b>요약 실패는 span 유실이 아니다</b>."
     *
     * <p>요약 쓰기만 결정적으로 실패시켜 <b>실패했을 때의 계약</b>을 값으로 고정한다.
     * ⓐ {@code ingest} 정상 반환 ⓑ {@code spans} 행 = 넣은 수 ⓒ 유실 카운터 0(요약 실패는 유실이 아니다)
     * ⓓ 요약 실패 카운터 1 ⓔ {@code traces} 행 0.
     * ⓑ·ⓔ 가 없으면 "예외가 안 난다" 만 남는데 그것은 <b>아무 일도 안 하고 돌아와도 통과</b>한다.
     */
    @Test
    void keepsCommittedSpansAndCountsTheDeferredSummaryWhenTheSummaryWriteFails() throws Exception {
        injectJdbc(new FailingSummaryWriteJdbc(dataSource));

        assertDoesNotThrow(() -> service.ingest(traceOf("t-failed-summary", 4)),
                "ⓐ 요약이 실패해도 적재는 정상 반환한다 (host-throw-0)");

        assertEquals(4, spanRowCount("t-failed-summary"), "ⓑ 커밋된 span 은 그대로 남는다");
        assertEquals(0L, service.sqliteBusyDroppedCount(),
                "ⓒ 요약 실패는 span 유실이 아니다 — 유실 카운터는 안 오른다");
        assertEquals(1L, service.traceSummaryDeferredCount(), "ⓓ 요약 실패 카운터가 오른다");
        assertEquals(0, traceRowCount("t-failed-summary"), "ⓔ 요약 행은 안 만들어진다");
    }

    // ─── G-R23-04 · null 가드 축 ───────────────────────────────────────────────

    /**
     * R23/AC-02-2 verbatim: "집계 결과가 비었을 때 <b>요약 저장 자체를 건너뛴다.</b> 0으로 채우지 않는다."
     * R23/AC-02-3 verbatim: "그 상황에서 <b>바깥으로 예외가 나가지 않고</b>, 흐름 요약 행도 만들어지지 않는다."
     *
     * <p>트랜잭션을 걷어낸 뒤 두 SELECT 는 서로 다른 스냅샷을 볼 수 있다 — <b>집계는 비었는데 대표 span
     * 조회는 행을 보는</b> 순서가 새로 열린다. 그 순서를 타이밍이 아니라 <b>결정적으로</b> 만들어 가드를 잰다.
     * 가드가 없으면 여기서 NPE 가 나고, NPE 는 {@code catch (DataAccessException)} 을 빠져나가 적재가 500 이 된다.
     *
     * <p>★ 0 으로 채우지 않는 이유: {@code min_start} 를 0 으로 채우면 <b>시작 시각이 1970년인 가짜 흐름</b>이
     * 생긴다. 건너뛰기는 가드 누락이 아니라 <b>정상 경로</b>다.
     */
    @Test
    void skipsTheSummaryAndCountsItWhenTheAggregateComesBackEmpty() throws Exception {
        injectJdbc(new EmptyAggregateJdbc(dataSource));

        assertDoesNotThrow(() -> service.ingest(traceOf("t-empty-aggregate", 2)),
                "집계가 비어도 바깥으로 예외가 나가지 않는다 (host-throw-0)");

        assertEquals(0, traceRowCount("t-empty-aggregate"),
                "0 으로 채운 가짜 요약 행을 만들지 않는다");
        assertEquals(1L, service.traceSummaryDeferredCount(),
                "건너뛴 것도 요약 실패 카운터로 센다 — 결과가 「청크는 있는데 요약이 없다」로 같기 때문");
        assertEquals(2, spanRowCount("t-empty-aggregate"), "span 은 그대로 커밋돼 있다");
    }

    // ─── B-01 · 로그 위생 경계값 (§5.2 위임 본문 선흡수) ──────────────────────

    /**
     * [Phase R23] §5.2 — 근본 예외 메시지는 <b>외부가 정한 문자열</b>이다. 로그 한 줄에 실을 때
     * ① 개행을 공백으로 바꾸고(가짜 로그 줄 삽입 차단) ② 512자를 넘으면 잘라 낸다.
     *
     * <p>왜 필요한가: 이 WARN 의 앞머리가 유실률 기준선을 대조하는 앵커라, <b>한 줄 단위</b>가 깨지거나
     * 통제 못 하는 문자열이 회전 로그를 지배하면 과거와의 대조가 틀어진다.
     *
     * <p>경계값 <b>511 / 512 / 513</b> — 512 까지는 그대로, 512 를 넘는 것만 잘린다.
     */
    @Test
    void keepsTheRootMessageOnOneLineAndCutsItOnlyPastTheLengthBoundary() throws Exception {
        for (int length : new int[]{511, 512, 513}) {
            String withNewline = "a".repeat(length - 2) + "\n" + "z";
            String flattened = "a".repeat(length - 2) + " " + "z";
            assertEquals(length, withNewline.length(), "전제: 주입 메시지 길이가 경계값과 같아야 한다");

            IngestService fresh = newService();
            injectJdbc(fresh, new FailingSummaryWriteJdbc(dataSource, withNewline));
            String logged = captureSummaryDeferredWarn(
                    () -> fresh.ingest(traceOf("t-msg-" + length, 1)));

            String rootMessage = fieldOf(logged, "rootMessage=", " errorCode=");
            assertFalse(rootMessage.contains("\n") || rootMessage.contains("\r"),
                    "개행은 공백으로 바뀐다 — 한 줄 단위가 깨지면 기준선 대조가 틀린다");
            if (length <= 512) {
                assertEquals(flattened, rootMessage,
                        length + "자는 상한 이하라 그대로 실린다");
            } else {
                assertEquals(flattened.substring(0, 512) + "[truncated]", rootMessage,
                        length + "자는 상한을 넘으므로 512자까지만 싣고 잘렸음을 표시한다");
            }
        }
    }

    // ─── 대역 (double) ────────────────────────────────────────────────────────

    /**
     * ★ 요약의 <b>첫 읽기 직후</b>에 다른 커넥션이 커밋하게 만드는 대역.
     *
     * <p>★★ <b>순서가 load-bearing 이다</b>: 먼저 {@code super} 에 <b>위임</b>하고, 그 반환 <b>뒤에</b>
     * 외부 커밋을 한다. WAL 은 <b>첫 읽기에서 스냅샷을 잡으므로</b>, 위임 <b>전에</b> 커밋하면 그 커밋이
     * 스냅샷보다 앞서서 트랜잭션이 최신 상태를 보고 <b>승격이 그냥 성공</b>한다 — 실패가 재현되지 않는다.
     *
     * <p>★★ <b>production 과 같은 {@code DataSource} 인스턴스</b>로 만드는 것도 load-bearing 이다.
     * Spring 은 {@code DataSource} 인스턴스를 <b>키로</b> 커넥션을 바인딩하므로, 다른 것으로 만들면
     * 되감은 코드에서도 요약이 트랜잭션 <b>밖</b>에서 돌아 RED 가 안 난다(= 가드가 죽는다).
     *
     * <p>나머지 메서드는 전부 그대로 위임한다 — 청크 INSERT·payload 삭제·서비스 등록이 실제로 돌아야
     * {@code spans} 행 수 단언이 뜻을 가진다.
     *
     * <p>⚠️ 스레드로 "커밋을 계속 때리는" 방식으로 바꾸지 말 것 — 확률적이라 CI 간헐 실패가 된다.
     * 창을 <b>맞추는</b> 것이 아니라 창 <b>안에서 부르는</b> 것이 요점이다.
     */
    private static final class SummaryWindowContentionJdbc extends JdbcTemplate {
        private final String externalUrl;
        private int queryForMapCalls = 0;

        SummaryWindowContentionJdbc(DataSource dataSource, String externalUrl) {
            super(dataSource);
            this.externalUrl = externalUrl;
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) throws DataAccessException {
            Map<String, Object> result = super.queryForMap(sql, args);  // ① 먼저 위임 — 스냅샷 확보
            if (++queryForMapCalls == 1) {
                commitOn(externalUrl);                                  // ② 그 뒤 외부 커밋 — 스냅샷이 낡는다
            }
            return result;
        }
    }

    /** 요약의 <b>쓰기 한 문장</b>만 결정적으로 실패시키는 대역 (청크 INSERT 는 그대로 위임). */
    private static final class FailingSummaryWriteJdbc extends JdbcTemplate {
        private final String rootMessage;

        FailingSummaryWriteJdbc(DataSource dataSource) {
            this(dataSource, "SQLITE_BUSY: database is locked");
        }

        FailingSummaryWriteJdbc(DataSource dataSource, String rootMessage) {
            super(dataSource);
            this.rootMessage = rootMessage;
        }

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            if (sql.contains("INSERT OR REPLACE INTO traces")) {
                // 실측 errorCode==5 와 같은 모양의 SQLITE_BUSY 를 감싼 DataAccessException.
                //   근본 예외의 **메시지**가 원인을 가르는 자리라, 그 문자열을 주입할 수 있게 열어 둔다.
                throw new org.springframework.dao.TransientDataAccessResourceException(
                        "summary write refused",
                        new SQLException(rootMessage, "", 5));
            }
            return super.update(sql, args);
        }
    }

    /**
     * 집계 SELECT 만 <b>빈 결과</b>로 돌려주는 대역 — 대표 span 조회(두 번째 호출)는 그대로 위임한다.
     * 타이밍에 의존하지 않아 결정적이다.
     */
    private static final class EmptyAggregateJdbc extends JdbcTemplate {
        private int queryForMapCalls = 0;

        EmptyAggregateJdbc(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) throws DataAccessException {
            if (++queryForMapCalls == 1) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("min_start", null);   // ★ MIN(start_time) 이 NULL 인 상태
                empty.put("max_end", null);     // ★ MAX(end_time) 이 NULL 인 상태
                empty.put("span_count", 0);     // COUNT(*) 는 SQLite 에서 NULL 이 되지 않는다
                empty.put("service_count", 0);
                empty.put("error_count", null);
                return empty;
            }
            return super.queryForMap(sql, args);
        }
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * {@code IngestService} 의 {@code jdbc} 필드를 대역으로 바꾼다.
     * {@code IngestServiceChunkCommitTest} 가 {@code chunkTx} 에 <b>이미 쓰고 있는 그 방식</b>이다
     * ({@code private final} 필드에 동작하는 선례가 실재하고 그 테스트가 통과하는 것이 증거다).
     * production 코드 무변경 — 테스트 전용 주입.
     */
    private void injectJdbc(JdbcTemplate replacement) throws Exception {
        injectJdbc(service, replacement);
    }

    private static void injectJdbc(IngestService target, JdbcTemplate replacement) throws Exception {
        Field f = IngestService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        f.set(target, replacement);
    }

    /** 카운터가 0 에서 시작하는 새 인스턴스 — 경계값 3회를 한 테스트에서 돌리기 위해서다. */
    private IngestService newService() {
        MaskingEngineHolder maskingHolder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        maskingHolder.reload();
        return new IngestService(jdbc, maskingHolder, mapper, new IngestProperties(1_048_576L));
    }

    /** 요약 실패 WARN 한 줄을 실제 로거에서 잡아 온다 — 형식 자체를 값으로 재는 유일한 길이다. */
    private static String captureSummaryDeferredWarn(Runnable action) {
        ch.qos.logback.classic.Logger ingestLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ingestLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            ingestLogger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("trace summary deferred"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("요약 실패 WARN 이 안 남았다 — 앞머리 토큰 확인"));
    }

    /** 로그 한 줄에서 `key=` 와 다음 구분자 사이 값을 뽑는다. */
    private static String fieldOf(String logLine, String startToken, String endToken) {
        int from = logLine.indexOf(startToken);
        assertTrue(from >= 0, startToken + " 필드가 로그에 없다: " + logLine);
        int valueStart = from + startToken.length();
        int valueEnd = logLine.indexOf(endToken, valueStart);
        assertTrue(valueEnd >= 0, endToken + " 구분자가 로그에 없다: " + logLine);
        return logLine.substring(valueStart, valueEnd);
    }

    /**
     * ★ 두 번째 <b>진짜 커넥션</b>에서 커밋하고 즉시 닫는다. 목적은 WAL 을 한 칸 진전시켜 스냅샷을
     * 낡게 만드는 것뿐이라, 넣는 행은 <b>테스트 대상 흐름과 무관한 행 1개</b>다 (대상 흐름의
     * {@code spans}/{@code traces} 를 건드리면 두 SELECT 가 서로 다른 것을 보게 되어 다른 실패를 재현한다).
     */
    private static void commitOn(String url) {
        try (Connection external = DriverManager.getConnection(url);
             Statement st = external.createStatement()) {
            st.executeUpdate("INSERT OR REPLACE INTO settings (key, value, updated_at) "
                    + "VALUES ('r23.test.walAdvanceProbe', 'x', 1)");
        } catch (SQLException e) {
            throw new IllegalStateException("외부 커넥션 커밋 실패 — 트리거가 창을 못 맞춘다", e);
        }
    }

    private void commitFromAnotherConnection() {
        commitOn(testUrl);
    }

    private void seedTraceRow(String traceId) {
        jdbc.update("INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms, "
                        + "status, span_count, service_count, has_error, received_at) "
                        + "VALUES (?, 'op', 'svc', 1000, 1, 'OK', 1, 1, 0, 1000)",
                traceId);
    }

    private IngestRequest traceOf(String traceId, int n) {
        List<Span> spans = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String parent = (i == 0) ? null : traceId + "-s0";
            spans.add(new Span(
                    traceId + "-s" + i, traceId, parent,
                    "svc", "op-" + i,
                    (i == 0) ? SpanKind.SERVER : SpanKind.INTERNAL,
                    1000L + i, 1000L + i + 1, SpanStatus.OK,
                    null, List.<Payload>of()));
        }
        return new IngestRequest(spans);
    }

    private int spanRowCount(String traceId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM spans WHERE trace_id = ?", Integer.class, traceId);
        return c == null ? 0 : c;
    }

    private int traceRowCount(String traceId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM traces WHERE trace_id = ?", Integer.class, traceId);
        return c == null ? 0 : c;
    }

    /**
     * 테스트 classpath 의 main 리소스 {@code application.yml} 에서 datasource URL 원문을 뽑는다.
     * <b>정본은 {@code io.apilens.server.db.DbPragmaTest} 의 같은 이름 헬퍼</b>이고 이것은 동형 복제다
     * (그쪽이 {@code private static} 이라 다른 패키지에서 부를 수 없다 — 공용 헬퍼로 뽑는 것은 그 파일이
     * PRAGMA 봉인 테스트라 이번 범위 밖이다). 손으로 PRAGMA 를 적지 않는 것이 요점이다.
     */
    private static String readDatasourceUrlFromYml() throws Exception {
        try (InputStream in = IngestServiceSummaryPromotionTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath:/application.yml 미존재 — 리소스 구성 확인");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = YML_URL.matcher(yml);
            assertTrue(m.find(), "application.yml 에서 jdbc:sqlite URL 을 찾지 못함");
            return m.group(1);
        }
    }
}
