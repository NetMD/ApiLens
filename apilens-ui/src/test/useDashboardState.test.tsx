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
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import type { ReactNode } from 'react';
import { useDashboardState } from '../hooks/useDashboardState';
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
