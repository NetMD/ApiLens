// Phase F2 — Trace 상세 화면 (노드 그래프 + 선택 노드 카드).
//
// STEP 1: 헤더 + 데이터 페치 + 4종 BL-08 메시지 분기.
// STEP 2: TraceGraph + selectedSpanId state + ESC keydown 리스너.
// STEP 3: 수직 4 row 페이지 레이아웃 (header / graph / legend / card).
// STEP 4: PayloadView lazy (SelectedSpanCard 내부).
//
// BL-08 사용자 노출 메시지 정책:
// - trace 404 → "trace를 불러올 수 없습니다."
// - 5xx / network → "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
// - BE 본문(error.message) 절대 노출 금지 (NFR-07).
//
// BL-06 sidebar 토글 머신:
//   null/A 클릭 → A / A/A 클릭 → null / A/B 클릭 → B / pane → null / ESC → null
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { searchAcrossRoutes } from '../lib/routeSearch';
import type { SpanDetail } from '../types/api';
import { ApiError } from '../api/client';
import { TraceNotFoundError, fetchTraceDetail } from '../api/traces';
import { formatDuration, shortenOperation } from '../lib/format';
import { SLOW_THRESHOLD_MS } from '../lib/colors';
import { TraceGraph } from '../components/TraceGraph';
import { Legend } from '../components/TraceGraph/Legend';
import { SelectedSpanCard } from '../components/SelectedSpanCard';

/**
 * Mockup 박제: SelectedSpanCard 헤더의 " · 1 child slow" 요약 계산.
 * 직접 자식(parentSpanId === span.spanId) 중:
 *   - status === ERROR  → "{n} child error"
 *   - status !== ERROR && duration > SLOW_THRESHOLD_MS → "{n} child slow"
 * 둘 다 0이면 undefined (헤더에서 미표시).
 */
function computeChildSummary(
  span: SpanDetail | null,
  spans: ReadonlyArray<SpanDetail>,
): string | undefined {
  if (!span) return undefined;
  const children = spans.filter((s) => s.parentSpanId === span.spanId);
  if (children.length === 0) return undefined;
  let errorCount = 0;
  let slowCount = 0;
  for (const c of children) {
    if (c.status === 'ERROR') {
      errorCount++;
    } else if (c.endTime - c.startTime > SLOW_THRESHOLD_MS) {
      slowCount++;
    }
  }
  const parts: string[] = [];
  if (errorCount > 0) parts.push(`${errorCount} child error`);
  if (slowCount > 0) parts.push(`${slowCount} child slow`);
  return parts.length > 0 ? parts.join(', ') : undefined;
}

/**
 * Mockup 박제 (docs/mockups/apilens_trace_detail_node_graph.html line 6~8):
 *   "POST /api/orders" 같은 HTTP method + path 형태로 헤더 메인 라벨 구성.
 *
 * 우선순위: root span의 `http.method` + `http.route`/`http.url` → 그 외엔 rootOperation fallback.
 * url 이 full URL이면 pathname 만 떼서 표기.
 */
function rootHttpDescription(
  spans: ReadonlyArray<SpanDetail>,
  rootOperation: string,
): string {
  const root = spans.find((s) => s.parentSpanId === null) ?? spans[0];
  if (!root) return rootOperation;
  const method = root.attributes['http.method'];
  const route = root.attributes['http.route'];
  const url = root.attributes['http.url'];
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
  return rootOperation;
}

function TraceErrorState({ error }: { error: unknown }): ReactNode {
  // status code로만 분기 — BE 본문 노출 금지.
  let message = '네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.';
  if (error instanceof TraceNotFoundError) {
    message = 'trace를 불러올 수 없습니다.';
  } else if (error instanceof ApiError && error.status === 404) {
    message = 'trace를 불러올 수 없습니다.';
  }
  return (
    <div role="alert" className="flex h-full items-center justify-center">
      <p className="text-sm text-stone-500">{message}</p>
    </div>
  );
}

function TraceLoadingState(): ReactNode {
  return (
    <div role="status" className="flex h-full items-center justify-center">
      <p className="text-sm text-stone-500">불러오는 중...</p>
    </div>
  );
}

export function TraceDetail(): ReactNode {
  const { traceId } = useParams<{ traceId: string }>();
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  // dashboard 필터(service/live/range)를 보존해서 뒤로가기 시 자동 복원되게 함.
  // `<Link to="/">` 만 쓰면 search가 비워져 dashboard가 reset 상태로 mount된다.
  const [searchParams] = useSearchParams();

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['trace', traceId],
    queryFn: ({ signal }) => fetchTraceDetail(traceId!, signal),
    enabled: !!traceId,
    staleTime: 60_000,
    retry: (failureCount, err) => {
      if (err instanceof TraceNotFoundError) return false;
      if (err instanceof ApiError && err.status === 404) return false;
      return failureCount < 1;
    },
  });

  // ESC 닫기 (BL-06).
  useEffect(() => {
    const handler = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') setSelectedSpanId(null);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const trace = data?.trace;
  const spans = data?.spans ?? [];
  const selectedSpan = spans.find((s) => s.spanId === selectedSpanId) ?? null;
  const isErr = trace?.status === 'ERROR' || trace?.hasError === true;

  // [Phase F2 fix³] AC-01-1/2/3/4 — 페이지 레이아웃 수직 4 row 재작성
  //   (header / graph / legend / card). F2 fix² 의 grid 2-column (좌측 그래프 + 우측 360 sidebar)
  //   외피 구조 폐기. 사용자 명시 비협상 결정 (PM §0 / planner §0 / design §0).
  //
  // CLAUDE.md "수직 레이아웃 절대 금지" 의미 분리 (AC-05-2):
  //   - 노드 그래프 방향 (LR/TB) = 보존 (rankdir: 'LR' apilens-ui/src/components/TraceGraph/layout.ts)
  //   - 페이지 레이아웃 = mockup 일치 수직 4 row (사용자 명시 비협상)
  //
  // 직전 라운드 회고 §7 모범 패턴 (CLAUDE.md vs mockup 충돌 자체 재해석) 본 phase 비활성.
  return (
    <div className="flex h-full flex-col bg-stone-50">
      {/* Row 1: 헤더 (기존 보존) */}
      <header className="flex h-14 items-center gap-3 border-b border-stone-200 bg-white px-6">
        <Link
          to={{ pathname: '/', search: searchAcrossRoutes(searchParams) }}
          className="text-sm text-stone-500 hover:text-stone-900"
          aria-label="Back to dashboard"
        >
          ←
        </Link>
        {/* mockup 박제: ← back  trace <short-id>  <METHOD> <path>  ─── duration · status */}
        {traceId && (
          <span className="font-mono text-xs text-stone-500">
            trace {traceId.slice(0, 8)}
          </span>
        )}
        <span className="font-mono text-base font-semibold text-stone-900">
          {trace
            ? shortenOperation(rootHttpDescription(spans, trace.rootOperation))
            : 'Trace'}
        </span>
        <span className="ml-auto flex items-center gap-3 font-mono text-sm text-stone-500">
          {trace && (
            <>
              <span>{formatDuration(trace.durationMs)}</span>
              <span>·</span>
              <span>{trace.status}</span>
              {isErr && (
                <span className="rounded bg-[#FEE2E2] px-2 py-0.5 text-xs font-semibold text-status-error">
                  ERR
                </span>
              )}
            </>
          )}
        </span>
      </header>

      {/* Row 2 + 3 + 4: 본문. flex-col 단일 컨테이너. */}
      <main className="flex min-h-0 flex-1 flex-col overflow-hidden">
        {/* Row 2: 그래프 — flex-1 (가용 세로 공간 점유), min-h-[360px] 보장 (D-06) */}
        <div className="min-h-[360px] flex-1 px-4 pt-3.5 pb-1">
          {isLoading && <TraceLoadingState />}
          {isError && <TraceErrorState error={error} />}
          {!isLoading && !isError && data && (
            <TraceGraph
              spans={spans}
              selectedSpanId={selectedSpanId}
              onSelectSpan={setSelectedSpanId}
              traceId={traceId ?? ''}
            />
          )}
        </div>

        {/* Row 3: 범례 — 데이터 있을 때만 표시 (기존 동작 보존) */}
        {!isLoading && !isError && data && spans.length > 0 && (
          <div className="shrink-0 px-4 pb-1 pt-1">
            <Legend />
          </div>
        )}

        {/* Row 4: 선택 노드 카드 — 외피 항상 그려짐 (자리 유지), 콘텐츠만 분기 (AC-02-2) */}
        {!isLoading && !isError && data && spans.length > 0 && traceId && (() => {
          const summary = computeChildSummary(selectedSpan, spans);
          return (
            <SelectedSpanCard
              traceId={traceId}
              span={selectedSpan}
              className="mx-4 mt-1 mb-4 shrink-0"
              {...(summary !== undefined ? { childSummary: summary } : {})}
            />
          );
        })()}
      </main>
    </div>
  );
}
