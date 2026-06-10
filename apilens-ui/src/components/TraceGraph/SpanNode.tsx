// 커스텀 SpanNode — React Flow에서 type='span'으로 등록.
//
// 본 phase (F2 fix) 의 핵심 변경 (mockup 박제):
//   - 형태: rounded-lg 박스 → SVG <circle> 원형 (FR-01, A-05 번복)
//   - 라벨: 노드 안 → 노드 위쪽 가로 중앙 (FR-08, AC-02-3, AC-02-4)
//   - selected: Tailwind ring → SVG <circle> 외곽 ring + 아래 알약 (CL-06, A-04 번복)
//   - error: Tailwind border → SVG <circle stroke> ring + radius +2 (FR-09, A-06 번복)
//
// 색상은 SVG attribute 영역의 raw hex 사용 (CL-05 박제):
//   F2 layout.ts edge stroke 패턴과 일관 + happy-dom 환경 직렬화 안전.
//   src/index.css @theme 토큰의 hex와 정확 일치 (NFR-05 미러).
//
// SVG <svg style={{ overflow: 'visible' }}>: 알약(노드 아래 12px gap + 20h)이 hitbox 60 밖
//   으로 튀어나가야 하므로 overflow 명시 (SVG default 는 hidden).
import type { ReactNode } from 'react';
import type { NodeProps } from '@xyflow/react';
import { Handle, Position } from '@xyflow/react';
import type { SpanDetail, SpanKind } from '../../types/api';
import { shortenOperation } from '../../lib/format';
import { radius as computeRadius } from './nodeSizing';
import type { SpanFlowNode } from './types';

/**
 * Mockup 박제 (docs/mockups/apilens_trace_detail_node_graph.html line 39-42, 65-67):
 *   노드 라벨 아래에 spanKind 별 부가 정보 한 줄 — "POST /api/orders", "SQL INSERT".
 *
 *   - SERVER : http.method + path
 *   - DB     : "SQL <KIND>" (SELECT/INSERT/UPDATE/DELETE) — mybatis 주석 prefix 제거
 *   - 그 외  : undefined (secondary 미표시, layout 그대로)
 *
 * duration / status 같은 메트릭은 SelectedSpanCard 헤더에 별도 표시되므로 secondary에 중복 X.
 */
function secondaryLabel(span: SpanDetail): string | undefined {
  if (span.spanKind === 'SERVER') {
    const method = span.attributes['http.method'];
    const route = span.attributes['http.route'];
    const url = span.attributes['http.url'];
    const path = typeof route === 'string' ? route : typeof url === 'string' ? url : null;
    if (typeof method === 'string' && path !== null) {
      let cleanPath = path;
      try {
        cleanPath = new URL(path).pathname;
      } catch {
        // path가 이미 pathname 형태인 경우 그대로
      }
      return `${method} ${cleanPath}`;
    }
  }
  if (span.spanKind === 'DB') {
    const statement = span.attributes['db.statement'];
    if (typeof statement === 'string') {
      // /* MapperName.method */ 같은 mybatis 주석 prefix 제거 후 첫 단어 추출
      const trimmed = statement.replace(/^\/\*[^*]*\*\/\s*/, '').trim();
      const firstWord = trimmed.split(/\s+/)[0]?.toUpperCase();
      if (firstWord && ['SELECT', 'INSERT', 'UPDATE', 'DELETE'].includes(firstWord)) {
        return `SQL ${firstWord}`;
      }
    }
  }
  return undefined;
}

const NODE_W = 180;
const NODE_H = 60;
const CIRCLE_CY = 38; // hitbox 중앙(30) + 8px (라벨 위 공간 확보)
const LABEL_GAP = 6; // 노드 위 6px gap
const PILL_GAP = 12; // 노드 아래 12px gap (알약)
const PILL_W = 70;
const PILL_H = 20;
const PILL_RX = 10;

// raw hex lookup (설계서 §3.4 박제 — index.css @theme 토큰의 hex 미러).
// 새 spanKind 추가 시 index.css @theme 토큰과 동시 갱신 필수 (NFR-05).
const SPAN_KIND_FILL_HEX: Record<string, string> = {
  SERVER: '#1D9E75',
  INTERNAL: '#1D9E75',
  DB: '#378ADD',
  CLIENT: '#EF9F27',
  EXTERNAL: '#EF9F27',
  UI_EVENT: '#7F77DD',
};
const FALLBACK_FILL_HEX = '#1D9E75'; // span-server fallback (BL-03 / BL-05 안전망)
const ERROR_RING_HEX = '#E24B4A'; // --color-status-error 미러
const SELECTED_RING_HEX = '#185FA5'; // mockup 박제 (CL-06)
const SELECTED_PILL_BG = '#DBEAFE'; // Tailwind blue-100 (CL-06)
const SELECTED_PILL_FG = '#1E40AF'; // Tailwind blue-800 (CL-06)
const LABEL_FG = '#292524'; // Tailwind stone-800

function spanKindHex(kind: SpanKind | string): string {
  return SPAN_KIND_FILL_HEX[kind] ?? FALLBACK_FILL_HEX;
}

export function SpanNode({ data, selected }: NodeProps<SpanFlowNode>): ReactNode {
  const { span } = data;
  const isError = span.status === 'ERROR';
  const durationMs = span.endTime - span.startTime;
  const r = computeRadius(durationMs, isError);
  const fill = spanKindHex(span.spanKind);

  // 사용자 명시 + mockup 박제: 라벨을 두 줄로 분리 ("DashboardController" / "#getSystemInfo").
  // shortenOperation 결과의 # 기준 split. '#' 없는 단순 이름(jdbc.execute 등)은 한 줄.
  const shortened = shortenOperation(span.operationName);
  const hashIdx = shortened.indexOf('#');
  const labelLine1 = hashIdx === -1 ? shortened : shortened.substring(0, hashIdx);
  const labelLine2 = hashIdx === -1 ? null : shortened.substring(hashIdx); // "#method"

  // spanKind 별 부가 정보 — HTTP path / SQL kind. mockup 박제: 노드 아래.
  const secondary = secondaryLabel(span);

  const cx = NODE_W / 2;
  // 라벨이 두 줄이면 첫 줄을 11px 위로 올림 (두 줄 자리 확보), 둘째 줄은 기존 위치.
  const labelY1 = labelLine2 !== null
    ? CIRCLE_CY - r - LABEL_GAP - 11
    : CIRCLE_CY - r - LABEL_GAP;
  const labelY2 = CIRCLE_CY - r - LABEL_GAP; // 라벨 두 번째 줄 (한 줄일 때는 미사용)

  // secondary는 노드 아래 (mockup line 39-42 패턴), selected pill은 그 아래로 밀어냄.
  const secondaryBelowY = CIRCLE_CY + r + 10;
  const pillY = secondary
    ? secondaryBelowY + PILL_GAP
    : CIRCLE_CY + r + PILL_GAP;
  const pillTextY = pillY + 14;

  return (
    <div
      className="relative"
      style={{ width: NODE_W, height: NODE_H }}
      title={span.operationName}
    >
      {/*
       * React Flow handles — invisible. 0.1.1 버전: 부모-자식 관계의 두 가지 케이스 지원.
       *   - 자식 1개 (단일 경로) : forward(in/out, top 26) + reverse(out-rev/in-rev, top 34) 두 line
       *   - 자식 2개 이상 (분기) : forward(in/out) 한 line + 양쪽 화살촉 (시각 단순화)
       * 케이스 결정은 layout.ts에서. handle 4개 모두 등록.
       */}
      <Handle
        id="in"
        type="target"
        position={Position.Left}
        style={{ top: 26, opacity: 0, pointerEvents: 'none' }}
      />
      <Handle
        id="out-rev"
        type="source"
        position={Position.Left}
        style={{ top: 34, opacity: 0, pointerEvents: 'none' }}
      />
      <svg
        width={NODE_W}
        height={NODE_H}
        viewBox={`0 0 ${NODE_W} ${NODE_H}`}
        style={{ overflow: 'visible' }}
      >
        {/*
         * 라벨: 노드 위쪽 가로 중앙 (AC-02-3, AC-02-4).
         * Phase F2 fix² (US-02, AC-02-1/2/3): fontSize 11 → 10 으로 1px 축소.
         * mockup 고정 — operationName 라벨이 mockup 의 작은 폰트와 일치하도록
         * 단일 axis (강조/비강조 axis 도입은 0.2 이연). dagre nodesep/ranksep
         * 영향은 라벨 폭이 줄어드는 안전 방향이라 무영향.
         */}
        {/* 라벨 첫 줄 — 클래스명 / 단일 이름 */}
        <text
          x={cx}
          y={labelY1}
          textAnchor="middle"
          fontSize={10}
          fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace"
          fill={LABEL_FG}
          className="select-none"
        >
          {labelLine1}
        </text>

        {/* 라벨 두 번째 줄 — "#method" (있을 때만) */}
        {labelLine2 !== null && (
          <text
            x={cx}
            y={labelY2}
            textAnchor="middle"
            fontSize={10}
            fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace"
            fill={LABEL_FG}
            className="select-none"
          >
            {labelLine2}
          </text>
        )}

        {/* Mockup 박제: spanKind 별 secondary text는 노드 아래 (selected pill 위) */}
        {secondary && (
          <text
            x={cx}
            y={secondaryBelowY}
            textAnchor="middle"
            fontSize={9}
            fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace"
            fill="#888780"
            className="select-none"
          >
            {secondary}
          </text>
        )}

        {/* selected 외곽 ring (mockup 박제 — 본체보다 r+5, opacity 0.55) */}
        {selected && (
          <circle
            cx={cx}
            cy={CIRCLE_CY}
            r={r + 5}
            fill="none"
            stroke={SELECTED_RING_HEX}
            strokeWidth={2}
            opacity={0.55}
          />
        )}

        {/* error ring (FR-09 — 본체와 동일 좌표/반지름, stroke 만 추가) */}
        {isError && (
          <circle
            cx={cx}
            cy={CIRCLE_CY}
            r={r}
            fill="none"
            stroke={ERROR_RING_HEX}
            strokeWidth={2}
          />
        )}

        {/* 본체 원형 (FR-01) */}
        <circle cx={cx} cy={CIRCLE_CY} r={r} fill={fill} />

        {/* selected 알약 (FR-08, mockup 박제 — 노드 아래) */}
        {selected && (
          <g>
            <rect
              x={cx - PILL_W / 2}
              y={pillY}
              width={PILL_W}
              height={PILL_H}
              rx={PILL_RX}
              fill={SELECTED_PILL_BG}
            />
            <text
              x={cx}
              y={pillTextY}
              textAnchor="middle"
              fontSize={10}
              fontWeight={500}
              fill={SELECTED_PILL_FG}
            >
              selected
            </text>
          </g>
        )}
      </svg>
      <Handle
        id="out"
        type="source"
        position={Position.Right}
        style={{ top: 26, opacity: 0, pointerEvents: 'none' }}
      />
      <Handle
        id="in-rev"
        type="target"
        position={Position.Right}
        style={{ top: 34, opacity: 0, pointerEvents: 'none' }}
      />
    </div>
  );
}
