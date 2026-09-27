// Latency 산점도 — Recharts ScatterChart + log scale Y.
// 점 색상은 STATUS_HEX 미러를 통해 시맨틱 컬러와 단일 출처 유지.
//
// NOTE: NFR-06 — 시각화 컴포넌트는 unit test 작성 대상 아님 (사용자 시각 검증 영역).
//
// F2 LOW-1 시맨틱 토큰 정책 적용:
//   - 점 fill 색상은 이미 STATUS_HEX (status-ok / status-error / status-slow) 사용 중.
//   - axis stroke '#888780'은 STATUS_HEX.ok와 우연 일치 — chart 축 색상은 시맨틱 의미 아님 (중성 톤).
//   - grid stroke '#e7e5e4', tooltip cursor '#d6d3d1'은 Tailwind stone-200/stone-300 미러 — 시맨틱 의미 아님.
//   - 따라서 raw hex 직접 사용은 시맨틱 컬러 정책(NFR-05) 위반 아님.
//   - 토큰 치환을 강제하지 않는 사유: 시맨틱 의미가 아닌 위치에 시맨틱 토큰을 넣으면 의미 혼선.
import type { ReactNode } from 'react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { searchAcrossRoutes } from '../lib/routeSearch';
import {
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { TraceSummary } from '../types/api';
import { STATUS_HEX, statusColorKey } from '../lib/colors';
import {
  PAGE_SLIDE_MS,
  SCROLL_LAG_MS,
  computePageTicks,
  computeScrollWindow,
  computeTimeTicks,
  formatHms,
  formatPageTick,
  isClockRange,
  isNextPage,
  slideWindow,
} from '../lib/time';
import type { RangePreset } from '../lib/time';
import { useScrollClock } from '../hooks/useScrollClock';
import { useScatterHover } from '../hooks/useScatterHover';
import { usePrefersReducedMotion } from '../hooks/usePrefersReducedMotion';

/**
 * [R27/FR-27-01] 차트가 받는 시간 창.
 * - fixed  : Live OFF — 지금과 같은 고정 창.
 * - scroll : Live ON — 표시 중 응답에 실제로 실린 조회 창 + 도착 시각. 표시 창은 차트 안 프레임 시계로 계산.
 */
export type ChartWindow =
  | { kind: 'fixed'; range: RangePreset; since: number; until: number }
  | {
      kind: 'scroll';
      range: RangePreset;
      scrollEnabled: boolean;
      /** 표시 중 응답의 조회 창 · queryUntil = 요청을 보낸 시각 F. */
      querySince: number;
      queryUntil: number;
      /** 그 응답 도착 시각(dataUpdatedAt · 0 이면 queryUntil). */
      anchorAt: number;
    };

interface Props {
  traces: ReadonlyArray<TraceSummary>;
  /** X축 도메인의 출처. dataMin/dataMax 자동 스케일의 출렁임 차단(R12 뜻 유지 · 입력만 표시 창). */
  window: ChartWindow;
  /** Live ON 의 Y 상한(응답 사이 불변). null 이면 지금처럼 'dataMax'. */
  yMax: number | null;
}

/** recharts CartesianGrid 가 세로 격자 생성기에 넘기는 인자 중 여기서 쓰는 것만. */
interface GridGeneratorProps {
  xAxis?: { scale?: (v: number) => number } | null;
  offset?: { left?: number; width?: number };
}

interface Point {
  x: number; // startTime (epoch ms)
  y: number; // durationMs
  trace: TraceSummary;
}

/** [v0.8.0] Live OFF 한 칸 넘김 중인 미끄러짐 — 옛 창에서 새 창으로, 그동안 옛 점도 함께 그린다. */
interface PageSlide {
  from: { since: number; until: number };
  prevPoints: ReadonlyArray<Point>;
  startedAt: number;
  endsAt: number;
}

// log scale은 0 이하 값 처리 못 함. 0ms 들어오면 최소 1ms로 클램프.
function clampForLog(ms: number): number {
  return ms <= 0 ? 1 : ms;
}

interface TooltipPayloadItem {
  payload?: Point;
}

function CustomTooltip({
  active,
  payload,
}: {
  active?: boolean | undefined;
  payload?: ReadonlyArray<TooltipPayloadItem> | undefined;
}): ReactNode {
  if (!active || !payload || payload.length === 0) return null;
  const first = payload[0];
  if (!first || !first.payload) return null;
  const t = first.payload.trace;
  return (
    <div className="rounded border border-stone-200 bg-white p-2 text-xs text-stone-900 shadow">
      <div className="font-medium">{t.rootOperation}</div>
      <div className="text-stone-500">
        {formatHms(t.startTime)} · {t.durationMs}ms · {t.status}
      </div>
    </div>
  );
}

export function LatencyScatter({ traces, window: w, yMax }: Props): ReactNode {
  const navigate = useNavigate();
  // dashboard 필터를 trace 상세로 가져갈 때 보존 — 뒤로가기 시 history 복원 위함.
  const [searchParams] = useSearchParams();

  // [R27/RP-07] 누르는 동안 시계를 붙잡는다 — 점 <g> 의 key 에 x 좌표가 있어(recharts Scatter)
  //   누르는 사이 창이 밀리면 mousedown·mouseup 이 다른 노드에 떨어져 클릭 이동이 실패한다.
  //   마우스를 올리기만 해서는 멈추지 않는다(UXD-17 과 충돌 0).
  const [hold, setHold] = useState(false);
  // [v0.8.0] Live OFF 한 칸 넘김 — 넘어가는 0.5초 동안만 같은 프레임 시계를 돌린다.
  const [slide, setSlide] = useState<PageSlide | null>(null);
  const reducedMotion = usePrefersReducedMotion();
  const clock = useScrollClock({
    enabled: w.kind === 'scroll' && w.scrollEnabled,
    range: w.range,
    hold,
    runUntil: slide?.endsAt ?? null,
  });
  // [v0.8.0] 툴팁·안내선을 켜고 끄는 판정 — 흐르는 동안 점 요소가 매 프레임 새로 생겨 recharts 의
  //   「나감」 신호가 사라지므로 직접 판정한다(자세한 이유는 훅 머리 주석).
  const hover = useScatterHover(clock);

  // [R27/FR-27-04] 표시 창 — scroll 이면 같은 함수로(오른쪽 끝 = min(max(마지막 프레임, 도착 시각) − L, F)).
  //   시계가 안 도는 경우(1h 이상 · 움직임 줄이기 · 일시정지)도 같은 식이라 창 끝은 앞으로만 간다(UXD-05·06).
  let viewSince: number;
  let viewUntil: number;
  if (w.kind === 'scroll') {
    const v = computeScrollWindow({
      range: w.range,
      requestedAt: w.queryUntil,
      nowMs: Math.max(clock ?? 0, w.anchorAt),
      lagMs: SCROLL_LAG_MS,
    });
    viewSince = v.viewSince;
    viewUntil = v.viewUntil;
  } else if (slide !== null) {
    const v = slideWindow(slide.from, { since: w.since, until: w.until }, slide.startedAt, clock ?? slide.startedAt, slide.endsAt - slide.startedAt);
    viewSince = v.viewSince;
    viewUntil = v.viewUntil;
  } else {
    viewSince = w.since;
    viewUntil = w.until;
  }
  // [R27/UXD-02] 흐르는 범위는 눈금을 시각값에 붙여 직접 넘긴다(범위가 바뀔 때마다 새로 고르는 기본값 대신).
  // [v0.8.0] Live OFF 도 정해진 시각에 붙인 눈금을 쓴다 — 한 칸 넘길 때 눈금이 종이와 함께 흘러간다
  //   (창 시작에 붙은 기본 눈금은 넘기는 동안 글자만 굴러간다).
  const clockTicks =
    w.kind === 'scroll'
      ? isClockRange(w.range)
        ? computeTimeTicks(viewSince, viewUntil, w.range)
        : null
      : computePageTicks(viewSince, viewUntil, w.range);

  // [R27/RP-09] 세로 격자선 x 를 정수 px 로 — 프레임당 소수 px 이동에서 1px 선이 번졌다 선명해지기를 막는다.
  const verticalGrid = useCallback(
    (props: GridGeneratorProps): number[] => {
      const scale = props.xAxis?.scale;
      const left = props.offset?.left ?? 0;
      const right = left + (props.offset?.width ?? 0);
      if (clockTicks === null || typeof scale !== 'function') return [];
      return clockTicks
        .map((t) => Math.round(scale(t)))
        .filter((x) => Number.isFinite(x) && x >= left && x <= right);
    },
    [clockTicks],
  );

  const points = useMemo<Point[]>(
    () =>
      traces.map((t) => ({
        x: t.startTime,
        y: clampForLog(t.durationMs),
        trace: t,
      })),
    [traces],
  );

  // [v0.8.0] Live OFF 한 칸 넘김 감지 — 새 창이 옛 창 바로 다음 칸이면 미끄러짐을 건다.
  //   화면에 그리기 전에(레이아웃 효과) 걸어야 새 창이 한 프레임 먼저 번쩍이지 않는다.
  //   창과 점은 같은 응답에서 함께 바뀌므로, 직전 스냅숏의 점이 곧 옛 창의 점이다.
  const fixedSince = w.kind === 'fixed' ? w.since : null;
  const fixedUntil = w.kind === 'fixed' ? w.until : null;
  const prevFixed = useRef<{ since: number; until: number; points: ReadonlyArray<Point> } | null>(null);
  useLayoutEffect(() => {
    if (fixedSince === null || fixedUntil === null) {
      prevFixed.current = null;
      return;
    }
    const prev = prevFixed.current;
    const next = { since: fixedSince, until: fixedUntil };
    if (prev !== null && !reducedMotion && isNextPage(prev, next)) {
      const startedAt = Date.now();
      setSlide({ from: { since: prev.since, until: prev.until }, prevPoints: prev.points, startedAt, endsAt: startedAt + PAGE_SLIDE_MS });
    }
    prevFixed.current = { since: fixedSince, until: fixedUntil, points };
  }, [fixedSince, fixedUntil, points, reducedMotion]);
  // 끝 시각을 넘긴 프레임이 오면(또는 움직임 줄이기가 켜지면) 미끄러짐을 거둔다.
  useEffect(() => {
    if (slide === null) return;
    if (reducedMotion || (clock !== null && clock >= slide.endsAt)) setSlide(null);
  }, [clock, slide, reducedMotion]);
  // 미끄러지는 동안은 옛 창의 점과 새 창의 점을 함께 그린다(경계에 걸친 같은 trace 는 한 번만).
  const shownPoints = useMemo<Point[]>(() => {
    if (slide === null) return points;
    const seen = new Set(points.map((p) => p.trace.traceId));
    return [...slide.prevPoints.filter((p) => !seen.has(p.trace.traceId)), ...points];
  }, [slide, points]);

  const handleClick = (data: unknown): void => {
    // Recharts onClick은 이벤트 객체를 unknown으로 받아 안전하게 좁힘.
    if (
      typeof data === 'object' &&
      data !== null &&
      'payload' in data &&
      typeof (data as { payload: unknown }).payload === 'object' &&
      (data as { payload: { trace?: TraceSummary } }).payload?.trace
    ) {
      const trace = (data as { payload: { trace: TraceSummary } }).payload.trace;
      navigate({ pathname: `/traces/${trace.traceId}`, search: searchAcrossRoutes(searchParams) });
    }
  };

  return (
    <div
      role="img"
      aria-label="Latency scatter"
      className="h-80 w-full rounded-lg border border-stone-200 bg-white p-3"
      onPointerDown={() => setHold(true)}
      onPointerUp={() => setHold(false)}
      onPointerCancel={() => setHold(false)}
      onPointerMove={hover.onPointerMove}
      onPointerLeave={() => {
        setHold(false);
        hover.onPointerLeave();
      }}
    >
      <ResponsiveContainer width="100%" height="100%">
        <ScatterChart margin={{ top: 12, right: 16, bottom: 12, left: 16 }}>
          {clockTicks !== null ? (
            <CartesianGrid
              stroke="#e7e5e4"
              strokeDasharray="3 3"
              verticalCoordinatesGenerator={verticalGrid}
            />
          ) : (
            <CartesianGrid stroke="#e7e5e4" strokeDasharray="3 3" />
          )}
          <XAxis
            type="number"
            dataKey="x"
            // 선택한 시간 윈도우로 고정 — dataMin/dataMax 자동 스케일의 rubber-banding 차단.
            // allowDataOverflow: 시계 어긋난 윈도우 밖 trace 가 축을 늘리지 못하게 strict 적용.
            // [R27/UXD-03] 같은 설정이 창 밖 점을 그림 영역 clip 으로 자른다 — 새 점이 오른쪽 가장자리에서
            //   잘린 채 들어온다. 점 목록은 매 프레임 거르지 않는다(응답 때만 바뀜).
            domain={[viewSince, viewUntil]}
            allowDataOverflow
            // [R27/UXD-02] 흐르는 범위 = 눈금 배열 + 자동 숨김 끔(interval 0). 그 밖은 기본값 그대로.
            {...(clockTicks !== null ? { ticks: clockTicks, interval: 0 } : {})}
            tickFormatter={(v: number) => (w.kind === 'fixed' ? formatPageTick(v, w.range) : formatHms(v))}
            stroke="#888780"
            fontSize={11}
          />
          <YAxis
            type="number"
            dataKey="y"
            scale="log"
            // [R27/FR-27-03] Live ON 은 응답 사이 불변 상한(10 의 거듭제곱) — 창이 밀려도 위아래로 출렁이지 않는다.
            domain={[1, yMax ?? 'dataMax']}
            padding={{ top: 16, bottom: 12 }}
            tickFormatter={(v: number) => `${v}ms`}
            allowDataOverflow
            stroke="#888780"
            fontSize={11}
          />
          {/* [R27/UXD-04] 툴팁 이동 · 점 등장 애니메이션 끔 — 5초마다 점이 반짝이는 「튐」 제거. */}
          {/* [v0.8.0] 마우스가 점 위에 없으면 active=false 로 툴팁과 안내선을 함께 끈다(남는 선 방지). */}
          <Tooltip
            content={<CustomTooltip />}
            cursor={{ stroke: '#d6d3d1' }}
            isAnimationActive={false}
            {...(hover.overPoint ? {} : { active: false })}
          />
          {/* fillOpacity — 고밀도(초당 수십 trace)에서 점 겹침을 농담으로 드러냄. */}
          <Scatter data={shownPoints} onClick={handleClick} fillOpacity={0.7} isAnimationActive={false}>
            {shownPoints.map((p) => (
              <Cell
                key={p.trace.traceId}
                fill={STATUS_HEX[statusColorKey(p.trace)]}
                cursor="pointer"
              />
            ))}
          </Scatter>
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}
