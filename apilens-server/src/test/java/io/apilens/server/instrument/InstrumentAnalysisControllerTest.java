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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R19] InstrumentAnalysisController 요청 경계값 — 설계 §8.1 표(B-01~B-12b)를 그대로 옮긴 것.
 *
 * <p>실제 SQLite 임시 파일 DB + Flyway 로 200 경로가 끝까지 도는지도 함께 잰다(자료가 없으면
 * 0으로 채운 정상 응답이 나오는 것이 옳은 동작이다 — 빈 결과를 오류로 만들지 않는다).
 *
 * <p>비협상 AC verbatim 인용:
 * <ul>
 *   <li>AC-02-1: "시간 구간은 1 / 6 / 24 세 개만" (열거 검사 — 범위 검사가 아니다)</li>
 *   <li>AC-04-1: "시뮬레이션은 순위 응답이 준 구간을 그대로 되돌려 받는다"</li>
 * </ul>
 */
class InstrumentAnalysisControllerTest {

    private static final long HOUR_MS = 3_600_000L;

    @TempDir
    Path tempDir;
    private Path dbFile;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-instrument-api-test-", ".db");
        Files.deleteIfExists(dbFile);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        DataSource dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        InstrumentAnalysisService service = new InstrumentAnalysisService(
                new InstrumentAnalysisRepository(jdbc), new InstrumentAnalysisGate());
        this.mockMvc = MockMvcBuilders.standaloneSetup(new InstrumentAnalysisController(service)).build();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── B-01 ~ B-05 — windowHours 열거 검사 ────────────────────────────────

    @Test
    void acceptsWindowHoursOfOne() throws Exception {
        analyze("{\"serviceName\":\"svc\",\"windowHours\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.fromMs").exists())
                .andExpect(jsonPath("$.summary.totalTraces").value(0));
    }

    @Test
    void acceptsWindowHoursOfTwentyFour() throws Exception {
        analyze("{\"serviceName\":\"svc\",\"windowHours\":24}").andExpect(status().isOk());
    }

    @Test
    void answersBadRequestForWindowHoursOfZero() throws Exception {
        analyze("{\"serviceName\":\"svc\",\"windowHours\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void answersBadRequestForWindowHoursNotInTheEnumeration() throws Exception {
        analyze("{\"serviceName\":\"svc\",\"windowHours\":2}").andExpect(status().isBadRequest());
        analyze("{\"serviceName\":\"svc\",\"windowHours\":25}").andExpect(status().isBadRequest());
    }

    // ─── B-06 ~ B-08 — serviceName 길이 ─────────────────────────────────────

    @Test
    void answersBadRequestForBlankServiceName() throws Exception {
        analyze("{\"serviceName\":\"\",\"windowHours\":1}").andExpect(status().isBadRequest());
    }

    @Test
    void acceptsServiceNameOfExactlyTwoHundredFiftyFiveCharacters() throws Exception {
        analyze("{\"serviceName\":\"" + "a".repeat(255) + "\",\"windowHours\":1}")
                .andExpect(status().isOk());
    }

    @Test
    void answersBadRequestForServiceNameLongerThanTwoHundredFiftyFive() throws Exception {
        analyze("{\"serviceName\":\"" + "a".repeat(256) + "\",\"windowHours\":1}")
                .andExpect(status().isBadRequest());
    }

    // ─── B-09 ~ B-12b — 시뮬레이션 요청 ─────────────────────────────────────

    @Test
    void acceptsSimulationWithAnEmptyTargetList() throws Exception {
        simulate(simulationBody(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savings.spanDelta").value(0))
                .andExpect(jsonPath("$.impact.remainingSpans").value(0))
                .andExpect(jsonPath("$.depthCapped").value(false));
    }

    @Test
    void acceptsSimulationWithFiveHundredTargets() throws Exception {
        simulate(simulationBody(500)).andExpect(status().isOk());
    }

    @Test
    void answersBadRequestForMoreThanFiveHundredTargets() throws Exception {
        simulate(simulationBody(501)).andExpect(status().isBadRequest());
    }

    @Test
    void answersBadRequestForATargetLongerThanFiveHundredTwelve() throws Exception {
        long to = System.currentTimeMillis();
        String body = "{\"serviceName\":\"svc\",\"fromMs\":" + (to - HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[\"" + "c".repeat(513) + "\"]}";
        simulate(body).andExpect(status().isBadRequest());
    }

    @Test
    void acceptsATargetOfExactlyFiveHundredTwelveCharacters() throws Exception {
        long to = System.currentTimeMillis();
        String body = "{\"serviceName\":\"svc\",\"fromMs\":" + (to - HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[\"" + "c".repeat(512) + "\"]}";
        simulate(body).andExpect(status().isOk());
    }

    @Test
    void answersBadRequestForAWindowLengthNotInTheEnumeration() throws Exception {
        long to = System.currentTimeMillis();
        String body = "{\"serviceName\":\"svc\",\"fromMs\":" + (to - 2 * HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[]}";
        simulate(body).andExpect(status().isBadRequest());
    }

    @Test
    void answersBadRequestForAWindowEndFarInTheFuture() throws Exception {
        long to = System.currentTimeMillis() + 3_600_000L;
        String body = "{\"serviceName\":\"svc\",\"fromMs\":" + (to - HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[]}";
        simulate(body).andExpect(status().isBadRequest());
    }

    @Test
    void acceptsSimulationWindowsOfSixAndTwentyFourHours() throws Exception {
        long to = System.currentTimeMillis();
        simulate("{\"serviceName\":\"svc\",\"fromMs\":" + (to - 6 * HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[]}").andExpect(status().isOk());
        simulate("{\"serviceName\":\"svc\",\"fromMs\":" + (to - 24 * HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[]}").andExpect(status().isOk());
    }

    // ─── 상태 코드 분리 — 400 / 409 / 504 가 서로 다른 코드여야 한다 ────────

    /**
     * 실행 시간 상한 초과는 <b>504</b> 다. 500 이 아니다.
     *
     * <p>화면은 응답 본문을 노출하지 않고 상태 코드로만 문구를 고른다. 시간 초과를 "그 밖의 서버 오류"
     * 와 같은 코드로 묶으면 운영자가 실제로 할 수 있는 유일한 행동인 "구간을 좁혀 다시 시도해 주세요"
     * 를 영영 보지 못한다. 그래서 매핑 자체를 결정적으로 잰다(쿼리 시간에 의존하지 않는다).
     */
    @Test
    void answersGatewayTimeoutWhenTheAnalysisOutrunsItsDeadline() throws Exception {
        MockMvc slow = MockMvcBuilders.standaloneSetup(
                new InstrumentAnalysisController(failingWith(new InstrumentAnalysisGate.DeadlineExceededException()))
        ).build();

        slow.perform(post("/v1/instrument/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"svc\",\"windowHours\":1}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 이미 다른 분석이 도는 중은 409 — 504·400 과 다른 코드여야 한다. */
    @Test
    void answersConflictWhenAnotherAnalysisIsAlreadyRunning() throws Exception {
        MockMvc busy = MockMvcBuilders.standaloneSetup(
                new InstrumentAnalysisController(failingWith(new InstrumentAnalysisGate.BusyException()))
        ).build();

        busy.perform(post("/v1/instrument/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"svc\",\"windowHours\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // ─── S-3 계약 — 절감과 부작용은 언제나 함께 온다 ────────────────────────

    @Test
    void alwaysReturnsSavingsAndImpactTogether() throws Exception {
        simulate(simulationBody(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savings").exists())
                .andExpect(jsonPath("$.impact").exists())
                .andExpect(jsonPath("$.window.queriedAtMs").exists());
    }

    // ─── helper ─────────────────────────────────────────────────────────────

    /** 게이트가 던지는 예외의 <b>매핑</b>만 재기 위한 대역 — 쿼리 시간에 의존하지 않는다. */
    private static InstrumentAnalysisService failingWith(RuntimeException toThrow) {
        return new InstrumentAnalysisService(null, null) {
            @Override
            public io.apilens.server.instrument.dto.AnalysisResponse analyze(String serviceName, int windowHours) {
                throw toThrow;
            }
        };
    }

    private org.springframework.test.web.servlet.ResultActions analyze(String body) throws Exception {
        return mockMvc.perform(post("/v1/instrument/analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions simulate(String body) throws Exception {
        return mockMvc.perform(post("/v1/instrument/simulation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String simulationBody(int targetCount) {
        long to = System.currentTimeMillis();
        String targets = IntStream.range(0, targetCount)
                .mapToObj(i -> "\"com.acme.Class" + i + "\"")
                .collect(Collectors.joining(","));
        return "{\"serviceName\":\"svc\",\"fromMs\":" + (to - HOUR_MS) + ",\"toMs\":" + to
                + ",\"targets\":[" + targets + "]}";
    }
}
