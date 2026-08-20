// [Phase R19] Services 목록의 agent 버전 컬럼 + [계측 분석] 액션 + 같은 경로 안 화면 전환.
//
// 이 라운드에서 회귀 위험이 가장 큰 자리다 — 새 버튼이 행 클릭(SH-06 대시보드 이동)에 흡수되면
// [계측 분석] 을 눌렀는데 대시보드로 튄다. 그 사고를 사람 눈이 아니라 이 테스트가 막는다.
//
// 검증 의무 (정방향 동사 명시 — lock-in 회귀 가드. 본 라운드는 사용자 명시 비협상 결정 D-1~D-14 보유):
//   displaysAgentVersionValueWhenReported   — 값이 있으면 그대로 보여 준다
//   displaysDashWhenAgentVersionMissing     — 값이 없으면 `—` (기존 값 없음 표기와 같은 모양)
//   opensAnalysisScreenOnAnalyzeClick       — 행 클릭 흡수 없이 분석 화면으로 전환 (SH-19 전례 준수)
//   keepsRowClickNavigationForDashboard     — 행 클릭은 기존대로 대시보드 필터 이동 (회귀 가드)
//   displaysFooterNotesWhenRowsExist        — 표 하단 보조 문구 2문장
//   returnsToListFromAnalysisScreen         — [← Services 목록] 로 목록 복귀
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { ActiveServices } from '../pages/ActiveServices';
import { ToastProvider } from '../components/Toast';
import type { ServiceInfo } from '../types/api';

const SERVICES: ServiceInfo[] = [
  {
    name: 'orders-api',
    registeredAt: 1_785_000_000_000,
    lastSeenAt: 1_785_344_000_000,
    source: 'auto',
    traceCount: 12_483,
    healthStatus: 'active',
    agentVersion: '0.4.0',
  },
  {
    name: 'batch-job',
    registeredAt: 1_785_000_000_000,
    lastSeenAt: 1_785_343_000_000,
    source: 'auto',
    traceCount: 1_204,
    healthStatus: 'stale',
    agentVersion: null,
  },
];

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function mockApi(): void {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/maintenance/status')) {
      return Promise.resolve(jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    }
    if (url.includes('/v1/services')) {
      return Promise.resolve(jsonResponse({ services: SERVICES }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
}

/** 현재 주소를 화면에 노출해 이동 결과를 단언할 수 있게 한다. */
function LocationProbe(): ReactNode {
  const location = useLocation();
  return <div data-testid="loc">{`${location.pathname}${location.search}`}</div>;
}

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/services']}>
          <LocationProbe />
          <Routes>
            <Route path="/services" element={<ActiveServices />} />
            <Route path="/" element={<div>대시보드 자리</div>} />
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('Services 목록 — agent 버전 컬럼 + 계측 분석 전환', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('displaysAgentVersionValueWhenReported — 보고된 값을 그대로 보여 준다', async () => {
    mockApi();
    renderPage();
    expect(await screen.findByText('0.4.0')).toBeInTheDocument();
    // [R21/AC-07-1] T-23 — "지금 버전" 이라고 단정하지 않는 컬럼 헤더 (버전 라벨 정직화).
    expect(
      screen.getByRole('columnheader', { name: 'agent 버전 (마지막 확인 시점)' }),
    ).toBeInTheDocument();
  });

  it('displaysDashWhenAgentVersionMissing — 값이 없으면 — 로 보여 준다 (숨기지 않는다)', async () => {
    mockApi();
    renderPage();
    await screen.findByText('0.4.0');
    // lastSeenAt 은 두 서비스 모두 값이 있으므로, — 는 agent 버전 셀에서만 나온다.
    expect(screen.getAllByText('—')).toHaveLength(1);
  });

  it('displaysFooterNotesWhenRowsExist — 표 하단 보조 문구 2문장을 보여 준다', async () => {
    mockApi();
    renderPage();
    await screen.findByText('0.4.0');
    // [R21/AC-07-1] T-23 — 첫 단락 정직화 문구 (확정 라벨이 본문에 그대로 실린다).
    expect(
      screen.getByText(/여기 보이는 값은 마지막 확인 시점의 버전이에요/),
    ).toBeInTheDocument();
    expect(screen.getByText(/왼쪽 위에 보이는 제품 버전과 다를 수 있어요/)).toBeInTheDocument();
  });

  it('opensAnalysisScreenOnAnalyzeClick — 행 클릭 흡수 없이 계측 분석 화면으로 전환한다', async () => {
    mockApi();
    renderPage();
    await screen.findByText('0.4.0');

    fireEvent.click(screen.getByRole('button', { name: 'orders-api 계측 분석' }));

    // 같은 경로 + ?analyze= 로만 바뀐다 (신규 라우트 아님).
    expect(screen.getByTestId('loc')).toHaveTextContent('/services?analyze=orders-api');
    // 행 클릭이 흡수했다면 대시보드로 튀어 이 제목이 없다.
    expect(
      screen.getByRole('heading', { name: 'orders-api 계측 분석' }),
    ).toBeInTheDocument();
  });

  it('keepsRowClickNavigationForDashboard — 행 클릭은 기존대로 대시보드 필터 이동을 유지한다', async () => {
    mockApi();
    renderPage();
    const nameCell = await screen.findByText('orders-api');
    const row = nameCell.closest('tr');
    expect(row).not.toBe(null);

    fireEvent.click(row as HTMLElement);
    expect(screen.getByTestId('loc')).toHaveTextContent('/?service=orders-api');
  });

  it('returnsToListFromAnalysisScreen — [← Services 목록] 로 목록으로 돌아온다', async () => {
    mockApi();
    renderPage();
    await screen.findByText('0.4.0');
    fireEvent.click(screen.getByRole('button', { name: 'orders-api 계측 분석' }));
    await screen.findByRole('heading', { name: 'orders-api 계측 분석' });

    fireEvent.click(screen.getByRole('button', { name: '← Services 목록' }));

    expect(screen.getByTestId('loc')).toHaveTextContent('/services');
    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument();
  });
});
