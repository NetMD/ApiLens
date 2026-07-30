// [R10] AC-02-2 / AC-02-3 (D-H10-02 경로 B/C 비협상) — useDashboardState 자동 분기 단위.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   autoSelectsServiceWhenExactlyOnePresent — V-USER-R10-05 sign-off (경로 B)
//   skipsAutoSelectWhenMultipleServicesPresent — 다중 환경 보존 (경로 C)
//   skipsAutoSelectWhenServiceAlreadyInUrl — URL state 우선 (SH-06 정합)
//
// 회귀 가드 (반대 방향 lock-in 차단):
//   autoSelectsFirstServiceWhenManyPresent / overridesUrlService 같은 반대 방향 동사 0건
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { parseStatus, useDashboardState } from '../hooks/useDashboardState';
import type { ServiceInfo } from '../types/api';

function makeWrapper(initialPath = '/'): {
  wrapper: ({ children }: { children: ReactNode }) => ReactNode;
  queryClient: QueryClient;
} {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
    },
  });
  const wrapper = ({ children }: { children: ReactNode }): ReactNode => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return { wrapper, queryClient };
}

/** ServiceInfo 객체 minimal factory. */
function svc(name: string): ServiceInfo {
  return {
    name,
    registeredAt: 1716386700000,
    lastSeenAt: 1716386700000,
    source: 'wizard',
    traceCount: 0,
    healthStatus: 'active',
    // [Phase R19] AC-01-4 — ServiceInfo 에 agent 버전 필수 필드가 추가돼 factory 도 함께 맞춘다.
    // ⚠️ 이 파일은 tsconfig.app.json exclude 대상이라 필드를 빠뜨려도 `npm run build` 가 깨지지 않는다
    //    (tsc -b · vitest · eslint 어느 것도 테스트 파일을 타입 검사하지 않음). 손으로 맞춰야 한다.
    agentVersion: null,
  };
}

function mockServicesResponse(services: ServiceInfo[]): ReturnType<typeof vi.spyOn> {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify({ services }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
}

describe('useDashboardState — [R10] D-H10-02 자동 분기', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('autoSelectsServiceWhenExactlyOnePresent — services 정확 1건 + URL 비어 있으면 자동 선택 (경로 B)', async () => {
    mockServicesResponse([svc('my-api')]);
    const { wrapper } = makeWrapper('/');
    const { result } = renderHook(() => useDashboardState(), { wrapper });

    // 초기: service = null (URL 비어 있음)
    expect(result.current.service).toBe(null);
    // services fetch 해소 후 자동 setService('my-api') 호출
    await waitFor(() => {
      expect(result.current.service).toBe('my-api');
    });
  });

  it('skipsAutoSelectWhenMultipleServicesPresent — services 2건 이상 시 자동 선택 안 함 (경로 C)', async () => {
    mockServicesResponse([svc('my-api'), svc('order-service')]);
    const { wrapper } = makeWrapper('/');
    const { result } = renderHook(() => useDashboardState(), { wrapper });

    // 초기: service = null
    expect(result.current.service).toBe(null);
    // fetch 가 해소되어도 service 는 null 유지 (수동 선택 강요)
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.service).toBe(null);
  });

  it('skipsAutoSelectWhenNoServicesPresent — services 0건 시 자동 선택 안 함', async () => {
    mockServicesResponse([]);
    const { wrapper } = makeWrapper('/');
    const { result } = renderHook(() => useDashboardState(), { wrapper });

    expect(result.current.service).toBe(null);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.service).toBe(null);
  });

  it('skipsAutoSelectWhenServiceAlreadyInUrl — URL 에 service 있으면 services 1건이어도 자동 안 함 (SH-06)', async () => {
    mockServicesResponse([svc('other-service')]);
    const { wrapper } = makeWrapper('/?service=existing');
    const { result } = renderHook(() => useDashboardState(), { wrapper });

    // 초기: service = 'existing' (URL 박힘)
    expect(result.current.service).toBe('existing');
    // fetch 해소 후에도 URL 우선 보존 — services[0].name 으로 덮어쓰지 0
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.service).toBe('existing');
  });
});

// ── [R12] FT-C1 — status/q 필터 키 (AC-C1-1/AC-C1-3/AC-C2-3, V-10 가드) ─────────────
//
// AC-C1-1 verbatim: "useDashboardState 에 ?status= 키 (값 OK/ERROR, 전체 = 키 부재 — §9).
// 기존 updateParams(replace:true) 패턴 편승 (G-18) — searchParams 직접 조작 금지, routeSearch
// 불변식 경유 (🔴 제약 ⑤). ROUTE_LOCAL_PARAMS 추가 금지 (= 전 route 보존, G-19)."
// AC-C1-3 verbatim: "FE unit — URL 키 보존/기본값 키 제거 가드 (V-10 가드)."
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   parsesValidStatusFromUrl / normalizesInvalidStatusToAll / removesStatusKeyWhenSetToAll /
//   setsStatusKeyOnUrl / removesQKeyWhenBlank / preservesExistingKeysWhenSettingFilters
// 회귀 가드 (반대 방향 lock-in 차단): dropsServiceKeyOnFilterChange / overridesRangeOnFilter 0건
describe('useDashboardState — [R12] status/q 필터 (D-03 비협상: status + q 만, duration 필터 금지)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockServicesResponse([]);
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** 훅 + 현재 location 동시 관찰 (URL 키 존재/부재 단언용). */
  function useProbe(): { dash: ReturnType<typeof useDashboardState>; search: string } {
    const dash = useDashboardState();
    const location = useLocation();
    return { dash, search: location.search };
  }

  it('parsesValidStatusFromUrl — ?status=ERROR → status="ERROR" (AC-C1-1)', () => {
    const { wrapper } = makeWrapper('/?status=ERROR');
    const { result } = renderHook(() => useProbe(), { wrapper });
    expect(result.current.dash.status).toBe('ERROR');
  });

  it('normalizesInvalidStatusToAll — 무효값(?status=FOO)은 전체(null)로 정규화 (E-12, parseRange 동형)', () => {
    const { wrapper } = makeWrapper('/?status=FOO');
    const { result } = renderHook(() => useProbe(), { wrapper });
    expect(result.current.dash.status).toBe(null);
    // parseStatus 단독 경계: 소문자/공백/null 전부 전체
    expect(parseStatus('ok')).toBe(null);
    expect(parseStatus('')).toBe(null);
    expect(parseStatus(null)).toBe(null);
    expect(parseStatus('OK')).toBe('OK');
  });

  it('setsStatusKeyOnUrl — setStatus("ERROR") → URL ?status=ERROR 반영', () => {
    const { wrapper } = makeWrapper('/');
    const { result } = renderHook(() => useProbe(), { wrapper });
    act(() => result.current.dash.setStatus('ERROR'));
    expect(result.current.dash.status).toBe('ERROR');
    expect(result.current.search).toContain('status=ERROR');
  });

  it('removesStatusKeyWhenSetToAll — setStatus(null) → 키 제거 (전체 = 키 부재) + 기존 service 보존', () => {
    const { wrapper } = makeWrapper('/?status=OK&service=my-api');
    const { result } = renderHook(() => useProbe(), { wrapper });
    act(() => result.current.dash.setStatus(null));
    expect(result.current.dash.status).toBe(null);
    expect(result.current.search).not.toContain('status=');
    expect(result.current.search).toContain('service=my-api'); // V-10 — 기존 키 보존
  });

  it('removesQKeyWhenBlank — setQ 공백 문자열 → 키 제거 (빈 문자열 = 키 부재, AC-C2-3)', () => {
    const { wrapper } = makeWrapper('/?q=OrderApi');
    const { result } = renderHook(() => useProbe(), { wrapper });
    expect(result.current.dash.q).toBe('OrderApi');
    act(() => result.current.dash.setQ('   '));
    expect(result.current.dash.q).toBe('');
    expect(result.current.search).not.toContain('q=');
  });

  it('preservesExistingKeysWhenSettingFilters — status/q 설정이 service/range/live 를 보존 (V-10 가드)', () => {
    const { wrapper } = makeWrapper('/?service=my-api&range=1h&live=true');
    const { result } = renderHook(() => useProbe(), { wrapper });
    act(() => result.current.dash.setStatus('ERROR'));
    act(() => result.current.dash.setQ('OrderApi'));
    expect(result.current.search).toContain('service=my-api');
    expect(result.current.search).toContain('range=1h');
    expect(result.current.search).toContain('live=true');
    expect(result.current.search).toContain('status=ERROR');
    expect(result.current.search).toContain('q=OrderApi');
  });
});
