// [Phase R15] AC-B5-1/AC-B5-2 — Dashboard 수신 일시정지 시 Live 폴링 중단 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   stopsPollingWhenPaused — paused=true → Live 여도 traces refetchInterval 비동작(추가 폴링 0)
//   pollsWhenNotPaused — paused=false → Live 시 5초 후 traces 추가 폴링 발생
//   displaysLivePausedReasonWhenPaused — paused=true → T-09 사유 텍스트 노출
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Dashboard } from '../pages/Dashboard';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface FetchLog {
  tracesCalls: number;
}

// status / services / traces fetch mock. paused 분기. traces 호출 횟수 카운트.
function mockApi(paused: boolean): FetchLog {
  const log: FetchLog = { tracesCalls: 0 };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/maintenance/status')) {
      return Promise.resolve(jsonResponse({ paused, pausedAt: paused ? 1_730_000_000_000 : null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0 }));
    }
    if (url.includes('/v1/traces')) {
      log.tracesCalls += 1;
      return Promise.resolve(jsonResponse({ traces: [], nextCursor: null }));
    }
    if (url.includes('/v1/services')) {
      return Promise.resolve(jsonResponse({ services: [{ name: 'svc' }] }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
  return log;
}

function renderDashboard(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/?service=svc&live=true']}>
        <Dashboard />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Dashboard — 수신 일시정지 시 Live 폴링 중단', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('stopsPollingWhenPaused — paused=true 시 Live 여도 5초 경과 후 추가 traces 폴링 0', async () => {
    const log = mockApi(true);
    renderDashboard();

    // 초기 1회 fetch 까지 대기.
    await waitFor(() => expect(log.tracesCalls).toBeGreaterThanOrEqual(1));
    const initial = log.tracesCalls;

    // 5초 + 여유 경과 — paused 라 refetchInterval=false → 추가 폴링 없어야 함.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(7_000);
    });
    expect(log.tracesCalls).toBe(initial);
  });

  it('pollsWhenNotPaused — paused=false 시 Live 5초 후 traces 추가 폴링 발생', async () => {
    const log = mockApi(false);
    renderDashboard();

    await waitFor(() => expect(log.tracesCalls).toBeGreaterThanOrEqual(1));
    const initial = log.tracesCalls;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    expect(log.tracesCalls).toBeGreaterThan(initial);
  });

  it('displaysLivePausedReasonWhenPaused — paused=true 시 Live 정지 사유 텍스트(T-09) 노출', async () => {
    mockApi(true);
    renderDashboard();
    expect(
      await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.'),
    ).toBeInTheDocument();
  });
});
