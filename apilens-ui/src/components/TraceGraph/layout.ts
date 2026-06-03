// dagre 기반 LR(left-right) 자동 레이아웃.
//
// 박제 파라미터 (변경 금지 — CLAUDE.md UI 디자인 철학 + prompt mockup):
//   rankdir = 'LR' (수직 레이아웃 절대 금지)
//   nodesep = 50, ranksep = 80   ◀── F2 fix (CL-04): nodesep 40 → 50 (라벨 노드 위 배치 후 좌우 여유 +10)
//   NODE_W = 180, NODE_H = 60
//
// dagre는 CJS-only 패키지 (Vite 6 esModuleInterop으로 default import 자동 처리).
// STEP 0 smoke 검증 완료 (`import dagre from 'dagre'`).
//
// edge stroke은 시맨틱 토큰 hex의 미러:
//   --color-status-error : #E24B4A  (자식 status === 'ERROR' 일 때)
//   --color-status-ok    : #888780  (그 외)
// F2 fix (CL-03 / NFR-09 적용): raw hex 직접 사용 → STATUS_HEX import 미러로 통합.
// SVG attribute로 CSS var 직렬화 위험이 있어 여전히 hex 자체 사용 — src/index.css 토큰과
// 일치해야 한다 (NFR-05 미러 정책). STATUS_HEX 가 단일 출처.
import dagre from 'dagre';
import { MarkerType } from '@xyflow/react';
import type { SpanDetail } from '../../types/api';
import { STATUS_HEX } from '../../lib/colors';
import type { LayoutResult, SpanFlowEdge, SpanFlowNode } from './types';

// (v0.1.1) Edge 라벨은 사용자 피드백으로 폐기. 응답 정보는 SpanEdge의 hover popup
// (PAYLOAD IN/OUT)으로 대체.

const NODE_W = 180;
const NODE_H = 60;
// nodesep: 같은 column(같은 depth) 노드 vertical 간격.
// ranksep: 인접 column 노드 horizontal 간격.
// 사용자 명시: "한 점에 뭉침" — 4개 자식으로 분기되는 곡선이 부모 옆 좁은 영역에 모임 →
// ranksep 늘려 horizontal 거리 확보, nodesep도 살짝 늘려 분기 각도 여유.
const NODESEP = 70;
const RANKSEP = 150;

const EDGE_STROKE_OK = STATUS_HEX.ok; // '#888780' 미러
const EDGE_STROKE_ERROR = STATUS_HEX.error; // '#E24B4A' 미러

/**
 * spans 평면 배열 → dagre LR 자동 레이아웃 결과.
 *
 * 규칙:
 * - parentSpanId === null → root (BL-01). 다중 root 모두 표시.
 * - parentSpanId !== null 이지만 그 spanId가 spans 안에 없는 경우 → orphan,
 *   다중 root로 처리(엣지 미생성, BL-02).
 * - spans 빈 배열 → { nodes: [], edges: [] }.
 * - dagre 좌표는 노드 중심점 — React Flow는 좌상단 기준이라 NODE_W/2, NODE_H/2 빼서 변환.
 *
 * 인덱스 0번 = root 가정 절대 금지 (BL-01).
 */
export function computeLayout(
  spans: ReadonlyArray<SpanDetail>,
  traceId = '',
): LayoutResult {
  if (spans.length === 0) {
    return { nodes: [], edges: [] };
  }

  const knownIds = new Set(spans.map((s) => s.spanId));

  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: 'LR', nodesep: NODESEP, ranksep: RANKSEP });
  g.setDefaultEdgeLabel(() => ({}));

  spans.forEach((s) => {
    g.setNode(s.spanId, { width: NODE_W, height: NODE_H });
  });

  // edge 생성: parentSpanId가 null 아니고, parent가 spans 안에 존재할 때만.
  spans.forEach((s) => {
    if (s.parentSpanId !== null && knownIds.has(s.parentSpanId)) {
      g.setEdge(s.parentSpanId, s.spanId);
    }
  });

  dagre.layout(g);

  const nodes: SpanFlowNode[] = spans.map((s) => {
    const pos = g.node(s.spanId);
    return {
      id: s.spanId,
      type: 'span',
      position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 },
      data: { span: s },
    };
  });

  const childSpans = spans.filter(
    (s) => s.parentSpanId !== null && knownIds.has(s.parentSpanId),
  );

  // 사용자 명시: 부모가 자식 2개 이상으로 "분기"할 때만 한 line 양방향 (시각 단순화).
  // 단일 자식 경로는 두 line (forward 위 + reverse 아래) 그대로 — 호출/응답 흐름 분리 강조.
  //
  // parent당 자식 수 카운트.
  const childCountByParent = new Map<string, number>();
  for (const s of childSpans) {
    const pid = s.parentSpanId as string;
    childCountByParent.set(pid, (childCountByParent.get(pid) ?? 0) + 1);
  }

  const edges: SpanFlowEdge[] = [];
  for (const s of childSpans) {
    const stroke = s.status === 'ERROR' ? EDGE_STROKE_ERROR : EDGE_STROKE_OK;
    const marker = { type: MarkerType.ArrowClosed, color: stroke, width: 12, height: 12 };
    const isError = s.status === 'ERROR';
    const parentId = s.parentSpanId as string;
    const isBranching = (childCountByParent.get(parentId) ?? 0) >= 2;

    if (isBranching) {
      // 분기: 한 line + 양쪽 화살촉 (ERROR면 markerStart 생략 → 한 방향).
      // 분기 edge들은 부모 옆 좁은 구간에 겹쳐 그려져 hover시 한 edge만 잡히는 한계가 있음.
      // siblingSpanIds + siblingLabels로 같은 부모의 모든 자식 정보를 edge data에 넣어,
      // SpanEdge가 hover시 모든 sibling payload를 같이 fetch + popup에 자식별 섹션으로 표시.
      const siblings = childSpans.filter((c) => c.parentSpanId === parentId);
      const siblingSpanIds = siblings.map((c) => c.spanId);
      const siblingLabels = siblings.map((c) => c.operationName);
      edges.push({
        id: `${parentId}-${s.spanId}`,
        source: parentId,
        target: s.spanId,
        sourceHandle: 'out',
        targetHandle: 'in',
        type: 'span-edge',
        data: { spanId: s.spanId, traceId, siblingSpanIds, siblingLabels },
        style: { stroke, strokeWidth: 2 },
        markerEnd: marker,
        ...(isError ? {} : { markerStart: marker }),
      });
    } else {
      // 단일 경로: 두 line — forward + reverse.
      //
      // 순서 주의: reverse 먼저 push, forward 나중 push.
      // React Flow는 edges 배열 순서대로 SVG에 렌더 → 나중 edge가 위에 그려짐.
      // forward가 위 layer에 있어야 사용자 직관(위 line이 forward) 과 hover 우선순위 일치.
      if (!isError) {
        // ERROR가 아니어도 payload_out 실제 존재 여부는 layout 시점에 미상 — 옵션 B(서버 메타) 적용 전에는
        // 일단 정상 status면 reverse line 표시. ERROR만 reverse 생략.
        edges.push({
          id: `${s.spanId}-${parentId}-rev`,
          source: s.spanId,
          target: parentId,
          sourceHandle: 'out-rev',
          targetHandle: 'in-rev',
          type: 'span-edge',
          data: { spanId: s.spanId, traceId, direction: 'out' },
          style: { stroke, strokeWidth: 2 },
          markerEnd: marker,
        });
      }
      edges.push({
        id: `${parentId}-${s.spanId}`,
        source: parentId,
        target: s.spanId,
        sourceHandle: 'out',
        targetHandle: 'in',
        type: 'span-edge',
        data: { spanId: s.spanId, traceId, direction: 'in' },
        style: { stroke, strokeWidth: 2 },
        markerEnd: marker,
      });
    }
  }

  return { nodes, edges };
}

/** 외부 검증/테스트용 박제 파라미터. 변경 시 layout.test.ts 동시 갱신 필요. */
export const __LAYOUT_PARAMS__ = {
  NODE_W,
  NODE_H,
  NODESEP,
  RANKSEP,
  EDGE_STROKE_OK,
  EDGE_STROKE_ERROR,
} as const;
