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
import io.apilens.server.masking.dto.PreviewRequest;
import io.apilens.server.masking.dto.PreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * {@code /v1/masking-rules} CRUD + 라이브 프리뷰. 에러 응답은 기존 flat 표준
 * {@code { "error": "<message>" }} (Design §5.5). 인증 없음 — 신뢰 네트워크 전제 (NFR-07).
 */
@RestController
@RequestMapping("/v1/masking-rules")
public class MaskingRuleController {

    private final MaskingRuleService service;
    private final MaskingPreviewService previewService;

    public MaskingRuleController(MaskingRuleService service, MaskingPreviewService previewService) {
        this.service = service;
        this.previewService = previewService;
    }

    // [Phase R16] FR-04 — 상위 핵심 @Operation(§4.4 T-10 "마스킹 룰 관리" 묶음 → dev 가 endpoint 별 특화).
    @Operation(summary = "마스킹 룰 목록 조회 (default + custom)")
    @GetMapping
    public MaskingRuleListResponse list() {
        return service.list();
    }

    @Operation(summary = "마스킹 룰 생성 (custom)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaskingRuleDto create(@RequestBody CreateMaskingRuleRequest request) {
        return service.create(request);
    }

    /**
     * 토글 — body 는 {@code { "enabled": boolean }} 단일 필드 (Design §2-B2 식별자 단일명).
     * enabled 외 필드 포함 시 400 — v0.2 는 토글만, 룰 내용 수정(PUT)은 범위 외 확정.
     */
    @Operation(summary = "마스킹 룰 토글 (활성/비활성)")
    @PatchMapping("/{ruleId}")
    public MaskingRuleDto toggle(@PathVariable long ruleId, @RequestBody Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("enabled"))
                || !(body.get("enabled") instanceof Boolean enabled)) {
            throw new IllegalArgumentException("only 'enabled' can be updated in v0.2");
        }
        return service.toggle(ruleId, enabled);
    }

    /**
     * // [Phase R12] AC-B2-2 — CLAUDE.md '데이터 모델' verbatim: "default는 비활성만 가능,
     * // 삭제 불가" (사용자 명시 비협상 결정) — default 는 409 (E-02 확정).
     */
    @Operation(summary = "마스킹 룰 삭제 (custom만 — default는 409)")
    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long ruleId) {
        service.delete(ruleId);
    }

    /**
     * // [Phase R12] AC-B3-1 — D-02 비협상: "결재용 신뢰 도구 — 화면 토글 상태 동봉" (저장 전
     * // 상태 반영). 사용자 명시 비협상 결정. CLAUDE.md 'UI 디자인 철학' (마스킹 라이브 프리뷰) 인용.
     * // 서버 DB 의존 0 (ruleStates 가 화면 상태의 전체 스냅샷) — DB/holder 무변경.
     */
    @Operation(summary = "마스킹 라이브 프리뷰 (저장 전 화면 상태 반영)")
    @PostMapping("/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest request) {
        return previewService.preview(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(MaskingRuleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(MaskingRuleNotFoundException e) {
        // E-03 — 고정 본문 (Design §5.3): { "error": "rule not found" }
        return Map.of("error", "rule not found");
    }

    @ExceptionHandler(DefaultRuleProtectedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleDefaultProtected(DefaultRuleProtectedException e) {
        // E-02 — 고정 본문 (Design §5.3 verbatim)
        return Map.of("error", "default rule cannot be deleted — disable it instead");
    }
}
