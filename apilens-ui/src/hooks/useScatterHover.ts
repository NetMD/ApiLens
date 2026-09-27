// [v0.8.0] 흐르는 산점도에서 「마우스가 지금 점 위에 있는가」를 직접 판정한다.
//
// 왜 필요한가: recharts 2.15 Scatter 는 점 <g> 의 key 에 x 좌표를 넣는다. 차트가 매 프레임 흐르면
//   key 가 매 프레임 바뀌어 점 요소가 지워졌다 새로 생긴다. 마우스가 올라가 있던 요소가 지워지면
//   「나감」 신호가 오지 않아 툴팁·가로세로 안내선이 켜진 채 남는다(09-27 사용자 실측).
//   그래서 켜고 끄는 판정을 recharts 의 들어감·나감 신호 대신 여기서 한다.
//
// - 마우스가 움직일 때: 마우스 바로 아래 요소가 점인지 본다(브라우저 판정이라 요소가 바뀌어도 맞다).
// - 차트를 벗어날 때: 끈다.
// - 마우스를 가만히 두어도 점은 흘러가므로, 켜져 있는 동안은 프레임마다 마지막 마우스 자리를 다시 본다.
//   켤 때는 마우스가 움직였을 때만 켠다 — 점이 가만히 있는 마우스 밑으로 흘러 들어올 때 켜면
//   recharts 가 기억하는 이전 점의 내용이 뜰 수 있다.
// 흐름은 멈추지 않는다(UX 설계 UXD-17 「마우스를 올리면 멈추는 방식은 안 흐른다 오판을 부른다」).
import { useCallback, useEffect, useRef, useState } from 'react';
import type { PointerEvent } from 'react';

/** recharts 가 점 하나마다 붙이는 묶음 요소의 class. */
export const SCATTER_SYMBOL_SELECTOR = '.recharts-scatter-symbol';

/** Returns true when the target is (inside) a recharts scatter point. */
export function isScatterPoint(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest(SCATTER_SYMBOL_SELECTOR) !== null;
}

export interface ScatterHover {
  /** 지금 마우스가 점 위에 있는가 — false 면 툴팁·안내선을 끈다. */
  overPoint: boolean;
  onPointerMove: (e: PointerEvent<Element>) => void;
  onPointerLeave: () => void;
}

/**
 * Tracks whether the pointer is over a scatter point, independent of recharts' own
 * enter/leave events (which are lost when a point element is re-created every frame).
 *
 * @param frame any value that changes on every animation frame (the scroll clock)
 */
export function useScatterHover(frame: unknown): ScatterHover {
  const [overPoint, setOverPoint] = useState(false);
  const pointer = useRef<{ x: number; y: number } | null>(null);

  const onPointerMove = useCallback((e: PointerEvent<Element>) => {
    pointer.current = { x: e.clientX, y: e.clientY };
    setOverPoint(isScatterPoint(e.target));
  }, []);

  const onPointerLeave = useCallback(() => {
    pointer.current = null;
    setOverPoint(false);
  }, []);

  // 켜져 있는 동안만 프레임마다 다시 본다 — 꺼져 있으면 아무 일도 안 한다(흐르는 동안 비용 0).
  useEffect(() => {
    if (!overPoint) return;
    const p = pointer.current;
    if (p === null) {
      setOverPoint(false);
      return;
    }
    const hit =
      typeof document.elementFromPoint === 'function' ? document.elementFromPoint(p.x, p.y) : null;
    if (!isScatterPoint(hit)) setOverPoint(false);
  }, [frame, overPoint]);

  return { overPoint, onPointerMove, onPointerLeave };
}
