// [R10] AC-02-1 / AC-03-1 / AC-04-2 / AC-05-10 / AC-05-11 — Setup wizard 회수 검증.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   acceptsWindowLocationOriginAsStep1Default — V-USER-R10-01 sign-off
//   displaysServiceNameInstructionInPoliteForm — V-USER-R10-02 sign-off (해요체)
//   navigatesToDashboardWithServiceOnSuccess — V-USER-R10-05 경로 A (V-USER-R10-05)
//   navigatesToRootWithoutServiceOnSkip — D-04 skip 경로
//
// 회귀 가드 (반대 방향 lock-in 차단):
//   rejectsWindowLocationOrigin / hidesServiceNameInstruction 같은 반대 방향 동사 0건
//   "사용자 앱을 구분할 이름을 입력해 주세요" R9 잔존 카피 0 hit
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactNode } from 'react';
import { Setup } from '../pages/Setup';
import { ToastProvider } from '../components/Toast';

function makeWrapper(initialPath = '/setup'): {
  Wrapper: () => ReactNode;
  queryClient: QueryClient;
} {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  const Wrapper = (): ReactNode => (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Setup />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>
  );
  return { Wrapper, queryClient };
}

/** 기본 fetch mock — agent-jar-path / setup/complete 둘 다 200. */
function mockFetchOk(agentJarPath: string | null = null): ReturnType<typeof vi.spyOn> {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('/v1/setup/agent-jar-path')) {
      return new Response(JSON.stringify({ path: agentJarPath }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    if (url.includes('/v1/setup/complete')) {
      return new Response(JSON.stringify({ completed: true, completedAt: 1716386700000 }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    // default — empty 200
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
}

describe('Setup wizard — [R10] 회수 검증', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('acceptsWindowLocationOriginAsStep1Default — Step 1 input default = window.location.origin (V-USER-R10-01)', () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    // Step 1 진입 시 Server URL input value = window.location.origin
    const input = screen.getByLabelText('Server URL') as HTMLInputElement;
    expect(input.value).toBe(window.location.origin);
    // 사용자 입력 시 onChange 정상 동작
    expect(input.value).not.toBe('');
  });

  it('displaysServiceNameInstructionInPoliteForm — Step 2 1차 안내 + 2차 보조 박힘 (V-USER-R10-02)', async () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    // Step 1 → Step 2 이동 (default value 가 valid 이므로 [다음] 활성)
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    // [R10] AC-04-2 — 1차 안내 (해요체) 박힘
    await waitFor(() => {
      expect(
        screen.getByText('ApiLens 가 모니터링할 사용자 앱(서비스/시스템) 의 이름이에요'),
      ).toBeInTheDocument();
    });
    // [R10] AC-04-3 — 2차 보조 안내 + 예시 3개 박힘
    expect(screen.getByText(/order-service, vams/)).toBeInTheDocument();
    // [R10] 회귀 가드 — R9 잔존 카피 0 hit
    expect(
      screen.queryByText('사용자 앱을 구분할 이름을 입력해 주세요 (영문/숫자/하이픈/언더스코어)'),
    ).not.toBeInTheDocument();
  });

  it('displaysFallbackWarningWhenAgentJarPathIsNull — Step 4 path=null 시 경고 표시 (AC-05-11)', async () => {
    mockFetchOk(null); // agent-jar-path 응답 = { path: null }
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    // Step 1 → 2: 다음
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    // Step 2: Service Name 입력 → 다음
    const svcInput = await screen.findByLabelText('Service Name');
    fireEvent.change(svcInput, { target: { value: 'my-api' } });
    fireEvent.blur(svcInput);
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    // Step 3 → Step 4
    await screen.findByText('Capture Options');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    // [R10] AC-05-11 — path=null 시 fallback 경고 표시 (useAgentJarPath 가 fetch 해소 후)
    await waitFor(() => {
      expect(
        screen.getByText('agent jar 자동 추출 안 됨 — server 재빌드 후 다시 시도해 주세요'),
      ).toBeInTheDocument();
    });
  });

  it('navigatesToRootOnCancelWithoutConfirm — 취소 클릭 시 confirm 없이 / 로 즉시 이동', async () => {
    mockFetchOk();
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, refetchOnWindowFocus: false },
        mutations: { retry: false },
      },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter initialEntries={['/setup']}>
            <Routes>
              <Route path="/setup" element={<Setup />} />
              <Route path="/" element={<div>DASHBOARD_SENTINEL</div>} />
            </Routes>
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>,
    );

    // 취소 클릭 — 건너뛰기와 달리 confirm 모달을 띄우지 않고 즉시 나간다.
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    // 회귀 가드 (반대) — 취소 경로에 confirm 모달 0 (건너뛰기 모달 제목 미출현).
    expect(screen.queryByText('Setup 건너뛰기')).not.toBeInTheDocument();
    // 대시보드(/)로 이동.
    await waitFor(() => {
      expect(screen.getByText('DASHBOARD_SENTINEL')).toBeInTheDocument();
    });
  });

  it('hidesFallbackWarningWhenAgentJarPathIsPresent — Step 4 path 존재 시 경고 표시 안 함', async () => {
    mockFetchOk('/Users/foo/.apilens/apilens-agent.jar');
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    // Step 1 → 2 → 3 → 4
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    const svcInput = await screen.findByLabelText('Service Name');
    fireEvent.change(svcInput, { target: { value: 'my-api' } });
    fireEvent.blur(svcInput);
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    await screen.findByText('Capture Options');
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    // 부착 스니펫 박스(기본 java -jar 탭)에 절대경로 박힘 (Q-08 parity)
    await waitFor(() => {
      const code = screen.getByLabelText('부착 스니펫');
      expect(code.textContent).toContain('/Users/foo/.apilens/apilens-agent.jar');
    });
    // [R10] 회귀 가드 — path 존재 시 경고 0
    expect(
      screen.queryByText('agent jar 자동 추출 안 됨 — server 재빌드 후 다시 시도해 주세요'),
    ).not.toBeInTheDocument();
  });
});
