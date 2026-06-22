// ErrorState 단위 테스트 — 401 분기 (토큰 입력 유도).
//
// 비협상 AC verbatim 인용 (EXT-003 — Plan AC 본문 그대로):
//   AC-05-2: "401 수신 시 토큰 입력 프롬프트(또는 설정 이동 안내)로 전환된다." (US-05)
//
// 정방향 동사 명시 (EXT-003 lock-in 가드 — 반대 방향 동사 hides*/rejects*/omits* 0건):
//   showsTokenRequiredTitleOn401 / showsGoToSettingsButtonOn401 /
//   navigatesToSettingsOnButtonClick / showsServerErrorTitleOnNon401
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ErrorState } from '../components/ErrorState';
import { ApiError } from '../api/client';

const mockNavigate = vi.fn();
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderErrorState(error: unknown): void {
  render(
    <MemoryRouter>
      <ErrorState error={error} onRetry={() => undefined} />
    </MemoryRouter>,
  );
}

describe('ErrorState — 401 분기 (토큰 입력 유도, AC-05-2)', () => {
  it('showsTokenRequiredTitleOn401 — 401 시 "토큰이 필요해요" 안내 노출 (T-A13/T-A14)', () => {
    renderErrorState(new ApiError(401, 'unauthorized'));
    expect(screen.getByText('토큰이 필요해요')).toBeInTheDocument();
    expect(
      screen.getByText('이 화면을 보려면 API Key 가 필요해요. 설정에서 토큰을 입력해 주세요.'),
    ).toBeInTheDocument();
  });

  it('showsGoToSettingsButtonOn401 — 401 시 "설정으로 이동" 버튼 노출 (T-A15, Retry 대신)', () => {
    renderErrorState(new ApiError(401, 'unauthorized'));
    expect(screen.getByRole('button', { name: '설정으로 이동' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).toBe(null);
  });

  it('navigatesToSettingsOnButtonClick — "설정으로 이동" 클릭 시 /settings 로 이동', () => {
    mockNavigate.mockClear();
    renderErrorState(new ApiError(401, 'unauthorized'));
    fireEvent.click(screen.getByRole('button', { name: '설정으로 이동' }));
    expect(mockNavigate).toHaveBeenCalledWith('/settings');
  });

  it('showsServerErrorTitleOnNon401 — 401 이 아닌 서버 에러는 기존 "Server error" 분기 유지 (회귀 0)', () => {
    renderErrorState(new ApiError(500, 'boom'));
    expect(screen.getByText('Server error (HTTP 500)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
