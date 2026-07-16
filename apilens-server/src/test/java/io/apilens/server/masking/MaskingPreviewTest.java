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
import io.apilens.common.MaskingEngine;
import io.apilens.common.RegexTimeoutException;
import io.apilens.server.masking.dto.PreviewRequest;
import io.apilens.server.masking.dto.PreviewResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R12] T-B3 — POST /v1/masking-rules/preview 계산 본체 (Design §7.2).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>D-02: "라이브 프리뷰. 결재용 신뢰 도구" — 프리뷰는 **서버 공유 엔진 계산** (FE 재구현 금지,
 *       AC-B3-3): agent/server/프리뷰 3자 동일 엔진</li>
 *   <li>AC-B3-1: "ruleStates(화면 토글 상태) 오버라이드 = 저장 전 상태 반영" (비협상) —
 *       DB enabled=1 인 룰을 화면 false 로 → 프리뷰 미마스킹</li>
 * </ul>
 *
 * <p>경계 (Design §7.1): sample 65,536 bytes = 200 / 65,537 bytes = 400.
 */
class MaskingPreviewTest {

    private static final String SSN_RAW = "880101-1234567";
    private static final String CARD_RAW = "1234-5678-9012-3456";

    @TempDir
    Path tempDir;
    private JdbcTemplate jdbc;
    private MaskingEngineHolder holder;
    private MaskingPreviewService previewService;

    @BeforeEach
    void setup() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("apilens-preview-test.db").toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        this.jdbc = new JdbcTemplate(ds);

        ObjectMapper mapper = new ObjectMapper();
        MaskingRuleRepository repository = new MaskingRuleRepository(jdbc);
        this.holder = new MaskingEngineHolder(repository, mapper);
        holder.reload();
        this.previewService = new MaskingPreviewService(repository, mapper);
    }

    // ─── AC-B3-2: 내장 기본 샘플 — default 룰 4종 전부 반응 ─────────────────

    @Test
    void masksAllFourDefaultRuleTargetsInBuiltInSample() {
        PreviewResponse response = previewService.preview(new PreviewRequest(null, null, null));

        // sample = 입력 원문 echo (기본 샘플 모드의 Before 표시 성립 — UX §9 요구 ② 채택)
        assertEquals(MaskingPreviewService.DEFAULT_PREVIEW_SAMPLE, response.sample());
        assertEquals("application/json", response.contentType());

        String masked = response.masked();
        // default 4종 전부 반응: 주민번호(partial)/카드번호(partial)/password(full)/token(full)
        assertFalse(masked.contains(SSN_RAW), "주민번호 룰 미반응: " + masked);
        assertFalse(masked.contains(CARD_RAW), "카드번호 룰 미반응: " + masked);
        assertFalse(masked.contains("hunter2"), "password 룰 미반응: " + masked);
        assertFalse(masked.contains("eyJhbGc"), "token 룰 미반응: " + masked);
        // partial 전략 실측 형태 (keep = n/4): 주민번호 14자 → 앞 3자 보존, 카드번호 19자 → 앞 4자 보존
        assertTrue(masked.contains("880***********"), "주민번호 partial 실측 불일치: " + masked);
        assertTrue(masked.contains("1234***************"), "카드번호 partial 실측 불일치: " + masked);
        assertTrue(masked.contains("***"), "full 전략 마커 부재: " + masked);
    }

    @Test
    void echoesProvidedSampleAndContentType() {
        PreviewResponse response = previewService.preview(
                new PreviewRequest("{\"note\":\"no pii here\"}", "application/json", null));
        assertEquals("{\"note\":\"no pii here\"}", response.sample());
        assertEquals("{\"note\":\"no pii here\"}", response.masked(), "룰 비매칭 샘플은 원문 그대로");
    }

    // ─── AC-B3-1 비협상: 화면 토글 상태(ruleStates) 오버라이드 ──────────────

    @Test
    void appliesScreenRuleStatesOverDbStateWithoutSaving() {
        String sample = "{\"ssn\":\"" + SSN_RAW + "\"}";

        // DB 는 주민번호 룰(rule_id=1) enabled=1 그대로 — 화면에서만 false 로 토글한 상태
        PreviewResponse response = previewService.preview(new PreviewRequest(
                sample, null,
                List.of(new PreviewRequest.RuleState(1L, false))));

        assertTrue(response.masked().contains(SSN_RAW),
                "화면 false 오버라이드 = 저장 전 상태 반영 → 프리뷰 미마스킹이어야 한다: " + response.masked());

        // DB 상태는 무변경 (저장 전) — enabled=1 그대로
        Integer dbEnabled = jdbc.queryForObject(
                "SELECT enabled FROM masking_rules WHERE rule_id = 1", Integer.class);
        assertEquals(1, dbEnabled, "프리뷰는 DB 를 변경하지 않아야 한다");
    }

    @Test
    void ignoresUnknownRuleIdsInRuleStates() {
        // 미존재 ruleId = 무시 (stale 화면 관용 — Design §3.1.5 명문). 400 아님.
        PreviewResponse response = previewService.preview(new PreviewRequest(
                "{\"ssn\":\"" + SSN_RAW + "\"}", null,
                List.of(new PreviewRequest.RuleState(999L, false))));
        assertFalse(response.masked().contains(SSN_RAW), "미존재 id 는 무시되고 DB 상태(활성)가 적용돼야 한다");
    }

    // ─── E-05 검증: blank · 크기 경계 (65,536 = 200 / 65,537 = 400) ─────────

    @Test
    void returns400ForBlankSample() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> previewService.preview(new PreviewRequest("   ", null, null)));
        assertEquals("sample must not be blank", e.getMessage());
    }

    @Test
    void acceptsSampleAtExactly65536BytesAndRejects65537() {
        // 경계 입력 표 (Design §7.1) — ASCII 1 byte/char 로 정확 경계 구성
        String at = "a".repeat(65_536);
        assertEquals(at, previewService.preview(new PreviewRequest(at, null, null)).sample());

        String over = "a".repeat(65_537);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> previewService.preview(new PreviewRequest(over, null, null)));
        assertEquals("sample exceeds 65536 bytes", e.getMessage());
    }

    // ─── [Phase R18] AC-02-2 — 프리뷰 ReDoS deadline 초과 → 400 매핑 ─────────

    /**
     * 프리뷰 mask() 가 {@link RegexTimeoutException} 을 던지면 IllegalArgumentException 으로 매핑돼
     * (MaskingRuleController.handleBadRequest 가 400 으로 변환) 고정 문구가 반환된다. 룰저장 400 과 동형.
     *
     * <p>결정적: mockConstruction 으로 preview() 내부의 {@code new MaskingEngine(rules, mapper)} 를
     * 가로채 mask() 를 확정 throw 로 만든다(실제 backtracking 폭발 의존 0 — CI flaky 회피).
     */
    @Test
    void mapsReDoSTimeoutToBadRequest() {
        try (MockedConstruction<MaskingEngine> mocked = Mockito.mockConstruction(MaskingEngine.class,
                (mock, ctx) -> Mockito.when(mock.mask(Mockito.any(), Mockito.any()))
                        .thenThrow(RegexTimeoutException.INSTANCE))) {

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> previewService.preview(new PreviewRequest(
                            "{\"note\":\"evil-redos-input\"}", "application/json", null)));
            assertEquals("preview timed out: pattern too complex for this sample", e.getMessage());
        }
    }

    // ─── 부작용 0: holder/DB 무변경 (요청 스코프 임시 엔진 — EXT-008 허용 위치 2/2) ──

    @Test
    void keepsHolderInstanceAndDbRowsUntouchedAfterPreview() {
        MaskingEngine before = holder.current();
        List<Map<String, Object>> rowsBefore = jdbc.queryForList(
                "SELECT rule_id, enabled FROM masking_rules ORDER BY rule_id");

        previewService.preview(new PreviewRequest(null, null,
                List.of(new PreviewRequest.RuleState(1L, false),
                        new PreviewRequest.RuleState(2L, false))));

        assertSame(before, holder.current(), "프리뷰는 holder 의 엔진 인스턴스를 교체하지 않아야 한다");
        List<Map<String, Object>> rowsAfter = jdbc.queryForList(
                "SELECT rule_id, enabled FROM masking_rules ORDER BY rule_id");
        assertEquals(rowsBefore, rowsAfter, "프리뷰 호출 후 masking_rules 행 무변경");
        assertNotNull(before);
    }
}
