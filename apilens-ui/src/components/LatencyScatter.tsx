// Latency 산점도 — Recharts ScatterChart + log scale Y.
// 점 색상은 STATUS_HEX 미러를 통해 시맨틱 컬러와 단일 출처 유지.
//
// NOTE: NFR-06 — 시각 판정(흐름 · 말풍선 모습)은 사용자 · 실브라우저 검증 영역이다. [R29] 렌더 시험
//   (LatencyScatterChart.test.tsx)은 점 순서 · 축 글자색 · 가리킨 점 · 여백 전달처럼 DOM 으로 재는 것만 본다.
//
// F2 LOW-1 시맨틱 토큰 정책 적용:
//   - 점 fill 색상은 이미 STATUS_HEX (status-ok / status-error / status-slow) 사용 중.
//   - axis stroke '#888780'은 STATUS_HEX.ok와 우연 일치 — chart 축 색상은 시맨틱 의미 아님 (중성 톤).
//   - [R29/AC-R29-22 · UXD-R29-05] 축 눈금 글자 = stone-500 미러 · 의미색 아님(흰 바탕 4.80:1). 축 선 · 눈금 선은 위 회색 그대로.
//   - grid stroke '#e7e5e4', 가로세로 안내선 '#d6d3d1'은 Tailwind stone-200/stone-300 미러 — 시맨틱 의미 아님.
//   - 따라서 raw hex 직접 사용은 시맨틱 컬러 정책(NFR-05) 위반 아님.
//   - 토큰 치환을 강제하지 않는 사유: 시맨틱 의미가 아닌 위치에 시맨틱 토큰을 넣으면 의미 혼선.
import type { CSSProperties, ReactNode } from 'react';
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { searchAcrossRoutes } from '../lib/routeSearch';
import {
  CartesianGrid,
  Cell,
  ReferenceLine,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  XAxis,
  YAxis,
} from 'recharts';
import type { TracePoint } from '../types/api';
import { STATUS_HEX, statusColorKey } from '../lib/colors';
import { lookupBucket } from '../lib/chartSample';
import type { BucketCounts } from '../lib/chartSample';
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
import type { PointedPoint } from '../hooks/useScatterHover';
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
  /** [R28/AC-28-02-1] 차트 입력 = 서버가 칸마다 골라 보낸 대표 점(그리는 응답의 점 그대로 · 화면은 다시 고르지 않는다). */
  points: ReadonlyArray<TracePoint>;
  /**
   * [R28/UXD-23] 말풍선 셋째 줄용 칸 표 — 부르는 쪽이 그리는 응답 때 한 번 만든다(프레임마다 안 만듦).
   * 가장자리 칸은 처음부터 빠져 있다(DD-28-34). null 이면 셋째 줄 없음.
   */
  bucketCounts?: BucketCounts | null;
  /** X축 도메인의 출처. dataMin/dataMax 자동 스케일의 출렁임 차단(R12 뜻 유지 · 입력만 표시 창). */
  window: ChartWindow;
  /** Live ON 의 Y 상한(응답 사이 불변). null 이면 지금처럼 'dataMax'. */
  yMax: number | null;
}

/**
 * [R28/UXD-31 · AC-28-04-2ⓐ] 오른쪽 여백(px) — 16 → 28. 오른쪽 끝 눈금 글자(`HH:mm:ss` ≈42~46px · 11px 글꼴)의
 * 반쪽이 여백 밖으로 잘리지 않게 「가장 넓은 눈금 글자 폭의 절반 + 4px」 이상으로 둔다. 축 범위 · 눈금 배열은 그대로.
 * 값은 UX 권장 한 값 그대로다 — 실브라우저 실측(V-R28-11 · 1분 Live ON · 10분 Live OFF)은 이 단계가 못 했다.
 * [R29/AC-R29-38 · R28 QA R-9] 렌더 시험이 그림 영역 오른쪽 끝(= 틀 폭 − 이 값)을 재도록 내보낸다(값 그대로).
 */
export const CHART_MARGIN_RIGHT_PX = 28;

/**
 * [R29/UXD-R29-07] 말풍선 상자를 포인터에서 띄우는 거리(px) — 사분면 쪽으로 이만큼 떨어져 포인터 · 점을 안 가린다.
 * 상자 폭 상한 클래스(`max-w-[calc(50%_-_12px)]`)의 12px 와 같은 값이다 — 바꾸면 둘을 함께 바꾼다.
 */
const TOOLTIP_OFFSET_PX = 12;

/** recharts CartesianGrid 가 세로 격자 생성기에 넘기는 인자 중 여기서 쓰는 것만. */
interface GridGeneratorProps {
  xAxis?: { scale?: (v: number) => number } | null;
  offset?: { left?: number; width?: number };
}

interface Point {
  x: number; // startTime (epoch ms)
  y: number; // durationMs
  trace: TracePoint;
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

/**
 * [R29/AC-R29-26 · CX-1(말풍선 머묾)] 말풍선 세 줄 — 1 · 2줄 = 훅 `pointed.traceId` → `shownPoints` 에서 같은 `traceId` 의 점 →
 * `CustomTooltip` 의 `point.trace`(`rootOperation` · `startTime` · `durationMs` · `status`). recharts 의 기억값(`activePayload`)은 읽지 않는다.
 * 3줄 = `Dashboard.tsx` `buildBucketCounts(그리는 응답)` → `<LatencyScatter bucketCounts>` → `CustomTooltip bucketCounts` →
 * `lookupBucket(bucketCounts, point.trace.startTime)` — 칸 표에 없으면 줄 생략(0 으로 안 씀).
 */
function CustomTooltip({
  point,
  bucketCounts = null,
}: {
  point: Point;
  bucketCounts?: BucketCounts | null;
}): ReactNode {
  const t = point.trace;
  // [R28/UXD-23 · UC-R28-14] 그 점이 대표하는 칸의 건수 — 칸 표에 없으면(가장자리 칸 · 넘김 중 옛 점) 줄을 생략한다(0 으로 안 씀).
  const bucket = lookupBucket(bucketCounts, t.startTime);
  return (
    <div className="rounded border border-stone-200 bg-white p-2 text-xs text-stone-900 shadow">
      {/* [R29/AC-R29-56 · CX-1 ⓐ] 이름은 세 줄까지만 보임(CSS 줄 수 제한 · 높이를 재지 않음) — 전체 이름은 목록 줄 · 상세에 남는다. */}
      <div className="font-medium line-clamp-3">{t.rootOperation}</div>
      <div className="text-stone-500">
        {formatHms(t.startTime)} · {t.durationMs}ms · {t.status}
      </div>
      {bucket !== null && (
        <div className="text-stone-500 tabular-nums">
          이 칸 전체 {bucket.count.toLocaleString()}건 · 오류 {bucket.errorCount.toLocaleString()}건
        </div>
      )}
    </div>
  );
}

/**
 * [R29/UXD-R29-07 · DD-R29-19] 말풍선 상자 자리 — 포인터가 틀의 어느 사분면에 있는지로 정한다. 왼쪽 절반이면 포인터 오른쪽,
 * 오른쪽 절반이면 왼쪽 · 위 절반이면 아래, 아래 절반이면 위. 상자 크기를 재지 않아도 틀 안에 머문다(상자 폭 상한 = 틀의 절반 − 띄움).
 */
function tooltipBoxStyle(at: PointedPoint['at']): CSSProperties {
  const horizontal =
    at.x < at.width / 2 ? { left: at.x + TOOLTIP_OFFSET_PX } : { right: at.width - at.x + TOOLTIP_OFFSET_PX };
  const vertical =
    at.y < at.height / 2 ? { top: at.y + TOOLTIP_OFFSET_PX } : { bottom: at.height - at.y + TOOLTIP_OFFSET_PX };
  return { ...horizontal, ...vertical };
}

export function LatencyScatter({ points: input, bucketCounts = null, window: w, yMax }: Props): ReactNode {
  const navigate = useNavigate();
  // dashboard 필터를 trace 상세로 가져갈 때 보존 — 뒤로가기 시 history 복원 위함.
  const [searchParams] = useSearchParams();

  // [R27/RP-07] 누르는 동안 시계를 붙잡는다 — 점 <g> 의 key 에 x 좌표가 있어(recharts Scatter)
  //   누르는 사이 창이 밀리면 mousedown·mouseup 이 다른 노드에 떨어져 클릭 이동이 실패한다.
  //   마우스를 올리기만 해서는 멈추지 않는다(UXD-17 과 충돌 0).
  // [R28/UXD-30 · AC-28-04-1] 켜짐 = 주 단추(button 0 — 마우스 왼쪽 · 터치 · 펜)를 누를 때만. 풀림 = 뗌 · 취소 · 나감 +
  //   창 포커스 잃음 · 문서 숨김 · 문맥 메뉴(새 셋). 누른 채 다른 창으로 가거나 오른쪽 클릭 메뉴가 뜨면 「뗌」이
  //   이 요소로 안 와 흐름이 선 채 남았다(RA-R27-06). 풀림 듣기는 붙잡은 동안만 건다 — 사건 듣기뿐(새 프레임 루프 0).
  const [hold, setHold] = useState(false);
  useEffect(() => {
    if (!hold) return;
    const release = (): void => setHold(false);
    const releaseWhenHidden = (): void => {
      if (document.visibilityState === 'hidden') setHold(false);
    };
    window.addEventListener('blur', release);
    document.addEventListener('visibilitychange', releaseWhenHidden);
    return () => {
      window.removeEventListener('blur', release);
      document.removeEventListener('visibilitychange', releaseWhenHidden);
    };
  }, [hold]);
  // [v0.8.0] Live OFF 한 칸 넘김 — 넘어가는 0.5초 동안만 같은 프레임 시계를 돌린다.
  const [slide, setSlide] = useState<PageSlide | null>(null);
  const reducedMotion = usePrefersReducedMotion();
  const clock = useScrollClock({
    enabled: w.kind === 'scroll' && w.scrollEnabled,
    range: w.range,
    hold,
    runUntil: slide?.endsAt ?? null,
  });
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
      input.map((t) => ({
        x: t.startTime,
        y: clampForLog(t.durationMs),
        trace: t,
      })),
    [input],
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

  // [v0.8.0 → R29/AC-R29-24 · 26] 말풍선 · 안내선을 켜고 끄는 판정과 「어느 점인가」 — 흐르는 동안 점 요소가 매 프레임 새로 생겨
  //   recharts 의 「나감」 신호가 사라지고, recharts 가 기억하는 점은 점→점 순간 이동에서 앞 점에 머문다(R28 CX-1(말풍선 머묾)).
  //   그래서 그리는 점 목록(그리는 순서 그대로)을 훅에 넘겨 직접 판정한다(자세한 이유는 훅 머리 주석).
  //   말풍선 내용 · 가로세로 안내선 · 상자 자리가 모두 이 한 점에서 나온다.
  const hover = useScatterHover(clock, shownPoints);
  const pointedTraceId = hover.pointed?.traceId ?? null;
  const pointedPoint = pointedTraceId === null ? null : (shownPoints.find((p) => p.trace.traceId === pointedTraceId) ?? null);

  const handleClick = (data: unknown): void => {
    // Recharts onClick은 이벤트 객체를 unknown으로 받아 안전하게 좁힘.
    if (
      typeof data === 'object' &&
      data !== null &&
      'payload' in data &&
      typeof (data as { payload: unknown }).payload === 'object' &&
      (data as { payload: { trace?: TracePoint } }).payload?.trace
    ) {
      const trace = (data as { payload: { trace: TracePoint } }).payload.trace;
      navigate({ pathname: `/traces/${trace.traceId}`, search: searchAcrossRoutes(searchParams) });
    }
  };

  return (
    <div
      role="img"
      aria-label="Latency scatter"
      className="relative h-80 w-full rounded-lg border border-stone-200 bg-white p-3"
      onPointerDown={(e) => {
        if (e.button === 0) setHold(true);
      }}
      onPointerUp={() => setHold(false)}
      onPointerCancel={() => setHold(false)}
      onContextMenu={() => setHold(false)}
      onPointerMove={hover.onPointerMove}
      onPointerLeave={() => {
        setHold(false);
        hover.onPointerLeave();
      }}
    >
      <ResponsiveContainer width="100%" height="100%">
        <ScatterChart margin={{ top: 12, right: CHART_MARGIN_RIGHT_PX, bottom: 12, left: 16 }}>
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
            tick={{ fill: '#78716C' }}
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
            tick={{ fill: '#78716C' }}
            fontSize={11}
          />
          {/* [R29/AC-R29-26 · UXD-R29-07] 가로세로 안내선 = 가리킨 점 자리(교차점 = 그 점) — 가리킨 점이 없으면 둘 다 안 그린다.
              recharts 말풍선 틀(Tooltip)은 걷어 냈다 — 그 틀의 상자 보임 · 자리는 들어감 때의 기억값이라 앞 점에 남았다.
              포인터 판정을 가리지 않게 사건을 안 받는다. 가장자리 밖 점은 세로선이 안 그려진다(recharts 기본 ifOverflow).
              ★선 두 요소는 늘 자리를 지키고 좌표만 붙였다 뗀다 — 켤 때만 끼워 넣으면 recharts 가 그 뒤 점 묶음(Scatter)을
              순번으로 다시 맞춰 점 요소를 통째로 새로 만든다(렌더 시험 showsTheTooltipOfThePointUnderThePointerAfterAJump). */}
          <ReferenceLine
            {...(pointedPoint !== null ? { x: pointedPoint.x } : {})}
            stroke="#d6d3d1"
            className="pointer-events-none"
          />
          <ReferenceLine
            {...(pointedPoint !== null ? { y: pointedPoint.y } : {})}
            stroke="#d6d3d1"
            className="pointer-events-none"
          />
          {/* [R27/UXD-04] 점 등장 애니메이션 끔 — 5초마다 점이 반짝이는 「튐」 제거. */}
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
      {/* [R29/AC-R29-26 · UXD-R29-07] 말풍선 상자 — 가리킨 점 하나에서 내용 · 자리를 함께 정한다. 포인터 판정을 가리지 않게
          사건을 안 받는다 · 띄어쓰기 없는 긴 이름도 틀 안에서 접힌다. 새 입력 자리 0(서버 응답 점의 글자 · 숫자만 그림). */}
      {pointedPoint !== null && hover.pointed !== null && (
        <div
          data-testid="scatter-tooltip"
          className="pointer-events-none absolute z-10 max-w-[calc(50%_-_12px)] break-all"
          style={tooltipBoxStyle(hover.pointed.at)}
        >
          <CustomTooltip point={pointedPoint} bucketCounts={bucketCounts} />
        </div>
      )}
    </div>
  );
}
