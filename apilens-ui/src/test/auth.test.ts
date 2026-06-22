// api/auth.ts 단위 테스트 — API Key 토큰 sessionStorage 보관.
//
// 비협상 AC verbatim 인용 (EXT-003 — Plan AC 본문 그대로):
//   AC-04-1: "ApiKeySection 에서 토큰을 입력·저장하면 sessionStorage 에 보관된다(localStorage 미사용)." (비협상)
//
// 정방향 동사 명시 (EXT-003 lock-in 가드 — 반대 방향 동사 hides*/rejects*/denies*/clears* 0건):
//   storesTokenInSessionStorage / readsStoredTokenFromSessionStorage /
//   removesTokenOnClear / usesSessionStorageNotLocalStorage
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { getApiKey, setApiKey, clearApiKey } from '../api/auth';

const KEY = 'apilens.apiKey';

describe('api/auth — API Key sessionStorage 보관 (AC-04-1 비협상)', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });
  afterEach(() => {
    sessionStorage.clear();
  });

  it('storesTokenInSessionStorage — setApiKey 가 sessionStorage 에 토큰을 보관한다 (AC-04-1)', () => {
    setApiKey('secret-token-123');
    expect(sessionStorage.getItem(KEY)).toBe('secret-token-123');
  });

  it('readsStoredTokenFromSessionStorage — getApiKey 가 저장된 토큰을 반환한다', () => {
    sessionStorage.setItem(KEY, 'abc');
    expect(getApiKey()).toBe('abc');
  });

  it('readsNullWhenUnset — 미설정 시 getApiKey 는 null 을 반환한다 (인증 비활성 환경)', () => {
    expect(getApiKey()).toBe(null);
  });

  it('removesTokenOnClear — clearApiKey 후 getApiKey 는 null 이다', () => {
    setApiKey('xyz');
    clearApiKey();
    expect(getApiKey()).toBe(null);
  });

  it('usesSessionStorageNotLocalStorage — 토큰은 sessionStorage 에만 보관되고 localStorage 는 비어 있다 (AC-04-1 localStorage 미사용)', () => {
    setApiKey('only-session');
    expect(sessionStorage.getItem(KEY)).toBe('only-session');
    // localStorage 가 환경에 있을 때만 미사용을 검증 (Node 26 테스트 환경은 localStorage 비활성 — 그 자체로 미사용 보장).
    const ls = (globalThis as { localStorage?: Storage }).localStorage;
    if (ls) {
      expect(ls.getItem(KEY)).toBe(null);
    }
  });
});
