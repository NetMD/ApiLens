// [Phase R15] AC-B4-1 — ActiveServices 일시정지 배지 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   displaysPausedBadgeWhenPaused — paused=true → "수신 일시정지 중" 배지 노출
//   rendersNoBadgeWhenNotPaused — paused=false → 배지 미노출(동작 정상 단언)
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { ActiveServices } from '../pages/ActiveServices';
import { ToastProvider } from '../components/Toast';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

// status 폴링 + services 목록 fetch mock. behavior 로 paused 분기.
function mockApi(paused: boolean): void {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/maintenance/status')) {
      return Promise.resolve(jsonResponse({ paused, pausedAt: paused ? 1_730_000_000_000 : null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    }
    if (url.includes('/v1/services')) {
      return Promise.resolve(jsonResponse({ services: [] }));
    }
    if (url.includes('/v1/setup/state')) {
      return Promise.resolve(jsonResponse({ completed: true, completedAt: 1, serverUrl: 'x' }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
}

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/services']}>
          <ActiveServices />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('ActiveServices — 수신 일시정지 배지', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('displaysPausedBadgeWhenPaused — paused=true 시 "수신 일시정지 중" 배지 노출', async () => {
    mockApi(true);
    renderPage();
    expect(await screen.findByText('수신 일시정지 중')).toBeInTheDocument();
  });

  it('rendersNoBadgeWhenNotPaused — paused=false 시 배지 미노출', async () => {
    mockApi(false);
    renderPage();
    // h1 "Services" 는 떠도 배지는 없어야 함.
    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByText('수신 일시정지 중')).toBe(null);
    });
  });
});
