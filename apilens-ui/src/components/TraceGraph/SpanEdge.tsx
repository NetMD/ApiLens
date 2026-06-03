// Custom edge — hover시 PAYLOAD popup.
//
// 케이스별 동작 (사용자 명시 정리):
//   - 단일 경로 forward (direction='in')  : 자식의 PAYLOAD IN 만
//   - 단일 경로 reverse (direction='out') : 자식의 PAYLOAD OUT 만
//   - 분기 line 오른쪽 절반(자식 측 hover) : 그 자식 한 명의 IN + OUT 같이
//   - 분기 line 왼쪽  절반(부모 측 hover) : 모든 sibling의 OUT 합산
//
// hit zone:
//   - 단일: 전체 path 위 transparent 두꺼운 stroke (strokeWidth 20)
//   - 분기: 같은 path 위에서 마우스 좌표를 React Flow flow 좌표로 변환 후
//          line 가운데(midX) 기준 좌/우 판단 → 직각 path가 rect 밖으로 휘어도 정확.
import type { ReactNode } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { useState } from 'react';
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, useReactFlow } from '@xyflow/react';
import type { EdgeProps } from '@xyflow/react';
import { useQueries } from '@tanstack/react-query';
import { fetchPayloads } from '../../api/traces';
import { shortenOperation } from '../../lib/format';

interface SpanEdgeData extends Record<string, unknown> {
  spanId: string;
  traceId: string;
  /** 단일 경로 케이스에서만 set. 분기에선 undefined. */
  direction?: 'in' | 'out';
  /** 분기 케이스에서 같은 부모를 공유하는 모든 자식 spanId. */
  siblingSpanIds?: string[];
  /** siblingSpanIds 의 자식 operationName. */
  siblingLabels?: string[];
}

const PREVIEW_LIMIT = 2000;

function prettify(body: string | null | undefined): string {
  if (!body) return '';
  try {
    const parsed = JSON.parse(body);
    return JSON.stringify(parsed, null, 2);
  } catch {
    return body;
  }
}

function previewBody(body: string | null | undefined): string {
  const pretty = prettify(body);
  if (pretty.length <= PREVIEW_LIMIT) return pretty;
  return pretty.slice(0, PREVIEW_LIMIT) + '\n…';
}

type HoverSide = 'forward' | 'reverse' | null;

/**
 * 라벨 + 본문 또는 빈 안내. fetched=false 이면 "loading…", body 비면 "(no … body)".
 * 사용자 명시: payload 없어도 popup 표시해 hover 작동을 알려야 함.
 */
function PayloadSection({
  label,
  body,
  kind,
  fetched,
  className,
}: {
  label: string;
  body: string;
  kind: 'in' | 'out';
  fetched: boolean;
  className?: string;
}): ReactNode {
  const placeholder = kind === 'in' ? '(no request body)' : '(no response body)';
  return (
    <div className={className}>
      <div className="mb-1 text-[7px] font-semibold uppercase tracking-wider text-stone-500">
        {label}
      </div>
      {body.length > 0 ? (
        <pre className="m-0 whitespace-pre-wrap break-all font-mono text-[6px] leading-snug text-stone-900">
          {body}
        </pre>
      ) : (
        <div className="font-mono text-[6px] italic text-stone-400">
          {fetched ? placeholder : 'loading…'}
        </div>
      )}
    </div>
  );
}

export function SpanEdge(props: EdgeProps): ReactNode {
  const {
    id,
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    style,
    markerStart,
    markerEnd,
    data,
  } = props;
  const [hoverSide, setHoverSide] = useState<HoverSide>(null);
  const d = data as SpanEdgeData | undefined;
  const { screenToFlowPosition } = useReactFlow();

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    offset: 35,
  });

  const traceId = d?.traceId ?? '';
  const isBranching = (d?.siblingSpanIds?.length ?? 0) >= 2;

  const fetchSpanIds = isBranching
    ? (d?.siblingSpanIds ?? [])
    : d?.spanId ? [d.spanId] : [];
  const fetchLabels = isBranching
    ? (d?.siblingLabels ?? [])
    : d?.spanId ? [d.spanId] : [];

  const queries = useQueries({
    queries: fetchSpanIds.map((sid) => ({
      queryKey: ['payloads', traceId, sid],
      queryFn: ({ signal }: { signal?: AbortSignal }) => fetchPayloads(traceId, sid, signal),
      enabled: hoverSide !== null && !!traceId && !!sid,
      staleTime: 60_000,
      retry: 1,
    })),
  });

  // 자기 자식(d.spanId)의 payload
  const selfIdx = fetchSpanIds.findIndex((sid) => sid === d?.spanId);
  const selfPayloads = selfIdx >= 0 ? queries[selfIdx]?.data?.payloads : undefined;
  const selfIn = previewBody(selfPayloads?.find((p) => p.direction === 'in')?.body);
  const selfOut = previewBody(selfPayloads?.find((p) => p.direction === 'out')?.body);
  // fetch가 완료되어 결과를 받았는지 (응답이 도착했고 빈 payload인지) 판단.
  const selfFetched = selfIdx >= 0 && queries[selfIdx]?.status === 'success';

  // 분기 reverse: 모든 sibling OUT
  interface BranchOut {
    label: string;
    text: string;
  }
  const allSiblingOuts: BranchOut[] = isBranching
    ? queries
        .map((q, idx) => ({
          label: shortenOperation(fetchLabels[idx] ?? fetchSpanIds[idx] ?? ''),
          text: previewBody(q.data?.payloads?.find((p) => p.direction === 'out')?.body),
        }))
        .filter((s) => s.text.length > 0)
    : [];

  // ─── popup 본문 결정 ───────────────────────────────────────────────────────
  // hover 작동시 무조건 popup 보임 — payload 없으면 "(no request/response body)" 안내.
  // (사용자 명시: "내가 payload in에 마우스를 올렸고 정확히 이벤트가 작동하며 보여줄게 없다고 알 수 있도록")
  let renderMode:
    | 'singleIn'
    | 'singleOut'
    | 'branchForward'
    | 'branchReverse'
    | 'none' = 'none';
  if (hoverSide !== null) {
    if (!isBranching) {
      renderMode = d?.direction === 'out' ? 'singleOut' : 'singleIn';
    } else if (hoverSide === 'reverse') {
      renderMode = 'branchReverse';
    } else {
      renderMode = 'branchForward';
    }
  }
  const showPopup = renderMode !== 'none';

  // ─── hover side 결정 (분기 케이스만) ───────────────────────────────────────
  const midX = (sourceX + targetX) / 2;
  const sourceIsLeft = sourceX < targetX;

  const handleMouseMoveBranch = (e: ReactMouseEvent): void => {
    const flowPos = screenToFlowPosition({ x: e.clientX, y: e.clientY });
    // 마우스가 부모(source) 쪽이면 reverse, 자식(target) 쪽이면 forward.
    const isOnSourceSide = sourceIsLeft ? flowPos.x < midX : flowPos.x > midX;
    setHoverSide(isOnSourceSide ? 'reverse' : 'forward');
  };

  const handleMouseEnterSingle = (): void => {
    // 단일 경로는 direction 그대로.
    setHoverSide(d?.direction === 'out' ? 'reverse' : 'forward');
  };

  const handleMouseLeave = (): void => setHoverSide(null);

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={style}
        markerEnd={markerEnd}
        markerStart={markerStart}
      />
      {/*
       * hit area — transparent stroke.
       * 단일 경로: 두 line(forward 위 y=26, reverse 아래 y=34) 사이 간격 8px만큼 좁아
       * hit zone이 겹치면 위치별 hover가 부정확. stroke 10px (각 ±5) 로 거의 분리.
       * 분기: 한 line이라 stroke 24px 그대로 (hover 쉽게).
       */}
      <path
        d={edgePath}
        fill="none"
        stroke="transparent"
        strokeWidth={isBranching ? 24 : 10}
        onMouseEnter={isBranching ? handleMouseMoveBranch : handleMouseEnterSingle}
        onMouseMove={isBranching ? handleMouseMoveBranch : undefined}
        onMouseLeave={handleMouseLeave}
      />
      {showPopup && (
        <EdgeLabelRenderer>
          <div
            className="rounded-md border border-stone-200 bg-white p-2 shadow-lg"
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: 'none',
              maxWidth: 460,
              maxHeight: 320,
              overflow: 'auto',
              zIndex: 9999,
            }}
          >
            {renderMode === 'singleIn' && (
              <PayloadSection
                label="PAYLOAD IN"
                body={selfIn}
                kind="in"
                fetched={selfFetched}
              />
            )}
            {renderMode === 'singleOut' && (
              <PayloadSection
                label="PAYLOAD OUT"
                body={selfOut}
                kind="out"
                fetched={selfFetched}
              />
            )}
            {renderMode === 'branchForward' && (
              <>
                <PayloadSection
                  label="PAYLOAD IN"
                  body={selfIn}
                  kind="in"
                  fetched={selfFetched}
                  className={selfOut.length > 0 || !selfFetched ? 'mb-2' : ''}
                />
                <PayloadSection
                  label="PAYLOAD OUT"
                  body={selfOut}
                  kind="out"
                  fetched={selfFetched}
                />
              </>
            )}
            {renderMode === 'branchReverse' && (
              <>
                <div className="mb-1 text-[7px] font-semibold uppercase tracking-wider text-stone-500">
                  PAYLOAD OUT — all branches
                </div>
                {allSiblingOuts.length > 0 ? (
                  allSiblingOuts.map((s, idx) => (
                    <div
                      key={`${s.label}-${idx}`}
                      className={idx < allSiblingOuts.length - 1 ? 'mb-2 border-b border-stone-200 pb-2' : ''}
                    >
                      <div className="mb-0.5 text-[7px] font-semibold tracking-wider text-stone-700">
                        {s.label}
                      </div>
                      <pre className="m-0 whitespace-pre-wrap break-all font-mono text-[6px] leading-snug text-stone-900">
                        {s.text}
                      </pre>
                    </div>
                  ))
                ) : (
                  <div className="font-mono text-[6px] italic text-stone-400">
                    {queries.every((q) => q.status === 'success')
                      ? '(no response body)'
                      : 'loading…'}
                  </div>
                )}
              </>
            )}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
