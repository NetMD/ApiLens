// [R29] 산점도 렌더 시험 — 실제 LatencyScatter 를 고정 크기(800 × 300)로 그려 본다.
//
// 틀: recharts 의 ResponsiveContainer 만 고정 크기 복제로 바꾼다(happy-dom 은 레이아웃을 못 재 틀 크기가 0 이 된다).
//   프레임 시계 훅은 null 을 돌려주는 모의다(흐름 · 시계는 이 파일의 대상이 아니다).
// 붙잡기 시험(LatencyScatterHold.test.tsx)은 그림을 안 그리는 판이라 이 파일과 섞지 않는다(DD-R29-23).
// 시험마다 전제 단언(점 ≥ 1 · 눈금 ≥ 1 · 상자 노드)을 먼저 둔다 — 틀 크기 0 이면 빈 그물이 초록으로 나온다.
// ★타입 검사(tsc -b)는 시험 파일을 빼므로 이 파일의 형 오류는 vitest 실행이 잡는다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   drawsScatterSymbolsInTheDrawnPointOrder(착수 전 관문 · TA-R29-20) · paintsAxisTickLabelsWithTheDarkerGreyAndKeepsTheAxisLine
//   showsTheTooltipOfThePointUnderThePointerAfterAJump · showsTheBucketLineOnlyForAnInteriorBucket
//   passesTheRightMarginConstantToThePlotArea · clampsALongOperationNameToThreeLinesInTheTooltip
import { cloneElement } from 'react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { TracePoint } from '../types/api';
import { STATUS_HEX } from '../lib/colors';

const { CHART_W, CHART_H } = vi.hoisted(() => ({ CHART_W: 800, CHART_H: 300 }));
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>();
  return {
    ...actual,
    ResponsiveContainer: ({ children }: { children: ReactElement<{ width?: number; height?: number }> }) =>
      cloneElement(children, { width: CHART_W, height: CHART_H }),
  };
});
vi.mock('../hooks/useScrollClock', () => ({
  useScrollClock: (): number | null => null,
}));

import { CHART_MARGIN_RIGHT_PX, LatencyScatter } from '../components/LatencyScatter';
import type { BucketCounts } from '../lib/chartSample';

const SEC = 1_730_000_000_000;

function pt(id: string, startTime: number, durationMs: number, status: 'OK' | 'ERROR' = 'OK'): TracePoint {
  return { traceId: id, rootOperation: `GET /${id}`, startTime, durationMs, status, hasError: status === 'ERROR' };
}

function renderChart(points: TracePoint[], bucketCounts: BucketCounts | null = null): HTMLElement {
  const { container } = render(
    <MemoryRouter>
      <LatencyScatter
        points={points}
        bucketCounts={bucketCounts}
        window={{ kind: 'fixed', range: '1m', since: SEC - 60_000, until: SEC }}
        yMax={10_000}
      />
    </MemoryRouter>,
  );
  return container;
}

/** 그려진 점 묶음 요소 — 문서 순서(= 형제 순서). */
const symbols = (root: HTMLElement): Element[] => Array.from(root.querySelectorAll('.recharts-scatter-symbol'));
const tooltipBoxes = (): HTMLElement[] => screen.queryAllByTestId('scatter-tooltip');
const guideLines = (root: HTMLElement): Element[] => Array.from(root.querySelectorAll('.recharts-reference-line'));
const chartFrame = (): HTMLElement => screen.getByRole('img', { name: 'Latency scatter' });

/** 브라우저 판정 흉내(happy-dom 의 elementFromPoint 는 늘 null) — 마지막으로 포인터가 간 요소를 돌려준다. */
let underPointer: Element | null = null;

/** 실제 브라우저처럼 포인터 아래 요소에서 사건이 일어나 차트 틀까지 올라간다. */
function pointAt(target: Element, x: number, y: number): void {
  underPointer = target;
  fireEvent.pointerMove(target, { clientX: x, clientY: y });
}

beforeEach(() => {
  underPointer = null;
  Object.defineProperty(document, 'elementFromPoint', { value: () => underPointer, configurable: true });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LatencyScatter — 렌더(고정 크기 틀)', () => {
  // TA-R29-20 · AC-R29-25 — 착수 전 관문 [S-174]: 「recharts 2.15.0 은 `.recharts-scatter-symbol` 을 그리는 점 목록
  //   순서대로 형제로 그린다」. 점 셋의 색이 서로 달라(오류 · 정상 · 느림) 순서가 뒤집히면 가려진다.
  it('drawsScatterSymbolsInTheDrawnPointOrder', () => {
    const drawn = [
      pt('err', SEC - 40_000, 30, 'ERROR'),
      pt('ok', SEC - 30_000, 12),
      pt('slow', SEC - 20_000, 1_500),
    ];
    const root = renderChart(drawn);
    const groups = symbols(root);
    // 전제: 점이 실제로 그려졌다(틀 크기 0 이면 0 개).
    expect(groups.length).toBeGreaterThanOrEqual(1);
    expect(groups.length).toBe(drawn.length);
    // 전제: 같은 부모의 형제다(훅이 형제 순번을 읽는다).
    const parent = groups[0]!.parentElement;
    expect(groups.every((g) => g.parentElement === parent)).toBe(true);
    const fills = groups.map((g) => g.querySelector('path')?.getAttribute('fill') ?? null);
    expect(fills).toEqual([STATUS_HEX.error, STATUS_HEX.ok, STATUS_HEX.slow]);
  });

  // TA-R29-21 · AC-R29-22 · UXD-R29-05 — 축 눈금 글자만 진한 회색(흰 바탕 4.80:1) · 축 선은 원래 회색 그대로.
  it('paintsAxisTickLabelsWithTheDarkerGreyAndKeepsTheAxisLine', () => {
    const root = renderChart([pt('a', SEC - 30_000, 12)]);
    const tickTexts = Array.from(root.querySelectorAll('.recharts-cartesian-axis-tick-value'));
    // 전제: 눈금 글자가 실제로 그려졌다(X · Y 둘 다).
    expect(root.querySelectorAll('.xAxis .recharts-cartesian-axis-tick-value').length).toBeGreaterThanOrEqual(1);
    expect(root.querySelectorAll('.yAxis .recharts-cartesian-axis-tick-value').length).toBeGreaterThanOrEqual(1);
    for (const t of tickTexts) expect(t.getAttribute('fill')).toBe('#78716C');
    const axisLines = Array.from(root.querySelectorAll('.recharts-cartesian-axis-line'));
    expect(axisLines.length).toBe(2); // 전제: 축 선 둘
    for (const l of axisLines) expect(l.getAttribute('stroke')).toBe('#888780');
  });

  // TA-R29-22 · AC-R29-24 · 26 · CX-1(말풍선 머묾) — 점 A 위 → A 글, 맞닿은 점 B 로 곧장 → B 글(앞 점 글 0).
  //   상자 1 · 가로세로 안내선 2 가 함께 따라가고, 빈 곳으로 가면 셋 다 0.
  it('showsTheTooltipOfThePointUnderThePointerAfterAJump', () => {
    const root = renderChart([pt('a', SEC - 30_000, 12), pt('b', SEC - 29_000, 14)]);
    const [a, b] = symbols(root);
    expect(a).toBeDefined(); // 전제: 점 둘이 그려졌다
    expect(b).toBeDefined();
    expect(tooltipBoxes()).toHaveLength(0);
    expect(guideLines(root)).toHaveLength(0);
    // 틀 자리 · 크기(happy-dom 은 레이아웃을 못 재 0 이 된다) — 상자 자리 단언의 기준(틀 왼쪽 위 = 화면 100 · 50).
    vi.spyOn(chartFrame(), 'getBoundingClientRect').mockReturnValue(
      DOMRect.fromRect({ x: 100, y: 50, width: CHART_W, height: CHART_H }),
    );

    pointAt(a!.querySelector('path')!, 200, 150);
    expect(tooltipBoxes()).toHaveLength(1);
    expect(tooltipBoxes()[0]).toHaveTextContent('GET /a');
    expect(guideLines(root)).toHaveLength(2);

    // 켜도 점 요소는 그대로다 — 안내선을 켤 때 끼워 넣으면 recharts 가 점 묶음을 통째로 새로 만들었다(dev 실측 · DF-R29-02).
    expect(symbols(root)[0]).toBe(a);
    expect(symbols(root)[1]).toBe(b);
    pointAt(b!.querySelector('path')!, 202, 149);
    expect(tooltipBoxes()).toHaveLength(1);
    expect(tooltipBoxes()[0]).toHaveTextContent('GET /b');
    expect(tooltipBoxes()[0]).not.toHaveTextContent('GET /a');
    expect(guideLines(root)).toHaveLength(2);

    pointAt(chartFrame(), 5, 5);
    expect(tooltipBoxes()).toHaveLength(0);
    expect(guideLines(root)).toHaveLength(0);

    // [R29/AC-R29-26 · UXD-R29-07 · 실기동 스모크 2-b] 상자 자리는 B 를 가리킨 포인터에서 나온다 — 0 좌표 폴백(틀 오른쪽 아래)이 아니다.
    //   A → B 를 한 act 안에서 보내 큐를 비우지 않는다: 둘째 갱신은 렌더 때 돌고, 그때 React 는 이미 사건의
    //   currentTarget 을 비웠다(실브라우저에서는 흐르는 시계의 갱신이 큐를 채워 32번 중 24번이 이 길).
    act(() => {
      pointAt(a!.querySelector('path')!, 300, 150);
      pointAt(b!.querySelector('path')!, 304, 152);
    });
    expect(tooltipBoxes()).toHaveLength(1); // 전제: 상자 노드
    expect(tooltipBoxes()[0]).toHaveTextContent('GET /b');
    // 틀 기준 (204, 102) → 왼쪽 절반 · 위 절반 → 포인터 오른쪽 아래로 띄움 12.
    const box = tooltipBoxes()[0]!;
    expect(box.style.left).toBe('216px');
    expect(box.style.top).toBe('114px');
    expect(box.style.right).toBe('');
    expect(box.style.bottom).toBe('');
  });

  // [R29/AC-R29-56 · CX-1 ⓐ · QR-R29-06] 600자 이름 점을 틀 가운데 줄에서 가리키면 이름 요소가 세 줄 제한 클래스를 갖고,
  //   글자 노드 하나에 전체 이름을 그대로 담는다(줄임은 CSS 몫 · 글을 자르지 않음). 상자 자리는 사분면 규칙 그대로 · 높이를 재지 않는다.
  //   happy-dom 은 배치를 못 재 상자 높이는 못 본다 — 실제 상자 끝 ↔ 틀 끝은 QA 실브라우저 몫이다.
  it('clampsALongOperationNameToThreeLinesInTheTooltip', () => {
    const longName = `GET /api/${'x'.repeat(591)}`;
    expect(longName).toHaveLength(600); // 전제: 600자 이름
    const root = renderChart([{ ...pt('long', SEC - 30_000, 12), rootOperation: longName }]);
    const [p] = symbols(root);
    expect(p).toBeDefined(); // 전제: 점이 그려졌다
    vi.spyOn(chartFrame(), 'getBoundingClientRect').mockReturnValue(
      DOMRect.fromRect({ x: 100, y: 50, width: CHART_W, height: CHART_H }),
    );

    // 틀 기준 (200, 150) = 가운데 줄(높이 300 의 절반) → 왼쪽 절반 · 위 절반 아님 → 포인터 오른쪽 위로 띄움 12.
    pointAt(p!.querySelector('path')!, 300, 200);
    expect(tooltipBoxes()).toHaveLength(1); // 전제: 상자 노드
    const box = tooltipBoxes()[0]!;
    expect(box).toHaveTextContent(longName.slice(0, 40));
    expect(box).toHaveTextContent('12ms · OK'); // 둘째 줄(시각 · 시간 · 상태)은 그대로 보인다
    const name = within(box).getByText(longName);
    expect(name).toHaveClass('line-clamp-3');
    expect(name.childNodes).toHaveLength(1);
    expect(name.firstChild?.nodeType).toBe(Node.TEXT_NODE);
    expect(box.style.left).toBe('212px');
    expect(box.style.bottom).toBe('162px');
    expect(box.style.top).toBe('');
    expect(box.style.maxHeight).toBe('');
  });

  // TA-R29-23 · AC-R29-27 — 셋째 줄은 안쪽 칸 점만 「이 칸 전체 N건 · 오류 M건」 · 칸 표에 없는 점은 줄 없음(0 으로 안 씀).
  it('showsTheBucketLineOnlyForAnInteriorBucket', () => {
    const inside = pt('inside', SEC - 30_000, 12);
    const edge = pt('edge', SEC - 59_500, 14);
    const table: BucketCounts = { bucketMs: 1_000, counts: new Map([[SEC - 30_000, { count: 12, errorCount: 3 }]]) };
    const root = renderChart([inside, edge], table);
    const [first, second] = symbols(root);
    expect(first).toBeDefined(); // 전제: 점 둘이 그려졌다
    expect(second).toBeDefined();

    pointAt(first!.querySelector('path')!, 200, 150);
    expect(tooltipBoxes()).toHaveLength(1); // 전제: 상자 노드
    expect(tooltipBoxes()[0]).toHaveTextContent('이 칸 전체 12건 · 오류 3건');

    pointAt(second!.querySelector('path')!, 60, 150);
    expect(tooltipBoxes()).toHaveLength(1); // 전제: 상자 노드
    expect(tooltipBoxes()[0]).toHaveTextContent('GET /edge');
    expect(tooltipBoxes()[0]).not.toHaveTextContent('이 칸 전체');
  });

  // TA-R29-24 · AC-R29-38 · R28 QA R-9 — 오른쪽 여백 상수가 그림 영역으로 넘어간다(그림 영역 오른쪽 끝 = 틀 폭 − 여백).
  //   happy-dom 은 레이아웃을 못 잰다 — 실제 끝 눈금 잘림은 QA 실브라우저 몫이다.
  it('passesTheRightMarginConstantToThePlotArea', () => {
    const root = renderChart([pt('a', SEC - 30_000, 12)]);
    const clip = root.querySelector('.recharts-scatter clipPath rect');
    expect(clip).not.toBeNull(); // 전제: 그림 영역 자르기 사각형이 그려졌다
    const x = Number(clip!.getAttribute('x'));
    const width = Number(clip!.getAttribute('width'));
    expect(width).toBeGreaterThan(0);
    expect(x + width).toBe(CHART_W - CHART_MARGIN_RIGHT_PX);
  });
});
