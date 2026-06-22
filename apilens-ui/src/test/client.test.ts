// api/client.ts buildHeaders 단위 테스트 — Authorization: Bearer 조건부 첨부.
//
// 비협상 AC verbatim 인용 (EXT-003 — Plan AC 본문 그대로):
//   AC-04-3: "저장 후 client.ts 의 헤더 첨부 지점이 모든 요청에 Authorization: Bearer <token> 를 조건부로 단다." (US-01 보호 API)
//   AC-01-2: "키 설정 시, Authorization: Bearer <정상토큰> 헤더로 호출하면 200 정상 응답된다." (비협상)
//
// 정방향 동사 명시 (EXT-003 lock-in 가드 — 반대 방향 동사 hides*/rejects*/omits*/strips* 0건):
//   attachesBearerHeaderWhenTokenStored / sendsRequestWithoutAuthHeaderWhenTokenUnset /
//   attachesBearerOnPostJson / attachesBearerOnDeleteResource
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getJson, postJson, deleteResource } from '../api/client';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 마지막 fetch 호출의 Authorization 헤더를 캡처. */
function mockFetchCapturingHeaders(): { authOf: () => string | null } {
  let lastInit: RequestInit | undefined;
  vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
    lastInit = init ?? undefined;
    return Promise.resolve(jsonResponse({ ok: true }));
  });
  return {
    authOf: () => {
      const headers = lastInit?.headers as Record<string, string> | undefined;
      return headers?.Authorization ?? null;
    },
  };
}

describe('api/client — Authorization: Bearer 조건부 첨부 (AC-04-3 / AC-01-2)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('attachesBearerHeaderWhenTokenStored — 토큰 저장 시 getJson 에 Authorization: Bearer 첨부 (AC-04-3)', async () => {
    sessionStorage.setItem('apilens.apiKey', 'tok-1');
    const { authOf } = mockFetchCapturingHeaders();
    await getJson('/v1/traces');
    expect(authOf()).toBe('Bearer tok-1');
  });

  it('sendsRequestWithoutAuthHeaderWhenTokenUnset — 토큰 미설정 시 Authorization 미첨부 (무인증 환경 호환)', async () => {
    const { authOf } = mockFetchCapturingHeaders();
    await getJson('/v1/traces');
    expect(authOf()).toBe(null);
  });

  it('attachesBearerOnPostJson — postJson 에도 Authorization: Bearer 첨부 (AC-04-3 모든 요청)', async () => {
    sessionStorage.setItem('apilens.apiKey', 'tok-post');
    const { authOf } = mockFetchCapturingHeaders();
    await postJson('/v1/maintenance/optimize', {});
    expect(authOf()).toBe('Bearer tok-post');
  });

  it('attachesBearerOnDeleteResource — deleteResource 에도 Authorization: Bearer 첨부 (보호 경로 DELETE)', async () => {
    sessionStorage.setItem('apilens.apiKey', 'tok-del');
    const { authOf } = mockFetchCapturingHeaders();
    await deleteResource('/v1/services/foo');
    expect(authOf()).toBe('Bearer tok-del');
  });
});
