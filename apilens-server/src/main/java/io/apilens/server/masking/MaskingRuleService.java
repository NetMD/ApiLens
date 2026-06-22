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

import io.apilens.server.masking.dto.CreateMaskingRuleRequest;
import io.apilens.server.masking.dto.MaskingRuleDto;
import io.apilens.server.masking.dto.MaskingRuleListResponse;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Masking rule CRUD business rules: input validation, default-rule protection,
 * and hot reload after every successful mutation.
 *
 * <p>// [Phase R12] AC-B2-1/AC-B2-2 — D-02 비협상 ("마스킹 룰 관리 UI — 목록/토글/추가·삭제").
 * // 사용자 명시 비협상 결정. CLAUDE.md '데이터 모델' verbatim: "default는 비활성만 가능, 삭제 불가".
 */
@Service
public class MaskingRuleService {

    static final int NAME_MAX_LENGTH = 100;
    static final int PATTERN_MAX_LENGTH = 1000;

    private static final Set<String> RULE_TYPES = Set.of("field_name", "regex");
    private static final Set<String> MASK_STRATEGIES = Set.of("full", "partial", "hash", "length_only");

    private final MaskingRuleRepository repository;
    private final MaskingEngineHolder holder;

    public MaskingRuleService(MaskingRuleRepository repository, MaskingEngineHolder holder) {
        this.repository = repository;
        this.holder = holder;
    }

    public MaskingRuleListResponse list() {
        return new MaskingRuleListResponse(repository.findAll());
    }

    /**
     * Custom 룰 생성 — is_default=0 서버 강제 (repository SQL 리터럴).
     * regex 사전 컴파일 (E-04): invalid regex 의 DB 유입 차단 = reload 실패 예방 1차 (Design §6.2).
     */
    public MaskingRuleDto create(CreateMaskingRuleRequest request) {
        String name = requireLength(trimToNull(request.name()), "name", NAME_MAX_LENGTH);
        String pattern = requireLength(trimToNull(request.pattern()), "pattern", PATTERN_MAX_LENGTH);
        String ruleType = requireOneOf(request.ruleType(), "ruleType", RULE_TYPES);
        String maskStrategy = requireOneOf(request.maskStrategy(), "maskStrategy", MASK_STRATEGIES);
        Pattern compiled = compileOrReject(pattern); // 기존 — 구문 오류 차단
        // [Phase K] AC-06-1/AC-06-4 — ReDoS 1차 그물 (R14-D05 비협상, server-only 중기안).
        // RegexComplexityGuard 는 static 유틸이라 생성자 주입 0 → MaskingRuleService 공개 시그니처/생성자
        // 불변 유지(NFR-03 — agent fixture 가 POJO 로 직접 생성, R13 hotfix 287a7e7 회귀 차단).
        // 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' (공유 엔진 일관성·agent 격리) 인용.
        RegexComplexityGuard.rejectIfTooComplex(compiled);
        boolean enabled = request.enabled() == null || request.enabled(); // 생략 시 true (Design §5.3)

        long ruleId = repository.insert(name, ruleType, pattern, maskStrategy, enabled);
        holder.reload(); // mutation 성공 직후 — 이후 ingest 분부터 반영 (BL-06)
        return repository.findById(ruleId)
                .orElseThrow(() -> new IllegalStateException("created rule not found: " + ruleId));
    }

    /**
     * enable/disable 토글 — default 룰도 토글은 허용 (비활성만 가능 = 토글 가능, CLAUDE.md).
     */
    public MaskingRuleDto toggle(long ruleId, boolean enabled) {
        int updated = repository.updateEnabled(ruleId, enabled);
        if (updated == 0) {
            throw new MaskingRuleNotFoundException(ruleId); // E-03 404
        }
        holder.reload();
        return repository.findById(ruleId)
                .orElseThrow(() -> new MaskingRuleNotFoundException(ruleId));
    }

    /**
     * Custom 룰 삭제 — default 는 409 (E-02 확정, Design §2-B2).
     *
     * <p>// [Phase R12] AC-B2-2 — CLAUDE.md '데이터 모델' verbatim: "default는 비활성만 가능,
     * // 삭제 불가" (사용자 명시 비협상 결정).
     */
    public void delete(long ruleId) {
        MaskingRuleDto rule = repository.findById(ruleId)
                .orElseThrow(() -> new MaskingRuleNotFoundException(ruleId));
        if (rule.isDefault()) {
            throw new DefaultRuleProtectedException(ruleId);
        }
        repository.delete(ruleId);
        holder.reload();
    }

    // ── validation helpers ──────────────────────────────────────────────

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireLength(String value, String field, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static String requireOneOf(String value, String field, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(
                    field + " must be one of: " + String.join(", ", allowed.stream().sorted().toList()));
        }
        return value;
    }

    private static Pattern compileOrReject(String pattern) {
        try {
            // [Phase K] 컴파일된 Pattern 반환 — ReDoS 가드(rejectIfTooComplex)가 재컴파일 없이 재사용.
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            // Design §5.3 예시 형태: "pattern is not a valid regex: Unclosed group near index 4"
            throw new IllegalArgumentException("pattern is not a valid regex: " + briefMessage(e));
        }
    }

    private static String briefMessage(PatternSyntaxException e) {
        // PatternSyntaxException#getMessage 는 멀티라인(패턴 원문 + 캐럿) — 첫 줄만 사용
        String message = e.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
