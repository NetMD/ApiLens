// 대시보드 메인 화면 — Header + 차트 자리(LatencyScatter) + 범례 줄 + 필터 바 + 목록 자리(TraceList).
//
// [R28/FR-28-05·06] 한 화면 두 조회 — 차트는 대표 점(`GET /v1/trace-points` · 서버가 시간 칸마다 가장 느린 정상 하나와
//   오류를 골라 보냄) · 목록은 trace 목록(`GET /v1/traces` · 최근 100건 중 앞 10건을 그림). 두 키 모두 ['traces', …]
//   접두 아래라 정리·삭제 뒤 무효화가 함께 걸린다(DD-28-12). service==null 이면 두 조회 모두 disable (BL-05).
//
// [R12] D-03 비협상 — 필터는 status + operation 검색(q)만. duration 필터 추가 금지.
// 필터는 두 조회의 쿼리 파라미터로 적용 → 산점도와 TraceList 가 동시 필터됨 (의도된
// 동작 — "에러만 보기" 시 산점도도 에러만, UX §3.5).
//   [R28/AC-28-03-1] 검색어 q 가 이제 실제로 서버에 실린다(그전엔 주소 함수가 빠뜨려 걸리지 않았다).
import type { ReactNode } from 'react';
import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { ScatterLegend } from '../components/ScatterLegend';
import { TraceList } from '../components/TraceList';
import { TraceFilterBar } from '../components/TraceFilterBar';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingSkeleton } from '../components/LoadingSkeleton';
import { useDashboardState } from '../hooks/useDashboardState';
import { useMaintenanceStatus } from '../hooks/useMaintenanceStatus';
import { listServices, listTracePoints, listTraces } from '../api/traces';
import {
  LIVE_POLL_MS,
  POINTS_BUSY_RETRY_MS,
  SCROLL_LAG_MS,
  advancePinnedUntil,
  computeScrollWindow,
  computeWindow,
  nextPageAt,
  pointsPollMs,
} from '../lib/time';
import type { RangePreset } from '../lib/time';
import {
  announcementFor,
  buildBucketCounts,
  chartSlotState,
  isApiStatus,
  legendNoticeFor,
  yUpperBound,
} from '../lib/chartSample';
import type { ChartSlotStateId } from '../lib/chartSample';
import type { TracePointsResponse, TracesResponse } from '../types/api';
import type { ChartWindow } from '../components/LatencyScatter';

/** [R28/DD-28-18 · UQ-R28-09] 목록 한도 — 모든 범위에서 100 하나(목록은 앞 10건만 그린다 · 기존 값). */
const TRACES_LIMIT = 100;

/**
 * [R27/FR-27-04] 화면 안 전용 꼬리표 — 응답에 그 요청이 실제로 쓴 조회 창과 조건을 붙인다(서버 응답 형식 불변).
 * [R28/UXD-34] `requestKey` = 그 요청의 조건(서비스 · 범위 · Live · 상태 · 검색어) — 그린 점이 「지금 조건」의
 *   응답인지 가른다(Live OFF 한 칸 넘김 · Live ON 폴링의 이전 응답은 같은 조건이다).
 */
interface RequestTag {
  requestWindow: { since: number; until: number };
  /** [v0.8.0] 이 응답을 부른 조건 — Live OFF 차트 창을 「응답과 같은 창」으로 그릴 때 대조한다. */
  requestLive: boolean;
  requestRange: RangePreset;
  requestKey: string;
}
type DashboardPointsData = TracePointsResponse & RequestTag;
type DashboardListData = TracesResponse & RequestTag;
/** 받은 응답과 그 도착 시각(dataUpdatedAt). */
interface Received<T> {
  data: T;
  updatedAt: number;
}

/**
 * [R28/UXD-19 · ST-R28-09·10] 차트 자리 래퍼 — 모든 상태에서 같은 요소 · 같은 클래스(h-80 = 산점도 외곽과 같은 값이라
 * 교체 순간 layout shift 0). 오류 상자는 이 래퍼가 세로 가운데로 둔다(ErrorState 무접촉).
 */
const CHART_SLOT_CLASS = 'flex h-80 flex-col justify-center';

/** 산점도를 그리는 상태 — 그리는 응답이 0건이면 같은 상태라도 빈 상자다(ST-06 · ST-12 「옛 내용 그대로」). */
const SCATTER_STATES: ReadonlySet<ChartSlotStateId> = new Set<ChartSlotStateId>([
  'ST-02',
  'ST-03',
  'ST-04',
  'ST-06',
  'ST-07',
  'ST-12',
]);

const is401 = (e: unknown): boolean => isApiStatus(e, 401);
const is503 = (e: unknown): boolean => isApiStatus(e, 503);

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
  const queryClient = useQueryClient();

  // 시간 윈도우 처리:
  //   Live ON  → [R27] queryFn 이 요청을 보내는 순간 F 를 읽고 computeScrollWindow 로 조회 창을 낸다.
  //              표시 창은 차트 안 프레임 시계가 같은 함수로 계산한다(이 페이지는 응답 때만 다시 그린다).
  //   Live OFF → pinnedUntil 사용 (range 선택/Live OFF 토글 시점에 freeze)
  //              [v0.8.0] 그 뒤 선택한 시간마다 한 칸씩 앞으로 넘긴다(아래 타이머).
  // queryKey에 시간을 직접 넣지 않는다 — 매 호출마다 키 바뀌면 캐시 의미 없음.
  //   [v0.8.0] 단 Live OFF 의 pinnedUntil 은 한 칸 넘길 때만 바뀌므로 키에 넣는다(넘기면 새로 조회).
  //   [R28] 두 조회가 같은 pinnedUntil 을 읽는다 — 한 칸 넘기면 두 키가 함께 바뀐다(I-09).
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
  // [Phase Y · R28] AC-28-02-4/DD-28-16 — 두 응답(대표 점 · 목록)을 함께 얼린다. 사용자 명시 결정(2026-09-27
  //   [차트 일시 정지] 이름·얼림·해제 그대로). CLAUDE.md 「UI 디자인 철학」 수평 시간 흐름 그대로.
  const [chartPaused, setChartPaused] = useState(false);
  const [frozen, setFrozen] = useState<{
    points: Received<DashboardPointsData>;
    list: Received<DashboardListData>;
  } | null>(null);
  const viewKey = JSON.stringify([service, range, live, status, q]);
  // [R28/L-15 · DD-28-32] 지금 조건(viewKey)의 마지막 성공 대표 점 응답(자리표시 아님) — Live OFF 한 칸 넘김 ·
  //   일시 정지 해제는 viewKey 는 그대로인데 조회 키(pinned)를 바꾼다. 새 키 첫 호출이 바쁨(503)이면 그 질의에는
  //   data 가 없고 자리표시 데이터는 pending 에서만 쓰여 옛 점이 사라진다 → 이 값으로 「옛 창에 머묾」(ST-07 OFF).
  const [lastPointsOk, setLastPointsOk] = useState<Received<DashboardPointsData> | null>(null);
  const [pausedFor, setPausedFor] = useState(viewKey);
  if (pausedFor !== viewKey) {
    setPausedFor(viewKey);
    setChartPaused(false);
    setFrozen(null);
    setLastPointsOk(null); // 조건이 바뀌면 비운다(L-15) — 렌더 중 비교 갱신 · ref 쓰기 0
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
  // [R28/DD-28-15 · L-07] auth401 = 두 조회 **중 하나**라도 401 을 받았는가(다시 마운트할 때까지).
  const [auth401, setAuth401] = useState(false);
  // [R28/CR-28-32 · L-17] 401 상자에 넘길 오류 객체 — 상태로 붙잡는다. Live ON 에서 401 → 효과가 Live 를 끄면
  //   두 조회 키(live · pinned)가 바뀌어 같은 렌더의 오류가 null 이 된다 → 그대로 넘기면 「Unknown error」(가려짐 · AC-28-04-3).
  const [authError, setAuthError] = useState<unknown>(null);

  // [Phase R15] AC-B5-1 — 수신 일시정지 중이면 Live 폴링 무의미(새 데이터 0) → refetchInterval 조건부 중단.
  // 사용자 명시 비협상 결정(D05 수동 재개 / D06 정리 미강제). CLAUDE.md '아키텍처 핵심 원칙' (수신 일시정지 단일 기능).
  // 공유 queryKey ['maintenance','status'] — 배너·배지와 동기. 폴링만 멈추고 Live 토글 컨트롤 자체는 enabled 유지.
  const { paused } = useMaintenanceStatus();

  // [R12] AC-C1-2/AC-C2-3 — queryKey 에 status/q 포함 (캐시 분리) + 두 조회 모두에 전달.
  // placeholderData: keepPreviousData 채택 (UX §5.3 — 세그먼트 전환 시 스켈레톤 깜빡임 방지).
  // [R28/RC-7] 키 = ['traces','points',…] · ['traces','list',…](옛 limit 항 지움 — 목록 한도는 100 하나).
  const keyParams = { service, range, live, status, q, pinned: live ? null : pinnedUntil };
  const pointsKey = ['traces', 'points', keyParams] as const;
  const listKey = ['traces', 'list', keyParams] as const;

  // [R28/DD-28-15 · CR-28-32] blocked = 401 — 두 조회 중 하나라도 401 이면 **같은 렌더에서** 둘 다 멈춘다(효과 지연 0).
  //   두 조회의 오류를 훅을 부르기 **전에** 캐시에서 읽는다 — `enabled` 와 폴링 식이 같은 렌더의 값을 쓰게(값은 두 조회
  //   결과의 오류와 같다 · 훅 순서상 결과는 아직 없다).
  const pointsCachedError = queryClient.getQueryState(pointsKey)?.error ?? null;
  const listCachedError = queryClient.getQueryState(listKey)?.error ?? null;
  const seen401: unknown = is401(pointsCachedError)
    ? pointsCachedError
    : is401(listCachedError)
      ? listCachedError
      : null;
  const blocked = auth401 || seen401 !== null;

  // [R27/RP-08] 두 조회 모두 보내는 순간 자기 조회 창을 정하고 응답에 싣는다(anchorAt·queryUntil 이 늘 유한수).
  const sendWindow = (): { since: number; until: number } => {
    if (live) {
      // ★순서 의존: 조회 창은 요청을 보내는 순간 F 로 먼저 정한다 — 도착 시각을 F 로 쓰면
      //   오른쪽 부등식(표시 ≤ 조회)이 응답 시간만큼 깨진다.
      const requestedAt = Date.now();
      const w = computeScrollWindow({ range, requestedAt, nowMs: requestedAt, lagMs: SCROLL_LAG_MS });
      return { since: w.querySince, until: w.queryUntil };
    }
    return computeWindow({ range, live: false, pinnedUntil, now: pinnedUntil });
  };
  const requestTag = (w: { since: number; until: number }): RequestTag => ({
    requestWindow: w,
    requestLive: live,
    requestRange: range,
    requestKey: viewKey,
  });
  const filterParams = { ...(status !== null ? { status } : {}), ...(q.trim() !== '' ? { q } : {}) };

  // [Phase Y · R28] AC-28-02-1/AC-28-01-1 — 차트 입력 = 서버가 칸마다 고른 대표 점(오류 아닌 것 중 가장 느린 1 + 오류 전부).
  //   사용자 명시 결정(UD-2 · 2026-10-02 「권장안대로」 — 「상한만 올린다」·「화면에서 표본한다」 기각). CLAUDE.md
  //   「UI 디자인 철학」 노드·흐름 우선 · 수평 시간 흐름 그대로(gantt 안 씀).
  const pointsQuery = useQuery({
    queryKey: pointsKey,
    queryFn: async ({ signal }): Promise<DashboardPointsData> => {
      // enabled 가 service !== null 일 때만 부른다 — 아래 갈래는 닿지 않는다(창·서비스는 서버 필수 인자).
      if (service === null) throw new Error('service is required');
      const w = sendWindow();
      const res = await listTracePoints({ service, since: w.since, until: w.until, ...filterParams }, signal);
      return { ...res, ...requestTag(w) };
    },
    enabled: service !== null && !blocked,
    staleTime: 2_000,
    // [Phase K] (US-05, AC-05-1): 401 은 자동 retry 금지. [R28/DD-28-13] 503(바쁨)도 RQ 재시도 0 — 아래 폴링 식이 다시 부른다.
    retry: (failureCount, err) => !(is401(err) || is503(err)) && failureCount < 1,
    refetchOnWindowFocus: false,
    // [R28/CR-28-31 · DD-28-13] 함수 — react-query 가 질의 상태가 바뀔 때마다(오류 포함) 다시 불러 간격을 다시 건다.
    //   바쁨이면 직전 응답이 있는 Live ON 은 평소 폴링 주기(긴 창 30초 · 수신 일시정지면 0), 직전 응답이 없거나
    //   Live OFF 면 5초(수신 일시정지와 무관 — 「잠시 뒤 다시 불러와요」가 참이 되게 · 다음 넘김까지 빈 채로 두지 않음).
    refetchInterval: (query) => {
      if (blocked) return false;
      if (is503(query.state.error)) {
        if (live && query.state.data !== undefined) return paused ? false : pointsPollMs(range);
        return POINTS_BUSY_RETRY_MS;
      }
      return live && !paused ? pointsPollMs(range) : false;
    },
    refetchIntervalInBackground: false,
    placeholderData: keepPreviousData,
  });

  const listQuery = useQuery({
    queryKey: listKey,
    queryFn: async ({ signal }): Promise<DashboardListData> => {
      const w = sendWindow();
      const res = await listTraces(
        { ...(service !== null ? { service } : {}), since: w.since, until: w.until, ...filterParams, limit: TRACES_LIMIT },
        signal,
      );
      return { ...res, ...requestTag(w) };
    },
    // [Phase K] (US-05, AC-05-1): 401 수신 시 enabled=false → 자동 재조회 차단 (무한루프 0).
    enabled: service !== null && !blocked,
    staleTime: 2_000,
    // [Phase K] (US-05, AC-05-1): 401 은 토큰 재입력으로만 해소 → 자동 retry 금지 (재시도 0).
    retry: (failureCount, err) => !is401(err) && failureCount < 1,
    refetchOnWindowFocus: false,
    // [Phase K] (US-05, AC-05-1): 401 이면 live 여도 폴링 중단 (refetchInterval false).
    // [Phase R15] AC-B5-1: 수신 일시정지(paused) 중에도 폴링 중단(새 데이터 0). 사용자 명시 비협상 결정(D05/D06). CLAUDE.md '아키텍처 핵심 원칙'.
    refetchInterval: live && !blocked && !paused ? LIVE_POLL_MS : false,
    refetchIntervalInBackground: false,
    placeholderData: keepPreviousData,
  });

  // [Phase Y · R28] AC-28-04-4/I-09 — Live OFF 한 칸 넘김: 다음 칸이 다 차고 여유(PAGE_SETTLE_MS)까지 지나면 고정 시각을
  //   한 칸 옮긴다(두 조회가 함께 넘어간다). 사용자 명시 결정(2026-09-27 「선택한 시간마다 한 칸씩 넘김」 · 3초 · 0.5초).
  //   CLAUDE.md 「UI 디자인 철학」 수평 시간 흐름. 401 이면 넘기지 않는다(자동 재조회 0 · AC-05-1). 수신 일시정지와는
  //   무관하게 돈다(ST-R28-11 · 09-27 동작 변경 0). 탭이 뒤에 있어 늦게 돌면 다 찬 칸만큼 한 번에 건너뛴다.
  useEffect(() => {
    if (live || blocked || chartPaused) return;
    const timer = setTimeout(
      () => setPinnedUntil((p) => advancePinnedUntil(p, range, Date.now())),
      Math.max(0, nextPageAt(pinnedUntil, range) - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [live, blocked, chartPaused, range, pinnedUntil]);

  // [Phase K] (US-05, AC-05-1): 두 조회 중 하나의 오류가 401 로 바뀌면 auth401 ON + 401 오류 붙잡기 + Live 강제 off.
  //   401 해소(토큰 재입력 후 사용자가 다시 진입/refetch)는 ErrorState '설정으로 이동' → /settings 흐름.
  useEffect(() => {
    const err: unknown = is401(pointsQuery.error)
      ? pointsQuery.error
      : is401(listQuery.error)
        ? listQuery.error
        : null;
    if (err === null) return;
    setAuth401(true);
    setAuthError((prev: unknown) => prev ?? err);
    if (live) setLive(false);
  }, [pointsQuery.error, listQuery.error, live, setLive]);

  // [R28/DD-28-13 보강] 바쁨(503) 뒤 다시 부르는 동안 react-query 는 데이터가 없는 질의의 오류를 비운다(fetch 시작 때
  //   status = pending · error = null) — 그 사이에도 같은 조회 키의 바쁨으로 읽어 차트 자리 · 안내 문구 · 숨은 알림이
  //   5초마다 「응답」↔「바쁨」으로 깜빡이지 않게 한다. 지움 = 진짜 응답(자리표시 아님) · 다른 오류 · 키 바뀜.
  const pointsKeyHash = JSON.stringify(pointsKey);
  const [busyHold, setBusyHold] = useState<{ key: string; error: unknown } | null>(null);
  if (is503(pointsQuery.error)) {
    if (busyHold === null || busyHold.key !== pointsKeyHash || busyHold.error !== pointsQuery.error) {
      setBusyHold({ key: pointsKeyHash, error: pointsQuery.error });
    }
  } else if (
    busyHold !== null &&
    (busyHold.key !== pointsKeyHash ||
      pointsQuery.error !== null ||
      (pointsQuery.isSuccess && !pointsQuery.isPlaceholderData))
  ) {
    setBusyHold(null);
  }
  const pointsError: unknown =
    pointsQuery.error ??
    (busyHold !== null && busyHold.key === pointsKeyHash && pointsQuery.isFetching ? busyHold.error : null);

  // [R28/L-15] 지금 조건의 마지막 성공 대표 점 응답을 기억한다(렌더 중 비교 갱신 — pausedFor 꼴 · ref 쓰기 0).
  const pointsData: DashboardPointsData | undefined = pointsQuery.data;
  if (
    pointsData !== undefined &&
    !pointsQuery.isPlaceholderData &&
    pointsData.requestKey === viewKey &&
    lastPointsOk?.data !== pointsData
  ) {
    setLastPointsOk({ data: pointsData, updatedAt: pointsQuery.dataUpdatedAt });
  }

  // [R28/CR-28-33 · AC-28-02-5] 그리는 대표 점 응답 `drawn` — 얼린 응답 · 지금 질의의 응답(자리표시 포함) ·
  //   바쁨이면 지금 조건의 직전 성공 응답. 도착 시각도 같은 출처.
  // [R12] 🔴 회귀 가드 — LatencyScatter X축 도메인은 시간 윈도우 고정 유지. 필터는 창에 무관여(자동스케일 회귀 금지).
  const drawn: DashboardPointsData | undefined =
    chartPaused && frozen !== null
      ? frozen.points.data
      : (pointsData ??
        (is503(pointsError) && lastPointsOk !== null && lastPointsOk.data.requestKey === viewKey
          ? lastPointsOk.data
          : undefined));
  const drawnUpdatedAt =
    chartPaused && frozen !== null
      ? frozen.points.updatedAt
      : pointsData !== undefined
        ? pointsQuery.dataUpdatedAt
        : drawn !== undefined && lastPointsOk !== null
          ? lastPointsOk.updatedAt
          : 0;

  // [R28/AC-28-02-6 · CR-28-37] 차트 자리 분기 — 우선순위 401 > 일시 정지 > 바쁨·오류 > 조건 바뀜 > 0건 > 응답 > 첫 응답 전.
  const slot = chartSlotState({
    blocked,
    chartPaused,
    frozen: frozen !== null,
    pointsError,
    drawn,
    viewKey,
  });

  // [R28/UXD-23 · L-10] 말풍선 칸 표 · Y 상한 — 그리는 응답이 바뀔 때 한 번(새 응답 없이 시계만 흐르면 같은 객체).
  const drawnPoints = useMemo(() => drawn?.points ?? [], [drawn]);
  const bucketCounts = useMemo(() => (drawn !== undefined ? buildBucketCounts(drawn) : null), [drawn]);
  const yMax = useMemo(() => (live ? yUpperBound(drawnPoints) : null), [live, drawnPoints]);
  const notice = useMemo(() => legendNoticeFor(slot.notice, drawn), [slot.notice, drawn]);

  // [R27/FR-27-05] 시계 게이트 — 폴링 식(refetchInterval)의 부분집합 + 그리는 대표 점 ≥ 1.
  //   [R28/UXD-20] 목록 길이가 아니라 차트에 그리는 대표 점 수로 연다. ★!blocked 는 오류가 도착한 렌더와
  //   setLive(false) 효과 사이 한 렌더를 덮는 방어 항이다 — 빼도 시험이 빨강이 안 되는 항이라 여기에 적어 둔다(RP-30 ②).
  const scrollEnabled = live && !blocked && !paused && !chartPaused && drawnPoints.length > 0;
  const chartWindow: ChartWindow =
    live && drawn !== undefined
      ? {
          kind: 'scroll',
          range,
          scrollEnabled,
          querySince: drawn.requestWindow.since,
          queryUntil: drawn.requestWindow.until,
          // 자리표시 데이터(범위 전환 중)는 도착 시각이 0 → 그 데이터의 조회 창 끝으로 대신한다.
          anchorAt: drawnUpdatedAt > 0 ? drawnUpdatedAt : drawn.requestWindow.until,
        }
      : {
          kind: 'fixed',
          range,
          // [v0.8.0] 창은 지금 보이는 점을 불러온 응답의 창으로 그린다 — 한 칸 넘길 때 새 응답이 올 때까지
          //   옛 점이 옛 창에 그대로 있고, 도착하면 차트가 새 창으로 미끄러진다. 응답이 다른 조건의 것
          //   (Live 를 막 끔 · 범위를 막 바꿈)이면 지금처럼 고정 시각으로 계산한다.
          // [R28/CR-28-35] 출처 = 그리는 대표 점 응답 — Live OFF 넘김이 바쁨이면 직전 성공 응답의 옛 창에 머문다.
          ...(drawn !== undefined && !drawn.requestLive && drawn.requestRange === range
            ? drawn.requestWindow
            : computeWindow({ range, live: false, pinnedUntil, now: pinnedUntil })),
        };
  const scatterShown = SCATTER_STATES.has(slot.state) && drawn !== undefined && drawn.totalCount > 0;

  // [R28/UXD-22 · L-08 · L-09] 숨은 알림 — 직전 차트 자리 상태와 알림 글을 상태로 든다(렌더 중 비교 갱신 꼴 ·
  //   렌더 중 ref 쓰기 0 — StrictMode 의 두 번째 렌더가 「안 바뀜」으로 보아 글을 비우는 일을 막는다).
  //   상태가 바뀐 렌더에서만 규칙(UC-R28-19~22)으로 글을 정한다 — 같은 상태가 이어지는 동안 글 그대로.
  const [prevSlotState, setPrevSlotState] = useState<ChartSlotStateId>('ST-01');
  const [announcement, setAnnouncement] = useState('');
  if (prevSlotState !== slot.state) {
    setPrevSlotState(slot.state);
    setAnnouncement(announcementFor(prevSlotState, slot.state));
  }

  // [R28/UF-R28-02·03·05 · CR-28-40] 다시 그려지는 자리의 키보드 초점 — 받을 자리 = 늘 있는 두 래퍼(탭 순서 밖).
  const chartSlotRef = useRef<HTMLDivElement | null>(null);
  const listSlotRef = useRef<HTMLDivElement | null>(null);
  // [L-16] 초점이 목록 자리 안에 있는가 — 래퍼의 onFocus/onBlur 로 든다(커밋 뒤 activeElement 로 판정하지 않는다).
  const listFocusInside = useRef(false);
  // [L-12 · L-13] 「Retry 누름」 표시 — 사건 처리기에서만 쓴다(S-132 수단 재량).
  const chartRetryPressed = useRef(false);
  const listRetryPressed = useRef(false);

  // UF-R28-02 — 차트 자리 [Retry] 뒤: 회복(오류 → 응답)이면 차트 자리 래퍼로 · 다시 실패면 새 [Retry] 단추로.
  useEffect(() => {
    if (!chartRetryPressed.current || pointsQuery.isFetching) return;
    if (slot.state === 'ST-01') return; // 아직 응답 전
    chartRetryPressed.current = false;
    if (slot.state === 'ST-09') {
      chartSlotRef.current?.querySelector('button')?.focus();
      return;
    }
    chartSlotRef.current?.focus();
  }, [slot.state, pointsQuery.isFetching]);

  // UF-R28-03 — 목록 자리 [Retry] 뒤: 회복이면 목록 자리 래퍼로 · 다시 실패면 새 [Retry] 단추로.
  useEffect(() => {
    if (!listRetryPressed.current || listQuery.isFetching) return;
    listRetryPressed.current = false;
    if (listQuery.isError) {
      listSlotRef.current?.querySelector('button')?.focus();
      return;
    }
    listSlotRef.current?.focus();
  }, [listQuery.isFetching, listQuery.isError]);

  // UF-R28-05 — 401 로 목록 자리가 숨겨질 때 초점이 그 안에 있었으면 차트 자리 래퍼(401 상자를 담음)로.
  useEffect(() => {
    if (blocked && listFocusInside.current) {
      listFocusInside.current = false;
      chartSlotRef.current?.focus();
    }
  }, [blocked]);

  const retryBoth = (): void => {
    chartRetryPressed.current = true;
    void pointsQuery.refetch();
    void listQuery.refetch();
  };
  const retryList = (): void => {
    listRetryPressed.current = true;
    void listQuery.refetch();
  };

  // [R12] UX §3.5 — 필터 활성 여부 (0건 이중 분기 + T-30 노출 판단).
  const filterActive = status !== null || q.trim() !== '';

  // [R12] 필터 바 — service !== null 일 때 항상 (로딩/에러/0건 분기에서도 유지 — 필터 해제 경로 보장).
  // no-services / 서비스 미선택 분기에서는 비노출 (필터 대상 자체 부재, UX §3.5).
  // [R28/UXD-19] 고정 틀의 셋째 자리 한 곳에서만 그린다 — 부모와 형제 순번이 모든 상태에서 같아 검색란이 다시
  //   만들어지지 않는다(초점 · 디바운스 전 글자 보존 · AC-28-03-4).
  const filterBar = (
    <TraceFilterBar status={status} q={q} onStatusChange={setStatus} onQChange={setQ} />
  );

  // [R12] 0건 이중 분기 (T-30 vs 기존 EmptyState, UX §3.5): 필터 활성 + 0건 → T-30 / 비활성 + 0건 → 기존 no-traces.
  //   [R28/ST-R28-05] 판정은 대표 점 응답의 totalCount 로 — 목록이 1건이어도 차트 자리는 빈 상자 · 목록 자리는 숨김.
  const emptyBox = filterActive ? (
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
  );

  // ① 차트 자리 — 상태는 자리 **안** 내용만 바꾼다(UXD-19 · 분기 표 CR-28-37).
  const renderChartSlot = (): ReactNode => {
    switch (slot.state) {
      case 'ST-10':
        // 401 — 자리표시 데이터·직전 점보다 이긴다 · [설정으로 이동]만(Retry 0).
        return <ErrorState error={authError ?? seen401} />;
      case 'ST-09':
        // 오류(401·503 밖) — 오류 상자는 하나 · 그 Retry 가 두 조회를 다시 부른다.
        return <ErrorState error={pointsError} onRetry={retryBoth} />;
      case 'ST-01':
      case 'ST-08':
        // 첫 응답 전 · 바쁨(같은 조건 응답 없음 — 다른 조건의 옛 점이 있었어도 거둠).
        return <LoadingSkeleton variant="chart" />;
      case 'ST-05':
        return emptyBox;
      default:
        // 02·03·04 응답 · 06 일시 정지 · 07 바쁨(직전 응답) · 12 조건 바뀜(옛 내용 그대로).
        if (!scatterShown) return emptyBox;
        return (
          // R21/AC-04-3 — fallback = 산점도와 같은 h-80 자리(래퍼가 높이를 고정 · 교체 순간 layout shift 0).
          <Suspense fallback={<LoadingSkeleton variant="chart" />}>
            <LatencyScatter points={drawnPoints} bucketCounts={bucketCounts} window={chartWindow} yMax={yMax} />
          </Suspense>
        );
    }
  };

  // ④ 목록 자리 — 자기 상태(첫 응답 전 · 오류 · 있음)대로. 401 · 0건 · (차트 자리 오류와 함께인 목록 오류)는 숨김.
  const renderListSlot = (): ReactNode => {
    if (slot.state === 'ST-10' || slot.state === 'ST-05') return null;
    // [v0.8.0] 차트 일시 정지 중에는 얼린 목록 — 뒤쪽 조회가 실패해도 멈춘 화면을 그대로 둔다(다시 누르면 드러난다).
    if (chartPaused && frozen !== null) return <TraceList traces={frozen.list.data.traces} />;
    if (listQuery.isError) {
      if (slot.state === 'ST-09') return null;
      return <ErrorState error={listQuery.error} onRetry={retryList} />;
    }
    if (listQuery.data === undefined) return <LoadingSkeleton variant="list" />;
    return <TraceList traces={listQuery.data.traces} />;
  };

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

    // 3) [R28/UXD-19] 고정 틀 — 서비스를 고른 뒤에는 한 가지 모양만: ① 차트 자리 → ② 범례 줄 → ③ 필터 바 → ④ 목록 자리.
    //    필터 바는 TraceList 카드 바로 위 독립 행 (AC-C1-2 "TraceList 상단" 준수).
    return (
      <div className="space-y-4">
        <div className="space-y-1.5">
          <div ref={chartSlotRef} tabIndex={-1} data-testid="chart-slot" className={CHART_SLOT_CLASS}>
            {renderChartSlot()}
          </div>
          <ScatterLegend dotsShown={scatterShown} notice={notice} announcement={announcement} />
        </div>
        {filterBar}
        <div
          ref={listSlotRef}
          tabIndex={-1}
          data-testid="list-slot"
          onFocus={() => {
            listFocusInside.current = true;
          }}
          onBlur={(e) => {
            const next = e.relatedTarget;
            if (next instanceof Node && e.currentTarget.contains(next)) return;
            // 초점을 가진 요소가 지워지며 나는 blur 는 「안에 있었다」를 지우지 않는다(401 로 목록 자리가 숨겨질 때 · UF-R28-05).
            if (e.target instanceof Node && !e.target.isConnected) return;
            listFocusInside.current = false;
          }}
        >
          {renderListSlot()}
        </div>
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
            // [R28/DD-28-16 · UXD-34] 둘 중 하나라도 없거나 지금 조건의 응답이 아니면 누름을 무시한다 —
            //   멈출 「지금 조건」의 응답이 아직 없다(조건을 바꾼 직후 · 목록만 자리표시인 순간 포함).
            if (
              drawn === undefined ||
              listQuery.data === undefined ||
              drawn.requestKey !== viewKey ||
              listQuery.data.requestKey !== viewKey
            ) {
              return;
            }
            setFrozen({
              points: { data: drawn, updatedAt: drawnUpdatedAt },
              list: { data: listQuery.data, updatedAt: listQuery.dataUpdatedAt },
            });
            setChartPaused(true);
          } else {
            setChartPaused(false);
            setFrozen(null);
            if (!live) setPinnedUntil(Date.now()); // Live OFF — 지금 시각으로 다시 고정(두 키가 함께 바뀌어 새로 조회)
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
          {/* [R28/UC-R28-10 · UQ-R28-16 답 전 기본값 = 바꿈] 다시 그리는 것은 대표 점이라 「모두 보여 줘요」를 「포함해 다시 그려요」로. */}
          {chartPaused && (
            <p role="status" className="mb-3 text-center text-xs text-stone-500">
              차트를 일시 정지했어요. 다시 누르면 지금 시각으로 돌아가 그동안 들어온 요청을 포함해 다시 그려요.
            </p>
          )}
          {renderBody()}
        </div>
      </main>
    </div>
  );
}
