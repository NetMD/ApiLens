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
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R24] R24/FR-05 — 수신이 끊겼다 이어진 구간을 수집기 로그 한 줄로 남기는 동작의 회귀 가드.
 *
 * <p>비협상 anchor (Plan AC verbatim 인용):
 * <ul>
 *   <li>R24/AC-02-1: "로그 한 줄에 <b>네 요소</b>가 있다 — 앞머리 토큰 {@code ingest resumed:} ·
 *       직전 수신 시각 · 그 사이 공백 · 서비스 이름."</li>
 *   <li>R24/AC-02-4: "재개 판정이 <b>어떤 이유로 실패해도 수신은 정상</b>이다(호스트로 예외가 새지 않는다)."</li>
 *   <li>R24/AC-02-6: "직전 값을 읽는 자리가 {@code last_seen_at} 을 덮는 UPSERT <b>보다 앞</b>이다."</li>
 *   <li>R24/AC-02-7: "안 찍히는 것이 정상인 두 축 — ㉠ 직전 값이 없다 ㉡ 공백이 임계 미만이다."</li>
 * </ul>
 * 사용자 명시 비협상 결정(호스트 throw 0). CLAUDE.md '아키텍처 핵심 원칙'
 * (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
 *
 * <p>★ <b>{@code Thread.sleep} 0</b> — 시간 의존 단언은 전부 심어 둔 {@code last_seen_at} 값으로 만든다.
 * 경계 판정은 순수 함수 {@link IngestService#isResumeGap(long, long)} 을 직접 불러 잰다
 * (생성자에 시간 소스를 주입하지 않는다 — 4-인자 봉인 유지).
 */
class IngestServiceResumeGapTest {

    /** 임계와 같은 값. 제품 상수가 바뀌면 이 파일의 기대도 함께 손봐야 한다는 뜻이다. */
    private static final long THRESHOLD_MS = 600_000L;
    private static final long DAY_MS = 86_400_000L;
    private static final String RESUME_PREFIX = "ingest resumed:";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-resume-gap-test-", ".db");
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

    // ─── R-T1 — 임계 넘는 공백 뒤 수신 (R24/AC-02-1 · R24/AC-02-2) ──────────

    @Test
    void logsResumedLineWithFourElementsWhenIngestResumesAfterALongGap() {
        long lastSeen = System.currentTimeMillis() - 4 * DAY_MS;
        seedService("svc-resumed", lastSeen);

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "svc-resumed")))));

        assertEquals(1, lines.size(), "임계를 넘는 공백 뒤 첫 수신에서 정확히 한 줄");
        String line = lines.get(0);
        // 네 요소 — 앞머리 토큰 · 직전 수신 시각 · 공백 · 서비스 이름.
        assertTrue(line.startsWith(RESUME_PREFIX), "앞머리 토큰 고정: " + line);
        assertTrue(line.contains("lastSeenBefore="), "직전 수신 시각 필드: " + line);
        assertTrue(line.contains("gapMs="), "공백 필드: " + line);
        assertTrue(line.contains("service="), "서비스 이름 필드: " + line);

        // 공백 값이 심어 둔 값과 맞는다. receivedAt 은 ingest 안에서 정해지므로 아래를 하한으로 잰다
        // (Thread.sleep 없이 결정적으로 재는 방법 — 위쪽은 테스트 실행 시간만큼의 여유를 준다).
        long gapMs = Long.parseLong(fieldOf(line, "gapMs=", " "));
        assertTrue(gapMs >= 4 * DAY_MS && gapMs < 4 * DAY_MS + 60_000L,
                "심어 둔 공백과 맞는다 (실측 gapMs=" + gapMs + ")");
        // 자릿수 구분 기호를 넣지 않는다 — 기본 로캘에 따라 문자열이 달라지면 값 대조가 끊긴다.
        assertFalse(fieldOf(line, "gapMs=", " ").contains(","), "gapMs 는 구분 기호 없는 정수: " + line);
        assertEquals("svc-resumed", fieldOf(line, "service=", null), "서비스 이름 값: " + line);
    }

    // ─── R-T8 — 시각 표기와 괄호 표기 (B-05 · B-06) ────────────────────────

    /**
     * ISO 표준 표기({@code LocalDateTime.toString()})는 초가 0 이면 {@code :00} 을 <b>생략</b>해
     * 자릿수가 들쭉날쭉해진다. 형식을 고정한 이유가 그것이고, 이 테스트가 그 이유를 지킨다.
     * 괄호 값은 {@code Locale.ROOT} 고정이라 로캘이 바뀌어도 소수점이 쉼표가 되지 않는다.
     */
    @Test
    void printsSecondsAndADotSeparatedDayFigureInTheResumedLine() {
        // 초가 정확히 00 인 시각을 심는다 — 표준 표기를 그냥 썼다면 ":00" 이 사라진다.
        long lastSeen = ZonedDateTime.now(ZoneId.systemDefault())
                .minusDays(4)
                .truncatedTo(ChronoUnit.MINUTES)
                .toInstant()
                .toEpochMilli();
        seedService("svc-fmt", lastSeen);

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "svc-fmt")))));

        assertEquals(1, lines.size(), "임계를 넘는 공백 뒤 한 줄");
        String stamp = fieldOf(lines.get(0), "lastSeenBefore=", " ");
        assertTrue(stamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:00"),
                "초 자리가 살아 있다 (실측 lastSeenBefore=" + stamp + ")");

        String days = fieldOf(lines.get(0), "(", "d)");
        assertTrue(days.matches("\\d+\\.\\d{2}"),
                "소수점은 점이고 두 자리다 — 로캘이 바뀌어도 쉼표가 되지 않는다 (실측 " + days + ")");
    }

    // ─── R-T2 — services 행 자체가 없다 (R24/AC-02-7 ㉠ · B-08) ────────────

    @Test
    void keepsTheLogSilentWhenTheServiceHasNoPreviousRow() {
        assertEquals(0, count("SELECT COUNT(*) FROM services"), "전제: 첫 등록 상태(행 0)");

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "brand-new")))));

        assertEquals(List.of(), lines, "직전 값이 없으면 계산이 성립하지 않는다 — 안 찍는 것이 정상");
        assertEquals(1, count("SELECT COUNT(*) FROM services"), "그래도 자동 등록은 그대로 일어난다");
    }

    // ─── R-T3 — last_seen_at 이 NULL (R24/AC-02-7 ㉠ · B-07) ──────────────

    @Test
    void keepsTheLogSilentWhenTheLastSeenAtIsNull() {
        // 스키마가 적어 둔 "wizard 등록 후 trace 미수신" 상태.
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES ('wizard-only', 1000, NULL, 'wizard')");

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "wizard-only")))));

        assertEquals(List.of(), lines, "직전 값이 NULL 이면 안 찍는 것이 정상");
    }

    // ─── R-T4 — 공백이 임계 미만 (R24/AC-02-7 ㉡) ─────────────────────────

    @Test
    void keepsTheLogSilentWhenTheGapIsBelowTheThreshold() {
        // 임계보다 1분 짧은 공백 — 대상 앱의 짧은 흔들림은 잡음이 되면 안 된다.
        seedService("quiet-svc", System.currentTimeMillis() - (THRESHOLD_MS - 60_000L));

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "quiet-svc")))));

        assertEquals(List.of(), lines, "임계 미만 공백은 안 찍는 것이 정상");
    }

    // ─── R-T5 — 경계 3종 + 음수 (B-01 ~ B-04, Thread.sleep 0) ─────────────

    @Test
    void treatsTheThresholdAsInclusiveAndIgnoresNegativeGaps() {
        long now = 1_800_000_000_000L; // 임의 고정 시각 — 순수 함수라 실제 시계와 무관하다.

        assertFalse(IngestService.isResumeGap(now - (THRESHOLD_MS - 1), now), "임계 −1 → 안 찍는다");
        assertTrue(IngestService.isResumeGap(now - THRESHOLD_MS, now), "임계 정확 → 찍는다 (경계는 이상)");
        assertTrue(IngestService.isResumeGap(now - (THRESHOLD_MS + 1), now), "임계 +1 → 찍는다");
        assertFalse(IngestService.isResumeGap(now + 1, now),
                "시계 역행·미래 값(음수 공백) → 안 찍는 쪽이 안전한 방향");
    }

    // ─── R-T6 — 조회가 터져도 수신은 정상 (R24/AC-02-4) ────────────────────

    @Test
    void keepsIngestWorkingWhenTheResumeGapQueryFails() {
        jdbc.execute("DROP TABLE services"); // 조회가 확실히 터지는 상태를 만든다

        ch.qos.logback.classic.Logger ingestLogger = ingestLogger();
        ListAppender<ILoggingEvent> appender = attach(ingestLogger);
        IngestResponse response;
        try {
            response = assertDoesNotThrow(
                    () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "svc-x")))),
                    "재개 판정 실패가 호스트로 새면 안 된다");
        } finally {
            detach(ingestLogger, appender);
        }

        assertEquals(1, response.accepted(), "응답 개수 정상");
        assertEquals(1, response.traces(), "응답 흐름 수 정상");
        assertEquals(1, count("SELECT COUNT(*) FROM spans WHERE trace_id = 't-1'"),
                "span 이 실제로 DB 에 들어갔다");
        assertTrue(messages(appender, Level.WARN).stream()
                        .anyMatch(m -> m.startsWith("ingest resume gap check skipped")),
                "건너뛴 사실이 WARN 한 줄로 남는다");
    }

    // ─── R-T7 — 자기 제한 (B-10) ──────────────────────────────────────────

    /**
     * 두 번째 요청에서 안 찍히는 이유는 첫 요청의 UPSERT 가 {@code last_seen_at} 을 이미 갱신했기
     * 때문이다 — 즉 <b>읽는 자리가 덮는 자리보다 앞</b>이라는 사실(R24/AC-02-6)이 동작으로도 확인된다.
     */
    @Test
    void logsOnlyOnceWhenTwoRequestsArriveBackToBack() {
        seedService("burst-svc", System.currentTimeMillis() - 4 * DAY_MS);

        List<String> lines = captureResumeLines(() -> {
            service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", "burst-svc"))));
            service.ingest(new IngestRequest(List.of(makeSpan("s-2", "t-2", "burst-svc"))));
        });

        assertEquals(1, lines.size(), "첫 요청만 찍고 둘째는 안 찍는다 (실측 " + lines + ")");
    }

    // ─── fixture / 관측 helper ────────────────────────────────────────────

    private void seedService(String name, long lastSeenAt) {
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES (?, ?, ?, 'auto')", name, lastSeenAt, lastSeenAt);
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    private static Span makeSpan(String spanId, String traceId, String serviceName) {
        return new Span(
                spanId, traceId, null,
                serviceName, "GET /probe", SpanKind.SERVER,
                100L, 200L, SpanStatus.OK,
                null, List.<Payload>of()
        );
    }

    /**
     * 재개 로그 줄만 실제 로거에서 잡아 온다 — 형식 자체를 값으로 재는 유일한 길이다
     * ({@code IngestServiceSummaryPromotionTest.captureSummaryDeferredWarn} 과 같은 관용구).
     * 레벨을 이 자리에서 INFO 로 고정했다가 되돌린다 — 바깥 설정이 바뀌어도 결과가 흔들리지 않게.
     */
    private static List<String> captureResumeLines(Runnable action) {
        ch.qos.logback.classic.Logger ingestLogger = ingestLogger();
        Level previous = ingestLogger.getLevel();
        ingestLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = attach(ingestLogger);
        try {
            action.run();
        } finally {
            detach(ingestLogger, appender);
            ingestLogger.setLevel(previous); // null 이면 상위 레벨 상속으로 되돌아간다
        }
        return messages(appender, Level.INFO).stream()
                .filter(m -> m.startsWith(RESUME_PREFIX))
                .toList();
    }

    private static ch.qos.logback.classic.Logger ingestLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestService.class);
    }

    private static ListAppender<ILoggingEvent> attach(ch.qos.logback.classic.Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.classic.Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 로그 한 줄에서 {@code key=} 와 다음 구분자 사이 값을 뽑는다. {@code endToken} 이 null 이면 줄 끝까지. */
    private static String fieldOf(String logLine, String startToken, String endToken) {
        int from = logLine.indexOf(startToken);
        assertTrue(from >= 0, startToken + " 필드가 로그에 없다: " + logLine);
        int valueStart = from + startToken.length();
        if (endToken == null) {
            return logLine.substring(valueStart);
        }
        int valueEnd = logLine.indexOf(endToken, valueStart);
        assertTrue(valueEnd >= 0, endToken + " 구분자가 로그에 없다: " + logLine);
        return logLine.substring(valueStart, valueEnd);
    }
}
