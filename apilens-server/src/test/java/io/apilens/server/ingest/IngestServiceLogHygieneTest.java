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
import io.apilens.common.MaskingEngine;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.RegexTimeoutException;
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
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R25] AC-25-04-1/AC-25-04-2/AC-25-04-3 — <b>로그 위생</b>. 인증 없는 입구가 정한 값이
 * 로그 한 줄에 실릴 때 개행이 들어가면 <b>뒤 줄을 통째로 위조</b>할 수 있다.
 *
 * <p>AC-25-04-1 원문: "인증 없는 입구가 정한 값을 로그에 싣는 자리를 <b>전수</b>로 위생 처리한다.
 * 판정: 대상 줄에서 감싸지 않은 인자가 0."
 *
 * <p>AC-25-04-2 원문: "새 클래스도 공용 도구도 만들지 않는다 — <b>이미 같은 파일에 있는</b>
 * {@code sanitizeForLog} 를 쓴다."
 *
 * <p>AC-25-04-3 원문: "앞머리 문구와 필드 이름은 <b>안 바꾼다</b>(과거 기록 대조의 기준점)."
 *
 * <p>★<b>서비스 이름은 줄 맨 끝에 실린다</b> — 개행이 들면 그 뒤가 새 줄로 보이므로, 이 자리가
 * 일곱 줄 가운데 위조에 가장 약하다. 그래서 이 시험이 그 자리를 잡는다.
 *
 * <p><b>정방향 단언만 쓴다</b>: 확인하는 것은 "한 줄로 남는다" 이지 "거부한다" 가 아니다 —
 * 위생 처리는 <b>막는 방어가 아니라</b> 값을 한 줄로 접는 방어다. 값 자체는 버리지 않는다.
 */
class IngestServiceLogHygieneTest {

    /** 임계를 확실히 넘기는 공백. {@code isResumeGap} 임계는 10분이다. */
    private static final long DAY_MS = 86_400_000L;
    private static final String RESUME_PREFIX = "ingest resumed:";

    /**
     * 위조를 노린 서비스 이름 — 개행 뒤에 <b>다른 로그 줄처럼 보이는</b> 문자열을 붙였다.
     * 위생 처리가 없으면 로그 파일에 가짜 ERROR 줄이 한 줄 더 생긴다.
     */
    private static final String FORGED_NAME =
            "svc-forged\nERROR fake line injected by the caller";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private IngestService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-log-hygiene-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

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

    /**
     * TS-R25-33 — 개행이 든 서비스 이름을 넣어도 로그가 <b>한 줄</b>이다.
     *
     * <p>같은 줄에서 세 가지를 함께 확인한다 — ① 개행이 사라졌다 ② 값은 버려지지 않았다
     * (무손실 — 개행이 공백으로 접힌 것뿐) ③ 앞머리 문구와 필드 이름이 그대로다(AC-25-04-3).
     */
    @Test
    void keepsTheResumedLineOnASingleLineWhenTheServiceNameCarriesANewline() {
        long lastSeen = System.currentTimeMillis() - 4 * DAY_MS;
        seedService(FORGED_NAME, lastSeen);
        // 전제: 개행이 실제로 든 이름이 심어졌다 — 없으면 이 시험이 통과하면서 아무것도 검증하지 않는다.
        assertTrue(FORGED_NAME.indexOf('\n') >= 0, "전제: 픽스처 이름에 개행이 실제로 들어 있어야 한다");
        assertEquals(1, count("SELECT COUNT(*) FROM services WHERE instr(service_name, char(10)) > 0"),
                "전제: 개행이 든 services 행이 실제로 하나 있어야 한다");

        List<String> lines = captureResumeLines(
                () -> service.ingest(new IngestRequest(List.of(makeSpan("s-1", "t-1", FORGED_NAME)))));

        assertEquals(1, lines.size(), "임계를 넘는 공백 뒤 첫 수신에서 정확히 한 줄");
        String line = lines.get(0);

        // ① 개행이 사라졌다 — 로그 파일에서 이 줄이 두 줄로 쪼개지지 않는다.
        assertFalse(line.contains("\n"), "줄 안에 개행이 남으면 뒤 줄이 위조된다: " + line);
        assertFalse(line.contains("\r"), "복귀 문자도 같이 접힌다: " + line);

        // ② 값은 버려지지 않았다 — 개행이 공백으로 바뀐 것뿐이다(무손실).
        assertTrue(line.contains("svc-forged"), "이름의 앞부분이 그대로 남는다: " + line);
        assertTrue(line.contains("ERROR fake line injected by the caller"),
                "뒷부분도 버려지지 않고 같은 줄에 접힌다: " + line);

        // ③ 앞머리 문구와 필드 이름은 안 바뀐다 — 과거 기록 대조의 기준점이다.
        assertTrue(line.startsWith(RESUME_PREFIX), "앞머리 토큰 고정: " + line);
        assertTrue(line.contains("lastSeenBefore="), "필드 이름 무변경: " + line);
        assertTrue(line.contains("gapMs="), "필드 이름 무변경: " + line);
        assertTrue(line.contains("service="), "필드 이름 무변경: " + line);
    }

    // ── [Phase R26] R26/AC-R26-34 — 「이 파일의 로그 인자를」 좁힌 말이 실제로 그 범위인지 누른다 ──
    //
    //  AC-R26-34 원문: "{@code IngestService.java:406} 의 「전수」를 「이 파일의」로 좁히고 시험 **2건** 추가".
    //
    //  ★R25 의 자동 그물은 위 한 줄(`ingest resumed: … service=`)만 덮었다. 나머지 여섯 줄은 감싼 것을
    //    지워도 아무 시험이 안 깨졌다 — 사람이 돌린 검색만 있었다. 아래 둘이 그중 **행위로 도달 가능한**
    //    두 자리를 잡는다. 두 자리 다 인자 값이 밖에서 정한 문자열이다.

    /**
     * 정규식 마감 시각 초과로 본문을 통째로 가리는 갈래의 WARN 이 <b>한 줄</b>이다.
     *
     * <p>이 줄은 {@code spanId=} 와 {@code contentType=} 둘 다 밖에서 온 값이다. 그 둘에 개행을 넣어
     * 보내고 로그가 안 쪼개지는지 본다. ★{@code spanId} 형식 검사는 <b>HTTP 입구</b>에 있으므로,
     * 적재 진입점을 직접 부르는 이 자리에는 그 검사가 <b>안 걸린다</b> — 그래서 이 축이 여전히 살아 있다.
     */
    @Test
    void keepsTheDegradeWarningOnASingleLineWhenTheSpanIdAndContentTypeCarryNewlines() {
        IngestService degrading = newServiceWithAlwaysTimingOutMasking();
        String forgedSpanId = "rs-s0\nERROR forged by the caller";
        String forgedContentType = "application/json\nWARN forged content type";
        // 전제: 두 인자에 개행이 실제로 들어 있어야 이 시험이 뜻을 가진다.
        assertTrue(forgedSpanId.indexOf('\n') >= 0 && forgedContentType.indexOf('\n') >= 0,
                "전제: 두 픽스처에 개행이 실제로 들어 있어야 한다");

        Span span = new Span(forgedSpanId, "t-degrade", null, "svc", "op",
                SpanKind.SERVER, 1_000L, 1_001L, SpanStatus.OK, Map.of(),
                List.of(new Payload(PayloadDirection.IN, forgedContentType, "{\"note\":\"x\"}", 13, false)));

        List<String> lines = captureWarnLines("ReDoS deadline exceeded",
                () -> degrading.ingest(new IngestRequest(List.of(span))));

        assertEquals(1, lines.size(), "그 갈래의 WARN 이 정확히 한 줄 — 실제: " + lines);
        String line = lines.get(0);
        assertFalse(line.contains("\n"), "줄 안에 개행이 남으면 뒤 줄이 위조된다: " + line);
        assertTrue(line.contains("rs-s0") && line.contains("application/json"),
                "값은 버려지지 않고 같은 줄에 접힌다: " + line);
        assertTrue(line.contains("spanId=") && line.contains("contentType="),
                "필드 이름 무변경: " + line);
    }

    /**
     * 집계가 빈 갈래({@code reason=empty-aggregate})의 WARN 이 <b>한 줄</b>이다.
     *
     * <p>이 줄의 {@code traceId=} 는 인증 없는 입구가 정한 값이고, 앞머리 토큰이 운영 로그 대조의
     * 기준점이라 한 줄 단위가 깨지면 과거와의 대조가 틀어진다. 갈래 도달은 타이밍이 아니라
     * <b>집계 한 문장을 결정적으로 비게</b> 만들어 연다(생산 코드 무변경 · 시험 전용 주입).
     */
    @Test
    void keepsTheDeferredSummaryLineOnASingleLineWhenTheTraceIdCarriesANewline() throws Exception {
        String forgedTraceId = "t-forged\nERROR forged summary line";
        assertTrue(forgedTraceId.indexOf('\n') >= 0, "전제: traceId 에 개행이 실제로 들어 있어야 한다");
        injectJdbc(new EmptyAggregateJdbc(jdbc.getDataSource()));

        List<String> lines = captureWarnLines("trace summary deferred",
                () -> service.ingest(new IngestRequest(
                        List.of(makeSpan("s-forged", forgedTraceId, "svc-a")))));

        assertEquals(1, lines.size(), "그 갈래의 WARN 이 정확히 한 줄 — 실제: " + lines);
        String line = lines.get(0);
        assertFalse(line.contains("\n"), "줄 안에 개행이 남으면 뒤 줄이 위조된다: " + line);
        assertTrue(line.contains("reason=empty-aggregate"), "잰 갈래가 맞는지 확인: " + line);
        assertTrue(line.contains("t-forged") && line.contains("ERROR forged summary line"),
                "값은 버려지지 않고 같은 줄에 접힌다: " + line);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    /** 마스킹이 언제나 마감 시각 초과로 끝나는 적재 진입점 — degrade 갈래를 결정적으로 연다. */
    private IngestService newServiceWithAlwaysTimingOutMasking() {
        MaskingEngine timingOut = Mockito.mock(MaskingEngine.class);
        Mockito.when(timingOut.mask(Mockito.any(), Mockito.any())).thenThrow(RegexTimeoutException.INSTANCE);
        return new IngestService(jdbc,
                new FixedEngineHolder(new MaskingRuleRepository(jdbc), mapper, timingOut),
                mapper, new IngestProperties(1_048_576L));
    }

    private void injectJdbc(JdbcTemplate replacement) throws Exception {
        java.lang.reflect.Field f = IngestService.class.getDeclaredField("jdbc");
        f.setAccessible(true);
        f.set(service, replacement);
    }

    /** {@code current()} 가 정해진 엔진을 돌려주는 시험 전용 holder. */
    private static final class FixedEngineHolder extends MaskingEngineHolder {
        private final MaskingEngine engine;

        FixedEngineHolder(MaskingRuleRepository repository, ObjectMapper mapper, MaskingEngine engine) {
            super(repository, mapper);
            this.engine = engine;
        }

        @Override
        public MaskingEngine current() {
            return engine;
        }
    }

    /** 집계 한 문장만 비게 돌려주는 시험 전용 JdbcTemplate — 나머지 문장은 실제 DB 로 간다. */
    private static final class EmptyAggregateJdbc extends JdbcTemplate {
        private int queryForMapCalls = 0;

        EmptyAggregateJdbc(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            if (++queryForMapCalls == 1) {
                Map<String, Object> empty = new java.util.HashMap<>();
                empty.put("min_start", null);   // MIN(start_time) 이 NULL 인 상태
                empty.put("max_end", null);     // MAX(end_time) 이 NULL 인 상태
                empty.put("span_count", 0);     // COUNT(*) 는 SQLite 에서 NULL 이 되지 않는다
                empty.put("service_count", 0);
                empty.put("error_count", null);
                return empty;
            }
            return super.queryForMap(sql, args);
        }
    }

    /** 앞머리가 주어진 문구로 시작하는 WARN 만 모은다. */
    private static List<String> captureWarnLines(String prefix, Runnable action) {
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
                .filter(m -> m.startsWith(prefix))
                .toList();
    }

    private void seedService(String name, long lastSeenAt) {
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES (?, ?, ?, 'auto')", name, lastSeenAt, lastSeenAt);
    }

    private static Span makeSpan(String spanId, String traceId, String serviceName) {
        long now = System.currentTimeMillis();
        return new Span(spanId, traceId, null, serviceName, "GET /x",
                SpanKind.SERVER, now, now + 10, SpanStatus.OK, Map.of(), List.of());
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    private static List<String> captureResumeLines(Runnable action) {
        ch.qos.logback.classic.Logger ingestLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(IngestService.class);
        Level previous = ingestLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ingestLogger.addAppender(appender);
        ingestLogger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            ingestLogger.detachAppender(appender);
            appender.stop();
            ingestLogger.setLevel(previous);   // null 이면 상위 레벨 상속으로 되돌아간다
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(RESUME_PREFIX))
                .toList();
    }
}
