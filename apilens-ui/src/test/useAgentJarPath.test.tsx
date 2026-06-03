// [R10] AC-05-8 (D-H10-01 비협상) — useAgentJarPath hook 단위 테스트.
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   acceptsExtractedPath / acceptsNullPathAsFallback
//
// 회귀 가드 (반대 방향 lock-in 차단):
//   rejectsExtractedPath / refetchesOnWindowFocus 같은 반대 방향 동사 0건
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useAgentJarPath } from '../hooks/useAgentJarPath';

function makeWrapper(): { wrapper: ({ children }: { children: ReactNode }) => ReactNode; queryClient: QueryClient } {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  const wrapper = ({ children }: { children: ReactNode }): ReactNode => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { wrapper, queryClient };
}

describe('useAgentJarPath', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('acceptsExtractedPath — server path != null 시 그대로 반환', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ path: '/Users/foo/.apilens/apilens-agent.jar' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgentJarPath(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
    expect(result.current.path).toBe('/Users/foo/.apilens/apilens-agent.jar');
    expect(result.current.isError).toBe(false);
  });

  it('acceptsNullPathAsFallback — server path=null (NFR-02) 도 정상 응답 처리', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ path: null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    const { wrapper } = makeWrapper();
    const { result } = renderHook(() => useAgentJarPath(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
    expect(result.current.path).toBe(null);
    expect(result.current.isError).toBe(false);
  });
});
