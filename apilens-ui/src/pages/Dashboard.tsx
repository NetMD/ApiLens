// 대시보드 메인 화면 — Header + LatencyScatter + TraceList.
//
// queryKey 공유: LatencyScatter와 TraceList는 같은 queryKey로 dedupe되어 한 번만 호출.
// service==null 이면 traces query disable (BL-05).
//
// [R12] D-03 비협상 — 필터는 status + operation 검색(q)만. duration 필터 추가 금지.
// 필터는 listTraces 쿼리 파라미터로 적용 → LatencyScatter 와 TraceList 가 동시 필터됨 (의도된
// 동작 — "에러만 보기" 시 산점도도 에러만, UX §3.5).
import type { ReactNode } from 'react';
import { Suspense, lazy, useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ApiError } from '../api/client';
import { Header } from '../components/Header';
import { ScatterLegend } from '../components/ScatterLegend';
import { TraceList } from '../components/TraceList';
import { TraceFilterBar } from '../components/TraceFilterBar';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingSkeleton } from '../components/LoadingSkeleton';
import { useDashboardState } from '../hooks/useDashboardState';
import { useMaintenanceStatus } from '../hooks/useMaintenanceStatus';
import { listServices, listTraces } from '../api/traces';
import {
  LIVE_POLL_MS,
  SCROLL_LAG_MS,
  advancePinnedUntil,
  computeScrollWindow,
  computeWindow,
  isClockRange,
  nextPageAt,
} from '../lib/time';
import type { RangePreset } from '../lib/time';
import { chartLimitNotice, sampleTracesPerSecond, yUpperBound } from '../lib/chartSample';
import type { TracesResponse } from '../types/api';
import type { ChartWindow } from '../components/LatencyScatter';

const TRACES_LIMIT = 100;
// [R27/RP-05] Live ON · 1m·5m·10m 에서만 500(서버 MAX_LIMIT 과 같음 · 서버 무접촉).
//   1h 이상과 Live OFF 는 위 100 그대로다(UA-R27-1 「1h 이상은 최신 N 유지」 · FR-27-01 「Live OFF 지금 그대로」).
const TRACES_LIMIT_SCROLL = 500;

/** [R27/FR-27-04] 화면 안 전용 — 응답에 그 요청이 실제로 쓴 조회 창을 붙인다(서버 응답 형식 불변). */
type DashboardTracesData = TracesResponse & {
  requestWindow: { since: number; until: number };
  /** [v0.8.0] 이 응답을 부른 조건 — Live OFF 차트 창을 「응답과 같은 창」으로 그릴 때 대조한다. */
  requestLive: boolean;
  requestRange: RangePreset;
};

// [R21/AC-04-3] 차트(recharts) 지연 마운트 — 공동 확정 (UX §10 + 설계 §9.2). 판단 사유:
// 라우트 lazy 만으로는 recharts 가 Dashboard 청크 안에 남아 첫 화면(/) 로드가 recharts 파싱을
// 포함하므로, 차트 분리가 첫 화면 이득을 완성한다 — 자리 표시 규격(차트 스켈레톤)이 이미 있어
// 수용 비용이 낮다. ScatterLegend 는 recharts 무관이라 eager 유지 (GT-4).
const LatencyScatter = lazy(() =>
  import('../components/LatencyScatter').then((m) => ({ default: m.LatencyScatter })),
);

export function Dashboard(): ReactNode {
  const { service, range, live, status, q, setService, setRange, setLive, setStatus, setQ } =
    useDashboardState();

  // 시간 윈도우 처리:
  //   Live ON  → [R27] queryFn 이 요청을 보내는 순간 F 를 읽고 computeScrollWindow 로 조회 창을 낸다.
  //              표시 창은 차트 안 프레임 시계가 같은 함수로 계산한다(이 페이지는 5초 주기로만 다시 그린다).
  //   Live OFF → pinnedUntil 사용 (range 선택/Live OFF 토글 시점에 freeze)
  //              [v0.8.0] 그 뒤 선택한 시간마다 한 칸씩 앞으로 넘긴다(아래 타이머).
  // queryKey에 시간을 직접 넣지 않는다 — 매 호출마다 키 바뀌면 캐시 의미 없음.
  //   [v0.8.0] 단 Live OFF 의 pinnedUntil 은 한 칸 넘길 때만 바뀌므로 키에 넣는다(넘기면 새로 조회).
  const [pinnedUntil, setPinnedUntil] = useState<number>(() => Date.now());
  // range·live 가 바뀐 그 렌더에서 곧바로 다시 고정한다 — 효과로 미루면 옛 고정 시각으로 한 번 더 조회한다.
  const [pinnedFor, setPinnedFor] = useState({ range, live });
  if (pinnedFor.range !== range || pinnedFor.live !== live) {
    setPinnedFor({ range, live });
    setPinnedUntil(Date.now());
  }

  // [v0.8.0] 차트 일시 정지 — 누른 순간의 응답(frozen)을 차트와 목록에 그대로 보여 준다.
  //   Live ON 은 뒤에서 조회를 계속하고, Live OFF 는 한 칸 넘김을 멈춘다. 다시 누르면 지금 시각으로 돌아간다
  //   (Live ON = 최신 응답 · Live OFF = 지금 시각으로 다시 고정해 새로 조회) — 그동안 들어온 요청이 모두 보인다.
  //   서비스·범위·Live·필터를 바꾸면 풀린다(멈춘 응답이 새 조건과 맞지 않는다).
  const [chartPaused, setChartPaused] = useState(false);
  const [frozen, setFrozen] = useState<{ data: DashboardTracesData; updatedAt: number } | null>(null);
  const viewKey = JSON.stringify([service, range, live, status, q]);
  const [pausedFor, setPausedFor] = useState(viewKey);
  if (pausedFor !== viewKey) {
    setPausedFor(viewKey);
    setChartPaused(false);
    setFrozen(null);
  }

  // 서비스 목록 — 빈 상태 분기용 (헤더 ServiceSelector도 같은 queryKey로 dedupe)
  const servicesQuery = useQuery({
    queryKey: ['services'],
    queryFn: ({ signal }) => listServices(signal),
    staleTime: 30_000,
    retry: 1,
  });

  // [Phase K] (US-05, AC-05-1) — 401 무한루프 차단 wiring (설계 §2.6e / GT-8 / BL-13).
  // AC-05-1 verbatim: "401 수신 시 Live 폴링(Dashboard 5초)·자동 재조회가 중단된다(401 무한루프 차단)." (비협상)
  // 사용자 명시 비협상 결정 (R14-D02 인증 = API Key 헤더 토큰). CLAUDE.md '아키텍처 핵심 원칙'.
  // auth401 = 마지막 tracesQuery 에러가 401 인지. enabled 에 반영해 자동 재조회 차단 + live 강제 off.
  const [auth401, setAuth401] = useState(false);

  // [Phase R15] AC-B5-1 — 수신 일시정지 중이면 Live 폴링 무의미(새 데이터 0) → refetchInterval 조건부 중단.
  // 사용자 명시 비협상 결정(D05 수동 재개 / D06 정리 미강제). CLAUDE.md '아키텍처 핵심 원칙' (수신 일시정지 단일 기능).
  // 공유 queryKey ['maintenance','status'] — 배너·배지와 동기. 폴링만 멈추고 Live 토글 컨트롤 자체는 enabled 유지.
  const { paused } = useMaintenanceStatus();

  // [R12] AC-C1-2/AC-C2-3 — queryKey 에 status/q 포함 (캐시 분리) + listTraces 전달.
  // placeholderData: keepPreviousData 채택 (UX §5.3 — 세그먼트 전환 시 스켈레톤 깜빡임 방지).
  const clockRange = isClockRange(range);
  const limit = live && clockRange ? TRACES_LIMIT_SCROLL : TRACES_LIMIT;
  const tracesQuery = useQuery({
    queryKey: ['traces', { service, range, live, status, q, limit, pinned: live ? null : pinnedUntil }] as const,
    queryFn: async ({ signal }): Promise<DashboardTracesData> => {
      // [R27/RP-08] 두 갈래 모두 그 요청의 조회 창을 응답에 싣는다(anchorAt·queryUntil 이 늘 유한수).
      let since: number;
      let until: number;
      if (live) {
        // ★순서 의존: 조회 창은 요청을 보내는 순간 F 로 먼저 정한다 — 도착 시각을 F 로 쓰면
        //   오른쪽 부등식(표시 ≤ 조회)이 응답 시간만큼 깨진다.
        const requestedAt = Date.now();
        const w = computeScrollWindow({ range, requestedAt, nowMs: requestedAt, lagMs: SCROLL_LAG_MS });
        since = w.querySince;
        until = w.queryUntil;
      } else {
        ({ since, until } = computeWindow({ range, live: false, pinnedUntil, now: pinnedUntil }));
      }
      const res = await listTraces(
        {
          ...(service !== null ? { service } : {}),
          since,
          until,
          ...(status !== null ? { status } : {}),
          ...(q.trim() !== '' ? { q } : {}),
          limit,
        },
        signal,
      );
      return { ...res, requestWindow: { since, until }, requestLive: live, requestRange: range };
    },
    // [Phase K] (US-05, AC-05-1): 401 수신 시 enabled=false → 자동 재조회 차단 (무한루프 0).
    enabled: service !== null && !auth401,
    staleTime: 2_000,
    // [Phase K] (US-05, AC-05-1): 401 은 토큰 재입력으로만 해소 → 자동 retry 금지 (재시도 0).
    retry: (failureCount, err) => !(err instanceof ApiError && err.status === 401) && failureCount < 1,
    refetchOnWindowFocus: false,
    // [Phase K] (US-05, AC-05-1): 401 이면 live 여도 폴링 중단 (refetchInterval false).
    // [Phase R15] AC-B5-1: 수신 일시정지(paused) 중에도 폴링 중단(새 데이터 0). 사용자 명시 비협상 결정(D05/D06). CLAUDE.md '아키텍처 핵심 원칙'.
    refetchInterval: live && !auth401 && !paused ? LIVE_POLL_MS : false,
    refetchIntervalInBackground: false,
    placeholderData: keepPreviousData,
  });

  // [v0.8.0] Live OFF 한 칸 넘김 — 다음 칸이 다 차고 여유(PAGE_SETTLE_MS)까지 지나면 고정 시각을 한 칸 옮긴다.
  //   401 이면 넘기지 않는다(자동 재조회 0 · AC-05-1). 탭이 뒤에 있어 늦게 돌면 다 찬 칸만큼 한 번에 건너뛴다.
  useEffect(() => {
    if (live || auth401 || chartPaused) return;
    const timer = setTimeout(
      () => setPinnedUntil((p) => advancePinnedUntil(p, range, Date.now())),
      Math.max(0, nextPageAt(pinnedUntil, range) - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [live, auth401, chartPaused, range, pinnedUntil]);

  // [Phase K] (US-05, AC-05-1): tracesQuery 에러가 401 로 바뀌면 auth401 ON + Live 강제 off (폴링 중단).
  //   401 해소(토큰 재입력 후 사용자가 다시 진입/refetch)는 ErrorState '설정으로 이동' → /settings 흐름.
  useEffect(() => {
    if (tracesQuery.error instanceof ApiError && tracesQuery.error.status === 401) {
      setAuth401(true);
      if (live) setLive(false);
    }
  }, [tracesQuery.error, live, setLive]);

  // [R12] 🔴 회귀 가드 — LatencyScatter X축 도메인은 computeWindow 시간 윈도우 고정 유지 (diff 0).
  // 필터(status/q)는 since/until 에 무관여 — 점이 줄어도 축은 윈도우 그대로 (자동스케일 회귀 금지).
  // Latency 산점도 X축 도메인 = 선택한 시간 윈도우 (고정). XAxis 가 dataMin/dataMax 로
  // 자동 스케일하면 매 polling 마다 이상치(오래된/시계 어긋난 trace)에 축이 출렁여(rubber-band)
  // 점이 몰렸다 퍼졌다 한다. 윈도우로 고정하면 점이 진짜 시각 위치에 안정적으로 박힌다.
  // Live 면 until = 마지막 fetch 시각(dataUpdatedAt)으로 데이터와 정렬 (Date.now() per-render jitter 회피).
  // [R27/FR-27-04] Live 의 until 은 이제 프레임 시계(now − lag) — 조회 창은 보낸 시각 F 에서 같은 함수로
  //   계산하고, 두 부등식(조회 ⊇ 표시)이 식으로 선다. 위 옛 문장은 이력으로 둔다. Live OFF 는 아래 fixed 창.
  const data = chartPaused && frozen !== null ? frozen.data : tracesQuery.data;
  const dataUpdatedAt = chartPaused && frozen !== null ? frozen.updatedAt : tracesQuery.dataUpdatedAt;
  const rawTraces = data?.traces ?? [];

  // [R27/RP-05] 표본·한도 알림은 Live ON · 1m·5m·10m 에서만 · Y 상한은 Live ON 전 범위. 목록은 응답 그대로(UXD-08 ③).
  //   의존성에 range 를 넣지 않는다 — 1m↔5m↔10m 전환은 다시 뽑지 않는다(호출 수 = 응답 수).
  const sampleOn = live && clockRange;
  const chartTraces = useMemo(() => {
    // 응답 전(data 없음)에는 뽑지 않는다 — 표본 호출 수 = 응답 수(TZ-29).
    if (data === undefined) return [];
    return sampleOn ? sampleTracesPerSecond(data.traces) : data.traces;
  }, [data, sampleOn]);
  const yMax = useMemo(() => (live ? yUpperBound(chartTraces) : null), [live, chartTraces]);
  const limitNotice = live ? chartLimitNotice(range, rawTraces.length, TRACES_LIMIT_SCROLL) : 'none';

  // [R27/FR-27-05] 시계 게이트 — 폴링 식(refetchInterval)의 부분집합 + 응답 ≥ 1.
  //   ★!auth401 은 오류가 도착한 렌더와 setLive(false) 효과 사이 한 렌더를 덮는 방어 항이다 —
  //   빼도 시험이 빨강이 안 되는 항이라 여기에 적어 둔다(RP-30 ②).
  const scrollEnabled = live && !auth401 && !paused && !chartPaused && rawTraces.length > 0;
  const chartWindow: ChartWindow =
    live && data !== undefined
      ? {
          kind: 'scroll',
          range,
          scrollEnabled,
          querySince: data.requestWindow.since,
          queryUntil: data.requestWindow.until,
          // 자리표시 데이터(범위 전환 중)는 도착 시각이 0 → 그 데이터의 조회 창 끝으로 대신한다.
          anchorAt: dataUpdatedAt > 0 ? dataUpdatedAt : data.requestWindow.until,
        }
      : {
          kind: 'fixed',
          range,
          // [v0.8.0] 창은 지금 보이는 점을 불러온 응답의 창으로 그린다 — 한 칸 넘길 때 새 응답이 올 때까지
          //   옛 점이 옛 창에 그대로 있고, 도착하면 차트가 새 창으로 미끄러진다. 응답이 다른 조건의 것
          //   (Live 를 막 끔 · 범위를 막 바꿈)이면 지금처럼 고정 시각으로 계산한다.
          ...(data !== undefined && !data.requestLive && data.requestRange === range
            ? data.requestWindow
            : computeWindow({ range, live: false, pinnedUntil, now: pinnedUntil })),
        };

  // [R12] UX §3.5 — 필터 활성 여부 (0건 이중 분기 + T-30 노출 판단).
  const filterActive = status !== null || q.trim() !== '';

  // [R12] 필터 바 — service !== null 일 때 항상 (로딩/에러/0건 분기에서도 유지 — 필터 해제 경로 보장).
  // no-services / 서비스 미선택 분기에서는 비노출 (필터 대상 자체 부재, UX §3.5).
  const filterBar = (
    <TraceFilterBar status={status} q={q} onStatusChange={setStatus} onQChange={setQ} />
  );

  // ── 본문 분기 결정 ──────────────────────────────────────────────────────
  const renderBody = (): ReactNode => {
    // 1) 서비스 자체가 없는 경우 — 빈 상태 (no-services)
    if (
      !servicesQuery.isLoading &&
      servicesQuery.data &&
      servicesQuery.data.services.length === 0
    ) {
      return <EmptyState kind="no-services" />;
    }

    // 2) 서비스 미선택 — 안내
    if (service === null) {
      return (
        <div
          role="status"
          className="flex h-80 items-center justify-center rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center text-sm text-stone-500"
        >
          상단에서 서비스를 선택하세요.
        </div>
      );
    }

    // 3) traces 로딩 — 필터 바는 유지 (UX §3.5 회귀 가드)
    if (tracesQuery.isLoading) {
      return (
        <div className="space-y-4">
          {filterBar}
          {/* [R26/AC-R26-43] 차트 자리 표시를 h-80 래퍼로 감싼다. 값은 아래 Suspense fallback
              (R21/AC-04-3) 과 같은 h-80 이고, 그쪽이 본보기다 — 맨몸 스켈레톤은 min-h-64(256px)
              라 실물 산점도(h-80=320px) 로 바뀌는 순간 64px 만큼 자리가 튄다.
              ★목록 스켈레톤(바로 아래 variant="list")은 일부러 그대로 둔다 — 행 수에 따라
              높이가 변해서 고정할 실물 값이 없다. */}
          <div className="h-80">
            <LoadingSkeleton variant="chart" />
          </div>
          <LoadingSkeleton variant="list" />
        </div>
      );
    }

    // 4) 에러 — 기존 ErrorState 분기 유지 (필터 바는 유지)
    //    [v0.8.0] 차트 일시 정지 중에는 뒤쪽 조회가 실패해도 멈춘 화면을 그대로 둔다(다시 누르면 드러난다).
    if (tracesQuery.isError && !(chartPaused && frozen !== null)) {
      return (
        <div className="space-y-4">
          {filterBar}
          <ErrorState error={tracesQuery.error} onRetry={() => void tracesQuery.refetch()} />
        </div>
      );
    }

    // 5) traces 0건 — 이중 분기 (T-30 vs 기존 EmptyState, UX §3.5):
    //    필터 활성 + 0건 → T-30 (필터 바 유지 — 해제 가능해야 함) / 비활성 + 0건 → 기존 no-traces.
    const traces = rawTraces;
    if (traces.length === 0) {
      return (
        <div className="space-y-4">
          {filterBar}
          {filterActive ? (
            <div
              role="status"
              className="flex h-full min-h-40 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center"
            >
              {/* T-30 (E-11) — 기존 no-traces 빈 상태와 구분 (필터 활성 시에만) */}
              <p className="max-w-md text-sm text-stone-500">
                조건에 맞는 trace 가 없어요. 필터를 확인해 주세요.
              </p>
            </div>
          ) : (
            <EmptyState kind="no-traces" />
          )}
        </div>
      );
    }

    // 6) 정상 — 필터 바는 TraceList 카드 바로 위 독립 행 (AC-C1-2 "TraceList 상단" 준수)
    return (
      <div className="space-y-4">
        <div className="space-y-1.5">
          {/* R21/AC-04-3 — fallback 래퍼 h-80 = LatencyScatter 외곽(h-80)과 동일값. h-full 스켈레톤이
              부모를 따라가므로 래퍼가 높이를 고정해야 교체 순간 layout shift 0 (UX §10 조건 —
              맨몸 fallback 은 min-h-64=256px 라 64px shift 발생, 설계 §9.2 실측). */}
          <Suspense
            fallback={
              <div className="h-80">
                <LoadingSkeleton variant="chart" />
              </div>
            }
          >
            <LatencyScatter traces={chartTraces} window={chartWindow} yMax={yMax} />
          </Suspense>
          <ScatterLegend
            limitNotice={
              limitNotice === 'none'
                ? undefined
                : {
                    // UC-01 — 숫자는 한도 상수에서 읽는다.
                    text: `최근 ${TRACES_LIMIT_SCROLL}건까지만 불러와요 — 왼쪽 빈 구간에도 trace 가 있어요`,
                    shown: limitNotice === 'shown',
                  }
            }
          />
        </div>
        {filterBar}
        <TraceList traces={traces} />
      </div>
    );
  };

  return (
    <div className="flex h-full flex-col bg-stone-50">
      <Header
        service={service}
        range={range}
        live={live}
        onServiceChange={setService}
        onRangeChange={setRange}
        onLiveChange={setLive}
        chartPaused={chartPaused}
        onChartPausedChange={(next) => {
          if (next) {
            if (tracesQuery.data === undefined) return; // 보여 줄 응답이 아직 없으면 멈출 것도 없다
            setFrozen({ data: tracesQuery.data, updatedAt: tracesQuery.dataUpdatedAt });
            setChartPaused(true);
          } else {
            setChartPaused(false);
            setFrozen(null);
            if (!live) setPinnedUntil(Date.now()); // Live OFF — 지금 시각으로 다시 고정(새로 조회)
          }
        }}
      />
      <main className="flex-1 overflow-auto px-6 py-4">
        <div className="mx-auto max-w-6xl">
          {/* [Phase R15] AC-B5-2/T-09 — 일시정지로 Live 폴링이 멈춘 사유 안내(텍스트만, 컨트롤 disabled 아님). 사용자 명시 비협상 결정(D05/D06). CLAUDE.md '아키텍처 핵심 원칙'. */}
          {paused && (
            <p role="status" className="mb-3 text-center text-xs text-amber-700">
              수신 일시정지 중이라 실시간 갱신을 멈췄어요.
            </p>
          )}
          {chartPaused && (
            <p role="status" className="mb-3 text-center text-xs text-stone-500">
              차트를 일시 정지했어요. 다시 누르면 지금 시각으로 돌아가 그동안 들어온 요청을 모두 보여 줘요.
            </p>
          )}
          {renderBody()}
        </div>
      </main>
    </div>
  );
}
