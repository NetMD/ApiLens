// setup step 누수 회귀 가드 — route 경계에서 step 이 제거되는지 검증.
import { describe, expect, it } from 'vitest';
import { searchAcrossRoutes, withoutRouteLocalParams } from '../lib/routeSearch';

describe('routeSearch', () => {
  it('step 은 제거하고 보존 필터(service/range/live)는 유지', () => {
    const params = new URLSearchParams('step=4&service=my-api&range=1h&live=true');
    const result = searchAcrossRoutes(params);
    expect(result).not.toContain('step');
    expect(result).toContain('service=my-api');
    expect(result).toContain('range=1h');
    expect(result).toContain('live=true');
  });

  it('step 이 없으면 그대로 둔다', () => {
    expect(searchAcrossRoutes(new URLSearchParams('range=1h'))).toBe('range=1h');
  });

  // [R21/AC-02] 'config' 도 route-local — 빠지면 설정 파라미터가 대시보드·설정 URL 로 샌다 (§2.4).
  it('analyze 와 config 는 제거하고 보존 필터(service)는 유지한다', () => {
    const params = new URLSearchParams('analyze=my-api&config=my-api&service=my-api');
    const result = searchAcrossRoutes(params);
    expect(result).not.toContain('analyze');
    expect(result).not.toContain('config');
    expect(result).toContain('service=my-api');
  });

  it('빈 search 는 빈 문자열', () => {
    expect(searchAcrossRoutes(new URLSearchParams(''))).toBe('');
  });

  it('원본 URLSearchParams 는 변형하지 않는다 (불변)', () => {
    const params = new URLSearchParams('step=2&range=1h');
    withoutRouteLocalParams(params);
    expect(params.get('step')).toBe('2');
  });
});
