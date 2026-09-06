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
package io.apilens.server.masking;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestProperties;
import io.apilens.server.ingest.IngestService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [Phase R12] T-B2 — /v1/masking-rules CRUD + 핫 리로드 (Design §7.2, MockMvc).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>D-02: "설정 페이지에 마스킹 룰 관리 UI 포함 — 목록/토글/추가·삭제 + 라이브 프리뷰.
 *       결재용 신뢰 도구" (사용자 명시 비협상 결정)</li>
 *   <li>AC-B2-2 — CLAUDE.md '데이터 모델' verbatim: "default는 비활성만 가능, 삭제 불가"
 *       → DELETE default = 409 + 행 잔존, PATCH(토글)는 default 도 허용</li>
 * </ul>
 *
 * <p>핫 리로드 본체 검증: 토글 → 이후 ingest 분의 payload 마스킹 결과 변화 (BL-06 —
 * 기존 저장 payload 재마스킹 없음은 구조상 경로 부재).
 */
class MaskingRuleApiTest {

    private static final String JSON = "application/json";
    private static final String SSN_RAW = "880101-1234567";

    @TempDir
    Path tempDir;
    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private MaskingEngineHolder holder;
    private IngestService ingestService;

    @BeforeEach
    void setup() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("apilens-masking-test.db").toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        this.jdbc = new JdbcTemplate(ds);

        ObjectMapper mapper = new ObjectMapper();
        MaskingRuleRepository repository = new MaskingRuleRepository(jdbc);
        this.holder = new MaskingEngineHolder(repository, mapper);
        holder.reload(); // startup 1회 — MaskingConfig 동형
        MaskingRuleService service = new MaskingRuleService(repository, holder);
        MaskingPreviewService previewService = new MaskingPreviewService(repository, mapper);
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new MaskingRuleController(service, previewService))
                .build();
        this.ingestService = new IngestService(jdbc, holder, mapper, new IngestProperties(1_048_576L));
    }

    // ─── 목록 (Design §5.3 — default 4종 상단 고정) ─────────────────────────

    @Test
    void listsDefaultRulesFirstWithV1SeedFour() throws Exception {
        mockMvc.perform(get("/v1/masking-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(4))
                .andExpect(jsonPath("$.rules[0].isDefault").value(true))
                .andExpect(jsonPath("$.rules[0].ruleId").value(1))
                .andExpect(jsonPath("$.rules[0].enabled").value(true));
    }

    // ─── custom CRUD 왕복 (실 INSERT — NOT NULL 컬럼 실측 봉인) ──────────────

    @Test
    void createsCustomRuleAndRoundTripsThroughListAndDelete() throws Exception {
        mockMvc.perform(post("/v1/masking-rules").contentType(JSON).content(
                        "{\"name\":\"my-api-key\",\"ruleType\":\"field_name\",\"pattern\":\"x-api-key\",\"maskStrategy\":\"full\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").value(5))
                .andExpect(jsonPath("$.isDefault").value(false)) // is_default=0 서버 강제
                .andExpect(jsonPath("$.enabled").value(true));   // enabled 생략 시 true (Design §5.3)

        // 실 INSERT 봉인 — NOT NULL 컬럼(created_at/updated_at 포함)이 실제 채워졌는지 (S-60)
        Long createdAt = jdbc.queryForObject(
                "SELECT created_at FROM masking_rules WHERE rule_id = 5", Long.class);
        assertNotNull(createdAt);
        assertTrue(createdAt > 0L, "created_at 은 epoch ms 로 채워져야 한다 (NOT NULL 실측)");

        mockMvc.perform(get("/v1/masking-rules"))
                .andExpect(jsonPath("$.rules.length()").value(5))
                .andExpect(jsonPath("$.rules[4].name").value("my-api-key")); // custom 은 default 4종 뒤

        mockMvc.perform(delete("/v1/masking-rules/5"))
                .andExpect(status().isNoContent());
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM masking_rules WHERE rule_id = 5", Integer.class);
        assertEquals(0, remaining);
    }

    // ─── POST 검증 400 (regex 컴파일 · enum · 필수 — Design §6.2) ───────────

    @Test
    void returns400ForInvalidRegexPattern() throws Exception {
        mockMvc.perform(post("/v1/masking-rules").contentType(JSON).content(
                        "{\"name\":\"bad\",\"ruleType\":\"regex\",\"pattern\":\"(unclosed\",\"maskStrategy\":\"full\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("pattern is not a valid regex")));
    }

    @Test
    void returns400ForUnknownRuleTypeOrStrategyOrBlankFields() throws Exception {
        mockMvc.perform(post("/v1/masking-rules").contentType(JSON).content(
                        "{\"name\":\"x\",\"ruleType\":\"glob\",\"pattern\":\"p\",\"maskStrategy\":\"full\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ruleType must be one of: field_name, regex"));

        mockMvc.perform(post("/v1/masking-rules").contentType(JSON).content(
                        "{\"name\":\"x\",\"ruleType\":\"regex\",\"pattern\":\"p\",\"maskStrategy\":\"zero\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("maskStrategy must be one of")));

        mockMvc.perform(post("/v1/masking-rules").contentType(JSON).content(
                        "{\"name\":\"  \",\"ruleType\":\"regex\",\"pattern\":\"p\",\"maskStrategy\":\"full\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("name must not be blank"));
    }

    // ─── PATCH 토글 (default 포함 허용 — "비활성만 가능 = 토글 가능") ────────

    @Test
    void togglesDefaultRuleEnabledFlag() throws Exception {
        mockMvc.perform(patch("/v1/masking-rules/1").contentType(JSON).content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(1))
                .andExpect(jsonPath("$.enabled").value(false));

        Integer enabled = jdbc.queryForObject(
                "SELECT enabled FROM masking_rules WHERE rule_id = 1", Integer.class);
        assertEquals(0, enabled);
    }

    @Test
    void returns400WhenPatchBodyContainsFieldsOtherThanEnabled() throws Exception {
        mockMvc.perform(patch("/v1/masking-rules/1").contentType(JSON)
                        .content("{\"enabled\": false, \"name\": \"renamed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("only 'enabled' can be updated in v0.2"));
    }

    @Test
    void returns404ForMissingRuleOnPatchAndDelete() throws Exception {
        mockMvc.perform(patch("/v1/masking-rules/999").contentType(JSON).content("{\"enabled\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("rule not found"));
        mockMvc.perform(delete("/v1/masking-rules/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("rule not found"));
    }

    // ─── DELETE default = 409 + 행 잔존 (AC-B2-2 비협상) ────────────────────

    @Test
    void keepsDefaultRuleRowAndReturns409OnDelete() throws Exception {
        mockMvc.perform(delete("/v1/masking-rules/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("default rule cannot be deleted — disable it instead")); // E-02 고정 본문

        Integer stillThere = jdbc.queryForObject(
                "SELECT COUNT(*) FROM masking_rules WHERE rule_id = 1", Integer.class);
        assertEquals(1, stillThere, "409 거부 후 default 행이 그대로 남아야 한다");
    }

    // ─── 핫 리로드 본체: 토글 → 신규 ingest 반영 (BL-05/BL-06) ──────────────

    @Test
    void appliesToggleToSubsequentIngestWithoutRemaskingExistingPayloads() throws Exception {
        // 1) 토글 전 ingest — 주민번호 regex 룰(rule_id=1) 활성: 마스킹됨
        ingestService.ingest(new IngestRequest(List.of(spanWithSsnPayload("s-before", "t-before"))));
        String maskedBody = storedBodyOf("s-before");
        assertNotNull(maskedBody);
        assertFalse(maskedBody.contains(SSN_RAW), "토글 전: 주민번호가 마스킹돼 저장돼야 한다 — " + maskedBody);

        // 2) PATCH 로 주민번호 룰 비활성 → holder.reload() 가 mutation 직후 자동 수행
        mockMvc.perform(patch("/v1/masking-rules/1").contentType(JSON).content("{\"enabled\": false}"))
                .andExpect(status().isOk());

        // 3) 토글 후 ingest — 이후 분부터 미마스킹 (핫 리로드 반영)
        ingestService.ingest(new IngestRequest(List.of(spanWithSsnPayload("s-after", "t-after"))));
        String rawBody = storedBodyOf("s-after");
        assertNotNull(rawBody);
        assertTrue(rawBody.contains(SSN_RAW), "토글 후 신규 ingest 분은 룰 비활성이 반영돼야 한다 — " + rawBody);

        // 4) 기존 payload 재마스킹 없음 (BL-06): 토글 전 저장분은 그대로 마스킹 상태
        String beforeStill = storedBodyOf("s-before");
        assertNotNull(beforeStill);
        assertFalse(beforeStill.contains(SSN_RAW), "기존 저장 payload 는 재마스킹/복원 경로가 없어야 한다");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /**
     * [Phase R25] AC-25-03-4 — 저장된 본문을 읽는 단일 자리. R25 부터 새 행은 {@code payloads.body} 가
     * 비어 있고 실물은 {@code payload_bodies} 에 있다. 읽기 SQL 은
     * {@code TraceQueryRepository.findPayloads} 와 같은 모양이라 옛 행도 그대로 읽힌다.
     */
    private String storedBodyOf(String spanId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(pb.body, p.body) FROM payloads p "
                        + "LEFT JOIN payload_bodies pb ON pb.body_hash = p.body_hash "
                        + "WHERE p.span_id = ?",
                String.class, spanId);
    }

    private static Span spanWithSsnPayload(String spanId, String traceId) {
        long now = System.currentTimeMillis();
        return new Span(
                spanId, traceId, null,
                "svc", "POST /citizens", SpanKind.SERVER,
                now - 100L, now, SpanStatus.OK,
                null,
                List.of(new Payload(
                        PayloadDirection.IN, "application/json",
                        "{\"ssn\":\"" + SSN_RAW + "\"}",
                        30, false))
        );
    }
}
