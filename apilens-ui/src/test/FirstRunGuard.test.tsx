// [Phase H] FT-7/8/9/10 — FirstRunGuard 4 분기 검증.
//
//   completed=false  → /setup 리다이렉트 (D-01)
//   completed=true   → children 통과
//   isLoading        → 헤더 skeleton 만 (SH-08)
//   isError          → children 통과 (SH-07 — 빈 화면 / 무한 로딩 금지)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { FirstRunGuard } from '../components/FirstRunGuard';

function makeWrapper(initialPath: string): {
  Wrapper: () => React.JSX.Element;
  queryClient: QueryClient;
} {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
    },
  });
  const Wrapper = (): React.JSX.Element => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <FirstRunGuard>
          <Routes>
            <Route path="/" element={<div data-testid="dashboard">DASHBOARD</div>} />
            <Route path="/setup" element={<div data-testid="setup">SETUP</div>} />
            <Route path="/services" element={<div data-testid="services">SERVICES</div>} />
          </Routes>
        </FirstRunGuard>
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { Wrapper, queryClient };
}

describe('FirstRunGuard', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('completed=false 이고 현재 경로가 /setup 이 아니면 /setup 으로 리다이렉트', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({ completed: false, completedAt: null, serverUrl: null }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    const { Wrapper } = makeWrapper('/');
    render(<Wrapper />);
    await waitFor(() => {
      expect(screen.getByTestId('setup')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('dashboard')).not.toBeInTheDocument();
  });

  it('completed=true 면 children 통과', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          completed: true,
          completedAt: 1716386700000,
          serverUrl: 'http://localhost:8765',
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    const { Wrapper } = makeWrapper('/');
    render(<Wrapper />);
    await waitFor(() => {
      expect(screen.getByTestId('dashboard')).toBeInTheDocument();
    });
  });

  it('isLoading 동안 헤더 skeleton 만 그려 flash 회피 (SH-08)', () => {
    // fetch 가 영원히 pending 상태로 머무름.
    vi.spyOn(global, 'fetch').mockReturnValue(new Promise(() => undefined));
    const { Wrapper } = makeWrapper('/');
    const result = render(<Wrapper />);
    // Dashboard / setup 어느 것도 렌더되지 않아야 함.
    expect(screen.queryByTestId('dashboard')).not.toBeInTheDocument();
    expect(screen.queryByTestId('setup')).not.toBeInTheDocument();
    // header skeleton 박스가 그려져 있어야 함 (h-14 클래스).
    const header = result.container.querySelector('header.h-14');
    expect(header).not.toBeNull();
  });

  it('isError 면 children 통과 (SH-07 — 빈 화면/무한 로딩 금지)', async () => {
    vi.spyOn(global, 'fetch').mockRejectedValue(new Error('network error'));
    // SH-07 fallback 분기에서 console.error 호출 — 테스트 노이즈 회피 위해 spyOn.
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { Wrapper } = makeWrapper('/');
    render(<Wrapper />);
    // FirstRunGuard 가 retry: 1 을 명시하므로 첫 실패 + 재시도 후 isError 도달까지 약간의 시간 필요.
    await waitFor(
      () => {
        expect(screen.getByTestId('dashboard')).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it('completed=false 여도 현재 경로가 /setup 이면 리다이렉트 안 함', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({ completed: false, completedAt: null, serverUrl: null }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    const { Wrapper } = makeWrapper('/setup');
    render(<Wrapper />);
    await waitFor(() => {
      expect(screen.getByTestId('setup')).toBeInTheDocument();
    });
  });
});
