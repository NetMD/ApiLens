// [v0.8.0] 흐르는 산점도에서 「마우스가 지금 점 위에 있는가」를 직접 판정한다.
// [R29/CX-1(말풍선 머묾) · AC-R29-24 · 26 · UXD-R29-07~10] 판정 결과를 「가리킨 점」 하나로 넓혔다 — 말풍선 내용 ·
//   가로세로 안내선 · 상자 자리가 모두 이 값에서 나온다(recharts 가 기억하는 점은 읽지 않는다).
//
// 왜 필요한가: recharts 2.15 Scatter 는 점 <g> 의 key 에 x 좌표를 넣는다. 차트가 매 프레임 흐르면
//   key 가 매 프레임 바뀌어 점 요소가 지워졌다 새로 생긴다. 마우스가 올라가 있던 요소가 지워지면
//   「나감」 신호가 오지 않아 툴팁·가로세로 안내선이 켜진 채 남는다(09-27 사용자 실측).
//   recharts 는 말풍선 내용과 자리를 들어감 때만 바꿔(점→점 순간 이동이면 앞 점에 머묾 · R28 CX-1(말풍선 머묾))
//   켜고 끄는 판정과 「어느 점인가」를 recharts 의 들어감·나감 신호 대신 여기서 한다.
//
// - 어느 점인가: 포인터 아래 요소에서 가장 가까운 점 묶음(<g class="recharts-scatter-symbol">)의 형제 순번
//   = 그리는 점 목록 순번(recharts 는 점을 목록 순서대로 형제로 그린다 · 시험 drawsScatterSymbolsInTheDrawnPointOrder).
//   점마다 data-* 표식을 다는 길은 recharts 2.15 가 걸러 내 안 쓴다. 형제 수 ≠ 목록 수면 「점 없음」(엉뚱한 점 대신 꺼짐).
// - 마우스가 움직일 때: 그 순간 포인터 아래 점을 가리킨다(점 → 다른 점이면 바로 다른 점 · 빈 곳이면 끔).
// - 차트를 벗어날 때 · Escape 를 누를 때: 끈다(Escape 는 켜져 있는 동안만 듣는다 — recharts 말풍선 틀의 닫기를 옮김).
// - 켜져 있는 동안은 프레임마다 그리고 그리는 점 목록이 바뀔 때마다 마지막 마우스 자리를 다시 본다.
//   밑이 빈 곳이거나 다른 점이면 끈다(새 점 글로 바꾸지 않는다) · 같은 점(같은 traceId)이면 켠 채 둔다 ·
//   점이 목록에서 빠지면 끈다. 움직임 줄이기 · 차트 일시 정지에서 프레임 시계가 안 돌아도 응답이 바뀌면 다시 본다.
// - 켤 때는 마우스가 움직였을 때만 켠다 — ① 가만히 둔 마우스 밑으로 점이 지날 때마다 말풍선이 떴다 꺼지면
//   곁눈 화면이 시끄럽다 ② 꺼진 동안은 프레임마다 검사하지 않아 비용 0 이다(UXD-R29-08).
// 흐름은 멈추지 않는다(UX 설계 UXD-17 「마우스를 올리면 멈추는 방식은 안 흐른다 오판을 부른다」).
import { useCallback, useEffect, useRef, useState } from 'react';
import type { PointerEvent } from 'react';

/** recharts 가 점 하나마다 붙이는 묶음 요소의 class. */
export const SCATTER_SYMBOL_SELECTOR = '.recharts-scatter-symbol';

/** Returns true when the target is (inside) a recharts scatter point. */
export function isScatterPoint(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(SCATTER_SYMBOL_SELECTOR) !== null;
}

/**
 * Returns the index of the scatter point under `target` among its sibling point groups,
 * or null when the target is not a point or the sibling count differs from `drawnCount`.
 */
export function pointIndexOf(target: EventTarget | null, drawnCount: number): number | null {
  if (!(target instanceof Element)) return null;
  const symbol = target.closest(SCATTER_SYMBOL_SELECTOR);
  const parent = symbol?.parentElement ?? null;
  if (symbol === null || parent === null) return null;
  const siblings = Array.from(parent.children).filter((el) => el.matches(SCATTER_SYMBOL_SELECTOR));
  // 형제 수가 그리는 점 수와 다르면 순번이 어느 점인지 알 수 없다 — 엉뚱한 점 대신 「점 없음」(안전 쪽).
  if (siblings.length !== drawnCount) return null;
  const index = siblings.indexOf(symbol);
  return index >= 0 ? index : null;
}

/** 가리킨 점 — 그리는 점의 traceId 와, 그 점을 가리킬 때의 포인터 자리(차트 틀 기준 px · 틀 크기 포함). */
export interface PointedPoint {
  traceId: string;
  at: { x: number; y: number; width: number; height: number };
}

export interface ScatterHover {
  /** 지금 가리킨 점 — null 이면 말풍선 · 안내선을 끈다. */
  pointed: PointedPoint | null;
  onPointerMove: (e: PointerEvent<Element>) => void;
  onPointerLeave: () => void;
}

/** 그리는 점 목록에서 훅이 읽는 것 — traceId 하나. */
type DrawnPoint = { trace: { traceId: string } };

/** 포인터 자리를 사건이 붙은 요소(차트 틀) 기준으로 — 요소가 아니면 0 들. */
function pointerAt(e: PointerEvent<Element>): PointedPoint['at'] {
  const box = e.currentTarget instanceof Element ? e.currentTarget.getBoundingClientRect() : null;
  if (box === null) return { x: 0, y: 0, width: 0, height: 0 };
  return { x: e.clientX - box.left, y: e.clientY - box.top, width: box.width, height: box.height };
}

/**
 * Tracks which scatter point the pointer is over, independent of recharts' own
 * enter/leave events (which are lost when a point element is re-created every frame).
 *
 * @param frame any value that changes on every animation frame (the scroll clock)
 * @param drawn the points in the order the chart draws them (same render as the DOM)
 */
export function useScatterHover(frame: unknown, drawn: ReadonlyArray<DrawnPoint>): ScatterHover {
  const [pointed, setPointed] = useState<PointedPoint | null>(null);
  const pointer = useRef<{ x: number; y: number } | null>(null);

  // 처리기는 같은 렌더의 목록을 닫아 쓴다 — 새 DOM 과 옛 목록이 섞이지 않는다(CR-R29-25).
  const onPointerMove = useCallback(
    (e: PointerEvent<Element>) => {
      pointer.current = { x: e.clientX, y: e.clientY };
      // [R29/AC-R29-26 · UXD-R29-07 · 실기동 스모크 2-b] 상자 자리는 처리기 본문에서 읽는다 — 갱신 함수는 큐가 차 있으면
      //   렌더 때 늦게 돌고, 그때 React 는 사건의 currentTarget 을 이미 비워 자리가 0 이 된다(상자가 틀 오른쪽 아래에 붙음).
      const at = pointerAt(e);
      const index = pointIndexOf(e.target, drawn.length);
      const traceId = index === null ? null : (drawn[index]?.trace.traceId ?? null);
      setPointed((prev) => {
        if (traceId === null) return null;
        // 같은 점이면 상태를 안 바꾼다(같은 객체 · 효과 되풀이 0) — 상자 자리(at)는 점이 바뀔 때만 갱신.
        if (prev !== null && prev.traceId === traceId) return prev;
        return { traceId, at };
      });
    },
    [drawn],
  );

  const onPointerLeave = useCallback(() => {
    pointer.current = null;
    setPointed(null);
  }, []);

  const isOn = pointed !== null;
  const pointedTraceId = pointed?.traceId ?? null;

  // 켜져 있는 동안만 프레임 · 목록이 바뀔 때마다 다시 본다 — 꺼져 있으면 아무 일도 안 한다(흐르는 동안 비용 0).
  useEffect(() => {
    if (pointedTraceId === null) return;
    const p = pointer.current;
    if (p === null) {
      setPointed(null);
      return;
    }
    const hit =
      typeof document.elementFromPoint === 'function' ? document.elementFromPoint(p.x, p.y) : null;
    const index = pointIndexOf(hit, drawn.length);
    const under = index === null ? null : (drawn[index]?.trace.traceId ?? null);
    // 빈 곳 · 다른 점 · 목록에서 빠짐 → 끔. 같은 점이면 그대로(새 응답의 같은 trace 도 켠 채).
    if (under !== pointedTraceId) setPointed(null);
  }, [frame, drawn, pointedTraceId]);

  // 켜져 있는 동안만 Escape 로 끈다(다음 움직임까지) — 꺼지면 듣기를 뗀다.
  useEffect(() => {
    if (!isOn) return;
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') setPointed(null);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOn]);

  return { pointed, onPointerMove, onPointerLeave };
}
