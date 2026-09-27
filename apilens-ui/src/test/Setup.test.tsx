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
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// ── [R27/FR-27-10 · FR-27-11] 설치 400 사유 줄 · 초점 · 포트 상한 ──────────────────────────
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   showsTheServerReasonNearTheButtonsOnComplete400 · keepsTheOldToastOnComplete500
//   showsTheServerReasonInsideTheSkipModalOn400 · keepsTheOldToastOnSkip500
//   showsHttp400WhenTheBodyHasNoReason · clearsTheReasonOnRetryCancelAndEscape
//   rejectsAPortAboveTheServerMaximum(포트 상한 = 정방향 동작이 「막는다」)
//
// UC-08 문면 원문: `서버가 거절한 이유: {사유}` · UC-02: `Setup 완료 실패 — 입력값을 확인해 주세요`.

const REASON = 'serverUrl must include a host (예: http://192.168.0.10:8765)';

type CompleteReply = () => Promise<Response>;

/** /v1/setup/complete 는 replies 를 차례로(마지막 것 반복). 나머지는 200. */
function mockFetchComplete(replies: CompleteReply[]): { completeCalls: () => number } {
  let n = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.includes('/v1/setup/complete')) {
      const reply = replies[Math.min(n, replies.length - 1)]!;
      n += 1;
      return reply();
    }
    if (url.includes('/v1/setup/agent-jar-path')) {
      return new Response(JSON.stringify({ path: '/Users/foo/.apilens/apilens-agent.jar' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  return { completeCalls: () => n };
}

const reply400 = (body: string): CompleteReply => () =>
  Promise.resolve(new Response(body, { status: 400, headers: { 'Content-Type': 'application/json' } }));
const reply500: CompleteReply = () =>
  Promise.resolve(
    new Response(JSON.stringify({ error: 'boom' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    }),
  );

/** 시험이 풀어 줄 때까지 기다리는 응답(실시간 대기 0) — 「요청 중」 렌더를 한 번 그리게 하는 용도. */
function deferred(reply: CompleteReply): { reply: CompleteReply; release: () => void } {
  let release: () => void = () => undefined;
  return {
    reply: () => new Promise<Response>((resolve) => {
      release = () => void reply().then(resolve);
    }),
    release: () => release(),
  };
}

async function goToStep4(): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  const svcInput = await screen.findByLabelText('Service Name');
  fireEvent.change(svcInput, { target: { value: 'my-api' } });
  fireEvent.blur(svcInput);
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  await screen.findByText('Capture Options');
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  await screen.findByRole('button', { name: '완료' });
}

/** 헤더 [건너뛰기 →]에 초점을 둔 채 모달을 연다(모달이 닫힐 때 돌아올 자리). */
async function openSkipModal(): Promise<HTMLElement> {
  const headerSkip = screen.getByRole('button', { name: '건너뛰기' });
  headerSkip.focus();
  fireEvent.click(headerSkip);
  const dialog = await screen.findByRole('dialog');
  // 열릴 때 첫 초점 = 모달 [취소](다음 tick).
  await waitFor(() => expect(within(dialog).getByRole('button', { name: '취소' })).toHaveFocus());
  return dialog;
}

describe('Setup wizard — [R27] 설치 400 사유 · 포트 상한', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // TZ-32 — [완료] 400: 알림 UC-02 한 줄 + 사유 줄(글자 그대로) · 초점 [완료] · [이전] 뒤에도 남음 · 입력 변경 시 사라짐.
  it('showsTheServerReasonNearTheButtonsOnComplete400', async () => {
    mockFetchComplete([reply400(JSON.stringify({ error: REASON }))]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    await goToStep4();

    fireEvent.click(screen.getByRole('button', { name: '완료' }));

    expect(await screen.findByText('Setup 완료 실패 — 입력값을 확인해 주세요')).toBeInTheDocument();
    const reasonLine = await screen.findByText(`서버가 거절한 이유: ${REASON}`);
    expect(reasonLine).toHaveAttribute('role', 'alert');
    // 서버 원문은 알림에 싣지 않는다(UXD-11).
    expect(screen.queryByText(new RegExp(`서버 응답:`))).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: '완료' })).toHaveFocus());

    // [이전] 뒤에도 남는다(RP-10 ④).
    fireEvent.click(screen.getByRole('button', { name: '이전' }));
    await screen.findByText('Capture Options');
    expect(screen.getByText(`서버가 거절한 이유: ${REASON}`)).toBeInTheDocument();

    // 이름 입력이 바뀌면 사라진다.
    fireEvent.click(screen.getByRole('button', { name: '이전' }));
    const svcInput = await screen.findByLabelText('Service Name');
    fireEvent.change(svcInput, { target: { value: 'my-api-2' } });
    expect(screen.queryByText(/서버가 거절한 이유/)).not.toBeInTheDocument();
  });

  // TZ-33 — [완료] 500 은 지금 문구 그대로 · 사유 줄 0. 전제: 알림은 보인다.
  it('keepsTheOldToastOnComplete500', async () => {
    mockFetchComplete([reply500]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    await goToStep4();
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    expect(await screen.findByText('Setup 완료 실패 — 잠시 후 다시 시도해 주세요')).toBeInTheDocument();
    expect(screen.queryByText(/서버가 거절한 이유/)).not.toBeInTheDocument();
  });

  // TZ-34 — [건너뛰기] 400: 알림 없음 · 모달 안 사유 줄 · 모달 열린 채 · 초점 [취소].
  //   더함(§2 TZ-34): 400 → [취소] → 초점이 헤더 [건너뛰기 →]로 돌아간다(모달 닫기 콜백 고정).
  it('showsTheServerReasonInsideTheSkipModalOn400', async () => {
    // 응답을 붙잡아 「요청 중」 렌더(단추 잠김)가 실제로 한 번 그려지게 한다 — 즉시 응답이면
    //   잠김·풀림이 한 렌더로 합쳐져 초점 가둠 효과의 재실행이 안 드러난다.
    const held = deferred(reply400(JSON.stringify({ error: REASON })));
    mockFetchComplete([held.reply]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    const dialog = await openSkipModal();

    // 실제 브라우저는 누른 단추에 초점을 준다 — fireEvent.click 은 안 주므로 먼저 옮긴다.
    const modalSkip = within(dialog).getByRole('button', { name: '건너뛰기' });
    modalSkip.focus();
    fireEvent.click(modalSkip);
    await waitFor(() => expect(modalSkip).toBeDisabled());
    held.release();

    const reasonLine = await within(dialog).findByText(`서버가 거절한 이유: ${REASON}`);
    expect(reasonLine).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByText(/건너뛰기 실패/)).not.toBeInTheDocument();
    const cancel = within(dialog).getByRole('button', { name: '취소' });
    await waitFor(() => expect(cancel).toBeEnabled());
    await waitFor(() => expect(cancel).toHaveFocus());

    fireEvent.click(cancel);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '건너뛰기' }));
  });

  // TZ-35 — [건너뛰기] 500 은 지금 문구 그대로.
  it('keepsTheOldToastOnSkip500', async () => {
    mockFetchComplete([reply500]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    const dialog = await openSkipModal();
    fireEvent.click(within(dialog).getByRole('button', { name: '건너뛰기' }));
    expect(await screen.findByText('건너뛰기 실패 — 잠시 후 다시 시도해 주세요')).toBeInTheDocument();
    expect(screen.queryByText(/서버가 거절한 이유/)).not.toBeInTheDocument();
  });

  // TZ-36 — 본문 없는 400 → `서버가 거절한 이유: HTTP 400`.
  it('showsHttp400WhenTheBodyHasNoReason', async () => {
    mockFetchComplete([reply400('')]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    await goToStep4();
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    expect(await screen.findByText('서버가 거절한 이유: HTTP 400')).toBeInTheDocument();
  });

  // TZ-37 — 재요청 · [취소] · Esc 세 길에서 사유 줄이 사라진다. 전제: 매번 먼저 보였다.
  it('clearsTheReasonOnRetryCancelAndEscape', async () => {
    let releaseRetry: (() => void) | null = null;
    const pendingThen400: CompleteReply = () =>
      new Promise<Response>((resolve) => {
        releaseRetry = () =>
          resolve(new Response(JSON.stringify({ error: REASON }), { status: 400, headers: { 'Content-Type': 'application/json' } }));
      });
    mockFetchComplete([
      reply400(JSON.stringify({ error: REASON })),
      pendingThen400,
      reply400(JSON.stringify({ error: REASON })),
    ]);
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    const reasonText = `서버가 거절한 이유: ${REASON}`;

    // ① 재요청 중에 사라진다.
    let dialog = await openSkipModal();
    fireEvent.click(within(dialog).getByRole('button', { name: '건너뛰기' }));
    await within(dialog).findByText(reasonText);
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '건너뛰기' })).toBeEnabled());
    fireEvent.click(within(dialog).getByRole('button', { name: '건너뛰기' }));
    await waitFor(() => expect(screen.queryByText(reasonText)).not.toBeInTheDocument());
    expect(releaseRetry).not.toBeNull();
    releaseRetry!();
    await within(dialog).findByText(reasonText);

    // ② [취소]로 닫으면 사라진다(다시 열어도 없음).
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '취소' })).toBeEnabled());
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    dialog = await openSkipModal();
    expect(within(dialog).queryByText(reasonText)).not.toBeInTheDocument();

    // ③ Esc 로 닫아도 사라진다.
    fireEvent.click(within(dialog).getByRole('button', { name: '건너뛰기' }));
    await within(dialog).findByText(reasonText);
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '취소' })).toBeEnabled());
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    dialog = await openSkipModal();
    expect(within(dialog).queryByText(reasonText)).not.toBeInTheDocument();
  });

  // TZ-38 — 65536 이상 포트는 서버와 같은 기준으로 막는다 · [다음] 잠김.
  it('rejectsAPortAboveTheServerMaximum', () => {
    mockFetchOk();
    const { Wrapper } = makeWrapper();
    render(<Wrapper />);
    const input = screen.getByLabelText('Server URL');
    fireEvent.change(input, { target: { value: 'http://host:65536' } });
    fireEvent.blur(input);
    expect(screen.getByRole('alert')).toHaveTextContent('URL 포트 형식 오류 (예: :8765)');
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
    // 상한 그 자체(65535)는 통과.
    fireEvent.change(input, { target: { value: 'http://host:65535' } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
  });
});
