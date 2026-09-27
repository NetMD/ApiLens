// [R27/FR-27-05 · NFR-27-06] 프레임 시계 훅의 수명 주기 — 가짜 rAF + 주입 시계(실시간 대기 0 · EXT-006).
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   requestsNoFrameBeforeEnabled · startsOnceWhenEnabled · cancelsTheLastIdWhenDisabledAndRunsNoMoreFrames
//   cancelsTheLastIdOnUnmount · requestsNoFrameForLongRanges · requestsNoFrameWhenReducedMotion
//   followsReducedMotionChangesWithoutReload · holdsTheLastValueWhenStopped
//   updatesTenMinuteWindowInHalfPixelSteps · holdsWhilePointerIsDown
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useScrollClock } from '../hooks/useScrollClock';
import type { UseScrollClockOptions } from '../hooks/useScrollClock';

/** 콜백을 대기열에 모으고 id 를 1씩 올려 돌려주는 가짜 rAF. `run` 이 한 프레임을 돌린다. */
class FakeFrames {
  private queue = new Map<number, FrameRequestCallback>();
  private nextId = 1;
  readonly request = vi.fn((cb: FrameRequestCallback): number => {
    const id = this.nextId++;
    this.queue.set(id, cb);
    return id;
  });
  readonly cancel = vi.fn((id: number): void => {
    this.queue.delete(id);
  });
  /** 지금 대기 중인 콜백 수. */
  get pending(): number {
    return this.queue.size;
  }
  /** 마지막으로 내준 id. */
  get lastId(): number {
    return this.nextId - 1;
  }
  /** n 프레임을 돌린다 — 프레임마다 그때 대기 중인 콜백만 부른다. 실제로 부른 콜백 수를 돌려준다. */
  run(n = 1): number {
    let called = 0;
    for (let i = 0; i < n; i++) {
      const due = [...this.queue.values()];
      this.queue.clear();
      act(() => {
        for (const cb of due) cb(0);
      });
      called += due.length;
    }
    return called;
  }
}

/** matchMedia 대역 — `set` 이 change 를 보낸다. */
function installMatchMedia(initial: boolean): {
  matchMedia: ReturnType<typeof vi.fn>;
  set: (next: boolean) => void;
} {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  let matches = initial;
  const matchMedia = vi.fn((query: string) => ({
    get matches() {
      return matches;
    },
    media: query,
    addEventListener: (_type: string, l: (e: MediaQueryListEvent) => void) => listeners.add(l),
    removeEventListener: (_type: string, l: (e: MediaQueryListEvent) => void) =>
      listeners.delete(l),
  }));
  Object.defineProperty(window, 'matchMedia', { value: matchMedia, configurable: true, writable: true });
  return {
    matchMedia,
    set: (next: boolean) => {
      matches = next;
      act(() => {
        for (const l of listeners) l({ matches: next } as MediaQueryListEvent);
      });
    },
  };
}

const BASE = 1_730_000_000_000;
let frames: FakeFrames;
let clockNow: number;
const nowFn = (): number => clockNow;
const originalMatchMedia = Object.getOwnPropertyDescriptor(window, 'matchMedia');

function renderClock(initial: UseScrollClockOptions) {
  return renderHook((p: UseScrollClockOptions) => useScrollClock(p), { initialProps: initial });
}

describe('useScrollClock — rAF 수명 주기', () => {
  beforeEach(() => {
    frames = new FakeFrames();
    clockNow = BASE;
    vi.stubGlobal('requestAnimationFrame', frames.request);
    vi.stubGlobal('cancelAnimationFrame', frames.cancel);
    installMatchMedia(false);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    if (originalMatchMedia) Object.defineProperty(window, 'matchMedia', originalMatchMedia);
    else Reflect.deleteProperty(window, 'matchMedia');
  });

  // TZ-15 — 기대 0 의 전제: 같은 시험에서 참으로 바꾸면 1 이 된다.
  it('requestsNoFrameBeforeEnabled', () => {
    const { rerender } = renderClock({ enabled: false, range: '1m', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(0);
    rerender({ enabled: true, range: '1m', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(1);
  });

  // TZ-16 — 거짓→참 시작 1회 · N 프레임 동안 취소 0 · rAF 호출 = N + 1.
  it('startsOnceWhenEnabled', () => {
    const { rerender } = renderClock({ enabled: false, range: '1m', nowFn });
    rerender({ enabled: true, range: '1m', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(1);
    const N = 4;
    for (let i = 0; i < N; i++) {
      clockNow += 16;
      frames.run(1);
    }
    expect(frames.cancel).toHaveBeenCalledTimes(0);
    expect(frames.request).toHaveBeenCalledTimes(N + 1);
  });

  // TZ-17 — 전제: 취소 전 프레임을 ≥ 2 돌려 마지막 id 가 첫 id 와 다르다.
  it('cancelsTheLastIdWhenDisabledAndRunsNoMoreFrames', () => {
    const { rerender } = renderClock({ enabled: true, range: '1m', nowFn });
    const firstId = frames.lastId;
    frames.run(2);
    const lastId = frames.lastId;
    expect(lastId).not.toBe(firstId);
    rerender({ enabled: false, range: '1m', nowFn });
    expect(frames.cancel).toHaveBeenCalledTimes(1);
    expect(frames.cancel).toHaveBeenLastCalledWith(lastId);
    const requestsAfterStop = frames.request.mock.calls.length;
    expect(frames.run(5)).toBe(0);
    expect(frames.request).toHaveBeenCalledTimes(requestsAfterStop);
  });

  // TZ-18 — 언마운트도 마지막 id 를 취소한다.
  it('cancelsTheLastIdOnUnmount', () => {
    const { unmount } = renderClock({ enabled: true, range: '1m', nowFn });
    const firstId = frames.lastId;
    frames.run(2);
    const lastId = frames.lastId;
    expect(lastId).not.toBe(firstId);
    unmount();
    expect(frames.cancel).toHaveBeenLastCalledWith(lastId);
    expect(frames.pending).toBe(0);
    expect(frames.run(3)).toBe(0);
  });

  // TZ-19 — 1h 이상은 응답 때만 옮긴다(시계 0). 전제: 같은 조건 1m 에서는 1.
  it('requestsNoFrameForLongRanges', () => {
    const long = renderClock({ enabled: true, range: '1h', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(0);
    long.unmount();
    renderClock({ enabled: true, range: '1m', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(1);
  });

  // TZ-20 — 움직임 줄이기면 시계 0. 전제: 모의 matchMedia 가 실제로 그 질의로 불렸다.
  it('requestsNoFrameWhenReducedMotion', () => {
    const media = installMatchMedia(true);
    renderClock({ enabled: true, range: '1m', nowFn });
    expect(media.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    expect(frames.request).toHaveBeenCalledTimes(0);
  });

  // TZ-21 — 설정을 바꾸면 새로고침 없이 따라간다(UXD-06 시험 2점).
  it('followsReducedMotionChangesWithoutReload — 켬 → 시계 취소', () => {
    const media = installMatchMedia(false);
    renderClock({ enabled: true, range: '1m', nowFn });
    frames.run(1);
    const lastId = frames.lastId;
    media.set(true);
    expect(frames.cancel).toHaveBeenLastCalledWith(lastId);
    expect(frames.run(3)).toBe(0);
  });

  it('followsReducedMotionChangesWithoutReload — 끔 → 다시 시작', () => {
    const media = installMatchMedia(true);
    renderClock({ enabled: true, range: '1m', nowFn });
    expect(frames.request).toHaveBeenCalledTimes(0);
    media.set(false);
    expect(frames.request).toHaveBeenCalledTimes(1);
  });

  // TZ-22 — 멈춘 뒤 반환값 = 마지막 프레임 값(null 로 안 돌아감 · UXD-05).
  it('holdsTheLastValueWhenStopped', () => {
    const { result, rerender } = renderClock({ enabled: true, range: '1m', nowFn });
    expect(result.current).toBeNull();
    clockNow = BASE + 100;
    frames.run(1);
    clockNow = BASE + 200;
    frames.run(1);
    expect(result.current).toBe(BASE + 200);
    rerender({ enabled: false, range: '1m', nowFn });
    clockNow = BASE + 9_999;
    expect(result.current).toBe(BASE + 200);
  });

  // TZ-23 · BV-05 — 10m: 299ms 앞섬 → 불변 · 300ms → 갱신(반 픽셀 걸음 · UXD-16).
  it('updatesTenMinuteWindowInHalfPixelSteps', () => {
    const { result } = renderClock({ enabled: true, range: '10m', nowFn });
    frames.run(1);
    expect(result.current).toBe(BASE);
    clockNow = BASE + 299;
    frames.run(1);
    expect(result.current).toBe(BASE);
    clockNow = BASE + 300;
    frames.run(1);
    expect(result.current).toBe(BASE + 300);
    // 시계 자체는 매 프레임 돈다 — 건너뛴 프레임에도 다음 요청이 나갔다.
    expect(frames.request).toHaveBeenCalledTimes(4);
  });

  // TZ-45 — 누르는 동안(hold) 마지막 id 취소 · 프레임 0 · 값 유지 / 떼면 다시 시작 1회(RP-07).
  it('holdsWhilePointerIsDown', () => {
    const { result, rerender } = renderClock({ enabled: true, range: '1m', nowFn, hold: false });
    clockNow = BASE + 50;
    frames.run(1);
    expect(result.current).toBe(BASE + 50);
    const lastId = frames.lastId;
    rerender({ enabled: true, range: '1m', nowFn, hold: true });
    expect(frames.cancel).toHaveBeenLastCalledWith(lastId);
    clockNow = BASE + 5_000;
    expect(frames.run(3)).toBe(0);
    expect(result.current).toBe(BASE + 50);
    const requestsWhileHeld = frames.request.mock.calls.length;
    rerender({ enabled: true, range: '1m', nowFn, hold: false });
    expect(frames.request).toHaveBeenCalledTimes(requestsWhileHeld + 1);
  });
});

// [v0.8.0] Live OFF 한 칸 넘김의 미끄러짐 — runUntil 까지만 돌고 스스로 멈춘다.
describe('useScrollClock — 한 칸 넘김 미끄러짐(runUntil)', () => {
  beforeEach(() => {
    frames = new FakeFrames();
    clockNow = BASE;
    vi.stubGlobal('requestAnimationFrame', frames.request);
    vi.stubGlobal('cancelAnimationFrame', frames.cancel);
    installMatchMedia(false);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    if (originalMatchMedia) Object.defineProperty(window, 'matchMedia', originalMatchMedia);
    else Reflect.deleteProperty(window, 'matchMedia');
  });

  it('runsForAnyRangeUntilRunUntilThenStopsByItself', () => {
    // 1시간 창 · 게이트 닫힘이어도 runUntil 이 있으면 돈다.
    const { result } = renderClock({ enabled: false, range: '1h', runUntil: BASE + 500, nowFn });
    expect(frames.request).toHaveBeenCalledTimes(1);
    clockNow = BASE + 16;
    frames.run(1);
    expect(result.current).toBe(BASE + 16);
    clockNow = BASE + 500;
    frames.run(1);
    expect(result.current).toBe(BASE + 500); // 끝 프레임 값은 남긴다
    expect(frames.pending).toBe(0); // 끝을 넘긴 프레임에서 다음을 청하지 않는다
    expect(frames.run(3)).toBe(0);
  });

  it('doesNotSlideWhenReducedMotionIsOn', () => {
    installMatchMedia(true);
    renderClock({ enabled: false, range: '1m', runUntil: BASE + 500, nowFn });
    expect(frames.request).toHaveBeenCalledTimes(0);
  });
});
