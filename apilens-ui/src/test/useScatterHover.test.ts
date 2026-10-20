// [v0.8.0] 흐르는 산점도의 툴팁·안내선 켜고 끄기 판정 — 점 요소가 매 프레임 새로 생겨도 선이 남지 않는다.
// 09-27 사용자 실측: 점에 마우스를 올리면 생긴 가로세로 선이 마우스를 옮겨도 안 없어졌다.
// [R29/CX-1(말풍선 머묾) · AC-R29-24 · 26] 판정 결과가 「가리킨 점」(traceId) 하나로 넓어졌다 — 점 → 다른 점 순간 이동이면
//   바로 다른 점 · 켜진 동안 포인터 밑이 다른 점이 되면 끔 · 같은 trace 면 켠 채 · 목록에서 빠지면 끔(UXD-R29-07~10).
//   점 찾기 = 형제 순번(그리는 순서) — happy-dom 의 elementFromPoint 는 늘 null 이라 브라우저 판정을 흉내 내는 함수를 넣는다.
// ★타입 검사(tsc -b)는 시험 파일을 빼므로 이 파일의 형 오류는 vitest 실행이 잡는다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   [R29] pointsAtTheNewPointWhenThePointerJumpsToAnother · turnsOffWhenADifferentPointSlidesUnderAStillPointer
//   keepsThePointAcrossANewResponseWithTheSameTrace · turnsOffWhenThePointLeavesTheDrawnList
//   treatsASymbolCountMismatchAsNoPoint · dismissesThePointedPointOnEscape
import { act, renderHook } from '@testing-library/react';
import type { PointerEvent } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { isScatterPoint, pointIndexOf, useScatterHover } from '../hooks/useScatterHover';

const NS = 'http://www.w3.org/2000/svg';

/** recharts 가 그리는 점 n 개와 같은 모양 — 한 부모 아래 형제 <g class="recharts-scatter-symbol"><path/></g>. */
function makePoints(n: number): Array<{ group: Element; path: Element }> {
  const svg = document.createElementNS(NS, 'svg');
  const layer = document.createElementNS(NS, 'g');
  svg.appendChild(layer);
  document.body.appendChild(svg);
  return Array.from({ length: n }, () => {
    const group = document.createElementNS(NS, 'g');
    group.setAttribute('class', 'recharts-layer recharts-scatter-symbol');
    const path = document.createElementNS(NS, 'path');
    group.appendChild(path);
    layer.appendChild(group);
    return { group, path };
  });
}

function makeBlank(): Element {
  const div = document.createElement('div');
  document.body.appendChild(div);
  return div;
}

/** 그리는 점 목록(훅이 읽는 것 = traceId 뿐). */
const drawnOf = (...ids: string[]): Array<{ trace: { traceId: string } }> => ids.map((traceId) => ({ trace: { traceId } }));

/** 브라우저 판정 흉내 — 기본값은 「마지막으로 마우스가 움직여 간 요소」를 돌려준다(실제 브라우저와 같게). */
let underPointer: Element | null = null;
const elementFromPoint = vi.fn<(x: number, y: number) => Element | null>(() => underPointer);

function move(target: Element, x = 10, y = 20): PointerEvent<Element> {
  underPointer = target;
  return { target, clientX: x, clientY: y } as unknown as PointerEvent<Element>;
}

/** 차트 틀(사건이 붙은 요소)을 실은 움직임 — 상자 자리(at)를 재는 갈래. */
function moveInFrame(target: Element, frameEl: Element, x: number, y: number): PointerEvent<Element> {
  underPointer = target;
  return { target, currentTarget: frameEl, clientX: x, clientY: y } as unknown as PointerEvent<Element>;
}

beforeEach(() => {
  underPointer = null;
  elementFromPoint.mockReset();
  elementFromPoint.mockImplementation(() => underPointer);
  Object.defineProperty(document, 'elementFromPoint', { value: elementFromPoint, configurable: true });
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.restoreAllMocks();
});

describe('isScatterPoint · pointIndexOf', () => {
  it('점 안쪽 요소면 참 · 점 밖이거나 값이 없으면 거짓', () => {
    const [{ group, path }] = makePoints(1) as [{ group: Element; path: Element }];
    expect(isScatterPoint(path)).toBe(true);
    expect(isScatterPoint(group)).toBe(true);
    expect(isScatterPoint(makeBlank())).toBe(false);
    expect(isScatterPoint(null)).toBe(false);
  });

  // TA-R29-19 · AC-R29-24 — 형제 수 ≠ 그리는 점 수면 순번이 어느 점인지 모른다 → 「점 없음」(엉뚱한 점 대신 꺼짐).
  it('treatsASymbolCountMismatchAsNoPoint', () => {
    const pts = makePoints(3);
    // 전제: 수가 맞으면 순번이 나온다(빈 통과 0).
    expect(pointIndexOf(pts[2]!.path, 3)).toBe(2);
    expect(pointIndexOf(pts[2]!.path, 2)).toBeNull();
    expect(pointIndexOf(pts[0]!.path, 4)).toBeNull();
    expect(pointIndexOf(makeBlank(), 3)).toBeNull();

    // 훅도 같은 판정 — 목록 둘 · 형제 셋이면 점 위로 움직여도 안 켜진다.
    const { result } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b') },
    });
    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    expect(result.current.pointed).toBeNull();
  });
});

describe('useScatterHover', () => {
  it('점 위로 움직이면 켜지고, 빈 곳으로 움직이면 꺼진다', () => {
    const pts = makePoints(3);
    const blank = makeBlank();
    const { result } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    expect(result.current.pointed).toBeNull();
    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    expect(result.current.pointed !== null).toBe(true);
    expect(result.current.pointed?.traceId).toBe('a');
    act(() => result.current.onPointerMove(move(blank)));
    expect(result.current.pointed).toBeNull();
  });

  it('차트를 벗어나면 꺼진다', () => {
    const pts = makePoints(3);
    const { result } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    act(() => result.current.onPointerMove(move(pts[1]!.path)));
    expect(result.current.pointed !== null).toBe(true); // 전제: 켜짐
    act(() => result.current.onPointerLeave());
    expect(result.current.pointed).toBeNull();
  });

  it('마우스를 가만히 둬도 점이 흘러가 버리면 다음 프레임에 꺼진다(요소가 새로 생겨 나감 신호가 없어도)', () => {
    const pts = makePoints(3);
    const blank = makeBlank();
    const drawn = drawnOf('a', 'b', 'c');
    const { result, rerender } = renderHook(({ frame, drawn: d }) => useScatterHover(frame, d), {
      initialProps: { frame: 0, drawn },
    });
    act(() => result.current.onPointerMove(move(pts[0]!.path, 33, 44)));
    rerender({ frame: 1, drawn });
    expect(result.current.pointed !== null).toBe(true); // 아직 점 위
    expect(elementFromPoint).toHaveBeenLastCalledWith(33, 44); // 마지막 마우스 자리를 다시 본다

    underPointer = blank; // 점이 흘러가 마우스 밑이 비었다(마우스는 안 움직임)
    rerender({ frame: 2, drawn });
    expect(result.current.pointed).toBeNull();
  });

  it('꺼져 있는 동안은 프레임이 흘러도 다시 보지 않고, 점이 흘러 들어와도 스스로 켜지지 않는다', () => {
    const pts = makePoints(3);
    const blank = makeBlank();
    const drawn = drawnOf('a', 'b', 'c');
    const { result, rerender } = renderHook(({ frame, drawn: d }) => useScatterHover(frame, d), {
      initialProps: { frame: 0, drawn },
    });
    act(() => result.current.onPointerMove(move(blank)));
    elementFromPoint.mockClear();
    underPointer = pts[0]!.path; // 점이 가만히 있는 마우스 밑으로 흘러 들어왔다
    rerender({ frame: 1, drawn });
    rerender({ frame: 2, drawn });
    rerender({ frame: 2, drawn: drawnOf('a', 'b', 'c') }); // 새 응답(목록 바뀜)도 꺼진 동안은 다시 안 봄
    expect(elementFromPoint).not.toHaveBeenCalled();
    expect(result.current.pointed !== null).toBe(false);
  });

  // TA-R29-15 · AC-R29-24 — 점 A 에서 맞닿은 점 B 로 순간 이동(빈 곳을 안 거침)이면 그 움직임에서 바로 B.
  it('pointsAtTheNewPointWhenThePointerJumpsToAnother', () => {
    const pts = makePoints(3);
    const frameEl = makeBlank();
    vi.spyOn(frameEl, 'getBoundingClientRect').mockReturnValue(DOMRect.fromRect({ x: 100, y: 50, width: 800, height: 300 }));
    const { result } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    act(() => result.current.onPointerMove(moveInFrame(pts[0]!.path, frameEl, 300, 150)));
    expect(result.current.pointed).toEqual({ traceId: 'a', at: { x: 200, y: 100, width: 800, height: 300 } });
    const onA = result.current.pointed;
    // 같은 점 안에서 움직이면 상태를 안 바꾼다(같은 객체 · 상자 자리 그대로).
    act(() => result.current.onPointerMove(moveInFrame(pts[0]!.path, frameEl, 302, 151)));
    expect(result.current.pointed).toBe(onA);

    act(() => result.current.onPointerMove(moveInFrame(pts[1]!.path, frameEl, 304, 152)));
    expect(result.current.pointed).toEqual({ traceId: 'b', at: { x: 204, y: 102, width: 800, height: 300 } });
  });

  // TA-R29-16 · AC-R29-26 · UXD-R29-09 — 켜진 동안 포인터를 안 움직였는데 밑이 다른 점이 되면 끈다(새 점 글로 안 바꿈).
  it('turnsOffWhenADifferentPointSlidesUnderAStillPointer', () => {
    const pts = makePoints(3);
    const drawn = drawnOf('a', 'b', 'c');
    const { result, rerender } = renderHook(({ frame, drawn: d }) => useScatterHover(frame, d), {
      initialProps: { frame: 0, drawn },
    });
    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    expect(result.current.pointed?.traceId).toBe('a'); // 전제: A 에 켜짐

    underPointer = pts[1]!.path; // 흐름으로 B 가 가만히 있는 포인터 밑에 왔다
    rerender({ frame: 1, drawn });
    expect(result.current.pointed).toBeNull();

    // 꺼진 뒤에는 B 가 밑에 있어도 스스로 켜지지 않는다(움직여야 켬 · UXD-R29-08).
    elementFromPoint.mockClear();
    rerender({ frame: 2, drawn });
    expect(elementFromPoint).not.toHaveBeenCalled();
    expect(result.current.pointed).toBeNull();
  });

  // TA-R29-17 · AC-R29-26 · UXD-R29-10 #6 — 새 응답(목록 바뀜)에도 포인터 밑이 같은 trace 면 켠 채 둔다(프레임 시계가 안 도는 판).
  it('keepsThePointAcrossANewResponseWithTheSameTrace', () => {
    const pts = makePoints(3);
    const { result, rerender } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    const onA = result.current.pointed;
    expect(onA?.traceId).toBe('a'); // 전제: A 에 켜짐

    // 새 응답: 앞에 새 점이 하나 들어와 A 는 둘째 형제가 됐다 — 포인터 밑 요소도 둘째 형제(같은 A).
    underPointer = pts[1]!.path;
    elementFromPoint.mockClear();
    rerender({ frame: 0, drawn: drawnOf('n', 'a', 'b') });
    expect(elementFromPoint).toHaveBeenCalledTimes(1); // 프레임이 그대로여도 목록이 바뀌면 다시 본다
    expect(result.current.pointed).toBe(onA);
  });

  // TA-R29-18 · AC-R29-26 · UXD-R29-10 #7 — 프레임은 그대로 · 목록만 바뀌어 가리킨 점이 빠지면 끈다.
  it('turnsOffWhenThePointLeavesTheDrawnList', () => {
    const pts = makePoints(3);
    const { result, rerender } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    expect(result.current.pointed?.traceId).toBe('a'); // 전제: A 에 켜짐

    const next = drawnOf('b', 'c', 'd'); // A 가 빠진 새 목록 — 포인터 밑 첫 형제는 이제 B 를 그린다
    // 전제: 바뀐 뒤 형제 수 = 새 목록 수(그래서 「다른 점」 갈래로 꺼진다 — 수가 어긋나는 「점 없음」 갈래가 아님).
    expect(pts[0]!.group.parentElement!.children.length).toBe(next.length);
    rerender({ frame: 0, drawn: next });
    expect(result.current.pointed).toBeNull();
  });

  // TA-R29-33 · CR-R29-23 — 켜진 동안 Escape 는 끈다(다음 움직임까지) · 꺼진 동안은 키를 안 듣는다.
  it('dismissesThePointedPointOnEscape', () => {
    const pts = makePoints(3);
    const addSpy = vi.spyOn(document, 'addEventListener');
    const removeSpy = vi.spyOn(document, 'removeEventListener');
    const keydownAdds = (): number => addSpy.mock.calls.filter(([type]) => type === 'keydown').length;
    const { result } = renderHook(({ frame, drawn }) => useScatterHover(frame, drawn), {
      initialProps: { frame: 0, drawn: drawnOf('a', 'b', 'c') },
    });
    expect(keydownAdds()).toBe(0); // 꺼진 동안은 듣지 않는다

    act(() => result.current.onPointerMove(move(pts[0]!.path)));
    expect(result.current.pointed?.traceId).toBe('a'); // 전제: 켜짐
    expect(keydownAdds()).toBe(1);

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    });
    expect(result.current.pointed?.traceId).toBe('a'); // 다른 키는 그대로

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    expect(result.current.pointed).toBeNull();
    expect(removeSpy.mock.calls.filter(([type]) => type === 'keydown').length).toBe(1); // 꺼지면 듣기를 뗀다

    act(() => result.current.onPointerMove(move(pts[2]!.path)));
    expect(result.current.pointed?.traceId).toBe('c'); // 다음 움직임에 다시 켜진다
  });
});
