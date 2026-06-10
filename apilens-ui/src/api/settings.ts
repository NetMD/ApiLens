// Phase R12 (FR-B1, AC-B1-1) — settings API 호출 함수 (설계 §5.2 계약 1:1).
//
// [R12] D-05 비협상 — "retention 기본 30일 유지 + 설정 페이지에서 변경 가능 (DB 저장 값이 yml 보다 우선)".
// resolve 는 서버 책임 (SettingsService.resolveRetentionDays) — FE 는 응답값을 그대로 prefill.
import { getJson, putJson } from './client';
import type { SettingsResponse, SettingsUpdateRequest } from '../types/api';

/** GET /v1/settings — 설정 + 마지막 cleanup 시각 (T-10/T-11 데이터 소스). */
export async function getSettings(signal?: AbortSignal): Promise<SettingsResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<SettingsResponse>('/v1/settings', fetchOpts);
}

/**
 * PUT /v1/settings — 설정 갱신 (원자 — 전체 유효 시에만, AC-B1-2 서버 400 거부).
 *
 * 응답 = 갱신 후 GET 과 동일 형태 (설계 §5.2) → setQueryData 로 즉시 캐시 반영 가능.
 */
export async function saveSettings(body: SettingsUpdateRequest): Promise<SettingsResponse> {
  return putJson<SettingsUpdateRequest, SettingsResponse>('/v1/settings', body);
}
