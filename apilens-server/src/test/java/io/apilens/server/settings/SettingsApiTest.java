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
package io.apilens.server.settings;

import io.apilens.server.retention.RetentionProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R12] T-B1 — GET/PUT /v1/settings (Design §7.2, MockMvc).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>D-05: "retention 기본 30일 유지 + 설정 페이지에서 변경 가능 (DB 저장 값이 yml 보다 우선)"</li>
 *   <li>AC-B1-3 (BL-07 — PM 확정 400 원자 거부): "전체 유효 시에만 적용 (부분 적용 0) + 400 본문에 허용 범위 포함"</li>
 * </ul>
 *
 * <p>경계 입력 표 (Design §7.1): 0/400 · 1/200 · 30/200 · 3650/200 · 3651/400 · −1/400 · "abc"/400 · 1.5/400.
 */
class SettingsApiTest {

    private static final String JSON = "application/json";

    @TempDir
    Path tempDir;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("apilens-settings-test.db").toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        this.jdbc = new JdbcTemplate(ds);

        SettingsService service = new SettingsService(jdbc, new SettingsRegistry(),
                new RetentionProperties(30, "0 0 4 * * *"));
        this.mockMvc = MockMvcBuilders.standaloneSetup(new SettingsController(service)).build();
    }

    // ─── GET — resolve 된 유효값 + lastCleanupAt (Design §5.2) ──────────────

    @Test
    void returnsYmlFallbackValueAndZeroLastCleanupAtOnFreshDb() throws Exception {
        // D-05: settings 행 부재 = yml fallback(30) 이 그대로 내려감 (FE prefill 단순화)
        mockMvc.perform(get("/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(30))
                .andExpect(jsonPath("$.lastCleanupAt").value(0)); // V1 시드 0 = 이력 없음 (FE T-11 분기)
    }

    /**
     * [Phase R22] R22/AC-04-5 verbatim: ""행 없음" 케이스 테스트가 있다. 기존
     * {@code SettingsApiTest} 의 {@code lastCleanupAt = 0} 단언은 <b>행이 없어도 통과</b>하므로 두 상태를
     * 구분하지 못한다 — 그 공백을 닫는다." 사용자 명시 결정(OQ-8·9).
     *
     * <p>R22/AC-04-3 verbatim: "<b>반환값은 기존과 같은 {@code 0L} 유지 · {@code SettingsResponse} DTO
     * 무변경 · FE 무변경 · FE 테스트 무변경.</b>" — 행이 없어도 200 이고 값도 0 그대로다. 달라지는 것은
     * <b>서버 로그에 경고가 남는다</b>는 것뿐이다("고쳤다" 가 아니라 "다시 생기면 알 수 있게 했다").
     */
    @Test
    void returns200WithZeroLastCleanupAtWhenTheRetentionMetaRowIsMissing() throws Exception {
        jdbc.update("DELETE FROM retention_meta");   // ④가 겪은 운영 상태 재현 (원인은 미규명)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM retention_meta", Integer.class),
                "전제: 행이 실제로 없다 (기존 단언은 이 상태와 시드 0 을 구분하지 못했다)");

        mockMvc.perform(get("/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(30))
                .andExpect(jsonPath("$.lastCleanupAt").value(0));
    }

    /** [Phase R22] R22/AC-04-3 — 행이 있으면 그 값이 그대로 내려온다 (위 케이스와 짝). */
    @Test
    void returnsTheStoredLastCleanupAtWhenTheRetentionMetaRowExists() throws Exception {
        long stamped = 1_700_000_000_000L;
        jdbc.update("UPDATE retention_meta SET last_cleanup_at = ? WHERE id = 1", stamped);

        mockMvc.perform(get("/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCleanupAt").value(stamped));
    }

    @Test
    void returnsStoredDbValueOverYmlFallback() throws Exception {
        // D-05 비협상: "DB 저장 값이 yml 보다 우선"
        mockMvc.perform(put("/v1/settings").contentType(JSON).content("{\"retention.days\": 14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(14));

        // 재조회 유지 (PUT 응답 = GET 동형, Design §5.2)
        mockMvc.perform(get("/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(14));
    }

    // ─── PUT 경계 (Design §7.1 입력 표 전건) ────────────────────────────────

    @Test
    void acceptsBoundaryValuesOneAnd3650() throws Exception {
        mockMvc.perform(put("/v1/settings").contentType(JSON).content("{\"retention.days\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(1));
        mockMvc.perform(put("/v1/settings").contentType(JSON).content("{\"retention.days\": 3650}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings['retention.days']").value(3650));
    }

    @Test
    void returns400WithAllowedRangeForOutOfRangeOrNonIntegerValues() throws Exception {
        // 경계 표: 0 / 3651 / -1 / "abc" / 1.5 → 전부 400 + 본문에 허용 범위 (E-01)
        for (String bad : new String[]{"0", "3651", "-1", "\"abc\"", "1.5", "true"}) {
            mockMvc.perform(put("/v1/settings").contentType(JSON)
                            .content("{\"retention.days\": " + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("between 1 and 3650")));
        }
    }

    @Test
    void returns400ForUnknownKeyWithAllowedKeyList() throws Exception {
        mockMvc.perform(put("/v1/settings").contentType(JSON).content("{\"foo.bar\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("allowed: retention.days")));
    }

    // ─── 원자성 (AC-B1-3 비협상 — 부분 적용 0) ──────────────────────────────

    @Test
    void appliesNothingWhenAnyKeyOfTheUpdateIsInvalid() throws Exception {
        // PUT { "retention.days": 14, "foo": 1 } → 전체 400 + DB 무변경 (retention.days 14 미적용 단언)
        mockMvc.perform(put("/v1/settings").contentType(JSON)
                        .content("{\"retention.days\": 14, \"foo\": 1}"))
                .andExpect(status().isBadRequest());

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM settings", Integer.class);
        assertEquals(0, rows, "원자 거부: 유효한 키(retention.days=14)도 함께 거부돼 DB 에 행이 없어야 한다");

        mockMvc.perform(get("/v1/settings"))
                .andExpect(jsonPath("$.settings['retention.days']").value(30)); // yml fallback 그대로
    }

    @Test
    void returns400ForEmptyBody() throws Exception {
        mockMvc.perform(put("/v1/settings").contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("retention.days")));
    }
}
