// [R27/FR-27-05] 흐르는 차트의 프레임 시계.
//
// 화면에서 requestAnimationFrame · cancelAnimationFrame 을 부르는 유일한 파일이다(G-R27-01).
// 이 훅은 LatencyScatter 안에서만 부른다 — 프레임 값을 페이지 상태로 올리면 헤더·목록까지
// 초당 60번 다시 그려진다(Dashboard 는 5초 주기로만 다시 그린다).
//
// 도는 조건: enabled && !hold && 1m·5m·10m && 움직임 줄이기 꺼짐.
// [v0.8.0] 또는 runUntil 이 있으면(Live OFF 한 칸 넘김의 미끄러짐) 그 시각까지만 돈다 — 범위와 상관없이,
//   움직임 줄이기가 켜져 있으면 돌지 않는다. rAF 를 부르는 곳을 이 파일 하나로 유지하려고 여기에 둔다.
// 멈춰도 마지막 값을 지우지 않는다(UXD-05 — 창이 마지막 프레임 자리에 선다). 처음부터 안 돌면 null.
import { useEffect, useRef, useState } from 'react';
import { isClockRange, scrollStepMs } from '../lib/time';
import type { RangePreset } from '../lib/time';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

export interface UseScrollClockOptions {
  /** 게이트 — Live ON · 인증 · 수신 중 · 응답 ≥ 1 을 부르는 쪽이 모아 넘긴다. */
  enabled: boolean;
  range: RangePreset;
  /** [R27/RP-07] 점을 누르는 동안 참 — 누르는 사이 창이 밀려 클릭이 다른 점에 떨어지는 것을 막는다. */
  hold?: boolean;
  /** [v0.8.0] 이 시각(epoch ms)까지는 게이트·범위와 상관없이 돈다. null 이면 해당 없음. */
  runUntil?: number | null;
  /** 시계 원천(시험 주입용). 기본 Date.now. */
  nowFn?: () => number;
}

/** 마지막으로 반영한 프레임 시각(epoch ms). 한 번도 안 돌았으면 null. */
export function useScrollClock({
  enabled,
  range,
  hold = false,
  runUntil = null,
  nowFn = Date.now,
}: UseScrollClockOptions): number | null {
  const reducedMotion = usePrefersReducedMotion();
  const scrolling = enabled && !hold && isClockRange(range) && !reducedMotion;
  const sliding = runUntil !== null && !reducedMotion;
  const running = scrolling || sliding;
  const [now, setNow] = useState<number | null>(null);

  // nowFn 이 렌더마다 새 함수여도 효과를 다시 걸지 않게 ref 로 읽는다.
  const nowFnRef = useRef(nowFn);
  useEffect(() => {
    nowFnRef.current = nowFn;
  }, [nowFn]);
  // 직전에 **반영한** 값 — 다시 그리기 걸음(scrollStepMs) 비교 기준.
  const lastRef = useRef<number | null>(null);

  useEffect(() => {
    if (!running) return;
    const step = scrolling ? scrollStepMs(range) : 0;
    // 매 프레임 새 id 로 덮어쓴다 — 정리에서 **마지막으로 받은 id** 를 취소해야 그 뒤 프레임이 0 이다.
    let frameId = 0;
    const tick = (): void => {
      const t = nowFnRef.current();
      const last = lastRef.current;
      // 시계는 매 프레임 돈다. 건너뛰는 것은 「새 값으로 다시 그리기」뿐(UXD-16 · 반 픽셀 미만 이동).
      if (last === null || t - last >= step) {
        lastRef.current = t;
        setNow(t);
      }
      // 미끄러짐만으로 돌던 중이면 끝 시각을 넘긴 프레임에서 멈춘다(마지막 값은 남긴다).
      if (!scrolling && runUntil !== null && t >= runUntil) return;
      frameId = requestAnimationFrame(tick);
    };
    frameId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frameId);
  }, [running, scrolling, range, runUntil]);

  return now;
}
