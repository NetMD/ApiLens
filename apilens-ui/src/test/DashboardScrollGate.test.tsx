// [R27/FR-27-01·02·04·05] 대시보드 배선 — 시계 게이트 · 조회 창 · 표본 · 한도 알림.
//
// LatencyScatter 는 모의 부품으로 바꿔 받은 props 만 본다(시각화는 시험 대상 아님 · NFR-06).
// ★타입 검사(tsc -b)는 시험 파일을 빼므로 모의 props 불일치는 이 시험의 단언만이 잡는다(EXT-008).
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   enablesTheClockGateWithOneTraceAndClosesItWhenLiveTurnsOff · closesTheGateWhenPaused · closesTheGateOn401
//   rendersNoChartOnServerError · passesTheSentQueryWindowToTheChart · samplesOncePerResponse
//   givesTheListTheRawResponseAndTheChartTheSample · judgesTheLimitNoticeOnTheRawResponseLength
//   keepsFiniteWindowValuesWhenLiveTurnsOn
//   [v0.8.0] advancesOnePageAfterTheRangePlusSettleWhenLiveIsOff · keepsTheOldWindowUntilTheNextPageArrives
//   queriesOnceWithTheNewPinWhenTheRangeChanges
//   [v0.8.0] freezesTheChartAndListWhilePausedAndShowsTheLatestOnResume · stopsPagingWhilePausedAndRepinsToNowOnResume
//   unpausesWhenTheRangeChanges
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Profiler } from 'react';
import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import type { TraceSummary } from '../types/api';
import type { ChartWindow } from '../components/LatencyScatter';

interface ChartProps {
  traces: ReadonlyArray<TraceSummary>;
  window: ChartWindow;
  yMax: number | null;
}

const chartSpy = vi.hoisted(() => vi.fn());
vi.mock('../components/LatencyScatter', () => ({
  LatencyScatter: (props: ChartProps): ReactNode => {
    chartSpy(props);
    return <div data-testid="mock-chart" />;
  },
}));
vi.mock('../lib/chartSample', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../lib/chartSample')>();
  return { ...actual, sampleTracesPerSecond: vi.fn(actual.sampleTracesPerSecond) };
});

import { Dashboard } from '../pages/Dashboard';
import { sampleTracesPerSecond, yUpperBound } from '../lib/chartSample';
import { PAGE_SETTLE_MS } from '../lib/time';

const MIN_1 = 60_000;
const MIN_5 = 300_000;

const SEC = 1_730_000_000_000;

function trace(id: string, startTime: number, durationMs: number, status: 'OK' | 'ERROR' = 'OK'): TraceSummary {
  return {
    traceId: id,
    rootOperation: `GET /${id}`,
    serviceName: 'svc',
    startTime,
    durationMs,
    status,
    spanCount: 1,
    hasError: status === 'ERROR',
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface TracesCall {
  since: number;
  until: number;
  limit: number;
  sentAt: number;
}

type TracesReply = { status: number; body: unknown; delayMs?: number };

/** /v1/traces 는 replies 를 차례로(마지막 것은 반복) 돌려준다. */
function mockApi(opts: { paused?: boolean; replies: TracesReply[] }): { calls: TracesCall[] } {
  const calls: TracesCall[] = [];
  let i = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/maintenance/status')) {
      return Promise.resolve(
        jsonResponse({ paused: opts.paused ?? false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1, freePageBytes: 0 }),
      );
    }
    if (url.includes('/v1/traces')) {
      const u = new URL(url, 'http://localhost');
      calls.push({
        since: Number(u.searchParams.get('since')),
        until: Number(u.searchParams.get('until')),
        limit: Number(u.searchParams.get('limit')),
        sentAt: Date.now(),
      });
      const reply = opts.replies[Math.min(i, opts.replies.length - 1)]!;
      i += 1;
      const res = jsonResponse(reply.body, reply.status);
      if (reply.delayMs === undefined) return Promise.resolve(res);
      return new Promise((resolve) => setTimeout(() => resolve(res), reply.delayMs));
    }
    if (url.includes('/v1/services')) {
      return Promise.resolve(jsonResponse({ services: [{ name: 'svc' }] }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
  return { calls };
}

const ok = (traces: TraceSummary[], delayMs?: number): TracesReply =>
  delayMs === undefined
    ? { status: 200, body: { traces, nextCursor: null } }
    : { status: 200, body: { traces, nextCursor: null }, delayMs };

function renderDashboard(search: string, onCommit?: () => void) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/?${search}`]}>
        <Dashboard />
      </MemoryRouter>
    </QueryClientProvider>
  );
  return render(
    onCommit === undefined ? (
      tree
    ) : (
      <Profiler id="dashboard" onRender={onCommit}>
        {tree}
      </Profiler>
    ),
  );
}

const lastChart = (): ChartProps => chartSpy.mock.calls.at(-1)![0] as ChartProps;

describe('Dashboard — 흐르는 차트 배선', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    vi.mocked(sampleTracesPerSecond).mockClear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // TZ-24 — trace 1건이면 게이트가 열리고 Live 를 끄면 고정 창.
  //   더함(§2 TZ-24): Live OFF 에서 표본 0(점 수 = 응답 수) · 알림 0 · 요청 limit=100.
  it('enablesTheClockGateWithOneTraceAndClosesItWhenLiveTurnsOff', async () => {
    const twoInOneSecond = [trace('a', SEC + 100, 10), trace('b', SEC + 200, 20)];
    const { calls } = mockApi({ replies: [ok([trace('only', SEC, 12)]), ok(twoInOneSecond)] });
    renderDashboard('service=svc&live=true&range=1m');

    await screen.findByTestId('mock-chart');
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(true);
    });

    fireEvent.click(screen.getByRole('switch', { name: 'Live refresh' }));
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(2));
    await waitFor(() => expect(lastChart().traces.length).toBe(2));
    expect(lastChart().window.kind).toBe('fixed');
    expect(calls.at(-1)!.limit).toBe(100);
    expect(screen.queryByTestId('chart-limit-notice')).not.toBeInTheDocument();
  });

  // TZ-25 — 수신 일시정지면 trace 가 있어도 게이트 닫힘.
  it('closesTheGateWhenPaused', async () => {
    mockApi({ paused: true, replies: [ok([trace('only', SEC, 12)])] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.');
    await screen.findByTestId('mock-chart');
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind).toBe('scroll');
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(false);
    });
  });

  // TZ-26 — 첫 응답 200(1건) → 다음 401 → 최종 상태에서 차트 0 이거나 fixed(둘 다 시계 0 · RP-30 ①).
  it('closesTheGateOn401', async () => {
    mockApi({
      replies: [ok([trace('only', SEC, 12)]), { status: 401, body: { error: 'unauthorized' } }],
    });
    renderDashboard('service=svc&live=true&range=1m');
    // 전제: 401 전에 차트가 한 번 그려짐(게이트 열림).
    await screen.findByTestId('mock-chart');
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(true);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    await waitFor(() => {
      const chart = screen.queryByTestId('mock-chart');
      const w = lastChart().window;
      expect(chart === null || w.kind === 'fixed').toBe(true);
    });
  });

  // TZ-27 — 기존 오류 분기 회귀 가드: 5xx(재시도 1 뒤) → 오류 화면 · 차트 0 → 시계 0.
  it('rendersNoChartOnServerError', async () => {
    mockApi({ replies: [{ status: 500, body: { error: 'boom' } }] });
    renderDashboard('service=svc&live=true&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000);
    });
    // 전제: 오류 화면이 실제로 보인다.
    expect(await screen.findByText(/잠시 후 다시 시도해 보세요/)).toBeInTheDocument();
    expect(screen.queryByTestId('mock-chart')).not.toBeInTheDocument();
    expect(chartSpy).not.toHaveBeenCalled();
  });

  // TZ-28 — 가로챈 요청의 since·until = 차트가 받은 조회 창 · queryUntil = 보낸 시각(응답 지연을 둔 모의).
  //   더함(§2 TZ-28): 1m 요청 limit=500 · 1h 요청 limit=100.
  it('passesTheSentQueryWindowToTheChart', async () => {
    const { calls } = mockApi({ replies: [ok([trace('only', SEC, 12)], 400)] });
    const first = renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    const sent = calls[0]!;
    const w = lastChart().window;
    expect(w.kind).toBe('scroll');
    if (w.kind !== 'scroll') return;
    expect(w.querySince).toBe(sent.since);
    expect(w.queryUntil).toBe(sent.until);
    expect(w.queryUntil).toBe(sent.sentAt);
    // 도착 시각은 보낸 시각보다 늦다 — queryUntil 이 도착 시각이 아님을 가른다.
    expect(w.anchorAt).toBeGreaterThan(w.queryUntil);
    expect(sent.limit).toBe(500);
    first.unmount();

    const hour = mockApi({ replies: [ok([trace('only', SEC, 12)])] });
    renderDashboard('service=svc&live=true&range=1h');
    await screen.findByTestId('mock-chart');
    expect(hour.calls[0]!.limit).toBe(100);
  });

  // TZ-29 — 표본 함수 호출 수 = 응답 수(폴링 2회). 전제: 응답이 실제로 2회.
  it('samplesOncePerResponse', async () => {
    const { calls } = mockApi({
      replies: [ok([trace('p1', SEC, 12)]), ok([trace('p2', SEC + 5_000, 15)])],
    });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_500);
    });
    await waitFor(() => expect(lastChart().traces.map((t) => t.traceId)).toEqual(['p2']));
    expect(calls.length).toBe(2);
    expect(vi.mocked(sampleTracesPerSecond)).toHaveBeenCalledTimes(calls.length);
  });

  // TZ-30 — 같은 초 2건 → 차트 1 · 목록 2행 · yMax = yUpperBound(표본).
  //   더함(§2 TZ-30): 새 응답 없이 2초 밀어도 yMax 불변 · Dashboard 렌더 수 불변.
  it('givesTheListTheRawResponseAndTheChartTheSample', async () => {
    let commits = 0;
    const sameSecond = [trace('fast', SEC + 100, 30), trace('slow', SEC + 800, 340)];
    mockApi({ replies: [ok(sameSecond)] });
    renderDashboard('service=svc&live=true&range=1m', () => {
      commits += 1;
    });
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().traces.map((t) => t.traceId)).toEqual(['slow']));
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
    expect(lastChart().yMax).toBe(yUpperBound(lastChart().traces));
    expect(lastChart().yMax).toBe(1_000);

    const yMaxBefore = lastChart().yMax;
    const commitsBefore = commits;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(lastChart().yMax).toBe(yMaxBefore);
    expect(commits).toBe(commitsBefore);
  });

  // TZ-31 — 5m · 한도만큼(같은 초) 응답 → 표본 1점이어도 알림 보임(판정 = 표본 전 길이).
  it('judgesTheLimitNoticeOnTheRawResponseLength', async () => {
    const full = Array.from({ length: 500 }, (_, i) => trace(`t${String(i).padStart(3, '0')}`, SEC + (i % 1_000), 10));
    const { calls } = mockApi({ replies: [ok(full)] });
    renderDashboard('service=svc&live=true&range=5m');
    await screen.findByTestId('mock-chart');
    expect(calls[0]!.limit).toBe(500);
    await waitFor(() => expect(lastChart().traces.length).toBe(1));
    const notice = screen.getByTestId('chart-limit-notice');
    expect(notice).toHaveTextContent('최근 500건까지만 불러와요 — 왼쪽 빈 구간에도 trace 가 있어요');
    expect(notice).toBeVisible();
  });

  // TZ-44 — Live OFF→ON 전환 직후(자리표시 데이터) 차트가 받은 queryUntil · anchorAt 이 유한수.
  it('keepsFiniteWindowValuesWhenLiveTurnsOn', async () => {
    mockApi({ replies: [ok([trace('only', SEC, 12)], 300)] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    expect(lastChart().window.kind).toBe('fixed');
    const before = chartSpy.mock.calls.length;

    fireEvent.click(screen.getByRole('switch', { name: 'Live refresh' }));
    await waitFor(() => expect(lastChart().window.kind).toBe('scroll'));
    const afterSwitch = chartSpy.mock.calls.slice(before).map((c) => (c[0] as ChartProps).window);
    const scrolls = afterSwitch.filter((w) => w.kind === 'scroll');
    expect(scrolls.length).toBeGreaterThanOrEqual(1);
    for (const w of scrolls) {
      if (w.kind !== 'scroll') continue;
      expect(Number.isFinite(w.queryUntil)).toBe(true);
      expect(Number.isFinite(w.anchorAt)).toBe(true);
      expect(Number.isFinite(w.querySince)).toBe(true);
    }
  });
});

// [v0.8.0] 사용자 요구(2026-09-27) — Live 를 끄면 선택한 시간마다 차트가 한 칸씩 옆으로 넘어간다.
describe('Dashboard — Live OFF 한 칸 넘김', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('advancesOnePageAfterTheRangePlusSettleWhenLiveIsOff', async () => {
    const { calls } = mockApi({ replies: [ok([trace('a', SEC, 12)]), ok([trace('b', SEC, 20)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(calls.length).toBe(1));
    const first = calls[0]!;
    expect(first.until - first.since).toBe(MIN_1);
    const w0 = lastChart().window;
    expect(w0.kind === 'fixed' && w0.since === first.since && w0.until === first.until).toBe(true);

    // 한 칸 + 여유가 다 차기 전에는 넘기지 않는다(1분 동안 멈춰 있다).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS - 1_000);
    });
    expect(calls.length).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    await waitFor(() => expect(calls.length).toBe(2));
    // 다음 칸 = 옛 끝에서 시작하는 같은 길이의 창.
    expect(calls[1]!.since).toBe(first.until);
    expect(calls[1]!.until).toBe(first.until + MIN_1);
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'fixed' && w.since === first.until).toBe(true);
    });
  });

  it('keepsTheOldWindowUntilTheNextPageArrives', async () => {
    const { calls } = mockApi({
      replies: [ok([trace('a', SEC, 12)]), ok([trace('b', SEC, 20)], 2_000)],
    });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().traces.length).toBe(1));
    const first = calls[0]!;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 100);
    });
    await waitFor(() => expect(calls.length).toBe(2));
    // 새 응답 전: 옛 점이 옛 창에 그대로 — 창만 먼저 넘어가 점이 한쪽으로 쏠리지 않는다.
    const pending = lastChart();
    expect(pending.window.kind === 'fixed' && pending.window.since === first.since).toBe(true);
    expect(pending.traces[0]!.traceId).toBe('a');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => {
      const c = lastChart();
      expect(c.traces[0]!.traceId).toBe('b');
      expect(c.window.kind === 'fixed' && c.window.since === first.until).toBe(true);
    });
  });

  it('queriesOnceWithTheNewPinWhenTheRangeChanges', async () => {
    const { calls } = mockApi({ replies: [ok([trace('a', SEC, 12)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(calls.length).toBe(1));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Last 5 min' }));
    await waitFor(() => expect(calls.length).toBe(2));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    // 옛 고정 시각으로 한 번 더 조회하지 않는다 — 새 범위 · 지금 시각으로 딱 한 번.
    expect(calls.length).toBe(2);
    expect(calls[1]!.until - calls[1]!.since).toBe(MIN_5);
    expect(Math.abs(calls[1]!.until - calls[1]!.sentAt)).toBeLessThan(1_000);
  });
});

// [v0.8.0] 사용자 결정(2026-09-27) — 「차트 일시 정지」: 누르면 멈추고, 다시 누르면 지금 시각으로 돌아가
//   그동안 들어온 요청을 모두 보여 준다.
describe('Dashboard — 차트 일시 정지', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const pauseButton = () => screen.getByRole('button', { name: '차트 일시 정지' });

  it('freezesTheChartAndListWhilePausedAndShowsTheLatestOnResume', async () => {
    const { calls } = mockApi({
      replies: [ok([trace('first', SEC, 12)]), ok([trace('first', SEC, 12), trace('later', SEC + 5_000, 30)])],
    });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().traces.length).toBe(1));

    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/차트를 일시 정지했어요/)).toBeInTheDocument();
    const w = lastChart().window;
    expect(w.kind === 'scroll' && w.scrollEnabled).toBe(false); // 흐름 멈춤

    // 멈춘 동안에도 뒤에서는 계속 받는다 — 화면(차트·목록)은 그대로.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(2));
    expect(lastChart().traces.map((t) => t.traceId)).toEqual(['first']);
    expect(screen.queryByText('GET /later')).not.toBeInTheDocument();

    // 다시 누르면 지금 시각 · 그동안 받은 요청이 모두 보인다.
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'false');
    await waitFor(() => {
      const c = lastChart();
      expect(c.traces.map((t) => t.traceId).sort()).toEqual(['first', 'later']);
      expect(c.window.kind === 'scroll' && c.window.scrollEnabled).toBe(true);
    });
    expect(screen.getByText('GET /later')).toBeInTheDocument();
  });

  it('stopsPagingWhilePausedAndRepinsToNowOnResume', async () => {
    const { calls } = mockApi({ replies: [ok([trace('a', SEC, 12)]), ok([trace('b', SEC, 20)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(calls.length).toBe(1));

    fireEvent.click(pauseButton());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 5_000);
    });
    expect(calls.length).toBe(1); // 멈춘 동안 넘기지 않는다
    expect(lastChart().traces[0]!.traceId).toBe('a');

    fireEvent.click(pauseButton());
    await waitFor(() => expect(calls.length).toBe(2));
    // 지금 시각으로 다시 고정 — 옛 끝에서 한 칸이 아니라 지금 끝나는 창.
    expect(Math.abs(calls[1]!.until - calls[1]!.sentAt)).toBeLessThan(1_000);
    expect(calls[1]!.until - calls[1]!.since).toBe(MIN_1);
    await waitFor(() => expect(lastChart().traces[0]!.traceId).toBe('b'));
  });

  it('unpausesWhenTheRangeChanges', async () => {
    mockApi({ replies: [ok([trace('a', SEC, 12)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Last 5 min' }));
    await waitFor(() => expect(pauseButton()).toHaveAttribute('aria-pressed', 'false'));
    expect(screen.queryByText(/차트를 일시 정지했어요/)).not.toBeInTheDocument();
  });
});
