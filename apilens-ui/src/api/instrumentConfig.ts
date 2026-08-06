// [R21] 원격 계측 설정 API 호출 — R20 기존 endpoint 3종 (이번 라운드 계약 diff 0, FE 호출 층만 신설).
//
// R21/AC-02-8 (Plan verbatim): "새 HTTP 헬퍼 신설 0 — 기존 putJson·getJson·deleteResource +
// buildHeaders 토큰 자동 첨부가 이미 구조로 강제." — 신규 fetch 경로 없음.
import { ApiError, deleteResource, getJson, putJson } from './client';
import type { InstrumentConfigPayload } from '../types/api';

/** R21/AC-02-7 (P-R21-2) — 404 = "지시 없음(정상)". 오류가 아니라 null 값으로 변환한다.
 *  전례 계승: useMaintenanceStatus.ts (실패 → 기본값 폴백) 의 확장형.
 *
 *  "그 경로만 재시도 끔" 의 구조적 성립 (설계 §2.4·§11-2): 404 가 API 층에서 **성공 값(null)** 이
 *  되므로 react-query 관점에서 실패가 아니다 → 재시도 대상 자체가 아니다. query 옵션에 retry
 *  오버라이드가 필요 없고, 전역 `retry: 1`(App.tsx) 은 문자 그대로 diff 0 — G-12 를 옵션이
 *  아니라 구조로 충족한다. 404 아닌 오류(401·5xx)는 전역 재시도 1회 유지(의도 — 일시 오류 흡수). */
export async function getInstrumentConfig(
  name: string,
  signal?: AbortSignal,
): Promise<InstrumentConfigPayload | null> {
  try {
    return await getJson<InstrumentConfigPayload>(configPath(name), fetchOpts(signal));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null; // 지시 없음(정상)
    throw e; // 401·5xx 등은 그대로 — 전역 규약 유지
  }
}

/** PUT — 전체 교체 저장(멱등 upsert, R20 기존). 200 저장 echo / 400 flat. */
export async function putInstrumentConfig(
  name: string,
  payload: InstrumentConfigPayload,
): Promise<InstrumentConfigPayload> {
  return putJson<InstrumentConfigPayload, InstrumentConfigPayload>(configPath(name), payload);
}

/** DELETE — 지시 철회(멱등). 204 (부재여도 204). */
export async function deleteInstrumentConfig(name: string): Promise<void> {
  return deleteResource(configPath(name));
}

function configPath(name: string): string {
  return `/v1/services/${encodeURIComponent(name)}/instrument-config`;
}

// exactOptionalPropertyTypes 정합 — signal 은 정의된 경우만 전달 (getMaintenanceStatus 동형).
function fetchOpts(signal?: AbortSignal): { signal?: AbortSignal } {
  const opts: { signal?: AbortSignal } = {};
  if (signal) {
    opts.signal = signal;
  }
  return opts;
}
