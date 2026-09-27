// [v0.8.0] 흐르는 산점도의 툴팁·안내선 켜고 끄기 판정 — 점 요소가 매 프레임 새로 생겨도 선이 남지 않는다.
// 09-27 사용자 실측: 점에 마우스를 올리면 생긴 가로세로 선이 마우스를 옮겨도 안 없어졌다.
import { act, renderHook } from '@testing-library/react';
import type { PointerEvent } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { isScatterPoint, useScatterHover } from '../hooks/useScatterHover';

/** recharts 가 그리는 점 한 개와 같은 모양(<g class="recharts-scatter-symbol"><path/></g>). */
function makePoint(): { group: Element; path: Element } {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  const group = document.createElementNS(ns, 'g');
  group.setAttribute('class', 'recharts-scatter-symbol');
  const path = document.createElementNS(ns, 'path');
  group.appendChild(path);
  svg.appendChild(group);
  document.body.appendChild(svg);
  return { group, path };
}

function makeBlank(): Element {
  const div = document.createElement('div');
  document.body.appendChild(div);
  return div;
}

/** 브라우저 판정 흉내 — 기본값은 「마지막으로 마우스가 움직여 간 요소」를 돌려준다(실제 브라우저와 같게). */
let underPointer: Element | null = null;
const elementFromPoint = vi.fn<(x: number, y: number) => Element | null>(() => underPointer);

function move(target: Element, x = 10, y = 20): PointerEvent<Element> {
  underPointer = target;
  return { target, clientX: x, clientY: y } as unknown as PointerEvent<Element>;
}

beforeEach(() => {
  underPointer = null;
  elementFromPoint.mockReset();
  elementFromPoint.mockImplementation(() => underPointer);
  Object.defineProperty(document, 'elementFromPoint', { value: elementFromPoint, configurable: true });
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('isScatterPoint', () => {
  it('점 안쪽 요소면 참 · 점 밖이거나 값이 없으면 거짓', () => {
    const { group, path } = makePoint();
    expect(isScatterPoint(path)).toBe(true);
    expect(isScatterPoint(group)).toBe(true);
    expect(isScatterPoint(makeBlank())).toBe(false);
    expect(isScatterPoint(null)).toBe(false);
  });
});

describe('useScatterHover', () => {
  it('점 위로 움직이면 켜지고, 빈 곳으로 움직이면 꺼진다', () => {
    const { path } = makePoint();
    const blank = makeBlank();
    const { result } = renderHook(({ frame }) => useScatterHover(frame), {
      initialProps: { frame: 0 },
    });
    expect(result.current.overPoint).toBe(false);
    act(() => result.current.onPointerMove(move(path)));
    expect(result.current.overPoint).toBe(true);
    act(() => result.current.onPointerMove(move(blank)));
    expect(result.current.overPoint).toBe(false);
  });

  it('차트를 벗어나면 꺼진다', () => {
    const { path } = makePoint();
    const { result } = renderHook(({ frame }) => useScatterHover(frame), {
      initialProps: { frame: 0 },
    });
    act(() => result.current.onPointerMove(move(path)));
    act(() => result.current.onPointerLeave());
    expect(result.current.overPoint).toBe(false);
  });

  it('마우스를 가만히 둬도 점이 흘러가 버리면 다음 프레임에 꺼진다(요소가 새로 생겨 나감 신호가 없어도)', () => {
    const { path } = makePoint();
    const blank = makeBlank();
    const { result, rerender } = renderHook(({ frame }) => useScatterHover(frame), {
      initialProps: { frame: 0 },
    });
    act(() => result.current.onPointerMove(move(path, 33, 44)));
    rerender({ frame: 1 });
    expect(result.current.overPoint).toBe(true); // 아직 점 위
    expect(elementFromPoint).toHaveBeenLastCalledWith(33, 44); // 마지막 마우스 자리를 다시 본다

    underPointer = blank; // 점이 흘러가 마우스 밑이 비었다(마우스는 안 움직임)
    rerender({ frame: 2 });
    expect(result.current.overPoint).toBe(false);
  });

  it('꺼져 있는 동안은 프레임이 흘러도 다시 보지 않고, 점이 흘러 들어와도 스스로 켜지지 않는다', () => {
    const { path } = makePoint();
    const blank = makeBlank();
    const { result, rerender } = renderHook(({ frame }) => useScatterHover(frame), {
      initialProps: { frame: 0 },
    });
    act(() => result.current.onPointerMove(move(blank)));
    elementFromPoint.mockClear();
    underPointer = path; // 점이 가만히 있는 마우스 밑으로 흘러 들어왔다
    rerender({ frame: 1 });
    rerender({ frame: 2 });
    expect(elementFromPoint).not.toHaveBeenCalled();
    expect(result.current.overPoint).toBe(false);
  });
});
