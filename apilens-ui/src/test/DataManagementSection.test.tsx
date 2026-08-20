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
//
// [Phase K] (US-07) optimize 추가 검증 (정방향 동사 명시 — EXT-003 lock-in 가드):
//   showsOptimizeButton — 진입 시 "최적화" 버튼 노출
//   showsInlineConfirmBeforeOptimize — optimize 는 인라인 [확인] 후에만 호출
//   showsOptimizeSuccessToastWithFreedBytes — busy=false 정상 회수 시 확보 용량 토스트 (T-C06)
//   showsPartialReclaimToastWhenBusyWithFreedBytes — busy=true + freedBytes>0 부분 회수 (T-C07)
//   showsDiskShortageToastWhenBusyWithZeroFreed — busy=true + freedBytes==0 디스크 부족 거부 (T-C08)
//   disablesOptimizeButtonWhileCleanupRunning — cleanup 실행 중이면 optimize 버튼 잠금 (C-C01)
//
// [Phase T / R23] AC-06-2/AC-07-2 추가 검증 (정방향 동사 명시 — EXT-003 lock-in 가드):
//   showsZeroDeferredSummaryWithNormalBadge — 요약 실패 0 은 "0건 (정상)" (T-R23-02)
//   showsDeferredSummaryCountWithoutNormalBadge — 0 이 아니면 "(정상)" 병기 없음 (T-R23-03)
//   displaysThousandsSeparatedDeferredCount — 1234 → "1,234건" (toLocaleString)
//   showsDiskUsageInItsOwnSection — 「디스크 사용량」이 「적재 상태」와 다른 구획에 뜬다 (AC-07-2 비협상)
//   showsZeroFreeSpaceAsPlainValue — 회수 가능한 빈 공간 0 → "0 B" (결함 아님 — 전체 삭제 직후 정상값)
//   keepsExistingHelpTextAndButtonsUnchanged — 기존 도움말·라벨·버튼 3개 문구 무변경 (T-R23-09/10)
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
  optimizeCalls: number;
}

/**
 * URL/method 기반 fetch mock. behavior 로 cleanup/purge/optimize 성공·실패·busy 분기.
 *   - 'optimizeBusyPartial' : optimize 응답 busy=true + freedBytes>0 (부분 회수, T-C07).
 *   - 'optimizeBusyDisk'    : optimize 응답 busy=true + freedBytes==0 (디스크 부족, T-C08).
 */
function mockApi(
  behavior: 'ok' | 'cleanupFail' | 'optimizeBusyPartial' | 'optimizeBusyDisk',
): FetchLog {
  const log: FetchLog = { cleanupCalls: 0, purgeCalls: 0, optimizeCalls: 0 };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = init?.method ?? 'GET';

    // [Phase R15] 수신 일시정지 상태 폴링 — DataManagementSection 이 useMaintenanceStatus 공유(기본 paused=false).
    if (url.includes('/v1/maintenance/status')) {
      return Promise.resolve(jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    }

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
    if (method === 'POST' && url.includes('/v1/maintenance/optimize')) {
      log.optimizeCalls += 1;
      if (behavior === 'optimizeBusyPartial') {
        // busy=true + freedBytes>0 → 부분 회수 (T-C07). 1048576 → "1 MB"
        return Promise.resolve(
          jsonResponse({ deletedTraces: 0, freedBytes: 1048576, dbSizeBytes: 41943040, busy: true }),
        );
      }
      if (behavior === 'optimizeBusyDisk') {
        // busy=true + freedBytes==0 → 디스크 부족 거부 (T-C08).
        return Promise.resolve(
          jsonResponse({ deletedTraces: 0, freedBytes: 0, dbSizeBytes: 41943040, busy: true }),
        );
      }
      // 정상 회수 (busy=false). freedBytes = 53687091200 → "50 GB"
      return Promise.resolve(
        jsonResponse({ deletedTraces: 0, freedBytes: 53687091200, dbSizeBytes: 41943040, busy: false }),
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

describe('DataManagementSection — 디스크 조각 정리(최적화, US-07)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('showsOptimizeButton — 진입 시 "최적화" 버튼 + "삭제 없음" 의미 차별 문구 노출 (T-C01/T-C03)', () => {
    mockApi('ok');
    renderSection();
    expect(screen.getByRole('button', { name: '최적화' })).toBeInTheDocument();
    // T-C02 — purge 혼동 차단 ("삭제하지 않고")
    expect(
      screen.getByText('데이터를 삭제하지 않고 파일 조각만 정리해 디스크 크기를 줄여요.'),
    ).toBeInTheDocument();
  });

  it('showsInlineConfirmBeforeOptimize — optimize 는 인라인 [확인] 후에만 호출 (T-C05)', async () => {
    const log = mockApi('ok');
    renderSection();

    // 첫 클릭 = 확인 단계 노출만 (API 호출 0)
    fireEvent.click(screen.getByRole('button', { name: '최적화' }));
    expect(log.optimizeCalls).toBe(0);
    expect(
      screen.getByText(
        '데이터는 그대로 두고 파일 조각만 정리할까요? 라이브 적재 중이면 일부만 회수될 수 있어요.',
      ),
    ).toBeInTheDocument();

    // [확인] 클릭 → optimize 호출
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    await waitFor(() => expect(log.optimizeCalls).toBe(1));
  });

  it('showsOptimizeSuccessToastWithFreedBytes — busy=false 정상 회수 시 확보 용량 토스트 (T-C06)', async () => {
    mockApi('ok');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '최적화' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(
      await screen.findByText('파일 조각을 정리했어요. (약 50 GB 확보)'),
    ).toBeInTheDocument();
  });

  it('showsPartialReclaimToastWhenBusyWithFreedBytes — busy=true + freedBytes>0 부분 회수 토스트 (T-C07)', async () => {
    mockApi('optimizeBusyPartial');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '최적화' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(
      await screen.findByText('적재 중이라 일부만 회수됐어요. 저사용 시간대에 다시 시도해 주세요.'),
    ).toBeInTheDocument();
  });

  it('showsDiskShortageToastWhenBusyWithZeroFreed — busy=true + freedBytes==0 디스크 부족 거부 토스트 (T-C08)', async () => {
    mockApi('optimizeBusyDisk');
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: '최적화' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(
      await screen.findByText(
        '디스크 여유 공간이 부족해 최적화를 건너뛰었어요. DB 크기 이상의 여유가 필요해요.',
      ),
    ).toBeInTheDocument();
  });

  it('disablesOptimizeButtonWhileCleanupRunning — cleanup 실행 중이면 optimize 버튼 잠금 (C-C01 상호 배타)', async () => {
    // cleanup 응답을 지연시켜 isPending 구간 확보.
    let resolveCleanup: ((r: Response) => void) | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      const method = init?.method ?? 'GET';
      if (method === 'POST' && url.includes('/v1/maintenance/cleanup')) {
        return new Promise<Response>((resolve) => {
          resolveCleanup = resolve;
        });
      }
      return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
    });
    renderSection();

    // cleanup 시작 (인라인 확인 → 확인 클릭 → pending)
    fireEvent.click(screen.getByRole('button', { name: '지난 데이터 정리' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    // cleanup pending 동안 optimize 버튼 disabled (C-C01).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '최적화' })).toBeDisabled(),
    );

    // cleanup 완료시켜 정리.
    resolveCleanup?.(
      jsonResponse({ deletedTraces: 1, freedBytes: 0, dbSizeBytes: 0 }),
    );
  });
});

// [Phase R15] AC-B2-1/AC-B2-2/AC-B6-1 — 수신 일시정지/재개 토글 + 유도(미강제).
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드. 본 phase 는 사용자 명시 비협상 D01~D08 보유):
//   pausesReceivingAndInvalidatesOnToggle — 토글 클릭 → pauseReceiving 호출 + invalidate + toast(T-07)
//   showsErrorToastOnToggleFailure — 토글 실패 → toast(T-08), BE 본문 비노출
//   disablesToggleWhileCleanupRunning — cleanup pending 중 토글 disabled(§7.1 매트릭스)
//   showsCleanupHintAndKeepsButtonsEnabledWhenNotPaused — paused=false + optimize/purge → T-10 유도 + 버튼 enabled
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
interface ToggleLog {
  pauseCalls: number;
  resumeCalls: number;
  statusInvalidations: number;
}

/**
 * status(paused 분기) + pause/resume mock. behavior 로 토글 성공·실패.
 *   - paused=false → 토글은 pause 방향. paused=true → resume 방향.
 *   - 'toggleFail' → pause POST 가 500(BE 본문 'boom') 반환.
 */
function mockToggleApi(paused: boolean, behavior: 'ok' | 'toggleFail' = 'ok'): ToggleLog {
  const log: ToggleLog = { pauseCalls: 0, resumeCalls: 0, statusInvalidations: 0 };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = init?.method ?? 'GET';

    if (method === 'GET' && url.includes('/v1/maintenance/status')) {
      log.statusInvalidations += 1;
      return Promise.resolve(
        jsonResponse({ paused, pausedAt: paused ? 1_730_000_000_000 : null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }),
      );
    }
    if (method === 'POST' && url.includes('/v1/maintenance/pause')) {
      log.pauseCalls += 1;
      if (behavior === 'toggleFail') {
        return Promise.resolve(jsonResponse({ error: 'boom' }, 500));
      }
      return Promise.resolve(jsonResponse({ paused: true, pausedAt: 1_730_000_000_000, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    }
    if (method === 'POST' && url.includes('/v1/maintenance/resume')) {
      log.resumeCalls += 1;
      return Promise.resolve(jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
  return log;
}

describe('DataManagementSection — 수신 일시정지/재개 토글 (R15)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('pausesReceivingAndInvalidatesOnToggle — 토글 클릭 시 pauseReceiving 호출 + status 무효화 + 토스트(T-07)', async () => {
    const log = mockToggleApi(false, 'ok');
    renderSection();

    // 초기 status 폴링(paused=false) 완료 후 버튼 라벨 = "수신 일시정지".
    const toggleBtn = await screen.findByRole('button', { name: '수신 일시정지' });
    const initialStatus = log.statusInvalidations;
    fireEvent.click(toggleBtn);

    await waitFor(() => expect(log.pauseCalls).toBe(1));
    // 성공 토스트(T-07).
    expect(
      await screen.findByText('수신을 일시정지했어요. 정리가 끝나면 다시 재개해 주세요.'),
    ).toBeInTheDocument();
    // invalidate(['maintenance','status']) → status 재폴링 발생.
    await waitFor(() => expect(log.statusInvalidations).toBeGreaterThan(initialStatus));
  });

  it('showsErrorToastOnToggleFailure — 토글 실패 시 고정 문구 토스트(T-08), BE 본문 비노출', async () => {
    mockToggleApi(false, 'toggleFail');
    renderSection();

    const toggleBtn = await screen.findByRole('button', { name: '수신 일시정지' });
    fireEvent.click(toggleBtn);

    expect(
      await screen.findByText('상태 변경에 실패했어요. 잠시 후 다시 시도해 주세요.'),
    ).toBeInTheDocument();
    // 서버 error 본문('boom') 직접 노출 금지.
    expect(screen.queryByText(/boom/)).toBe(null);
  });

  it('disablesToggleWhileCleanupRunning — cleanup pending 중 토글 버튼 disabled (§7.1 매트릭스)', async () => {
    // status(paused=false) 는 즉답, cleanup 은 지연시켜 isPending 구간 확보.
    let resolveCleanup: ((r: Response) => void) | undefined;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      const method = init?.method ?? 'GET';
      if (url.includes('/v1/maintenance/status')) {
        return Promise.resolve(jsonResponse({ paused: false, pausedAt: null, sqliteBusyEncountered: 0, sqliteBusyDropped: 0, traceSummaryDeferred: 0, dbSizeBytes: 1_442_205_696, freePageBytes: 179_621_888 }));
      }
      if (method === 'POST' && url.includes('/v1/maintenance/cleanup')) {
        return new Promise<Response>((resolve) => {
          resolveCleanup = resolve;
        });
      }
      return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
    });
    renderSection();

    // 토글 버튼이 뜬 뒤 cleanup 시작.
    await screen.findByRole('button', { name: '수신 일시정지' });
    fireEvent.click(screen.getByRole('button', { name: '지난 데이터 정리' }));
    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    // cleanup pending 동안 토글 버튼 disabled (§7.1).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '수신 일시정지' })).toBeDisabled(),
    );

    resolveCleanup?.(jsonResponse({ deletedTraces: 1, freedBytes: 0, dbSizeBytes: 0 }));
  });

  it('showsCleanupHintAndKeepsButtonsEnabledWhenNotPaused — paused=false 시 유도 텍스트(T-10) + optimize/purge 버튼 enabled', async () => {
    mockToggleApi(false, 'ok');
    renderSection();

    // status(paused=false) 도착 후 유도 텍스트 노출 (optimize/purge 카드, 미강제).
    const hints = await screen.findAllByText('먼저 수신을 일시정지하면 정리가 더 빠르고 안전해요.');
    expect(hints.length).toBeGreaterThanOrEqual(2); // optimize + purge 두 곳
    // 버튼은 disabled 아님(enabled 유지 — 미강제).
    expect(screen.getByRole('button', { name: '최적화' })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '전체 삭제' })).not.toBeDisabled();
  });
});

// ── [R21/AC-03-1] 적재 상태 — SQLITE_BUSY 카운터 2종 (읽기 전용 구획, B-25) ──────────
//
// R21/AC-03-1 verbatim: "sqliteBusyEncountered·sqliteBusyDropped 가 Settings 데이터 관리
// 섹션(DataManagementSection)에 노출된다. 카운터 0 도 정상값 — 결핍 어휘 금지(NFR-12)."
// 정방향 동사(shows*/displays*) — 반대 방향 lock-in 동사 0건 (EXT-003 가드).
describe('DataManagementSection — 적재 상태 카운터 (R21/AC-03-1)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * status 응답의 카운터만 바꿔 mock (다른 endpoint 는 비대상).
   *
   * [Phase T / R23] AC-07-4/AC-07-5 — 이 factory 는 픽스처가 **여러 줄로 쪼개져 있어**
   *   회귀 가드 G-R23-07 의 게이트 A(한 줄짜리 상태 픽스처를 세는 축)가 못 잡는 자리다.
   *   같은 가드의 게이트 B(뷰 필드 이름을 세는 축)가 이 사각지대를 덮는다 —
   *   게이트 하나로 닫으면 여기가 조용히 낡는다. 두 게이트의 판정 명령 원문은 설계 §6.1 에 있다.
   *   ⚠️ 게이트가 세는 문자열을 이 주석에 그대로 옮겨 적지 않는다(적으면 주석 자신이 거짓 hit 이 된다).
   *   요약 실패 흐름 수·디스크 두 값도 같은 자리에서 함께 넓힌다.
   */
  function mockStatusWithCounters(
    encountered: number,
    dropped: number,
    deferred = 0,
    dbSizeBytes = 1_442_205_696,
    freePageBytes = 179_621_888,
  ): void {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      if (url.includes('/v1/maintenance/status')) {
        return Promise.resolve(
          jsonResponse({
            paused: false,
            pausedAt: null,
            sqliteBusyEncountered: encountered,
            sqliteBusyDropped: dropped,
            traceSummaryDeferred: deferred,
            dbSizeBytes,
            freePageBytes,
          }),
        );
      }
      return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
    });
  }

  it('showsZeroCountersWithNormalBadge — 0 은 "0회 (정상)" / "0개 (정상)" (B-25 / T-17)', async () => {
    mockStatusWithCounters(0, 0);
    renderSection();
    expect(await screen.findByText('적재 상태')).toBeInTheDocument();
    // T-15 — 단위가 다른 라벨 2종 (횟수 vs 청크).
    expect(screen.getByText('적재 중 잠금 경합 (SQLITE_BUSY)')).toBeInTheDocument();
    expect(screen.getByText('경합으로 저장하지 못한 청크')).toBeInTheDocument();
    // T-17 — "(정상)" 병기 (결핍 어휘 금지 — 불변식 12).
    // [Phase T / R23] AC-06-2 — 2 → 3. 「적재 상태」 세 번째 줄(요약을 저장하지 못한 흐름)이
    //   같은 형태로 붙으면서 이 구획의 "(정상)" 개수가 하나 늘었다. 「디스크 사용량」 구획에는
    //   "(정상)" 을 붙이지 않으므로 이 수는 여전히 「적재 상태」 구획만 센다.
    const normals = await screen.findAllByText('(정상)');
    expect(normals).toHaveLength(3);
    // T-16 — 리셋 안내 상시.
    expect(
      screen.getByText(/서버를 재시작하면 0부터 다시 세요 — 재시작 후 0 은 정상이에요/),
    ).toBeInTheDocument();
  });

  it('showsSingleCountWithoutNormalBadge — 1 은 "1회" (정상 병기 없음, B-25)', async () => {
    mockStatusWithCounters(1, 0);
    renderSection();
    expect(await screen.findByText('1회')).toBeInTheDocument();
  });

  it('displaysThousandsSeparatedCount — 1234 → "1,234회" (toLocaleString, B-25)', async () => {
    mockStatusWithCounters(1234, 0);
    renderSection();
    expect(await screen.findByText('1,234회')).toBeInTheDocument();
  });

  it('displaysDroppedChunkCountWithUnit — dropped 는 청크 단위 "N개" (T-15 단위 구분)', async () => {
    mockStatusWithCounters(0, 3);
    renderSection();
    expect(await screen.findByText('3개')).toBeInTheDocument();
  });
});

// ── [Phase T / R23] AC-06-2 · AC-07-1 · AC-07-2 — 요약 실패 흐름 + 「디스크 사용량」 구획 ──────
//
// R23/AC-06-2 verbatim: "화면 「적재 상태」에 **세 번째 줄**이 뜬다. 값이 0 이 아니면 `(정상)` 이
//   **안 붙는다.**"
// R23/AC-07-1 verbatim: "DB 크기와 회수 가능 공간이 **버튼을 누르지 않아도** 상태 응답에 실린다."
// R23/AC-07-2 verbatim: "★ 두 값은 **「적재 상태」와 별도 구획**에 표시된다(§7 — 기존 도움말 문장이
//   이 두 값에 대해 거짓이 되는 것을 막는다)." (사용자 명시 비협상 결정)
// 정방향 동사(shows*/displays*/keeps*) — 반대 방향 lock-in 동사 0건 (EXT-003 가드).
describe('DataManagementSection — 요약 실패 흐름 · 디스크 사용량 (R23/AC-06-2, AC-07-1/2)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** 위 describe 의 factory 와 같은 형태 — status 응답만 바꾼다(다른 endpoint 는 비대상). */
  function mockStatus(
    deferred: number,
    dbSizeBytes = 1_073_741_824,
    freePageBytes = 41_943_040,
  ): void {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      if (url.includes('/v1/maintenance/status')) {
        return Promise.resolve(
          jsonResponse({
            paused: false,
            pausedAt: null,
            sqliteBusyEncountered: 0,
            sqliteBusyDropped: 0,
            traceSummaryDeferred: deferred,
            dbSizeBytes,
            freePageBytes,
          }),
        );
      }
      return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
    });
  }

  it('showsZeroDeferredSummaryWithNormalBadge — 0 은 "0건 (정상)" (T-R23-01/02)', async () => {
    mockStatus(0);
    renderSection();
    // ⚠️ 0 은 훅의 `?? 0` 폴백 값과 같아서, 응답이 도착하기 전에도 "0건" 이 보인다.
    //    응답이 실제로 도착했다는 것을 폴백과 값이 다른 자리(DB 크기)로 먼저 확인한 뒤 단언한다.
    await screen.findByText('1 GB');
    // T-R23-01 — 라벨이 단위를 밝힌다(흐름 = 건). 위 두 줄은 횟수·청크 수라 셋이 서로 다르다.
    expect(screen.getByText('요약을 저장하지 못한 흐름')).toBeInTheDocument();
    expect(screen.getByText('0건')).toBeInTheDocument();
  });

  it('showsDeferredSummaryCountWithoutNormalBadge — 1 은 "1건" (정상 병기 없음, T-R23-03)', async () => {
    mockStatus(1);
    renderSection();
    expect(await screen.findByText('1건')).toBeInTheDocument();
    // 이 값이 0 이 아니면 "(정상)" 은 나머지 두 줄(경합 0회 · 청크 0개)에만 붙는다.
    expect(screen.getAllByText('(정상)')).toHaveLength(2);
  });

  it('displaysThousandsSeparatedDeferredCount — 1234 → "1,234건" (toLocaleString)', async () => {
    mockStatus(1234);
    renderSection();
    expect(await screen.findByText('1,234건')).toBeInTheDocument();
  });

  it('showsDiskUsageInItsOwnSection — 「디스크 사용량」이 「적재 상태」와 다른 구획에 뜬다 (AC-07-2 비협상)', async () => {
    mockStatus(0);
    renderSection();

    // 신설 구획 — 제목 + 두 라벨 + 전용 도움말이 한 덩어리 안에 있다.
    // 제목은 응답 전에도 그려지므로, 값이 도착한 뒤에 구획을 잡는다.
    await screen.findByText('1 GB');
    const diskTitle = screen.getByText('디스크 사용량');
    const diskBlock = diskTitle.parentElement as HTMLElement;
    expect(within(diskBlock).getByText('DB 크기')).toBeInTheDocument();
    expect(within(diskBlock).getByText('회수 가능한 빈 공간')).toBeInTheDocument();
    expect(within(diskBlock).getByText('1 GB')).toBeInTheDocument();
    expect(within(diskBlock).getByText('40 MB')).toBeInTheDocument();
    expect(within(diskBlock).getByText(/DB 파일에서 바로 읽는 값이라/)).toBeInTheDocument();

    // ★ 두 구획이 갈려 있다는 것이 이 라운드의 비협상 결정이다.
    //   인메모리 도움말("재시작하면 0부터 다시 세요")이 디스크 구획 안에 있으면
    //   그 문장이 DB 에서 읽는 두 값에 대해 거짓이 된다 — 그것을 막는 단언이다.
    expect(within(diskBlock).queryByText(/서버 메모리에만 있는 숫자라/)).toBe(null);

    // 반대 방향도 같다 — 「적재 상태」 구획 안에 디스크 두 줄이 섞이지 않는다.
    const ingestBlock = screen.getByText('적재 상태').parentElement as HTMLElement;
    expect(within(ingestBlock).getByText('요약을 저장하지 못한 흐름')).toBeInTheDocument();
    expect(within(ingestBlock).queryByText('DB 크기')).toBe(null);
    expect(within(ingestBlock).queryByText('회수 가능한 빈 공간')).toBe(null);
  });

  it('showsZeroFreeSpaceAsPlainValue — 회수 가능한 빈 공간 0 → "0 B" (전체 삭제 직후 정상값)', async () => {
    // 2026-08-13 운영 실측: 전체 삭제 + 최적화 뒤 freelist 가 0 이었다. 결함이 아니다.
    mockStatus(0, 1_073_741_824, 0);
    renderSection();
    // DB 크기가 먼저 도착한 것을 확인해야 "0 B" 가 폴백이 아니라 응답 값임이 확정된다.
    await screen.findByText('1 GB');
    const diskBlock = screen.getByText('디스크 사용량').parentElement as HTMLElement;
    expect(within(diskBlock).getByText('0 B')).toBeInTheDocument();
  });

  it('keepsExistingHelpTextAndButtonsUnchanged — 기존 도움말·라벨·버튼 3개 문구 무변경 (T-R23-09/10)', async () => {
    mockStatus(0);
    renderSection();

    // T-R23-09 — 「적재 상태」 도움말 문면 그대로.
    expect(
      await screen.findByText(
        /서버 메모리에만 있는 숫자라 서버를 재시작하면 0부터 다시 세요 — 재시작 후 0 은 정상이에요\. 청크 1개 ≈ 500 span 이에요\./,
      ),
    ).toBeInTheDocument();
    // T-R23-10 — 기존 두 라벨 + 버튼 3개 문구.
    expect(screen.getByText('적재 중 잠금 경합 (SQLITE_BUSY)')).toBeInTheDocument();
    expect(screen.getByText('경합으로 저장하지 못한 청크')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '지난 데이터 정리' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체 삭제' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '최적화' })).toBeInTheDocument();
  });
});
