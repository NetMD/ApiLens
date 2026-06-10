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
import io.apilens.common.MaskingRule;
import io.apilens.common.MaskingRuleType;
import io.apilens.common.MaskingStrategy;
import io.apilens.server.masking.dto.MaskingRuleDto;
import io.apilens.server.masking.dto.PreviewRequest;
import io.apilens.server.masking.dto.PreviewResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live masking preview — 화면 토글 상태(ruleStates)를 DB 정의에 오버라이드한 임시 엔진으로
 * 계산. holder/DB 상태 무변경 (요청 스코프 인스턴스).
 *
 * <p>// [Phase R12] AC-B3-1/AC-B3-2/AC-B3-3 — D-02 비협상: "목록/토글/추가·삭제 + 라이브
 * // 프리뷰. 결재용 신뢰 도구". 사용자 명시 비협상 결정. 프리뷰는 **서버 공유 엔진 계산**
 * // (FE 재구현 금지 — AC-B3-3): agent/server/프리뷰 3자 동일 엔진.
 * // CLAUDE.md 'UI 디자인 철학' verbatim: "마스킹 라이브 프리뷰 — 룰 토글 시 샘플 페이로드
 * // 즉시 반영. 결재용 신뢰 도구".
 */
@Service
public class MaskingPreviewService {

    /**
     * = agent {@code apilens.payload.max-bytes} 기본값과 동일값 정렬 (Design §3.1.5 — E-05).
     */
    static final int PREVIEW_SAMPLE_MAX_BYTES = 65_536;

    /**
     * AC-B3-2: 서버 내장 기본 샘플 — default 룰 4종(주민번호/카드번호/password/token)이
     * 전부 반응하는 JSON (Design §5.4 예시 그대로).
     */
    static final String DEFAULT_PREVIEW_SAMPLE =
            "{\"ssn\":\"880101-1234567\",\"cardNumber\":\"1234-5678-9012-3456\","
                    + "\"password\":\"hunter2\",\"token\":\"eyJhbGc\"}";

    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final MaskingRuleRepository repository;
    private final ObjectMapper mapper;

    public MaskingPreviewService(MaskingRuleRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public PreviewResponse preview(PreviewRequest request) {
        String sample = resolveSample(request.sample());
        String contentType = (request.contentType() == null || request.contentType().isBlank())
                ? DEFAULT_CONTENT_TYPE
                : request.contentType();

        // DB 정의가 기준 + 화면 토글 상태 오버라이드 (저장 전 상태 — 비협상).
        // 미존재 ruleId 는 무시 (stale 화면 관용 — 다음 invalidate 가 동기화, Design §3.1.5).
        Map<Long, Boolean> overlay = new HashMap<>();
        if (request.ruleStates() != null) {
            for (PreviewRequest.RuleState state : request.ruleStates()) {
                overlay.put(state.ruleId(), state.enabled());
            }
        }
        List<MaskingRule> rules = repository.findAll().stream()
                .map(row -> toEngineRule(row, overlay.getOrDefault(row.ruleId(), row.enabled())))
                .toList();

        // 요청 스코프 임시 인스턴스 — holder 비변경 (DB·전역 상태 부작용 0, T-B3 단언)
        MaskingEngine engine = new MaskingEngine(rules, mapper); // allow: preview 임시 인스턴스 (EXT-008 허용 위치 2/2)
        return new PreviewResponse(sample, engine.mask(sample, contentType), contentType);
    }

    /**
     * E-05 검증: sample null/생략 = 내장 샘플 / 명시됐는데 blank → 400 /
     * UTF-8 64KB 초과 → 400 (경계: 65,536 = 200, 65,537 = 400 — Design §7.1).
     */
    private static String resolveSample(String sample) {
        if (sample == null) {
            return DEFAULT_PREVIEW_SAMPLE;
        }
        if (sample.isBlank()) {
            throw new IllegalArgumentException("sample must not be blank");
        }
        if (sample.getBytes(StandardCharsets.UTF_8).length > PREVIEW_SAMPLE_MAX_BYTES) {
            throw new IllegalArgumentException("sample exceeds " + PREVIEW_SAMPLE_MAX_BYTES + " bytes");
        }
        return sample;
    }

    private static MaskingRule toEngineRule(MaskingRuleDto row, boolean enabled) {
        return new MaskingRule(
                row.name(),
                MaskingRuleType.valueOf(row.ruleType().toUpperCase(Locale.ROOT)),
                row.pattern(),
                MaskingStrategy.valueOf(row.maskStrategy().toUpperCase(Locale.ROOT)),
                enabled
        );
    }
}
