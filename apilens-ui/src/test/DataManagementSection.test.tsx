// DataManagementSection 단위 테스트 — 데이터 관리(수동 디스크 정리) 섹션.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 회귀 가드 정합. 본 task 는 "비협상" 라벨 AC 0건이라
// EXT-003 트리거 비발동이 정상이나, 단위 테스트 정방향 동사 관행은 유지):
//   showsTwoActionButtons — 진입 시 두 동작 버튼(지난 데이터 정리 / 전체 삭제) 노출
//   showsInlineConfirmBeforeCleanup — cleanup 은 인라인 [확인] 후에만 호출 (가벼운 확인)
//   showsCleanupSuccessToastWithFreedBytes — cleanup 성공 시 trace 건수 + 확보 용량 토스트
//   showsPurgeModalOnPurgeClick — 전체 삭제 클릭 시 강한 확인 모달 노출
//   showsPurgeSuccessToastAfterModalConfirm — 모달 [확인] 후에만 purge 호출 + 성공 토스트
//   showsErrorToastOnCleanupFailure — cleanup 실패 시 고정 문구 토스트 (BE 본문 노출 0)
// 반대 방향 lock-in 동사(hides*/rejects*/skips*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DataManagementSection } from '../components/settings/DataManagementSection';
import { ToastProvider } from '../components/Toast';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface FetchLog {
  cleanupCalls: number;
  purgeCalls: number;
}

/** URL/method 기반 fetch mock. behavior 로 cleanup/purge 성공·실패 분기. */
function mockApi(behavior: 'ok' | 'cleanupFail'): FetchLog {
  const log: FetchLog = { cleanupCalls: 0, purgeCalls: 0 };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = init?.method ?? 'GET';

    if (method === 'POST' && url.includes('/v1/maintenance/cleanup')) {
      log.cleanupCalls += 1;
      if (behavior === 'cleanupFail') {
        return Promise.resolve(jsonResponse({ error: 'boom' }, 500));
      }
      // freedBytes = 53687091200 → formatBytes → "50 GB"
      return Promise.resolve(
        jsonResponse({ deletedTraces: 12345, freedBytes: 53687091200, dbSizeBytes: 41943040 }),
      );
    }
    if (method === 'POST' && url.includes('/v1/maintenance/purge')) {
      log.purgeCalls += 1;
      return Promise.resolve(
        jsonResponse({ deletedTraces: 99999, freedBytes: 53687091200, dbSizeBytes: 0 }),
      );
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected call' }, 500));
  });
  return log;
}

function renderSection(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <DataManagementSection />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('DataManagementSection — 수동 디스크 정리 (cleanup / purge)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('showsTwoActionButtons — 진입 시 두 동작 버튼 노출', () => {
    mockApi('ok');
    renderSection();
    expect(screen.getByRole('button', { name: '지난 데이터 정리' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체 삭제' })).toBeInTheDocument();
  });

  it('showsInlineConfirmBeforeCleanup — cleanup 은 인라인 [확인] 후에만 호출', async () => {
    const log = mockApi('ok');
    renderSection();

    // 첫 클릭 = 확인 단계 노출만 (API 호출 0)
    fireEvent.click(screen.getByRole('button', { name: '지난 데이터 정리' }));
    expect(log.cleanupCalls).toBe(0);
    expect(
      screen.getByText('보관 기간이 지난 trace 를 지금 삭제할까요? 되돌릴 수 없어요.'),
    ).toBeInTheDocument();

    // [확인] 클릭 → cleanup 호출
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    await waitFor(() => expect(log.cleanupCalls).toBe(1));
  });

  it('showsCleanupSuccessToastWithFreedBytes — cleanup 성공 시 trace 건수 + 확보 용량 토스트', async () => {
    mockApi('ok');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '지난 데이터 정리' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(
      await screen.findByText('지난 데이터를 정리했어요. (trace 12345건 삭제, 약 50 GB 확보)'),
    ).toBeInTheDocument();
  });

  it('showsPurgeModalOnPurgeClick — 전체 삭제 클릭 시 강한 확인 모달 노출', () => {
    const log = mockApi('ok');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '전체 삭제' }));
    // 모달 강한 확인 문구 노출 + 아직 purge 미호출
    expect(screen.getByText('정말 모든 로그를 삭제할까요? 되돌릴 수 없어요.')).toBeInTheDocument();
    expect(log.purgeCalls).toBe(0);
  });

  it('showsPurgeSuccessToastAfterModalConfirm — 모달 [확인] 후에만 purge 호출 + 성공 토스트', async () => {
    const log = mockApi('ok');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '전체 삭제' }));
    // 모달 내 [확인] 버튼 (role dialog 안에서 조회)
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '확인' }));

    await waitFor(() => expect(log.purgeCalls).toBe(1));
    expect(await screen.findByText('모든 로그를 삭제했어요. (약 50 GB 확보)')).toBeInTheDocument();
  });

  it('showsErrorToastOnCleanupFailure — cleanup 실패 시 고정 문구 토스트 (BE 본문 노출 0)', async () => {
    mockApi('cleanupFail');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '지난 데이터 정리' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(
      await screen.findByText('정리에 실패했어요. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
    // 서버 error 본문('boom') 직접 노출 금지
    expect(screen.queryByText(/boom/)).toBe(null);
  });
});
