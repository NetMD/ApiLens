// [Phase R15] AC-B3-1 — MaintenanceModeBanner 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   displaysBannerWithElapsedMinutesWhenPaused — paused=true → 배너 + "N분 경과" 노출
//   rendersNullWhenNotPaused — paused=false → 미렌더(배너 미노출, 동작 정상 단언)
//   rendersNullOnPollFailure — 폴링 실패(거짓 일시정지 차단) → paused=false → 배너 미표시
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MaintenanceModeBanner } from '../components/MaintenanceModeBanner';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderBanner(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MaintenanceModeBanner />
    </QueryClientProvider>,
  );
}

describe('MaintenanceModeBanner', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('displaysBannerWithElapsedMinutesWhenPaused — paused=true 시 배너 + 경과시간 노출', async () => {
    const pausedAt = 1_730_000_000_000;
    vi.spyOn(Date, 'now').mockReturnValue(pausedAt + 180_000); // +3분
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ paused: true, pausedAt, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    renderBanner();

    expect(
      await screen.findByText(
        '수신 일시정지 중 (3분 경과) — 이 동안 들어온 데이터는 저장되지 않습니다.',
      ),
    ).toBeInTheDocument();
  });

  it('rendersNullWhenNotPaused — paused=false 시 배너 미렌더(미노출)', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    renderBanner();

    // 데이터 도착(paused=false) 후에도 배너 없음.
    await waitFor(() => {
      expect(screen.queryByText(/수신 일시정지 중/)).toBe(null);
    });
  });

  it('rendersNullOnPollFailure — 폴링 실패 시 paused=false fallback → 배너 미표시(거짓 일시정지 차단)', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network down'));
    renderBanner();

    await waitFor(() => {
      expect(screen.queryByText(/수신 일시정지 중/)).toBe(null);
    });
  });
});
