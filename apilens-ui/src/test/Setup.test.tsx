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
    // [R10] AC-04-3 — 2차 보조 안내 + 예시 박힘 (계보 보존)
    // [R26/AC-R26-46] 예시에서 실운영 이름 하나를 뺐다. 남은 두 개를 이어서 단언한다 —
    // 낱말 하나만 보면 다른 자리의 같은 낱말에도 걸려서 이 줄을 실제로 봤는지 알 수 없다.
    expect(screen.getByText(/my-api, order-service/)).toBeInTheDocument();
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

  // [R26/AC-R26-45] (U2) AC 원문: "Setup 4단계 **복사 버튼과 알림 시험 1건** 추가.
  // fallback 갈래 시험은 **이미 있으므로 새로 만들지 않는다**"
  //
  // 여기서 말하는 "이미 있는 fallback 갈래 시험 2건" = 바로 위 두 개
  // (displaysFallbackWarningWhenAgentJarPathIsNull · hidesFallbackWarningWhenAgentJarPathIsPresent).
  // 복사 실패(권한 거부) 갈래는 새로 만들지 않는다 — AC 가 "1건 추가" 로 못 박았다.
  //
  // 무엇을 눌러 보나: 4단계 [복사] 버튼을 실제로 눌러, SH-02 가 약속한 두 가지
  // (버튼 라벨 변경 + 알림) 가 **둘 다** 나는지 본다. 하나만 보면 나머지 하나가 조용히
  // 빠져도 초록으로 지나간다.
  it('showsToastWhenTheSnippetCopyButtonIsClicked — Step 4 [복사] 클릭 시 알림 + 라벨 변경 (AC-R26-45)', async () => {
    mockFetchOk('/Users/foo/.apilens/apilens-agent.jar');

    // happy-dom 의 navigator.clipboard 대신 이 시험이 쓰는 대역을 끼운다.
    // vi.restoreAllMocks() 는 defineProperty 로 바꾼 속성을 되돌리지 않으므로 finally 에서 직접 되돌린다.
    const writeText = vi.fn(() => Promise.resolve());
    const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });

    try {
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

      // 전제 단언 — 스니펫이 실제로 만들어져야 [복사] 가 살아 있다(빈 스니펫이면 disabled).
      const copyButton = await screen.findByRole('button', { name: '스니펫 복사' });
      expect(copyButton).toBeEnabled();

      fireEvent.click(copyButton);

      // SH-02 ① 알림
      expect(await screen.findByText('붙여넣기용으로 복사했어요')).toBeInTheDocument();
      // SH-02 ② 버튼 라벨 변경
      await waitFor(() => {
        expect(screen.getByRole('button', { name: '스니펫 복사' })).toHaveTextContent('복사됨');
      });
      // 클립보드에 실제로 스니펫이 넘어갔는지 — 빈 문자열을 복사하고 알림만 띄우는 갈래 차단.
      expect(writeText).toHaveBeenCalledTimes(1);
      expect(writeText.mock.calls[0]?.[0]).toContain('apilens-agent.jar');
    } finally {
      if (originalClipboard) {
        Object.defineProperty(navigator, 'clipboard', originalClipboard);
      } else {
        Reflect.deleteProperty(navigator, 'clipboard');
      }
    }
  });

  // [2026-09-24] 화면 주소 검사가 서버와 같은 기준(호스트 있음)으로 본다 — 접두만 맞는 "http://" 는 막는다.
  it('rejectsServerUrlWithoutHost — "http://" 만 입력하면 호스트 없음 오류 + [다음] 막힘', () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    const input = screen.getByLabelText('Server URL');
    fireEvent.change(input, { target: { value: 'http://' } });
    fireEvent.blur(input);

    expect(screen.getByRole('alert')).toHaveTextContent('URL 호스트 없음');
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
  });

  it('rejectsServerUrlWithUnderscoreHost — 브라우저는 받아도 서버(Java URI)가 거부하는 밑줄 호스트도 화면에서 막음', () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    const input = screen.getByLabelText('Server URL');
    fireEvent.change(input, { target: { value: 'http://foo_bar:8765' } });
    fireEvent.blur(input);

    expect(screen.getByRole('alert')).toHaveTextContent('URL 호스트 없음');
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
  });

  it('showsPortFormatErrorWhenOnlyPortIsWrong — 호스트는 맞고 포트만 틀리면 포트 문구로 고칠 자리를 짚음', () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    const input = screen.getByLabelText('Server URL');
    fireEvent.change(input, { target: { value: 'http://host:abc' } });
    fireEvent.blur(input);

    expect(screen.getByRole('alert')).toHaveTextContent('URL 포트 형식 오류 (예: :8765)');
    expect(screen.queryByText(/URL 호스트 없음/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
  });

  it('acceptsServerUrlWithHost — "http://your-host:8765" 는 통과해 Step 2 로 넘어감', async () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);

    const input = screen.getByLabelText('Server URL');
    fireEvent.change(input, { target: { value: 'http://your-host:8765' } });
    fireEvent.blur(input);

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    const next = screen.getByRole('button', { name: '다음' });
    expect(next).toBeEnabled();
    fireEvent.click(next);
    expect(await screen.findByLabelText('Service Name')).toBeInTheDocument();
  });
});
