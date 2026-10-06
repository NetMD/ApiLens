// [R28/AC-28-04-1 · UXD-30] 차트 누름 붙잡기 — 주 단추만 켜고, 창 포커스 잃음 · 문서 숨김 · 문맥 메뉴에서 푼다.
//
// 프레임 시계 훅을 모의로 바꿔 차트가 넘기는 붙잡기 인자(hold)만 본다 — 시계·그림은 시험 대상이 아니다(NFR-06).
// 실시간 대기 0 · 가짜 타이머 0(사건만 보낸다 · G-R28-19 ②).
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   holdsOnlyForThePrimaryButton · releasesTheHoldOnWindowBlur · releasesTheHoldWhenTheDocumentHides
//   releasesTheHoldOnContextMenu · neverHoldsOnHoverOnly
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { TracePoint } from '../types/api';

const clockSpy = vi.hoisted(() => vi.fn());
vi.mock('../hooks/useScrollClock', () => ({
  useScrollClock: (opts: { hold?: boolean }): number | null => {
    clockSpy(opts);
    return null;
  },
}));

import { LatencyScatter } from '../components/LatencyScatter';

const SEC = 1_730_000_000_000;

const points: TracePoint[] = [
  { traceId: 'a', rootOperation: 'GET /a', startTime: SEC - 30_000, durationMs: 12, status: 'OK', hasError: false },
];

/** 마지막 렌더에서 차트가 프레임 시계에 넘긴 붙잡기 값. */
const lastHold = (): boolean => (clockSpy.mock.calls.at(-1)![0] as { hold?: boolean }).hold ?? false;

function renderChart(): HTMLElement {
  render(
    <MemoryRouter>
      <LatencyScatter
        points={points}
        window={{
          kind: 'scroll',
          range: '1m',
          scrollEnabled: true,
          querySince: SEC - 68_000,
          queryUntil: SEC,
          anchorAt: SEC + 100,
        }}
        yMax={1_000}
      />
    </MemoryRouter>,
  );
  return screen.getByRole('img', { name: 'Latency scatter' });
}

describe('LatencyScatter — 누름 붙잡기(주 단추만 · 풀림 셋 더함)', () => {
  beforeEach(() => {
    clockSpy.mockClear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // F01 — 주 단추(0)만 붙잡는다 · 오른쪽 단추(2)는 붙잡지 않는다(문맥 메뉴가 뜨는 동안 뗌이 메뉴로 간다).
  it.each([
    ['button 0 → 붙잡음', 0, true],
    ['button 2 → 안 붙잡음', 2, false],
  ] as const)('holdsOnlyForThePrimaryButton — %s', (_label, button, expected) => {
    const chart = renderChart();
    expect(lastHold()).toBe(false); // 전제: 누르기 전에는 붙잡지 않음
    fireEvent.pointerDown(chart, { button });
    expect(lastHold()).toBe(expected);
  });

  // F02 — 누른 채 창 포커스를 잃으면(다른 창으로) 뗌이 안 와도 푼다.
  it('releasesTheHoldOnWindowBlur', () => {
    const chart = renderChart();
    fireEvent.pointerDown(chart, { button: 0 });
    expect(lastHold()).toBe(true); // 전제: 붙잡힘
    act(() => {
      window.dispatchEvent(new Event('blur'));
    });
    expect(lastHold()).toBe(false);
  });

  // F03 — 누른 채 문서가 숨겨지면(탭 전환) 푼다.
  it('releasesTheHoldWhenTheDocumentHides', () => {
    const chart = renderChart();
    fireEvent.pointerDown(chart, { button: 0 });
    expect(lastHold()).toBe(true); // 전제: 붙잡힘
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(lastHold()).toBe(false);
  });

  // F04 — 문맥 메뉴가 열리면 푼다(메뉴가 뜨는 동안 뗌 사건이 메뉴로 간다).
  it('releasesTheHoldOnContextMenu', () => {
    const chart = renderChart();
    fireEvent.pointerDown(chart, { button: 0 });
    expect(lastHold()).toBe(true); // 전제: 붙잡힘
    fireEvent.contextMenu(chart);
    expect(lastHold()).toBe(false);
  });

  // F05 — 올리기만 해서는 붙잡지 않는다(UXD-17 그대로).
  it('neverHoldsOnHoverOnly', () => {
    const chart = renderChart();
    const rendersBefore = clockSpy.mock.calls.length;
    fireEvent.pointerMove(chart, { clientX: 10, clientY: 10 });
    fireEvent.pointerMove(chart, { clientX: 40, clientY: 20 });
    // 전제: 차트가 실제로 그려져 있다(시계 훅이 불렸다 — 빈 통과 0).
    expect(rendersBefore).toBeGreaterThan(0);
    expect(lastHold()).toBe(false);
    for (const [opts] of clockSpy.mock.calls) expect((opts as { hold?: boolean }).hold ?? false).toBe(false);
  });
});
