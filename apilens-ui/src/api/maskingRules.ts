// Phase R12 (FR-B2/B3, AC-B2-1/AC-B3-1) — masking-rules API 호출 함수 (설계 §5.3~5.4 계약 1:1).
//
// [R12] D-02 비협상 — "설정 페이지에 마스킹 룰 관리 UI 포함 — 목록/토글/추가·삭제 + 라이브 프리뷰.
// '결재용 신뢰 도구'". 프리뷰 계산은 서버 공유 엔진 (AC-B3-3 — FE 재구현 금지).
import { deleteResource, getJson, patchJson, postJson } from './client';
import type {
  CreateMaskingRuleRequest,
  MaskingRule,
  MaskingRulesResponse,
  PreviewRequest,
  PreviewResponse,
  ToggleMaskingRuleRequest,
} from '../types/api';

/** GET /v1/masking-rules — 룰 목록 (default+custom 전체. 정렬: is_default DESC, rule_id ASC). */
export async function listMaskingRules(signal?: AbortSignal): Promise<MaskingRulesResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<MaskingRulesResponse>('/v1/masking-rules', fetchOpts);
}

/** POST /v1/masking-rules — custom 룰 생성 (is_default=0 서버 강제). 201 + 생성 룰. */
export async function addMaskingRule(body: CreateMaskingRuleRequest): Promise<MaskingRule> {
  return postJson<CreateMaskingRuleRequest, MaskingRule>('/v1/masking-rules', body);
}

/**
 * PATCH /v1/masking-rules/{id} — enable/disable 토글 (설계 §2-B2 — body 필드 단일명 enabled).
 *
 * 200 + 갱신 룰 / 404 (E-03) / enabled 외 필드 400.
 */
export async function toggleMaskingRule(ruleId: number, enabled: boolean): Promise<MaskingRule> {
  return patchJson<ToggleMaskingRuleRequest, MaskingRule>(
    `/v1/masking-rules/${encodeURIComponent(String(ruleId))}`,
    { enabled },
  );
}

/** DELETE /v1/masking-rules/{id} — custom 룰 삭제. default 는 409 (E-02 → T-25 토스트 2차 방어). */
export async function deleteMaskingRule(ruleId: number): Promise<void> {
  return deleteResource(`/v1/masking-rules/${encodeURIComponent(String(ruleId))}`);
}

/**
 * POST /v1/masking-rules/preview — 라이브 프리뷰 (DB/holder 무변경 — 임시 엔진 계산).
 *
 * R12 (AC-B3-1): "샘플 페이로드 + (저장 전 토글 상태가 반영된) 룰 세트 → 마스킹 결과 반환" (비협상)
 * — 요청 본문에 화면 룰 세트 상태 동봉. AbortSignal 필수 파라미터 (설계 §3.2.2 — latest-wins 취소).
 */
export async function previewMasking(
  body: PreviewRequest,
  signal: AbortSignal,
): Promise<PreviewResponse> {
  return postJson<PreviewRequest, PreviewResponse>('/v1/masking-rules/preview', body, { signal });
}
