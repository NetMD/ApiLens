// [Phase R15] AC-B5-1/AC-B5-2 — Dashboard 수신 일시정지 시 Live 폴링 중단 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   stopsPollingWhenPaused — paused=true → Live 여도 traces refetchInterval 비동작(추가 폴링 0)
//   pollsWhenNotPaused — paused=false → Live 시 5초 후 traces 추가 폴링 발생
//   displaysLivePausedReasonWhenPaused — paused=true → T-09 사유 텍스트 노출
//
// [R28] 두 조회(대표 점 · 목록)를 따로 센다 — 모의를 경로 이름으로 **정확히** 가른다(AC-28-02-14 · 새 경로가
//   목록 경로 아래로 세이거나 500 갈래로 떨어지는 일 0). 폴링 판정은 조회별로 단언한다.
//   keepsLiveOffPagingWhilePaused — 수신 일시정지 + Live OFF → 한 칸 넘김 요청 1(지금 동작 고정 · ST-R28-11 · U30)
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Dashboard } from '../pages/Dashboard';
import { PAGE_SETTLE_MS } from '../lib/time';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface FetchLog {
  /** 대표 점 조회(`/v1/trace-points`) 호출 수. */
  pointsCalls: number;
  /** 목록 조회(`/v1/traces`) 호출 수. */
  listCalls: number;
}

// status / services / 두 조회 fetch mock. paused 분기. 조회별 호출 횟수를 따로 센다(경로 정확 일치).
function mockApi(paused: boolean): FetchLog {
  const log: FetchLog = { pointsCalls: 0, listCalls: 0 };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const path = new URL(url, 'http://localhost').pathname;
    if (path === '/v1/maintenance/status') {
      return Promise.resolve(jsonResponse({ paused, pausedAt: paused ? 1_730_000_000_000 : null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888, optimizeAdvised: false }));
    }
    if (path === '/v1/trace-points') {
      log.pointsCalls += 1;
      return Promise.resolve(
        jsonResponse({ bucketMs: 1_000, totalCount: 0, errorCount: 0, errorsReduced: false, points: [], buckets: [] }),
      );
    }
    if (path === '/v1/traces') {
      log.listCalls += 1;
      return Promise.resolve(jsonResponse({ traces: [], nextCursor: null }));
    }
    if (path === '/v1/services') {
      return Promise.resolve(jsonResponse({ services: [{ name: 'svc' }] }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
  return log;
}

function renderDashboard(search = 'service=svc&live=true'): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/?${search}`]}>
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

  // [R28/AC-28-02-4 · CR-28-36] Live ON · 503 아닌 응답 판 — 두 조회 모두 추가 폴링 0.
  it('stopsPollingWhenPaused — paused=true 시 Live 여도 5초 경과 후 두 조회 추가 폴링 0', async () => {
    const log = mockApi(true);
    renderDashboard();

    // 초기 1회 fetch 까지 대기(두 조회 각각) — 수신 일시정지 상태가 화면에 닿은 뒤(사유 문구)부터 센다.
    await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.');
    await waitFor(() => {
      expect(log.pointsCalls).toBeGreaterThanOrEqual(1);
      expect(log.listCalls).toBeGreaterThanOrEqual(1);
    });
    const points = log.pointsCalls;
    const list = log.listCalls;

    // 5초 + 여유 경과 — paused 라 refetchInterval=false → 추가 폴링 없어야 함.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(7_000);
    });
    expect(log.pointsCalls).toBe(points);
    expect(log.listCalls).toBe(list);
  });

  it('pollsWhenNotPaused — paused=false 시 Live 5초 후 두 조회 모두 추가 폴링 발생', async () => {
    const log = mockApi(false);
    renderDashboard();

    await waitFor(() => {
      expect(log.pointsCalls).toBeGreaterThanOrEqual(1);
      expect(log.listCalls).toBeGreaterThanOrEqual(1);
    });
    const points = log.pointsCalls;
    const list = log.listCalls;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    expect(log.pointsCalls).toBeGreaterThan(points);
    expect(log.listCalls).toBeGreaterThan(list);
  });

  // [R28/U30 · ST-R28-11] 수신 일시정지와 무관하게 Live OFF 한 칸 넘김은 돈다(09-27 동작 · 변경 0 — 지금 동작 고정).
  it('keepsLiveOffPagingWhilePaused — 수신 일시정지 + Live OFF → 한 칸 넘김 요청 1', async () => {
    const log = mockApi(true);
    renderDashboard('service=svc&range=1m');

    await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.'); // 전제: 수신 일시정지가 화면에 닿음
    await waitFor(() => {
      expect(log.pointsCalls).toBe(1);
      expect(log.listCalls).toBe(1);
    });

    // 한 칸(1분) + 여유가 다 차면 넘긴다 — 두 조회가 같은 고정 시각을 읽어 함께 한 번씩.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000 + PAGE_SETTLE_MS + 500);
    });
    await waitFor(() => {
      expect(log.pointsCalls).toBe(2);
      expect(log.listCalls).toBe(2);
    });
  });

  it('displaysLivePausedReasonWhenPaused — paused=true 시 Live 정지 사유 텍스트(T-09) 노출', async () => {
    mockApi(true);
    renderDashboard();
    expect(
      await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.'),
    ).toBeInTheDocument();
  });
});
