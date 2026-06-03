// TraceGraph — React Flow 12 + dagre LR 자동 레이아웃 컨테이너.
//
// 핵심:
//   - nodeTypes는 module-level 상수로 박제 (v12 경고 방지: 매 렌더 새 객체 생성 시 console.warn).
//   - useReactFlow()는 ReactFlowProvider 내부에서만 동작 → TraceGraph 외곽에 Provider.
//   - CSS import 필수: `@xyflow/react/dist/style.css`. 누락 시 노드/엣지 사라짐.
//   - useMemo([spans])로 nodes/edges identity 안정 (planner §13.6 viewport jump 회피).
//   - fitView는 spans reference가 변할 때만 호출 — sidebar 토글 때 viewport 흔들지 않음 (R6).
//
// BL-06 토글 머신: TraceDetail 상위에서 onSelectSpan으로 처리.
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo } from 'react';
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type NodeMouseHandler,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { SpanDetail } from '../../types/api';
import { SpanNode } from './SpanNode';
import { SpanEdge } from './SpanEdge';
import { computeLayout } from './layout';
// Phase F2 fix² (US-01, AC-01-1/2/3): Legend 는 TraceGraph 외부로 끌어올려
// TraceDetail 좌측 section 에서 그래프(상)+범례(하) 2-row 합성. 본 컴포넌트는
// 그래프 렌더링만 담당하고 Legend 마운트는 TraceDetail 책임.

interface Props {
  spans: ReadonlyArray<SpanDetail>;
  selectedSpanId: string | null;
  onSelectSpan: (spanId: string | null) => void;
  /** SpanEdge hover popup이 payload lazy fetch에 사용. */
  traceId: string;
}

const NODE_TYPES = { span: SpanNode } as const;
const EDGE_TYPES = { 'span-edge': SpanEdge } as const;

function TraceGraphInner({ spans, selectedSpanId, onSelectSpan, traceId }: Props): ReactNode {
  const { nodes: rawNodes, edges } = useMemo(
    () => computeLayout(spans, traceId),
    [spans, traceId],
  );

  // selected 표시는 React Flow node.selected boolean prop으로 주입.
  const nodes = useMemo(
    () => rawNodes.map((n) => ({ ...n, selected: n.id === selectedSpanId })),
    [rawNodes, selectedSpanId],
  );

  const onNodeClick: NodeMouseHandler = useCallback(
    (_evt, node) => {
      // BL-06: 같은 노드 재클릭 시 토글 닫힘 / 다른 노드 → 그 노드로 전환.
      onSelectSpan(selectedSpanId === node.id ? null : node.id);
    },
    [selectedSpanId, onSelectSpan],
  );

  const onPaneClick = useCallback(() => onSelectSpan(null), [onSelectSpan]);

  const rf = useReactFlow();
  useEffect(() => {
    if (rawNodes.length > 0) {
      rf.fitView({ padding: 0.2, duration: 0 });
    }
    // selectedSpanId 변할 때 viewport 흔들지 않게 rawNodes만 dep.
  }, [rawNodes, rf]);

  if (spans.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-stone-500">
        No spans in this trace
      </div>
    );
  }

  // Phase F2 fix² (US-01, AC-01-1/2/3): Legend 외부 마운트로 분리.
  // TraceGraph 는 ReactFlow 만 풀-블리드 렌더 (h-full). Legend 는 부모 컨테이너
  // (TraceDetail 좌측 section) 의 flex-col 안에서 그래프 아래에 별도 mount.
  // ReactFlow Background/Controls 와 Legend 가 z-index 경합하지 않도록 형제 분리 보존.
  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      edgeTypes={EDGE_TYPES}
      onNodeClick={onNodeClick}
      onPaneClick={onPaneClick}
      nodesDraggable={false}
      nodesConnectable={false}
      panOnDrag
      zoomOnScroll
      proOptions={{ hideAttribution: true }}
      fitView
    >
      <Background gap={16} />
      <Controls showInteractive={false} />
    </ReactFlow>
  );
}

export function TraceGraph(props: Props): ReactNode {
  return (
    <ReactFlowProvider>
      <TraceGraphInner {...props} />
    </ReactFlowProvider>
  );
}
