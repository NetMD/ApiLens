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
package io.apilens.server.instrument;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [2026-09-24] SEC-R26-02 — <b>로그 위생</b>, 계측 분석 쪽 두 줄. 요청이 정한 {@code service=} 값이
 * 로그 한 줄의 첫 인자로 실린다. 개행이 들어가면 <b>뒤 줄을 통째로 위조</b>할 수 있다.
 *
 * <p>R26 보안 검토 원문: "자리는 {@code InstrumentAnalysisService.java:147}·{@code :193} 두 곳이고, 처방은
 * 같은 라운드가 이미 만든 {@code IngestService.sanitizeForLog} 로 감싸기 + 짝 시험 2건입니다.
 * ★고침만 하고 시험을 안 넣으면 안 됩니다."
 *
 * <p>이 시험은 {@link io.apilens.server.ingest.IngestServiceLogHygieneTest} 와 같은 모양이다 —
 * <b>정방향 단언만 쓴다</b>: 확인하는 것은 "한 줄로 남는다" 이지 "거부한다" 가 아니다. 값은 버리지 않는다.
 * 앞머리 문구와 필드 이름은 안 바뀐다(과거 기록 대조의 기준점).
 *
 * <p>★서비스 이름 형식 검사는 <b>HTTP 입구</b>({@code InstrumentAnalysisController})에 있다. 서비스를 직접
 * 부르는 이 자리에는 그 검사가 안 걸린다 — 그래서 이 축이 살아 있고, 인증을 안 켠 기본 설치에서는
 * 입구 자체가 무인증이다.
 */
class InstrumentAnalysisServiceLogHygieneTest {

    private static final String ANALYSIS_PREFIX = "instrument analysis done:";
    private static final String SIMULATION_PREFIX = "instrument simulation done:";

    /**
     * 위조를 노린 서비스 이름 — 개행 뒤에 <b>다른 로그 줄처럼 보이는</b> 문자열을 붙였다.
     * 위생 처리가 없으면 로그 파일에 가짜 ERROR 줄이 한 줄 더 생긴다.
     */
    private static final String FORGED_NAME =
            "svc-forged\nERROR fake line injected by the caller";

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private InstrumentAnalysisService service;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-instrument-log-hygiene-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.jdbc = new JdbcTemplate(dataSource);
        this.service = new InstrumentAnalysisService(
                new InstrumentAnalysisRepository(jdbc), new InstrumentAnalysisGate());
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    /**
     * 순위 조회 요약 줄({@code instrument analysis done:})이 개행이 든 서비스 이름에도 <b>한 줄</b>이다.
     *
     * <p>같은 줄에서 세 가지를 함께 확인한다 — ① 개행이 사라졌다 ② 값은 버려지지 않았다
     * (무손실 — 개행이 공백으로 접힌 것뿐) ③ 앞머리 문구와 필드 이름이 그대로다.
     */
    @Test
    void keepsTheAnalysisLineOnASingleLineWhenTheServiceNameCarriesANewline() {
        long now = System.currentTimeMillis();
        seedOneTrace(FORGED_NAME, now - 60_000L);
        // 전제: 개행이 실제로 든 이름이 심어졌다 — 없으면 이 시험이 통과하면서 아무것도 검증하지 않는다.
        assertTrue(FORGED_NAME.indexOf('\n') >= 0, "전제: 픽스처 이름에 개행이 실제로 들어 있어야 한다");
        assertEquals(1, count("SELECT COUNT(*) FROM traces WHERE instr(service_name, char(10)) > 0"),
                "전제: 개행이 든 traces 행이 실제로 하나 있어야 한다");

        List<String> lines = captureInfoLines(ANALYSIS_PREFIX, () -> service.analyze(FORGED_NAME, 1));

        assertEquals(1, lines.size(), "순위 조회 한 번에 요약 줄 정확히 하나 — 실제: " + lines);
        String line = lines.get(0);

        // ① 개행이 사라졌다 — 로그 파일에서 이 줄이 두 줄로 쪼개지지 않는다.
        assertFalse(line.contains("\n"), "줄 안에 개행이 남으면 뒤 줄이 위조된다: " + line);
        assertFalse(line.contains("\r"), "복귀 문자도 같이 접힌다: " + line);

        // ② 값은 버려지지 않았다 — 개행이 공백으로 바뀐 것뿐이다(무손실).
        assertTrue(line.contains("svc-forged"), "이름의 앞부분이 그대로 남는다: " + line);
        assertTrue(line.contains("ERROR fake line injected by the caller"),
                "뒷부분도 버려지지 않고 같은 줄에 접힌다: " + line);

        // ③ 앞머리 문구와 필드 이름은 안 바뀐다 — 과거 기록 대조의 기준점이다.
        assertTrue(line.startsWith(ANALYSIS_PREFIX), "앞머리 토큰 고정: " + line);
        assertTrue(line.contains("service="), "필드 이름 무변경: " + line);
        assertTrue(line.contains("windowHours="), "필드 이름 무변경: " + line);
        assertTrue(line.contains("elapsedMs="), "필드 이름 무변경: " + line);
    }

    /**
     * 시뮬레이션 요약 줄({@code instrument simulation done:})이 개행이 든 서비스 이름에도 <b>한 줄</b>이다.
     * 위 순위 조회 줄과 한 쌍 — 감싼 자리가 두 곳이라 시험도 둘이다.
     */
    @Test
    void keepsTheSimulationLineOnASingleLineWhenTheServiceNameCarriesANewline() {
        long now = System.currentTimeMillis();
        seedOneTrace(FORGED_NAME, now - 60_000L);
        assertTrue(FORGED_NAME.indexOf('\n') >= 0, "전제: 픽스처 이름에 개행이 실제로 들어 있어야 한다");

        List<String> lines = captureInfoLines(SIMULATION_PREFIX,
                () -> service.simulate(FORGED_NAME, now - 3_600_000L, now + 1L, List.of("com.example.Foo")));

        assertEquals(1, lines.size(), "시뮬레이션 한 번에 요약 줄 정확히 하나 — 실제: " + lines);
        String line = lines.get(0);

        assertFalse(line.contains("\n"), "줄 안에 개행이 남으면 뒤 줄이 위조된다: " + line);
        assertFalse(line.contains("\r"), "복귀 문자도 같이 접힌다: " + line);
        assertTrue(line.contains("svc-forged") && line.contains("ERROR fake line injected by the caller"),
                "값은 버려지지 않고 같은 줄에 접힌다: " + line);
        assertTrue(line.startsWith(SIMULATION_PREFIX), "앞머리 토큰 고정: " + line);
        assertTrue(line.contains("service=") && line.contains("targets="), "필드 이름 무변경: " + line);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    /** trace 하나 + SERVER span 하나 — 순위·시뮬레이션이 빈 집합이 아니라 실제 행 위에서 돌게 한다. */
    private void seedOneTrace(String serviceName, long startTime) {
        jdbc.update("""
                        INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                            status, span_count, service_count, has_error, received_at)
                        VALUES (?, 'root', ?, ?, 10, 'OK', 1, 1, 0, ?)
                        """,
                "t-forged", serviceName, startTime, startTime);
        jdbc.update("""
                        INSERT INTO spans (span_id, trace_id, parent_span_id, service_name, operation_name,
                                           span_kind, start_time, end_time, status, attributes_json)
                        VALUES (?, ?, NULL, ?, 'com.example.Foo#bar', 'SERVER', ?, ?, 'OK', NULL)
                        """,
                "s-forged", "t-forged", serviceName, startTime, startTime + 1L);
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }

    /** 앞머리가 주어진 문구로 시작하는 INFO 만 모은다 — 레벨을 INFO 로 잠깐 내리고 끝나면 되돌린다. */
    private static List<String> captureInfoLines(String prefix, Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InstrumentAnalysisService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previous);   // null 이면 상위 레벨 상속으로 되돌아간다
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix))
                .toList();
    }
}
