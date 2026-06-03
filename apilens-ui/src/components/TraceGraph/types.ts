// TraceGraph 타입 — React Flow Node/Edge generic을 좁힌다.
import type { Edge, Node } from '@xyflow/react';
import type { SpanDetail } from '../../types/api';

/**
 * 커스텀 SpanNode가 받는 데이터.
 *
 * NOTE: @xyflow/react v12에서 Node<T>의 T는 `Record<string, unknown>` 제약.
 *       extends Record<string, unknown>로 만족시킨다.
 *       선택 상태는 React Flow가 props.selected로 별도 전달하므로 data에 두지 않는다.
 */
export interface SpanNodeData extends Record<string, unknown> {
  span: SpanDetail;
}

export type SpanFlowNode = Node<SpanNodeData, 'span'>;
export type SpanFlowEdge = Edge;

export interface LayoutResult {
  nodes: SpanFlowNode[];
  edges: SpanFlowEdge[];
}
