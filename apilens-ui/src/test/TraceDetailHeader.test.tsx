// [Phase AB / R31] TC-R31-82~84 — 상세 화면 머리글(설계 부록 A2-5 · 한 행 = it 하나 · it.each 안 씀 · happy-dom).
//
// 백로그 AR-R29-21 결함 둘(긴 이름이 머리 줄을 넘침 · 마우스 올림 글 없음) + 단독 CLIENT 흐름에서 호스트가 떨어지던 결함을 고정한다.
//   - fetchTraceDetail 은 가짜로 바꾼다(망 0) · 그래프는 이 시험 축이 아니라 가짜 컴포넌트로 바꾼다(React Flow 렌더 비용 · 경고 회피).
// ★TC-R31-84 는 클래스 **전제**만 본다 — 실제 폭 · 높이(창 1280 · 1024 px · h-14 = 56 px)는 실브라우저 스모크(AC-R31-39 · 40) 몫.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { SpanDetail, TraceDetailResponse } from '../types/api';
import { SERVER_ADDRESS_KEY, SERVER_PORT_KEY } from '../lib/spanLabels';
// vi.mock 두 개는 vitest 가 이 import 보다 먼저 끌어올려 등록한다.
import { TraceDetail } from '../pages/TraceDetail';

const { fetchTraceDetailMock } = vi.hoisted(() => ({
  fetchTraceDetailMock: vi.fn<(traceId: string, signal?: AbortSignal) => Promise<TraceDetailResponse>>(),
}));

vi.mock('../api/traces', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/traces')>();
  return {
    ...actual,
    fetchTraceDetail: (traceId: string, signal?: AbortSignal) => fetchTraceDetailMock(traceId, signal),
  };
});

vi.mock('../components/TraceGraph', () => ({
  TraceGraph: () => null,
}));

const TRACE_ID = '0af7651916cd43dd8448eb211c80319c';

function span(
  spanId: string,
  parentSpanId: string | null,
  spanKind: SpanDetail['spanKind'],
  operationName: string,
  attributes: Record<string, unknown>,
): SpanDetail {
  return {
    spanId,
    parentSpanId,
    serviceName: 'my-api',
    operationName,
    spanKind,
    startTime: 1_716_386_700_000,
    endTime: 1_716_386_700_500,
    status: 'OK',
    attributes,
  };
}

function response(rootOperation: string, spans: SpanDetail[]): TraceDetailResponse {
  return {
    trace: {
      traceId: TRACE_ID,
      rootOperation,
      serviceName: 'my-api',
      startTime: 1_716_386_700_000,
      durationMs: 500,
      status: 'OK',
      spanCount: spans.length,
      hasError: false,
    },
    spans,
  };
}

function renderDetail(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/traces/${TRACE_ID}`]}>
        <Routes>
          <Route path="/traces/:traceId" element={<TraceDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const CLIENT_OP = 'HTTP POST 192.0.2.39:1234/v1/chat/completions';

function clientOnly(): TraceDetailResponse {
  return response(CLIENT_OP, [
    span('c1', null, 'CLIENT', CLIENT_OP, {
      'http.method': 'POST',
      'http.url': 'http://192.0.2.39:1234/v1/chat/completions',
      [SERVER_ADDRESS_KEY]: '192.0.2.39',
      [SERVER_PORT_KEY]: 1234,
      'http.status_code': 200,
    }),
  ]);
}

describe('TraceDetail 머리글 — 외부 호출 · 한 줄 줄임 · 마우스 올림 글 [R31]', () => {
  afterEach(() => {
    fetchTraceDetailMock.mockReset();
  });

  it('TC-R31-82 단독 CLIENT 흐름의 머리글 = 「POST 192.0.2.39:1234/v1/chat/completions」 (호스트 남음 · AC-R31-37)', async () => {
    fetchTraceDetailMock.mockResolvedValue(clientOnly());
    renderDetail();
    const name = await screen.findByText('POST 192.0.2.39:1234/v1/chat/completions');
    expect(name.closest('header')).not.toBeNull();
    // 옛 꼴(호스트가 떨어진 「POST /v1/chat/completions」)은 머리글에 없다.
    expect(screen.queryByText('POST /v1/chat/completions')).toBeNull();
  });

  it('TC-R31-83 SERVER 흐름에서도 머리글 title = trace.rootOperation 원문 (AC-R31-38)', async () => {
    const root = 'com.example.OrderController#create';
    fetchTraceDetailMock.mockResolvedValue(
      response(root, [
        span('s1', null, 'SERVER', root, { 'http.method': 'POST', 'http.route': '/api/orders' }),
      ]),
    );
    renderDetail();
    // 보이는 글자는 옛 출력 그대로(AC-R31-68) · 마우스 올림 글만 새로 생긴다.
    const name = await screen.findByText('POST /api/orders');
    expect(name.getAttribute('title')).toBe(root);
  });

  it('TC-R31-84 머리글 이름 칸 min-w-0 · truncate · 양옆 칸 shrink-0 (AC-R31-39 · 40 전제 — 폭은 실브라우저)', async () => {
    fetchTraceDetailMock.mockResolvedValue(clientOnly());
    renderDetail();
    const name = await screen.findByText('POST 192.0.2.39:1234/v1/chat/completions');
    expect(name.classList.contains('min-w-0')).toBe(true);
    expect(name.classList.contains('truncate')).toBe(true);
    expect(name.getAttribute('title')).toBe(CLIENT_OP);

    const header = name.closest('header');
    expect(header).not.toBeNull();
    expect(header!.classList.contains('h-14')).toBe(true);
    const right = header!.querySelector('.ml-auto');
    expect(right).not.toBeNull();
    expect(right!.classList.contains('shrink-0')).toBe(true);
    const shortId = screen.getByText(`trace ${TRACE_ID.slice(0, 8)}`);
    expect(shortId.classList.contains('shrink-0')).toBe(true);
  });
});
