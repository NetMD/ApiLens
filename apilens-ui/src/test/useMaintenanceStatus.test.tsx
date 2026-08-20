// [Phase R15] AC-A3-1/AC-B1-1 — useMaintenanceStatus hook 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   exposesPausedTrueWithElapsedMinutes — paused=true + pausedAt 주입 → elapsedMinutes 내림 파생
//   exposesPausedFalseOnPollFailure — query error → paused=false fallback (거짓 일시정지 차단)
//   computesElapsedMinutesAtBoundary — now-59_999=0분 / now-60_000=1분 / now-120_000=2분 경계(EXT-002)
//
// [Phase T / R23] AC-06-1/AC-07-1 추가 검증 (정방향 동사 명시 — EXT-003 lock-in 가드):
//   exposesSummaryDeferredAndDiskUsageFromResponse — 새 3필드를 뷰로 그대로 파생
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
      jsonResponse({ paused: true, pausedAt: 1_730_000_000_000, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }),
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
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ paused: true, pausedAt, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.paused).toBe(true));
    expect(result.current.elapsedMinutes).toBe(expectedMin);
  });

  // [R21/AC-03-4] MaintenanceStatusView 확장 2필드 — 뷰까지 넓혀야 화면에 닿는다 (BL-07/W-17).
  it('exposesSqliteBusyCountersFromResponse — 카운터 2종을 뷰로 그대로 파생', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 7, sqliteBusyDropped: 2, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.sqliteBusyEncountered).toBe(7));
    expect(result.current.sqliteBusyDropped).toBe(2);
  });

  // [Phase T / R23] AC-06-1/AC-07-1 — MaintenanceStatusView 확장 3필드.
  //   BE 가 내려줘도 뷰까지 넓히지 않으면 화면에 닿지 않는다 (R19·R20 이 정확히 그렇게 죽었다 — S-61).
  it('exposesSummaryDeferredAndDiskUsageFromResponse — 새 3필드를 뷰로 그대로 파생', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({
        paused: false,
        pausedAt: null,
        sqliteBusyEncountered: 1,
        sqliteBusyDropped: 0,
        traceSummaryDeferred: 4,
        dbSizeBytes: 1_442_205_696,
        freePageBytes: 179_621_888,
      }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.traceSummaryDeferred).toBe(4));
    expect(result.current.dbSizeBytes).toBe(1_442_205_696);
    expect(result.current.freePageBytes).toBe(179_621_888);
    // 기존 4필드의 뜻이 그대로인지도 같은 자리에서 확인한다(추가만 허용 — 불변식 I-11).
    expect(result.current.paused).toBe(false);
    expect(result.current.sqliteBusyEncountered).toBe(1);
  });

  // [R21/AC-03-4, S-115] 구형 factory(2필드 응답)·로딩 중 undefined → `?? 0` 한 분기로 흡수.
  //
  // ★★ 이 테스트의 **픽스처(아래 jsonResponse 인자)는 손대지 않습니다.** 필드를 더하는 순간
  //    `?? 0` 폴백 가드가 조용히 사라집니다(불변식 I-12 · 설계 §6.3 갈래 ②). 낡은 픽스처가 아니라
  //    의도적인 폴백 고정 자리입니다. R23 에서 늘어난 3필드의 폴백도 **같은 픽스처 하나로** 잠급니다.
  it('exposesZeroCountersWhenFieldsAbsent — 카운터 필드 부재 응답도 0 폴백 (구형 응답 흡수)', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      jsonResponse({ paused: false, pausedAt: null }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useMaintenanceStatus(), { wrapper });

    await waitFor(() => expect(result.current.paused).toBe(false));
    expect(result.current.sqliteBusyEncountered).toBe(0);
    expect(result.current.sqliteBusyDropped).toBe(0);
    // [Phase T / R23] 같은 픽스처로 새 3필드의 `?? 0` 폴백까지 함께 잠근다 (픽스처는 무변경).
    expect(result.current.traceSummaryDeferred).toBe(0);
    expect(result.current.dbSizeBytes).toBe(0);
    expect(result.current.freePageBytes).toBe(0);
  });
});
