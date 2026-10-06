// [R27/FR-27-01·02·04·05] 대시보드 배선 — 시계 게이트 · 조회 창 · [R28] 두 조회(대표 점 · 목록) · 분기 · 범례 · 초점.
//
// LatencyScatter 는 모의 부품으로 바꿔 받은 props 만 본다(시각화는 시험 대상 아님 · NFR-06).
// ★타입 검사(tsc -b)는 시험 파일을 빼므로 모의 props 불일치는 이 시험의 단언만이 잡는다(EXT-008).
// [R28/AC-28-02-14] 모의는 경로 이름으로 **정확히** 가른다(`/v1/trace-points` · `/v1/traces`) — 조회별 호출 기록과
//   답 차례를 따로 둔다(새 경로가 목록 경로로 세이거나 500 갈래로 떨어지는 일 0 · G-R28-14).
// [R28/AC-28-02-12 · 설계 §4.7] 이 파일에서 지운 시험 2건(표본 호출 수 · 한도 알림 판정)은 대표 점 응답이 차트 입력이
//   되면서 뜻을 잃었다 — U01(차트 입력 = 응답 점) · U07(범례 줄)이 대신한다. 이름은 여기 다시 적지 않는다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   enablesTheClockGateWithOneTraceAndClosesItWhenLiveTurnsOff · closesTheGateWhenPaused · closesTheGateOn401
//   rendersNoChartOnServerError · passesTheSentQueryWindowToTheChart · givesTheListTheListResponseAndTheChartThePoints
//   keepsFiniteWindowValuesWhenLiveTurnsOn
//   [v0.8.0] advancesOnePageAfterTheRangePlusSettleWhenLiveIsOff · keepsTheOldWindowUntilTheNextPageArrives
//   queriesOnceWithTheNewPinWhenTheRangeChanges
//   [v0.8.0] freezesTheChartAndListWhilePausedAndShowsTheLatestOnResume · stopsPagingWhilePausedAndRepinsToNowOnResume
//   unpausesWhenTheRangeChanges
//   [R28] U01 drawsThePointsResponseAsTheChartInput · U03 stopsBothQueriesWhenEitherReturns401
//   U04 doesNotRetryAPoints401WithTheProductionRetryDefault · U05 freezesBothResponsesAndRepinsBothOnResume
//   U06 takesTheChartWindowFromThePointsResponse · U07 showsTheLegendLineWithTotalAndPointCounts
//   U08 showsAndClearsTheReducedNotice · U09 showsTheUnknownWordingWithoutTheReducedFlag
//   U10 keepsThePreviousPointsAndRetriesInFiveSecondsOnBusy · U11 showsTheBusyEmptyStateAndRetriesOnAFirstCall
//   U12 decidesTheEmptyBranchByThePointsTotal · U13 showsOneErrorBoxWhenPointsFail
//   U14 hidesTheLegendWordingWhileTheConditionChanges · U15 keepsTheSlotOrderAndWrapperAcrossLoadingAndReady
//   U16 keepsSearchFocusAndTypedTextAcrossZeroAndResults · U17 sendsQAndStatusOnBothRequests
//   U18 showsTheFilterEmptyWordingForASearchWithNoResults · U19 queriesPointsOnceWithTheOffTimeWhenLiveTurnsOff
//   U20 breaksTheLeftInequalityOnlyUntilTheWiderRangeArrives · U23 announcesStateChangesInTheHiddenStatusOnly
//   U24 movesFocusToTheChartSlotAfterRetryRecovers · U25 movesFocusToTheListSlotAfterListRetryRecovers
//   U26 movesFocusToTheChartSlotWhen401HidesTheFocusedList · U27 pollsLongRangePointsEveryThirtySeconds
//   U28 ignoresThePauseButtonWhileTheConditionChanges · U31 keepsTheOldPageAndWindowWhenAPageAdvanceIsBusy
//   U32 keepsTheReducedMarkWhileBusy
//   ★U14 의 이름은 설계 부록 D 가 정한 이름 그대로다(뜻 = 조건이 바뀐 동안 안내 문구 숨김을 정방향으로 단언).
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Profiler } from 'react';
import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import type { TraceBucket, TracePoint, TracePointsResponse, TraceSummary } from '../types/api';
import type { ChartWindow } from '../components/LatencyScatter';
import type { BucketCounts } from '../lib/chartSample';

interface ChartProps {
  points: ReadonlyArray<TracePoint>;
  bucketCounts?: BucketCounts | null;
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

import { Dashboard } from '../pages/Dashboard';
import { yUpperBound } from '../lib/chartSample';
import { PAGE_SETTLE_MS, SCROLL_LAG_MS, computeScrollWindow } from '../lib/time';

const MIN_1 = 60_000;
const MIN_5 = 300_000;

const SEC = 1_730_000_000_000;

/** 목록 응답의 한 행. */
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

/** 대표 점 응답의 한 점. */
function pt(id: string, startTime: number, durationMs: number, status: 'OK' | 'ERROR' = 'OK'): TracePoint {
  return { traceId: id, rootOperation: `GET /${id}`, startTime, durationMs, status, hasError: status === 'ERROR' };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface Call {
  since: number;
  until: number;
  limit: number;
  q: string | null;
  status: string | null;
  sentAt: number;
}

type Reply = { status: number; body: unknown; delayMs?: number };

/** 점에서 칸(1초)을 만든다 — 행이 든 칸만 · 시작 오름차순. */
function bucketsOf(points: ReadonlyArray<TracePoint>): TraceBucket[] {
  const m = new Map<number, TraceBucket>();
  for (const p of points) {
    const start = Math.floor(p.startTime / 1_000) * 1_000;
    const b = m.get(start) ?? { start, count: 0, errorCount: 0 };
    b.count += 1;
    if (p.status === 'ERROR' || p.hasError) b.errorCount += 1;
    m.set(start, b);
  }
  return [...m.values()].sort((a, b) => a.start - b.start);
}

/** 대표 점 200 응답(기본: 점 수 = 전체 건수 · 줄여 보내지 않음). extra 로 칸을 덮는다(undefined = 칸 없음). */
function pointsOk(points: TracePoint[], extra: Partial<Record<keyof TracePointsResponse, unknown>> = {}, delayMs?: number): Reply {
  const body: Record<string, unknown> = {
    bucketMs: 1_000,
    totalCount: points.length,
    errorCount: points.filter((p) => p.status === 'ERROR' || p.hasError).length,
    errorsReduced: false,
    points,
    buckets: bucketsOf(points),
  };
  for (const [k, v] of Object.entries(extra)) {
    if (v === undefined) delete body[k];
    else body[k] = v;
  }
  return delayMs === undefined ? { status: 200, body } : { status: 200, body, delayMs };
}

/** 목록 200 응답. */
function listOk(traces: TraceSummary[], delayMs?: number): Reply {
  const body = { traces, nextCursor: null };
  return delayMs === undefined ? { status: 200, body } : { status: 200, body, delayMs };
}

/** 대표 점 바쁨 — 서버 모양 그대로(503 + 영어 고정 문장 본문 · 설계 §2.1 · G-R28-16). */
const BUSY: Reply = { status: 503, body: { error: 'trace points query is busy — retry shortly' } };
const UNAUTHORIZED: Reply = { status: 401, body: { error: 'unauthorized' } };
const BOOM: Reply = { status: 500, body: { error: 'boom' } };

interface ApiLog {
  pointsCalls: Call[];
  listCalls: Call[];
}

/** 두 조회는 각자 replies 를 차례로(마지막 것은 반복) 돌려준다 — 경로 정확 일치로 가른다. */
function mockApi(opts: { paused?: boolean; points?: Reply[]; list?: Reply[] }): ApiLog {
  const log: ApiLog = { pointsCalls: [], listCalls: [] };
  const points = opts.points ?? [pointsOk([])];
  const list = opts.list ?? [listOk([])];
  let pi = 0;
  let li = 0;
  const reply = (r: Reply): Promise<Response> => {
    const res = jsonResponse(r.body, r.status);
    if (r.delayMs === undefined) return Promise.resolve(res);
    return new Promise((resolve) => setTimeout(() => resolve(res), r.delayMs));
  };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const u = new URL(url, 'http://localhost');
    const call = (): Call => ({
      since: Number(u.searchParams.get('since')),
      until: Number(u.searchParams.get('until')),
      limit: Number(u.searchParams.get('limit')),
      q: u.searchParams.get('q'),
      status: u.searchParams.get('status'),
      sentAt: Date.now(),
    });
    if (u.pathname === '/v1/maintenance/status') {
      return Promise.resolve(
        jsonResponse({ paused: opts.paused ?? false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1, freePageBytes: 0, optimizeAdvised: false }),
      );
    }
    if (u.pathname === '/v1/trace-points') {
      log.pointsCalls.push(call());
      const r = points[Math.min(pi, points.length - 1)]!;
      pi += 1;
      return reply(r);
    }
    if (u.pathname === '/v1/traces') {
      log.listCalls.push(call());
      const r = list[Math.min(li, list.length - 1)]!;
      li += 1;
      return reply(r);
    }
    if (u.pathname === '/v1/services') {
      return Promise.resolve(jsonResponse({ services: [{ name: 'svc' }] }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
  return log;
}

function renderDashboard(
  search: string,
  opts: { onCommit?: () => void; defaultRetry?: number | false } = {},
): { queryClient: QueryClient; unmount: () => void } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: opts.defaultRetry ?? false, refetchOnWindowFocus: false } },
  });
  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/?${search}`]}>
        <Dashboard />
      </MemoryRouter>
    </QueryClientProvider>
  );
  const { unmount } = render(
    opts.onCommit === undefined ? (
      tree
    ) : (
      <Profiler id="dashboard" onRender={opts.onCommit}>
        {tree}
      </Profiler>
    ),
  );
  return { queryClient, unmount };
}

const lastChart = (): ChartProps => chartSpy.mock.calls.at(-1)![0] as ChartProps;
const ids = (ps: ReadonlyArray<{ traceId: string }>): string[] => ps.map((p) => p.traceId);
const chartSlot = (): HTMLElement => screen.getByTestId('chart-slot');
const listSlot = (): HTMLElement => screen.getByTestId('list-slot');
const legendNotice = (): HTMLElement => screen.getByTestId('legend-notice');
const legendDots = (): HTMLElement => screen.getByTestId('legend-dots');
const announcement = (): HTMLElement => screen.getByTestId('legend-announcement');
const pauseButton = (): HTMLElement => screen.getByRole('button', { name: '차트 일시 정지' });

/** 문구 정본(UC-R28-01 · 03 · 06 · 07) — 숫자는 천 단위 쉼표. */
const UC01 = (n: string, m: string): string => `전체 ${n}건 중 ${m}점 · 칸마다 가장 느린 정상 요청 하나와 오류 전부`;
const UC03 = (n: string, m: string): string => `전체 ${n}건 중 ${m}점 · 칸마다 가장 느린 정상 요청 하나와 오류`;
const UC06 = (n: string, m: string): string => `전체 ${n}건 중 ${m}점 · 서버가 바빠 직전 응답을 보여 주고 있어요`;
const UC07 = '서버가 바빠 차트 점을 잠시 뒤 다시 불러와요';

/** 가짜 시계를 흘린다(act 안 · 응답 처리 · 효과까지). */
async function tick(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

beforeAll(async () => {
  // 지연 로딩 산점도(모의)를 미리 불러 둔다 — 실시간 대기 없는 경계 시험에서 lazy 가 모듈 캐시로 곧장 풀리게.
  await import('../components/LatencyScatter');
});

describe('Dashboard — 흐르는 차트 배선', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    // 사유: 이 describe 는 응답·렌더를 waitFor·findBy 로 기다린다 — 기다리는 동안 가짜 시계가 실시간으로 흘러야 한다(경계 시험은 아래 따로).
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // TZ-24 — 대표 점 1개면 게이트가 열리고 Live 를 끄면 고정 창.
  //   [R28 모의 고침] Live OFF 에서 차트 점 = 대표 점 응답 그대로 · 목록 요청 limit=100(목록 요청으로 단언).
  it('enablesTheClockGateWithOneTraceAndClosesItWhenLiveTurnsOff', async () => {
    const twoInOneSecond = [pt('a', SEC + 100, 10), pt('b', SEC + 200, 20)];
    const { listCalls } = mockApi({ points: [pointsOk([pt('only', SEC, 12)]), pointsOk(twoInOneSecond)] });
    renderDashboard('service=svc&live=true&range=1m');

    await screen.findByTestId('mock-chart');
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(true);
    });

    fireEvent.click(screen.getByRole('switch', { name: 'Live refresh' }));
    await waitFor(() => expect(lastChart().points.length).toBe(2));
    expect(lastChart().window.kind).toBe('fixed');
    expect(listCalls.at(-1)!.limit).toBe(100);
  });

  // TZ-25 — 수신 일시정지면 대표 점이 있어도 게이트 닫힘.
  it('closesTheGateWhenPaused', async () => {
    mockApi({ paused: true, points: [pointsOk([pt('only', SEC, 12)])] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.');
    await screen.findByTestId('mock-chart');
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind).toBe('scroll');
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(false);
    });
  });

  // TZ-26 — 첫 응답 200(1점) → 다음 폴링 401 → 차트 자리 = 401 상자 · 시계 0.
  //   [R28 모의 고침 · CR-28-67] 옛 판정의 「차트 0 이거나 고정 창」 갈래를 지운다 — 그 갈래가 401 가려짐을 초록으로 받아 왔다.
  it('closesTheGateOn401', async () => {
    mockApi({ points: [pointsOk([pt('only', SEC, 12)]), UNAUTHORIZED] });
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
    await waitFor(() => expect(within(chartSlot()).getByText('토큰이 필요해요')).toBeInTheDocument());
    expect(within(chartSlot()).getByRole('button', { name: '설정으로 이동' })).toBeInTheDocument();
    expect(screen.queryByTestId('mock-chart')).not.toBeInTheDocument();
  });

  // TZ-27 — 기존 오류 분기 회귀 가드: 5xx(재시도 1 뒤) → 오류 화면 · 차트 0 → 시계 0.
  it('rendersNoChartOnServerError', async () => {
    mockApi({ points: [BOOM], list: [BOOM] });
    renderDashboard('service=svc&live=true&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000);
    });
    // 전제: 오류 화면이 실제로 보인다(오류 상자는 하나 — 목록도 오류면 목록 자리는 숨김).
    expect(await screen.findByText(/잠시 후 다시 시도해 보세요/)).toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.queryByTestId('mock-chart')).not.toBeInTheDocument();
    expect(chartSpy).not.toHaveBeenCalled();
  });

  // TZ-28 — 가로챈 대표 점 요청의 since·until = 차트가 받은 조회 창 · queryUntil = 보낸 시각(응답 지연을 둔 모의).
  //   [R28 모의 고침] limit 단언은 목록 100 하나로(1m · 1h 모두 — 500 갈래 지움).
  it('passesTheSentQueryWindowToTheChart', async () => {
    const first = mockApi({ points: [pointsOk([pt('only', SEC, 12)], {}, 400)] });
    const view = renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    const sent = first.pointsCalls[0]!;
    const w = lastChart().window;
    expect(w.kind).toBe('scroll');
    if (w.kind !== 'scroll') return;
    expect(w.querySince).toBe(sent.since);
    expect(w.queryUntil).toBe(sent.until);
    expect(w.queryUntil).toBe(sent.sentAt);
    // 도착 시각은 보낸 시각보다 늦다 — queryUntil 이 도착 시각이 아님을 가른다.
    expect(w.anchorAt).toBeGreaterThan(w.queryUntil);
    expect(first.listCalls[0]!.limit).toBe(100);
    view.unmount();

    const hour = mockApi({ points: [pointsOk([pt('only', SEC, 12)])] });
    renderDashboard('service=svc&live=true&range=1h');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(hour.listCalls.length).toBeGreaterThanOrEqual(1));
    expect(hour.listCalls[0]!.limit).toBe(100);
  });

  // TZ-30 → [R28 고쳐 씀 · X-12] 목록 = 목록 응답 · 차트 = 대표 점 응답. yMax = yUpperBound(대표 점).
  //   더함(§2 TZ-30): 새 응답 없이 2초 밀어도 yMax 불변 · Dashboard 렌더 수 불변.
  //   더함(CR-28-44): 같은 2초 동안 차트가 받은 칸 표(bucketCounts)가 같은 객체 — 「응답 한 번에 계산 한 번」 축을 잇는다.
  it('givesTheListTheListResponseAndTheChartThePoints', async () => {
    let commits = 0;
    mockApi({
      points: [pointsOk([pt('slow', SEC + 800, 340)], { totalCount: 2 })],
      list: [listOk([trace('fast', SEC + 100, 30), trace('slow', SEC + 800, 340)])],
    });
    renderDashboard('service=svc&live=true&range=1m', {
      onCommit: () => {
        commits += 1;
      },
    });
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['slow']));
    expect(await screen.findByText('최근 2건')).toBeInTheDocument();
    expect(lastChart().yMax).toBe(yUpperBound(lastChart().points));
    expect(lastChart().yMax).toBe(1_000);

    const yMaxBefore = lastChart().yMax;
    const bucketsBefore = lastChart().bucketCounts;
    const commitsBefore = commits;
    expect(bucketsBefore).not.toBeNull(); // 전제: 칸 표가 실제로 넘어왔다
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(lastChart().yMax).toBe(yMaxBefore);
    expect(lastChart().bucketCounts).toBe(bucketsBefore);
    expect(commits).toBe(commitsBefore);
  });

  // TZ-44 — Live OFF→ON 전환 직후(자리표시 데이터) 차트가 받은 queryUntil · anchorAt 이 유한수.
  it('keepsFiniteWindowValuesWhenLiveTurnsOn', async () => {
    mockApi({ points: [pointsOk([pt('only', SEC, 12)], {}, 300)] });
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

  // [R28/U01 · AC-28-02-1] 차트 입력 = 대표 점 응답의 점(판 셋) — 목록 응답은 차트로 가지 않는다.
  it.each([
    ['1분 Live ON', 'service=svc&live=true&range=1m'],
    ['1시간 Live OFF', 'service=svc&range=1h'],
    ['24시간 Live ON', 'service=svc&live=true&range=24h'],
  ])('drawsThePointsResponseAsTheChartInput — %s', async (_label, search) => {
    const points = [pt('p-ok', SEC + 100, 40), pt('p-err', SEC + 2_100, 900, 'ERROR')];
    mockApi({ points: [pointsOk(points, { totalCount: 57 })], list: [listOk([trace('l-only', SEC + 50, 5)])] });
    renderDashboard(search);
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['p-ok', 'p-err']));
    expect(ids(lastChart().points)).not.toContain('l-only');
  });

  // [R28/U03 · AC-28-02-4 · AC-28-04-3] 둘 중 하나가 401 이면 두 조회 모두 멈추고 401 상자 — 판 셋.
  //   ★Live ON 판은 효과가 Live 를 끄고 두 조회 키가 바뀐 **다음 렌더들에서도** 401 상자가 남는다(authError · 가려짐 0).
  //   호출 수는 판마다 **전체**로도 센다 — Live 를 끈 뒤 바뀐 키로 한 번 더 부르는 갈래까지 잡는다([대표 점, 목록]).
  it.each([
    ['① Live ON 첫 대표 점 호출 401', 'service=svc&live=true&range=1m', [UNAUTHORIZED], [listOk([trace('l', SEC, 5)])], [1, 1]],
    ['② Live ON 응답 하나 뒤 목록 폴링 401', 'service=svc&live=true&range=1m', [pointsOk([pt('p', SEC, 5)])], [listOk([trace('l', SEC, 5)]), UNAUTHORIZED], [2, 2]],
    ['③ Live OFF 대표 점 401', 'service=svc&range=1m', [UNAUTHORIZED], [listOk([trace('l', SEC, 5)])], [1, 1]],
  ] as const)('stopsBothQueriesWhenEitherReturns401 — %s', async (_label, search, points, list, totals) => {
    const log = mockApi({ points: [...points], list: [...list] });
    renderDashboard(search);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    await waitFor(() => expect(within(chartSlot()).getByText('토큰이 필요해요')).toBeInTheDocument());
    const pointsN = log.pointsCalls.length;
    const listN = log.listCalls.length;
    // 효과가 Live 를 끈 뒤(키가 바뀜)에도 — 폴링 · 한 칸 넘김을 넘길 만큼 시계를 흘려 다음 렌더들을 만든다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 10_000);
    });
    expect(screen.getByRole('switch', { name: 'Live refresh' })).toHaveAttribute('aria-checked', 'false');
    expect(log.pointsCalls.length).toBe(pointsN);
    expect(log.listCalls.length).toBe(listN);
    expect([log.pointsCalls.length, log.listCalls.length]).toEqual([...totals]);
    expect(within(chartSlot()).getByText('토큰이 필요해요')).toBeInTheDocument();
    expect(within(chartSlot()).getByRole('button', { name: '설정으로 이동' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.queryByText('Unknown error')).toBeNull();
    expect(screen.queryByTestId('mock-chart')).toBeNull();
    expect(screen.queryByText('No traces in this range')).toBeNull();
  });

  // [R28/U04] 운영 QueryClient 기본값(retry: 1 — App.tsx 와 같음)에서도 대표 점 401 은 다시 부르지 않는다.
  it('doesNotRetryAPoints401WithTheProductionRetryDefault', async () => {
    const log = mockApi({ points: [UNAUTHORIZED] });
    renderDashboard('service=svc&range=1m', { defaultRetry: 1 });
    await waitFor(() => expect(within(chartSlot()).getByText('토큰이 필요해요')).toBeInTheDocument());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    expect(log.pointsCalls.length).toBe(1);
  });

  // [R28/U06 · AC-28-02-5] 차트 창 = 대표 점 응답의 창(판 둘).
  it('takesTheChartWindowFromThePointsResponse — ① 두 조회를 보낸 시각이 다르면 대표 점 쪽', async () => {
    // 목록 첫 호출이 실패해 1초 뒤 다시 보낸다 — 두 조회의 성공 요청을 보낸 시각이 갈린다.
    const log = mockApi({ points: [pointsOk([pt('p', SEC, 12)])], list: [BOOM, listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&live=true&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500); // 목록 재시도 1회(1초 뒤)
    });
    await screen.findByText('최근 1건');
    const pointsSent = log.pointsCalls[0]!;
    const listSent = log.listCalls.at(-1)!;
    expect(listSent.until).not.toBe(pointsSent.until); // 전제: 보낸 시각이 갈렸다
    const w = lastChart().window;
    expect(w.kind === 'scroll' && w.querySince === pointsSent.since && w.queryUntil === pointsSent.until).toBe(true);
  });

  it('takesTheChartWindowFromThePointsResponse — ② Live OFF 넘김에서 대표 점 답만 늦으면 옛 대표 점 창', async () => {
    const log = mockApi({
      points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)], {}, 2_000)],
      list: [listOk([trace('a', SEC, 12)]), listOk([trace('b', SEC, 20)])],
    });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    const firstSince = log.pointsCalls[0]!.since;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 100);
    });
    // 전제: 목록은 새 창 답을 받았고 대표 점은 아직이다.
    await waitFor(() => expect(log.listCalls.length).toBe(2));
    await screen.findByText('GET /b');
    expect(log.pointsCalls.length).toBe(2);
    const w = lastChart().window;
    expect(w.kind === 'fixed' && w.since === firstSince).toBe(true);
  });

  // [R28/U07 · AC-28-02-9] 범례 줄 = 전체 N건 중 M점 · 평소 문구(UC-R28-01) · 숫자 천 단위.
  it('showsTheLegendLineWithTotalAndPointCounts', async () => {
    const points = Array.from({ length: 56 }, (_, i) => pt(`p${String(i).padStart(2, '0')}`, SEC + i * 1_000, 10));
    mockApi({ points: [pointsOk(points, { totalCount: 1_234 })] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(legendNotice()).toHaveTextContent(UC01('1,234', '56')));
    expect(legendNotice()).toBeVisible();
    expect(legendDots()).toBeVisible();
  });

  // [R28/U08 · AC-28-02-10] 줄여 보낸 응답 → 진한 마디 「오류 X건 중 Y점만」 보임 → 거짓 응답 → 사라짐.
  it('showsAndClearsTheReducedNotice', async () => {
    const reduced = [pt('ok', SEC, 10), pt('e1', SEC + 1_000, 900, 'ERROR'), pt('e2', SEC + 2_000, 800, 'ERROR')];
    mockApi({
      points: [
        pointsOk(reduced, { totalCount: 9_000, errorCount: 8_120, errorsReduced: true }),
        pointsOk([pt('ok', SEC, 10)], { totalCount: 40, errorsReduced: false }),
      ],
    });
    renderDashboard('service=svc&live=true&range=1m');
    const mark = await within(legendNotice()).findByText('오류 8,120건 중 2점만');
    expect(mark).toBeVisible();
    expect(legendNotice()).toHaveTextContent('칸마다 가장 느린 정상 요청과 오류를 하나씩');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_500);
    });
    await waitFor(() => expect(legendNotice()).toHaveTextContent(UC01('40', '1')));
    expect(within(legendNotice()).queryByText(/오류 .*건 중 .*점만/)).toBeNull();
  });

  // [R28/U09] 판정 칸이 null · 칸 없음이면 「전부」를 주장하지 않는 문구(UC-R28-03).
  it.each([
    ['null', null],
    ['칸 없음', undefined],
  ] as const)('showsTheUnknownWordingWithoutTheReducedFlag — %s', async (_label, flag) => {
    mockApi({ points: [pointsOk([pt('a', SEC, 10), pt('b', SEC + 1_000, 20)], { totalCount: 7, errorsReduced: flag })] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(legendNotice().textContent).toBe(UC03('7', '2')));
    expect(legendNotice()).toBeVisible();
  });

  // [R28/U12 · AC-28-02-6 · ST-R28-05] 0건 판정 = 대표 점 응답의 전체 건수(판 둘).
  it('decidesTheEmptyBranchByThePointsTotal — ① 대표 점 0 · 목록 1 → 빈 상자 · 목록 숨김', async () => {
    mockApi({ points: [pointsOk([])], list: [listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&live=true&range=1m');
    expect(await within(chartSlot()).findByText('No traces in this range')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(within(listSlot()).queryByText('Recent traces')).toBeNull();
    expect(screen.queryByTestId('mock-chart')).toBeNull(); // 차트 0 → 시계 0(scrollEnabled 거짓 자리)
    expect(legendNotice()).not.toBeVisible();
  });

  it('decidesTheEmptyBranchByThePointsTotal — ② 대표 점 1 · 목록 0 → 산점도 · 「최근 0건」', async () => {
    mockApi({ points: [pointsOk([pt('p', SEC, 12)])], list: [listOk([])] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    expect(await within(listSlot()).findByText('최근 0건')).toBeInTheDocument();
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'scroll' && w.scrollEnabled).toBe(true);
    });
  });

  // [R28/U13 · ST-R28-09] 대표 점만 오류(목록 정상) → 차트 자리에 오류 상자 하나 · 범례 숨김 · 목록은 보임.
  it('showsOneErrorBoxWhenPointsFail', async () => {
    mockApi({ points: [BOOM], list: [listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500); // 대표 점 재시도 1회(1초 뒤)
    });
    expect(await within(chartSlot()).findByRole('alert')).toBeInTheDocument();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(within(listSlot()).getByText('Recent traces')).toBeInTheDocument();
    expect(legendNotice()).not.toBeVisible();
    expect(legendDots()).not.toBeVisible();
  });

  // [R28/U15 · AC-28-03-4 · UXD-19] 첫 응답 전 렌더와 응답 렌더의 래퍼·형제 순서가 같다(같은 요소) · 첫 응답 전 문구 숨김.
  it('keepsTheSlotOrderAndWrapperAcrossLoadingAndReady', async () => {
    mockApi({ points: [pointsOk([pt('p', SEC, 12)], {}, 800)], list: [listOk([trace('l', SEC, 5)], 800)] });
    renderDashboard('service=svc&live=true&range=1m');
    const slotBefore = await screen.findByTestId('chart-slot');
    const searchBefore = screen.getByLabelText('operation 검색');
    const order = (): string[] => {
      const body = slotBefore.parentElement!.parentElement!;
      return [...body.children].map((c) =>
        c.contains(slotBefore) ? 'chart+legend' : c.contains(searchBefore) ? 'filter' : c.getAttribute('data-testid') ?? '?',
      );
    };
    expect(slotBefore).toHaveClass('h-80');
    expect(order()).toEqual(['chart+legend', 'filter', 'list-slot']);
    expect(within(slotBefore).getByRole('status', { name: 'Loading' })).toBeInTheDocument(); // 전제: 응답 전
    expect(legendNotice()).not.toBeVisible();

    await screen.findByTestId('mock-chart');
    expect(screen.getByTestId('chart-slot')).toBe(slotBefore);
    expect(screen.getByLabelText('operation 검색')).toBe(searchBefore);
    expect(order()).toEqual(['chart+legend', 'filter', 'list-slot']);
    expect(slotBefore).toHaveClass('h-80');
  });

  // [R28/U16 · AC-28-03-4 · UF-R28-01] 검색어를 치는 동안 결과 ↔ 0건이 바뀌어도 초점 · 친 글자 그대로(두 방향).
  it.each([
    ['결과 → 0건', true],
    ['0건 → 결과', false],
  ] as const)('keepsSearchFocusAndTypedTextAcrossZeroAndResults — %s', async (_label, startsWithResults) => {
    const withResults = pointsOk([pt('p', SEC, 12)]);
    const zero = pointsOk([]);
    const log = mockApi({ points: startsWithResults ? [withResults, zero] : [zero, withResults] });
    renderDashboard('service=svc&range=1m');
    await waitFor(() => expect(log.pointsCalls.length).toBe(1));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    const input = screen.getByLabelText<HTMLInputElement>('operation 검색');
    input.focus();
    fireEvent.change(input, { target: { value: 'ab' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(350); // 디바운스 뒤 주소로 밀려남 → 새 조건 조회
    });
    // 주소로 밀려난 뒤 더 친 글자.
    fireEvent.change(input, { target: { value: 'abc' } });
    await waitFor(() => expect(log.pointsCalls.some((c) => c.q === 'ab')).toBe(true));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    // 전제: 차트 자리 내용이 실제로 바뀌었다.
    if (startsWithResults) {
      expect(within(chartSlot()).getByText('조건에 맞는 trace 가 없어요. 필터를 확인해 주세요.')).toBeInTheDocument();
    } else {
      expect(within(chartSlot()).getByTestId('mock-chart')).toBeInTheDocument();
    }
    expect(screen.getByLabelText('operation 검색')).toBe(input);
    expect(document.activeElement).toBe(input);
    expect(input.value).toBe('abc');
  });

  // [R28/U17 · AC-28-03-1] 검색어 · 상태를 두 요청 모두에 싣는다 — 지우면 둘 다 q 없음.
  it('sendsQAndStatusOnBothRequests', async () => {
    const log = mockApi({});
    renderDashboard('service=svc&range=1m');
    await waitFor(() => expect(log.pointsCalls.length).toBe(1));
    fireEvent.change(screen.getByLabelText('operation 검색'), { target: { value: 'orders' } });
    await waitFor(() => {
      expect(log.pointsCalls.at(-1)!.q).toBe('orders');
      expect(log.listCalls.at(-1)!.q).toBe('orders');
    });
    fireEvent.click(screen.getByRole('button', { name: 'ERROR' }));
    await waitFor(() => {
      expect(log.pointsCalls.at(-1)!.status).toBe('ERROR');
      expect(log.listCalls.at(-1)!.status).toBe('ERROR');
    });
    expect(log.pointsCalls.at(-1)!.q).toBe('orders');
    fireEvent.click(screen.getByRole('button', { name: '검색어 지우기' }));
    await waitFor(() => {
      expect(log.pointsCalls.at(-1)!.q).toBeNull();
      expect(log.listCalls.at(-1)!.q).toBeNull();
    });
  });

  // [R28/U18 · AC-28-03-2] q 가 걸린 0건 → 필터 0건 문구(T-07).
  it('showsTheFilterEmptyWordingForASearchWithNoResults', async () => {
    const log = mockApi({ points: [pointsOk([])], list: [listOk([])] });
    renderDashboard('service=svc&range=1m&q=nomatch');
    expect(
      await within(chartSlot()).findByText('조건에 맞는 trace 가 없어요. 필터를 확인해 주세요.'),
    ).toBeInTheDocument();
    expect(log.pointsCalls[0]!.q).toBe('nomatch'); // 전제: 검색어가 실제로 실렸다
  });

  // [R28/U20 · AC-28-04-5 · R-5ⓐ] 1분 → 10분 전환 · 대표 점 첫 응답을 늦춘 렌더에서만 왼쪽 부등식이 깨진다(예외 구간 그대로).
  it('breaksTheLeftInequalityOnlyUntilTheWiderRangeArrives', async () => {
    const log = mockApi({
      points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)], {}, 2_000)],
    });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().window.kind).toBe('scroll'));
    fireEvent.click(screen.getByRole('button', { name: '10 min' }));
    await waitFor(() => expect(log.pointsCalls.some((c) => c.until - c.since > 9 * MIN_1)).toBe(true));
    const view = (w: ChartWindow): { viewSince: number; viewUntil: number } => {
      if (w.kind !== 'scroll') throw new Error('scroll expected');
      return computeScrollWindow({ range: w.range, requestedAt: w.queryUntil, nowMs: w.anchorAt, lagMs: SCROLL_LAG_MS });
    };
    await waitFor(() => expect(lastChart().window.range).toBe('10m'));
    const pending = lastChart().window;
    if (pending.kind !== 'scroll') throw new Error('scroll expected');
    expect(pending.querySince).toBeGreaterThan(view(pending).viewSince); // 그 렌더에서 querySince > viewSince
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['b']));
    const settled = lastChart().window;
    if (settled.kind !== 'scroll') throw new Error('scroll expected');
    expect(settled.querySince).toBeLessThanOrEqual(view(settled).viewSince);
    expect(view(settled).viewUntil).toBeLessThanOrEqual(settled.queryUntil);
  });

  // [R28/U23 · UXD-22] 줄여 보냄으로 바뀔 때 숨은 알림 UC-R28-19 · 같은 상태의 다음 응답 → 글 그대로 · 보이는 줄에는 없다.
  it('announcesStateChangesInTheHiddenStatusOnly', async () => {
    const reduced = (n: number): ReturnType<typeof pointsOk> =>
      pointsOk([pt('e', SEC, 900, 'ERROR')], { totalCount: n, errorCount: 700, errorsReduced: true });
    mockApi({ points: [pointsOk([pt('ok', SEC, 10)]), reduced(800), reduced(900)] });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(legendNotice()).toHaveTextContent(UC01('1', '1')));
    expect(announcement()).toHaveTextContent(''); // 평소 첫 응답 = 알림 0
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_500);
    });
    const UC19 = '오류가 많아 칸마다 가장 느린 오류 하나씩만 그렸어요';
    await waitFor(() => expect(announcement()).toHaveTextContent(UC19));
    expect(announcement()).toHaveAttribute('role', 'status');
    expect(within(legendNotice()).queryByText(UC19)).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });
    await waitFor(() => expect(legendNotice()).toHaveTextContent('전체 900건'));
    expect(announcement()).toHaveTextContent(UC19); // 같은 상태가 이어지는 동안 글 그대로
  });

  // [R28/U24 · UF-R28-02] 차트 자리 [Retry] 회복 → 초점 = 차트 자리 래퍼.
  it('movesFocusToTheChartSlotAfterRetryRecovers', async () => {
    mockApi({ points: [BOOM, BOOM, pointsOk([pt('p', SEC, 12)])], list: [listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    const retry = await within(chartSlot()).findByRole('button', { name: 'Retry' });
    retry.focus();
    fireEvent.click(retry);
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(document.activeElement).toBe(chartSlot()));
  });

  // [R28/U25 · UF-R28-03] 목록 자리 [Retry] 회복 → 초점 = 목록 자리 래퍼.
  it('movesFocusToTheListSlotAfterListRetryRecovers', async () => {
    mockApi({ points: [pointsOk([pt('p', SEC, 12)])], list: [BOOM, BOOM, listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&range=1m');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    const retry = await within(listSlot()).findByRole('button', { name: 'Retry' });
    retry.focus();
    fireEvent.click(retry);
    await within(listSlot()).findByText('최근 1건');
    await waitFor(() => expect(document.activeElement).toBe(listSlot()));
  });

  // [R28/U26 · UF-R28-05] 목록 링크에 초점 → 폴링 401 로 목록 자리가 숨겨짐 → 초점 = 차트 자리 래퍼(401 상자를 담음).
  it('movesFocusToTheChartSlotWhen401HidesTheFocusedList', async () => {
    mockApi({
      points: [pointsOk([pt('p', SEC, 12)])],
      list: [listOk([trace('l1', SEC, 5), trace('l2', SEC + 1_000, 6)]), UNAUTHORIZED],
    });
    renderDashboard('service=svc&live=true&range=1m');
    const link = (await within(listSlot()).findAllByRole('link'))[0]!;
    link.focus();
    expect(document.activeElement).toBe(link); // 전제: 초점이 목록 자리 안
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_500);
    });
    await waitFor(() => expect(within(chartSlot()).getByText('토큰이 필요해요')).toBeInTheDocument());
    await waitFor(() => expect(document.activeElement).toBe(chartSlot()));
  });

  // [R28/U32 · AC-28-02-10 · UXD-26] 직전 응답이 줄여 보낸 것 + 바쁨 → 진한 마디를 남긴 바쁨 문구(UC-R28-18) · 숨은 알림 UC-R28-20.
  it('keepsTheReducedMarkWhileBusy', async () => {
    mockApi({
      points: [
        pointsOk([pt('ok', SEC, 10), pt('e', SEC + 1_000, 900, 'ERROR')], { totalCount: 12_345, errorCount: 8_120, errorsReduced: true }),
        BUSY,
      ],
    });
    renderDashboard('service=svc&live=true&range=1m');
    await within(legendNotice()).findByText('오류 8,120건 중 1점만');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_500);
    });
    await waitFor(() =>
      expect(legendNotice()).toHaveTextContent(
        '전체 12,345건 중 2점 · 오류 8,120건 중 1점만 · 서버가 바빠 직전 응답을 보여 주고 있어요',
      ),
    );
    expect(within(legendNotice()).getByText('오류 8,120건 중 1점만')).toBeVisible();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(ids(lastChart().points)).toEqual(['ok', 'e']);
    expect(announcement()).toHaveTextContent('서버가 바빠 직전 응답을 보여 주고 있어요');
  });
});

// [v0.8.0] 사용자 요구(2026-09-27) — Live 를 끄면 선택한 시간마다 차트가 한 칸씩 옆으로 넘어간다.
describe('Dashboard — Live OFF 한 칸 넘김', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    // 사유: 이 describe 도 응답·렌더를 waitFor·findBy 로 기다린다 — 한 칸 넘김의 1 ms 경계는 재지 않는다(경계는 아래 따로).
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('advancesOnePageAfterTheRangePlusSettleWhenLiveIsOff', async () => {
    const { pointsCalls } = mockApi({ points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(pointsCalls.length).toBe(1));
    const first = pointsCalls[0]!;
    expect(first.until - first.since).toBe(MIN_1);
    const w0 = lastChart().window;
    expect(w0.kind === 'fixed' && w0.since === first.since && w0.until === first.until).toBe(true);

    // 한 칸 + 여유가 다 차기 전에는 넘기지 않는다(1분 동안 멈춰 있다).
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS - 1_000);
    });
    expect(pointsCalls.length).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    await waitFor(() => expect(pointsCalls.length).toBe(2));
    // 다음 칸 = 옛 끝에서 시작하는 같은 길이의 창.
    expect(pointsCalls[1]!.since).toBe(first.until);
    expect(pointsCalls[1]!.until).toBe(first.until + MIN_1);
    await waitFor(() => {
      const w = lastChart().window;
      expect(w.kind === 'fixed' && w.since === first.until).toBe(true);
    });
  });

  it('keepsTheOldWindowUntilTheNextPageArrives', async () => {
    const { pointsCalls } = mockApi({
      points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)], {}, 2_000)],
    });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().points.length).toBe(1));
    const first = pointsCalls[0]!;

    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 100);
    });
    await waitFor(() => expect(pointsCalls.length).toBe(2));
    // 새 응답 전: 옛 점이 옛 창에 그대로 — 창만 먼저 넘어가 점이 한쪽으로 쏠리지 않는다.
    const pending = lastChart();
    expect(pending.window.kind === 'fixed' && pending.window.since === first.since).toBe(true);
    expect(pending.points[0]!.traceId).toBe('a');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => {
      const c = lastChart();
      expect(c.points[0]!.traceId).toBe('b');
      expect(c.window.kind === 'fixed' && c.window.since === first.until).toBe(true);
    });
  });

  // [R28 모의 고침] 범위 단추 이름 = 보이는 글자(「Last」 뺌 · UQ-R28-18 답 전 기본값).
  it('queriesOnceWithTheNewPinWhenTheRangeChanges', async () => {
    const { pointsCalls } = mockApi({ points: [pointsOk([pt('a', SEC, 12)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(pointsCalls.length).toBe(1));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });

    fireEvent.click(screen.getByRole('button', { name: '5 min' }));
    await waitFor(() => expect(pointsCalls.length).toBe(2));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    // 옛 고정 시각으로 한 번 더 조회하지 않는다 — 새 범위 · 지금 시각으로 딱 한 번.
    expect(pointsCalls.length).toBe(2);
    expect(pointsCalls[1]!.until - pointsCalls[1]!.since).toBe(MIN_5);
    expect(Math.abs(pointsCalls[1]!.until - pointsCalls[1]!.sentAt)).toBeLessThan(1_000);
  });

  // [R28/U14 · AC-28-02-10 · ST-R28-12 · DD-28-31] 조건을 바꾼 동안 안내 문구 — 판 둘.
  it('hidesTheLegendWordingWhileTheConditionChanges — ① 옛 응답 거짓: 범위 바꿈 동안 숨김 · Live OFF 넘김 동안 유지', async () => {
    const log = mockApi({
      points: [
        pointsOk([pt('a', SEC, 12)]),
        pointsOk([pt('b', SEC, 20)], {}, 2_000), // 한 칸 넘김 답 늦춤
        pointsOk([pt('c', SEC, 30)], {}, 2_000), // 범위 바꿈 답 늦춤
      ],
    });
    renderDashboard('service=svc&range=1m');
    await waitFor(() => expect(legendNotice()).toHaveTextContent(UC01('1', '1')));
    // Live OFF 한 칸 넘김의 이전 응답은 같은 조건 — 답을 기다리는 동안에도 문구 유지.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 100);
    });
    await waitFor(() => expect(log.pointsCalls.length).toBe(2));
    expect(legendNotice()).toBeVisible();
    expect(legendNotice()).toHaveTextContent(UC01('1', '1'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['b']));
    // 범위를 바꾸고 대표 점 답을 늦춤 → 문구 숨김 · 점 범례는 옛 점이 있으니 보임.
    fireEvent.click(screen.getByRole('button', { name: '10 min' }));
    await waitFor(() => expect(log.pointsCalls.length).toBe(3));
    await waitFor(() => expect(legendNotice()).not.toBeVisible());
    expect(legendDots()).toBeVisible();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['c']));
    expect(legendNotice()).toBeVisible();
  });

  it('hidesTheLegendWordingWhileTheConditionChanges — ② 옛 응답 참: 진한 마디만 남고 거짓 응답이 오면 사라짐', async () => {
    const log = mockApi({
      points: [
        pointsOk([pt('ok', SEC, 10), pt('e', SEC + 1_000, 900, 'ERROR')], { totalCount: 900, errorCount: 640, errorsReduced: true }),
        pointsOk([pt('n', SEC, 10)], { errorsReduced: false }, 2_000),
      ],
    });
    renderDashboard('service=svc&range=1m');
    await within(legendNotice()).findByText('오류 640건 중 1점만');
    fireEvent.click(screen.getByRole('button', { name: '10 min' }));
    await waitFor(() => expect(log.pointsCalls.length).toBe(2));
    // 조건 바뀜 동안: 진한 마디(옛 응답 X·Y) 보임 · 규칙 마디 숨김.
    await waitFor(() => expect(within(legendNotice()).getByText(/칸마다 가장 느린 정상 요청과 오류를 하나씩/)).not.toBeVisible());
    expect(within(legendNotice()).getByText('오류 640건 중 1점만')).toBeVisible();
    // 새 응답(거짓) 도착 → 진한 마디 사라짐.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_100);
    });
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['n']));
    expect(within(legendNotice()).queryByText(/오류 .*건 중 .*점만/)).toBeNull();
    expect(legendNotice()).toHaveTextContent(UC01('1', '1'));
  });
});

// [v0.8.0] 사용자 결정(2026-09-27) — 「차트 일시 정지」: 누르면 멈추고, 다시 누르면 지금 시각으로 돌아가
//   그동안 들어온 요청을 모두 보여 준다. [R28] 두 응답(대표 점 · 목록)을 함께 얼린다.
describe('Dashboard — 차트 일시 정지', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    // 사유: 이 describe 도 응답·렌더를 waitFor·findBy 로 기다린다 — 경계 시험이 아니다.
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // [R28 모의 고침 · ST-R28-06] 멈춘 동안 새 응답이 와도 안내 문구의 N 불변(단언 한 줄 더함).
  it('freezesTheChartAndListWhilePausedAndShowsTheLatestOnResume', async () => {
    const { pointsCalls } = mockApi({
      points: [
        pointsOk([pt('first', SEC, 12)]),
        pointsOk([pt('first', SEC, 12), pt('later', SEC + 5_000, 30)]),
      ],
      list: [listOk([trace('first', SEC, 12)]), listOk([trace('first', SEC, 12), trace('later', SEC + 5_000, 30)])],
    });
    renderDashboard('service=svc&live=true&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(lastChart().points.length).toBe(1));
    await screen.findByText('GET /first');

    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/차트를 일시 정지했어요/)).toBeInTheDocument();
    const w = lastChart().window;
    expect(w.kind === 'scroll' && w.scrollEnabled).toBe(false); // 흐름 멈춤

    // 멈춘 동안에도 뒤에서는 계속 받는다 — 화면(차트·목록·범례 N)은 그대로.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6_000);
    });
    await waitFor(() => expect(pointsCalls.length).toBeGreaterThanOrEqual(2));
    expect(ids(lastChart().points)).toEqual(['first']);
    expect(screen.queryByText('GET /later')).not.toBeInTheDocument();
    expect(legendNotice()).toHaveTextContent(UC01('1', '1'));

    // 다시 누르면 지금 시각 · 그동안 받은 요청이 모두 보인다.
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'false');
    await waitFor(() => {
      const c = lastChart();
      expect(ids(c.points).sort()).toEqual(['first', 'later']);
      expect(c.window.kind === 'scroll' && c.window.scrollEnabled).toBe(true);
    });
    expect(screen.getByText('GET /later')).toBeInTheDocument();
  });

  it('stopsPagingWhilePausedAndRepinsToNowOnResume', async () => {
    const { pointsCalls } = mockApi({ points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await waitFor(() => expect(pointsCalls.length).toBe(1));
    await screen.findByText('최근 0건');

    fireEvent.click(pauseButton());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MIN_1 + PAGE_SETTLE_MS + 5_000);
    });
    expect(pointsCalls.length).toBe(1); // 멈춘 동안 넘기지 않는다
    expect(lastChart().points[0]!.traceId).toBe('a');

    fireEvent.click(pauseButton());
    await waitFor(() => expect(pointsCalls.length).toBe(2));
    // 지금 시각으로 다시 고정 — 옛 끝에서 한 칸이 아니라 지금 끝나는 창.
    expect(Math.abs(pointsCalls[1]!.until - pointsCalls[1]!.sentAt)).toBeLessThan(1_000);
    expect(pointsCalls[1]!.until - pointsCalls[1]!.since).toBe(MIN_1);
    await waitFor(() => expect(lastChart().points[0]!.traceId).toBe('b'));
  });

  // [R28 모의 고침] 범위 단추 이름 = 보이는 글자(「Last」 뺌).
  it('unpausesWhenTheRangeChanges', async () => {
    mockApi({ points: [pointsOk([pt('a', SEC, 12)])] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await screen.findByText('최근 0건');
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '5 min' }));
    await waitFor(() => expect(pauseButton()).toHaveAttribute('aria-pressed', 'false'));
    expect(screen.queryByText(/차트를 일시 정지했어요/)).not.toBeInTheDocument();
  });

  // [R28/U05 · AC-28-02-4 · DD-28-16] 두 응답을 함께 얼리고, Live OFF 해제는 두 조회를 함께 지금 시각으로 다시 고정한다.
  it('freezesBothResponsesAndRepinsBothOnResume', async () => {
    const log = mockApi({
      points: [pointsOk([pt('p1', SEC, 12)]), pointsOk([pt('p2', SEC, 20)])],
      list: [listOk([trace('l1', SEC, 12)]), listOk([trace('l2', SEC, 20)])],
    });
    const { queryClient } = renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await screen.findByText('GET /l1');
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'true');

    // 멈춘 동안 두 조회가 새 응답을 받는다(정리 뒤 무효화 흉내) — 차트·목록은 그대로.
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['traces'] });
    });
    await waitFor(() => {
      expect(log.pointsCalls.length).toBe(2);
      expect(log.listCalls.length).toBe(2);
    });
    expect(ids(lastChart().points)).toEqual(['p1']);
    expect(screen.getByText('GET /l1')).toBeInTheDocument();
    expect(screen.queryByText('GET /l2')).toBeNull();

    const resumedAt = Date.now();
    fireEvent.click(pauseButton());
    await waitFor(() => {
      expect(log.pointsCalls.length).toBe(3);
      expect(log.listCalls.length).toBe(3);
    });
    expect(Math.abs(log.pointsCalls[2]!.until - resumedAt)).toBeLessThan(1_000);
    expect(log.listCalls[2]!.until).toBe(log.pointsCalls[2]!.until);
    await waitFor(() => expect(ids(lastChart().points)).toEqual(['p2']));
    expect(screen.getByText('GET /l2')).toBeInTheDocument();
  });

  // [R28/U28 · DD-28-16 · UXD-34] 조건이 바뀐 동안(대표 점 · 목록 둘 중 하나라도 옛 조건) [차트 일시 정지] 누름은 무시한다 — 판 둘.
  it('ignoresThePauseButtonWhileTheConditionChanges — ① 범위 바꿈 · 대표 점 자리표시', async () => {
    const log = mockApi({ points: [pointsOk([pt('a', SEC, 12)]), pointsOk([pt('b', SEC, 20)], {}, 2_000)] });
    renderDashboard('service=svc&range=1m');
    await screen.findByTestId('mock-chart');
    await screen.findByText('최근 0건');
    fireEvent.click(screen.getByRole('button', { name: '10 min' }));
    await waitFor(() => expect(log.pointsCalls.length).toBe(2));
    expect(ids(lastChart().points)).toEqual(['a']); // 전제: 옛 조건 점(자리표시) 동안
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'false');
  });

  it('ignoresThePauseButtonWhileTheConditionChanges — ② 대표 점은 새 조건 · 목록만 자리표시', async () => {
    const log = mockApi({
      points: [pointsOk([pt('a', SEC, 12)])],
      list: [listOk([trace('l1', SEC, 12)]), listOk([trace('l2', SEC, 20)], 2_000)],
    });
    renderDashboard('service=svc&range=1m');
    await screen.findByText('GET /l1');
    fireEvent.click(screen.getByRole('button', { name: '10 min' }));
    await waitFor(() => expect(log.pointsCalls.length).toBe(2));
    await waitFor(() => expect(lastChart().window.kind === 'fixed' && lastChart().window.range === '10m').toBe(true));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(screen.getByText('GET /l1')).toBeInTheDocument(); // 전제: 목록은 아직 옛 조건(자리표시)
    fireEvent.click(pauseButton());
    expect(pauseButton()).toHaveAttribute('aria-pressed', 'false');
  });
});

// [R28 · 부록 D-3 머리 · CR-28-66] 경계 시험 — 실시간 대기 없이 가짜 시계만 흘린다(1 ms 경계 · 「until = 끈 시각」).
//   waitFor·findBy 를 쓰지 않는다 — 503 처리 시각 t0 가 정확해야 4,999 / 5,000 · 29,999 / 30,000 이 선다.
describe('Dashboard — 경계 시험(가짜 시계만)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    chartSpy.mockClear();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** t 시각(가짜 시계)까지 흘린다. */
  async function tickTo(t: number): Promise<void> {
    await tick(t - Date.now());
  }

  // [R28/U10 · AC-28-02-7 · B-15] Live ON 응답 뒤 바쁨 → 오류 화면 0 · 직전 점 · UC-R28-06 · t0+4,999 요청 0 · t0+5,000 요청 1.
  it('keepsThePreviousPointsAndRetriesInFiveSecondsOnBusy', async () => {
    const log = mockApi({ points: [pointsOk([pt('p1', SEC, 12)]), BUSY, pointsOk([pt('p2', SEC, 15)])] });
    renderDashboard('service=svc&live=true&range=1m');
    await tick(50);
    expect(ids(lastChart().points)).toEqual(['p1']); // 전제: 첫 응답이 그려짐
    await tick(5_100);
    expect(log.pointsCalls.length).toBe(2); // 전제: 바쁨이 실제로 한 번 감
    const t0 = log.pointsCalls[1]!.sentAt;
    expect(screen.queryByRole('alert')).toBeNull();
    expect(ids(lastChart().points)).toEqual(['p1']);
    expect(legendNotice().textContent).toBe(UC06('1', '1'));
    await tickTo(t0 + 4_999);
    expect(log.pointsCalls.length).toBe(2);
    await tick(1);
    expect(log.pointsCalls.length).toBe(3);
  });

  // [R28/U11 · AC-28-02-15 · ST-R28-08] 첫 호출 바쁨 → 자리 표시 · UC-R28-07 · 점 범례 숨김 · 5초 뒤 다시 부름 — 판 셋.
  it.each([
    ['① Live OFF 24시간', 'service=svc&range=24h', false],
    ['② Live ON 1분', 'service=svc&live=true&range=1m', false],
    ['③ 수신 일시정지 + Live OFF 24시간', 'service=svc&range=24h', true],
  ] as const)('showsTheBusyEmptyStateAndRetriesOnAFirstCall — %s', async (_label, search, paused) => {
    // 다시 부른 요청은 1초 늦게 답한다 — 그 사이(오류가 비는 순간)에도 바쁨 상태가 이어지는지 함께 본다.
    const log = mockApi({ paused, points: [BUSY, { ...BUSY, delayMs: 1_000 }] });
    renderDashboard(search);
    await tick(0);
    expect(log.pointsCalls.length).toBe(1); // 전제: 바쁨이 실제로 한 번 감
    const t0 = log.pointsCalls[0]!.sentAt;
    await tick(10);
    if (paused) expect(screen.getByText('수신 일시정지 중이라 실시간 갱신을 멈췄어요.')).toBeInTheDocument();
    expect(within(chartSlot()).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(legendNotice().textContent).toBe(UC07);
    expect(legendNotice()).toBeVisible();
    expect(legendDots()).not.toBeVisible();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(announcement()).toHaveTextContent(UC07);
    await tickTo(t0 + 4_999);
    expect(log.pointsCalls.length).toBe(1);
    await tick(1);
    expect(log.pointsCalls.length).toBe(2);
    // [R28 · dev 보강] 다시 부른 요청이 답하기 전에도 자리 표시 · UC-R28-07 · 숨은 알림 글 그대로(5초마다 깜빡임 0).
    await tick(10);
    expect(legendNotice().textContent).toBe(UC07);
    expect(announcement()).toHaveTextContent(UC07);
  });

  // [R28/U19 · AC-28-04-4 · DV-13] Live ON 1분 창을 2분 흘린 뒤 Live 끔 → 대표 점 새 요청 정확히 1 · until = 끈 시각 · 차트 창 = 그 창.
  it('queriesPointsOnceWithTheOffTimeWhenLiveTurnsOff', async () => {
    const log = mockApi({ points: [pointsOk([pt('p', SEC, 12)])], list: [listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&live=true&range=1m');
    await tick(50);
    await tick(2 * MIN_1);
    const before = log.pointsCalls.length;
    expect(before).toBeGreaterThan(1); // 전제: 흐르는 동안 폴링이 돌았다
    const offAt = Date.now();
    await act(async () => {
      fireEvent.click(screen.getByRole('switch', { name: 'Live refresh' }));
    });
    await tick(10);
    expect(log.pointsCalls.length).toBe(before + 1);
    expect(log.pointsCalls.at(-1)!.until).toBe(offAt);
    expect(log.listCalls.at(-1)!.until).toBe(offAt);
    const w = lastChart().window;
    expect(w.kind === 'fixed' && w.until === offAt && w.since === offAt - MIN_1).toBe(true);
    await tick(5_000);
    expect(log.pointsCalls.length).toBe(before + 1); // Live OFF — 폴링 0
  });

  // [R28/U27 · AC-28-02-3 · B-14 · B-15] 24시간 Live ON 대표 점 = 30초 주기(목록은 5초) — 판 둘.
  it('pollsLongRangePointsEveryThirtySeconds — ① 정상: 29,999 ms 요청 0 · 30,000 ms 1 · 목록은 5초', async () => {
    const log = mockApi({ points: [pointsOk([pt('p', SEC, 12)])], list: [listOk([trace('l', SEC, 5)])] });
    renderDashboard('service=svc&live=true&range=24h');
    await tick(0);
    expect(log.pointsCalls.length).toBe(1);
    const t0 = log.pointsCalls[0]!.sentAt;
    await tickTo(t0 + 29_999);
    expect(log.pointsCalls.length).toBe(1);
    expect(log.listCalls.length).toBeGreaterThanOrEqual(6); // 목록은 5초 주기
    await tick(1);
    expect(log.pointsCalls.length).toBe(2);
  });

  it('pollsLongRangePointsEveryThirtySeconds — ② 응답 뒤 바쁨: 다시 부름도 30초 · 직전 점 · UC-R28-06', async () => {
    const log = mockApi({ points: [pointsOk([pt('p', SEC, 12)]), BUSY, pointsOk([pt('q', SEC, 12)])] });
    renderDashboard('service=svc&live=true&range=24h');
    await tick(0);
    const first = log.pointsCalls[0]!.sentAt;
    await tickTo(first + 30_000);
    expect(log.pointsCalls.length).toBe(2); // 전제: 바쁨이 실제로 한 번 감
    const t0 = log.pointsCalls[1]!.sentAt;
    await tick(10);
    expect(ids(lastChart().points)).toEqual(['p']);
    expect(legendNotice().textContent).toBe(UC06('1', '1'));
    await tickTo(t0 + 29_999);
    expect(log.pointsCalls.length).toBe(2);
    await tick(1);
    expect(log.pointsCalls.length).toBe(3);
  });

  // [R28/U31 · AC-28-02-15 · DD-28-32] Live OFF 한 칸 넘김 첫 호출 바쁨 → 옛 점 · 옛 창 · UC-R28-06 · 오류 화면 0 ·
  //   t0+5,000 에 같은 새 고정 시각으로 대표 점 요청 1.
  it('keepsTheOldPageAndWindowWhenAPageAdvanceIsBusy', async () => {
    const log = mockApi({ points: [pointsOk([pt('old', SEC, 12)]), BUSY, pointsOk([pt('new', SEC, 20)], {}, 1_000)] });
    renderDashboard('service=svc&range=1m');
    await tick(50);
    const first = log.pointsCalls[0]!;
    expect(ids(lastChart().points)).toEqual(['old']); // 전제
    await tickTo(first.until + MIN_1 + PAGE_SETTLE_MS + 10);
    expect(log.pointsCalls.length).toBe(2); // 전제: 한 칸 넘김 첫 호출이 바쁨
    const t0 = log.pointsCalls[1]!.sentAt;
    await tick(10);
    expect(ids(lastChart().points)).toEqual(['old']);
    const w = lastChart().window;
    expect(w.kind === 'fixed' && w.since === first.since && w.until === first.until).toBe(true);
    expect(legendNotice().textContent).toBe(UC06('1', '1'));
    expect(screen.queryByRole('alert')).toBeNull();
    await tickTo(t0 + 4_999);
    expect(log.pointsCalls.length).toBe(2);
    await tick(1);
    expect(log.pointsCalls.length).toBe(3);
    expect(log.pointsCalls[2]!.since).toBe(log.pointsCalls[1]!.since);
    expect(log.pointsCalls[2]!.until).toBe(log.pointsCalls[1]!.until);
    // [R28 · dev 보강] 다시 부른 요청이 답하기 전(1초)에도 옛 창 · 바쁨 문구 그대로 — 「다시 받았어요」로 잘못 알리지 않는다.
    await tick(10);
    expect(legendNotice().textContent).toBe(UC06('1', '1'));
    expect(announcement()).toHaveTextContent('서버가 바빠 직전 응답을 보여 주고 있어요');
    // 답이 오면 새 칸으로 넘어가고 평소 문구 · 숨은 알림 UC-R28-22.
    await tick(1_000);
    expect(ids(lastChart().points)).toEqual(['new']);
    const nw = lastChart().window;
    expect(nw.kind === 'fixed' && nw.since === first.until).toBe(true);
    expect(legendNotice().textContent).toBe(UC01('1', '1'));
    expect(announcement()).toHaveTextContent('차트 점을 다시 받았어요');
  });
});
