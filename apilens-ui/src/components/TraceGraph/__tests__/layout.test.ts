// computeLayout 회귀 가드 (NFR-04 강제).
//
// 검증 항목 (v0.1.1 분기/단일 경로 분기 처리):
//   1. 단일 경로 자식 → 두 edge (forward + reverse), direction 'in'/'out' 박힘
//   2. 분기 자식 (parent 자식 2개 이상) → 한 edge per child, markerStart + markerEnd 양방향
//   3. ERROR 자식 → reverse 생략 (단일 경로) / markerStart 생략 (분기)
//   4. 빈 배열, 1 span, orphan 케이스
import { describe, expect, it } from 'vitest';
import type { SpanDetail, SpanKind, TraceStatus } from '../../../types/api';
import { __LAYOUT_PARAMS__, computeLayout } from '../layout';

function span(
  spanId: string,
  parentSpanId: string | null,
  spanKind: SpanKind = 'INTERNAL',
  status: TraceStatus = 'OK',
): SpanDetail {
  return {
    spanId,
    parentSpanId,
    serviceName: 'test',
    operationName: spanId,
    spanKind,
    startTime: 0,
    endTime: 100,
    status,
    attributes: {},
  };
}

describe('computeLayout', () => {
  it('A는 자식 2(B/D) 분기 → 한 edge(양방향), B는 단일 자식 C → 두 edge(forward+reverse)', () => {
    const spans = [
      span('A', null, 'SERVER'),         // 자식 2개 (B, D) → 분기
      span('B', 'A', 'INTERNAL'),         // A의 자식 (분기) + 자기 자식 C 1개 → 단일
      span('C', 'B', 'DB'),               // B의 단일 자식
      span('D', 'A', 'CLIENT'),           // A의 자식 (분기)
    ];
    const r = computeLayout(spans, 'trace-xyz');

    expect(r.nodes).toHaveLength(4);
    // A→B (분기): edge 1개 양방향
    // A→D (분기): edge 1개 양방향
    // B→C (단일): forward + reverse = edge 2개
    // 총 4개 edge.
    expect(r.edges).toHaveLength(4);

    const aToB = r.edges.find((e) => e.id === 'A-B');
    const aToD = r.edges.find((e) => e.id === 'A-D');
    const bToCForward = r.edges.find((e) => e.id === 'B-C');
    const bToCReverse = r.edges.find((e) => e.id === 'C-B-rev');

    // 분기 케이스: markerStart + markerEnd 둘 다, data.direction 없음
    [aToB, aToD].forEach((e) => {
      expect(e).toBeDefined();
      expect(e!.markerStart).toBeDefined();
      expect(e!.markerEnd).toBeDefined();
      const ed = e!.data as { direction?: string };
      expect(ed.direction).toBeUndefined();
    });

    // 단일 경로: forward + reverse 두 edge, 각자 direction 박힘
    expect(bToCForward).toBeDefined();
    expect(bToCForward!.markerEnd).toBeDefined();
    expect((bToCForward!.data as { direction?: string }).direction).toBe('in');

    expect(bToCReverse).toBeDefined();
    expect(bToCReverse!.markerEnd).toBeDefined();
    expect((bToCReverse!.data as { direction?: string }).direction).toBe('out');

    // 모든 노드에 유한 좌표 존재
    r.nodes.forEach((n) => {
      expect(Number.isFinite(n.position.x)).toBe(true);
      expect(Number.isFinite(n.position.y)).toBe(true);
    });

    // root(A)가 leftmost — rankdir 'LR' 검증
    const a = r.nodes.find((n) => n.id === 'A');
    const c = r.nodes.find((n) => n.id === 'C');
    expect(a).toBeDefined();
    expect(c).toBeDefined();
    expect(a!.position.x).toBeLessThan(c!.position.x);
  });

  it('빈 배열 → 빈 result', () => {
    expect(computeLayout([])).toEqual({ nodes: [], edges: [] });
  });

  it('1 span (root만) → 1 node + 0 edges', () => {
    const r = computeLayout([span('A', null, 'SERVER')]);
    expect(r.nodes).toHaveLength(1);
    expect(r.edges).toHaveLength(0);
  });

  it('orphan span (parent가 spans 안에 없음) → 다중 root, 엣지 미생성', () => {
    const spans = [
      span('A', null, 'SERVER'),
      span('B', 'GHOST', 'DB'), // GHOST는 spans 밖
    ];
    const r = computeLayout(spans);
    expect(r.nodes).toHaveLength(2);
    expect(r.edges).toHaveLength(0);
  });

  it('ERROR 자식 (단일 경로) → forward edge 1개만, stroke 빨강(#E24B4A)', () => {
    // ERROR span은 응답이 없으므로(throw) reverse line 자체 미생성 (단일 경로 케이스).
    const spans = [
      span('A', null, 'SERVER'),
      span('B', 'A', 'DB', 'ERROR'),
    ];
    const r = computeLayout(spans);
    expect(r.edges).toHaveLength(1); // forward만, reverse 없음
    const e = r.edges[0]!;
    const stroke = (e.style as { stroke: string }).stroke;
    expect(stroke).toBe(__LAYOUT_PARAMS__.EDGE_STROKE_ERROR);
    expect(stroke).toBe('#E24B4A');
    expect(e.markerEnd).toBeDefined();
    expect((e.markerEnd as { color: string }).color).toBe('#E24B4A');
    // 단일 경로 forward는 markerStart 없음 (reverse가 별도 edge로 표시됐어야 했지만 ERROR라 생략).
    expect(e.markerStart).toBeUndefined();
    expect((e.data as { direction?: string }).direction).toBe('in');
  });

  it('ERROR 자식 (분기 케이스) → 한 edge 양방향이지만 markerStart 생략', () => {
    const spans = [
      span('A', null, 'SERVER'),
      span('B', 'A', 'DB', 'ERROR'),     // 분기 자식 1
      span('C', 'A', 'INTERNAL'),         // 분기 자식 2
    ];
    const r = computeLayout(spans);
    expect(r.edges).toHaveLength(2); // 둘 다 분기, 각 1개 edge
    const errEdge = r.edges.find((e) => e.id === 'A-B')!;
    expect(errEdge.markerEnd).toBeDefined();
    expect(errEdge.markerStart).toBeUndefined(); // ERROR라 reverse 화살촉 생략
    const okEdge = r.edges.find((e) => e.id === 'A-C')!;
    expect(okEdge.markerStart).toBeDefined(); // OK는 양방향
    expect(okEdge.markerEnd).toBeDefined();
  });

  // v0.1.1: 부모 → 다중 자식 분기 시 곡선이 부모 옆 좁은 영역에 뭉치는 문제 해소를 위해
  // NODESEP 50→70, RANKSEP 80→150 으로 상향 (사용자 명시 "한 점에 뭉침" 회귀 가드).
  it('__LAYOUT_PARAMS__ 박제: NODE_W=180, NODE_H=60, NODESEP=70, RANKSEP=150', () => {
    expect(__LAYOUT_PARAMS__.NODE_W).toBe(180);
    expect(__LAYOUT_PARAMS__.NODE_H).toBe(60);
    expect(__LAYOUT_PARAMS__.NODESEP).toBe(70);
    expect(__LAYOUT_PARAMS__.RANKSEP).toBe(150);
  });
});
