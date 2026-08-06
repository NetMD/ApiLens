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
package io.apilens.server.instrument.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R20] 서비스별 원격 계측 설정 API — PUT/GET/DELETE 왕복 + 어휘 폐쇄 + 입력 상한 경계.
 *
 * <p>실 SQLite 임시 파일 + Flyway(V5 포함)로 PK upsert·저장 형식(콤마 TEXT ↔ 배열)이 실제 DB 에서
 * 동작함을 검증한다([S-60] 실 persist 봉인 — mock 아님).
 *
 * <p>R20/AC-03-5 verbatim (비협상): "Q-U4 어휘 밖 항목(예: sampling rate·payload 상한)은 이 채널로
 * 설정 불가 — 어휘 폐쇄가 API 표면에서도 지켜진다" (수단 = 스키마 제한 — 미지 필드는 바인딩 대상도
 * 저장 컬럼도 없음).
 *
 * <p>[Phase R21] R21/AC-01-4 (R-04) — 대표 수락 기준: R20/AC-03-5(어휘 폐쇄 + 입력 상한 경계 — 실
 * SQLite persist 봉인) · R21/AC-02-11(서비스 삭제 시 설정 행 동반 삭제) · R21/AC-08-1(I-3 — JSON 파싱
 * 단계 400 flat).
 */
class ServiceInstrumentConfigApiTest {

    @TempDir
    Path tempDir;
    private Path dbFile;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() throws Exception {
        dbFile = Files.createTempFile(tempDir, "apilens-config-api-test-", ".db");
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
        ServiceInstrumentConfigController controller =
                new ServiceInstrumentConfigController(new ServiceInstrumentConfigService(jdbc));
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void teardown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    // ─── B-14: PUT → GET 왕복 (echo == GET) ─────────────────────────────────

    /** 정방향: 저장 echo 와 후속 GET 이 같은 모양 — 콤마 TEXT 저장 ↔ JSON 배열 왕복 정합. */
    @Test
    void putThenGetEchoesSavedConfig() throws Exception {
        mockMvc.perform(put("/v1/services/vams-prod/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"captureParams": false, "requireEntryRoot": true,
                                 "gateExcludes": ["com.vams.analysis.mapper.SimilarPairMapper"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureParams").value(false))
                .andExpect(jsonPath("$.requireEntryRoot").value(true))
                .andExpect(jsonPath("$.gateExcludes[0]").value("com.vams.analysis.mapper.SimilarPairMapper"))
                // 지시 없음(null) 축은 키 생략 — 부재 허용형.
                .andExpect(jsonPath("$.captureResultSet").doesNotExist());

        mockMvc.perform(get("/v1/services/vams-prod/instrument-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureParams").value(false))
                .andExpect(jsonPath("$.requireEntryRoot").value(true))
                .andExpect(jsonPath("$.gateExcludes[0]").value("com.vams.analysis.mapper.SimilarPairMapper"))
                .andExpect(jsonPath("$.captureResultSet").doesNotExist());
    }

    /** PK upsert 멱등 — 같은 서비스에 두 번 PUT 하면 마지막 값으로 전체 교체(행 1개 유지). */
    @Test
    void putTwiceReplacesWholeConfigIdempotently() throws Exception {
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captureParams\": false, \"gateExcludes\": [\"com.a.B\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireEntryRoot\": true}"))
                .andExpect(status().isOk());

        // 전체 교체 — 앞선 captureParams/gateExcludes 지시는 사라진다(부분 병합 아님).
        mockMvc.perform(get("/v1/services/svc/instrument-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireEntryRoot").value(true))
                .andExpect(jsonPath("$.captureParams").doesNotExist())
                .andExpect(jsonPath("$.gateExcludes").doesNotExist());

        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM service_instrument_configs WHERE service_name = 'svc'", Integer.class),
                "PK upsert — 행은 1개 유지");
    }

    /** GET — 행 부재 시 404 flat { "error": ... }. */
    @Test
    void getReturns404WhenAbsent() throws Exception {
        mockMvc.perform(get("/v1/services/no-such-service/instrument-config"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    // ─── B-17: DELETE 의미론 — 멱등 204 + 지시 철회 ─────────────────────────

    /** DELETE — 멱등(부재여도 204), 이후 GET 404(지시 철회 = 202 필드 부재의 원천). */
    @Test
    void deleteIsIdempotentAndWithdrawsConfig() throws Exception {
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captureParams\": false}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/v1/services/svc/instrument-config"))
                .andExpect(status().isNoContent());
        // 멱등 — 이미 없어도 204.
        mockMvc.perform(delete("/v1/services/svc/instrument-config"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/services/svc/instrument-config"))
                .andExpect(status().isNotFound());
    }

    // ─── B-15: 어휘 폐쇄 — 어휘 밖 필드는 저장 표면이 없다 (AC-03-5) ──────────

    /** 어휘 밖 필드(samplingRate 등) 동봉 PUT — 4종만 저장, 미지 필드는 echo·GET 어디에도 없다. */
    @Test
    void keepsOnlyClosedVocabularyOnPut() throws Exception {
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"captureParams": false, "samplingRate": 0.1,
                                 "payloadMaxBytes": 9999, "futureKnob": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureParams").value(false))
                .andExpect(jsonPath("$.samplingRate").doesNotExist())
                .andExpect(jsonPath("$.payloadMaxBytes").doesNotExist())
                .andExpect(jsonPath("$.futureKnob").doesNotExist());

        mockMvc.perform(get("/v1/services/svc/instrument-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureParams").value(false))
                .andExpect(jsonPath("$.samplingRate").doesNotExist());
    }

    // ─── B-16: 입력 상한 경계 — 100/101개 · 512/513자 ────────────────────────

    /** 경계 안: gateExcludes 100개 + 항목 512자 → 200. */
    @Test
    void acceptsGateExcludesAtLimits() throws Exception {
        String items = IntStream.range(0, 99)
                .mapToObj(i -> "\"com.acme.C" + i + "\"")
                .collect(Collectors.joining(","));
        String maxLenItem = "\"" + "x".repeat(512) + "\"";   // 100번째 항목 = 정확히 512자

        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateExcludes\": [" + items + "," + maxLenItem + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateExcludes.length()").value(100));
    }

    /** 경계 밖: 101개 → 400. */
    @Test
    void returns400OnGateExcludesCountBeyondLimit() throws Exception {
        String items = IntStream.range(0, 101)
                .mapToObj(i -> "\"com.acme.C" + i + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateExcludes\": [" + items + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 경계 밖: 항목 513자 → 400. */
    @Test
    void returns400OnGateExcludeItemBeyondLimit() throws Exception {
        String tooLong = "\"" + "x".repeat(513) + "\"";

        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateExcludes\": [" + tooLong + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** serviceName 201자 → 400 (상한 200). 공백 항목은 제거 후 저장(공백 항목 제거 규약). */
    @Test
    void returns400OnOverlongServiceNameAndDropsBlankItems() throws Exception {
        String overlong = "s".repeat(201);
        mockMvc.perform(put("/v1/services/" + overlong + "/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captureParams\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // 공백 항목 제거 — [" ", "com.a.B", ""] → ["com.a.B"].
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateExcludes\": [\" \", \"com.a.B\", \"\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateExcludes.length()").value(1))
                .andExpect(jsonPath("$.gateExcludes[0]").value("com.a.B"));
    }

    /** 콤마 포함 항목 → 400 (콤마 구분 TEXT 저장의 왕복 정합 보호 — FQN 에 콤마 불가). */
    @Test
    void returns400OnGateExcludeItemContainingComma() throws Exception {
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateExcludes\": [\"com.a.B,com.c.D\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** services 테이블에 없는 서비스명도 허용 — 재배포 전 선설정 운영 동선(config 는 지시이지 상태가 아님). */
    @Test
    void acceptsServiceNameNotYetRegistered() throws Exception {
        mockMvc.perform(put("/v1/services/future-service/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireEntryRoot\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireEntryRoot").value(true));
    }

    // ─── [Phase R21] R-U4: 서비스 삭제 시 설정 행 동반 삭제 + I-3: 파싱 400 flat ──

    /**
     * [Phase R21] R21/AC-02-11 (R-U4) — 서비스 삭제 시 저장된 계측 설정 지시도 함께 철회된다(같은
     * 트랜잭션). D-05 불변: traces 행은 보존(services + service_instrument_configs 만 touch).
     */
    @Test
    void deletesInstrumentConfigRowAlongWithServiceAndKeepsTraces() throws Exception {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO services (service_name, registered_at, last_seen_at, source) "
                + "VALUES (?, ?, ?, 'auto')", "svc", now, now);
        jdbc.update("""
                INSERT INTO traces (trace_id, root_operation, service_name, start_time, duration_ms,
                                    status, span_count, received_at)
                VALUES ('t-1', 'GET /x', 'svc', ?, 5, 'OK', 1, ?)
                """, now, now);

        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captureParams\": false}"))
                .andExpect(status().isOk());

        new io.apilens.server.services.ServicesService(jdbc).delete("svc");

        // 설정 행 동반 삭제 — GET 404 (지시 철회 = 202 필드 부재의 원천).
        mockMvc.perform(get("/v1/services/svc/instrument-config"))
                .andExpect(status().isNotFound());
        // D-05 불변 — traces 행 보존, services 행만 제거.
        assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM traces WHERE service_name = 'svc'", Integer.class),
                "서비스 삭제가 traces 를 지우면 D-05 위반");
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM services WHERE service_name = 'svc'", Integer.class),
                "services 행은 제거돼야 한다");
    }

    /**
     * [Phase R21] R21/AC-08-1 (I-3) — JSON 파싱 단계 400 도 flat { "error": ... } (검증 400 과 동형).
     * 컨트롤러 로컬 @ExceptionHandler(HttpMessageNotReadableException)가 파싱 예외를 받는 것을 실측 봉인.
     */
    @Test
    void returnsFlatErrorBodyOnUnreadableJsonPut() throws Exception {
        mockMvc.perform(put("/v1/services/svc/instrument-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captureParams\": fal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문(JSON)을 읽을 수 없습니다."));
    }
}
