// [Phase R15] AC-A3-1/AC-B1-1 — useMaintenanceStatus hook 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   exposesPausedTrueWithElapsedMinutes — paused=true + pausedAt 주입 → elapsedMinutes 내림 파생
//   exposesPausedFalseOnPollFailure — query error → paused=false fallback (거짓 일시정지 차단)
//   computesElapsedMinutesAtBoundary — now-59_999=0분 / now-60_000=1분 / now-120_000=2분 경계(EXT-002)
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useMaintenanceStatus } from '../hooks/useMaintenanceStatus';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function makeWrapper(): { wrapper: ({ children }: { children: ReactNode }) => ReactNode } {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }): ReactNode => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { wrapper };
}

describe('useMaintenanceStatus', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('exposesPausedTrueWithElapsedMinutes — paused=true + pausedAt 주입 시 elapsedMinutes 내림 파생', async () => {
    const now = 1_730_000_300_000; // pausedAt + 5분(300_000ms)
    vi.spyOn(Date, 'now').mockReturnValue(now);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ paused: true, pausedAt: 1_730_000_000_000 }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.paused).toBe(true));
    expect(result.current.pausedAt).toBe(1_730_000_000_000);
    expect(result.current.elapsedMinutes).toBe(5); // floor(300000/60000)
  });

  it('exposesPausedFalseOnPollFailure — status GET 실패 시 paused=false fallback (거짓 일시정지 차단)', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('network down'));
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    // 실패 후에도 data===undefined → paused=false 유지, elapsedMinutes=0.
    await waitFor(() => expect(result.current.paused).toBe(false));
    expect(result.current.pausedAt).toBe(null);
    expect(result.current.elapsedMinutes).toBe(0);
  });

  // [EXT-002] elapsedMinutes 내림 경계 — pausedAt 고정, Date.now() 만 제어해 결정적 단언.
  it.each([
    [59_999, 0],
    [60_000, 1],
    [119_999, 1],
    [120_000, 2],
  ])('computesElapsedMinutesAtBoundary — 경과 %ims → %i분', async (deltaMs, expectedMin) => {
    const pausedAt = 1_730_000_000_000;
    vi.spyOn(Date, 'now').mockReturnValue(pausedAt + deltaMs);
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ paused: true, pausedAt }));
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.paused).toBe(true));
    expect(result.current.elapsedMinutes).toBe(expectedMin);
  });
});
