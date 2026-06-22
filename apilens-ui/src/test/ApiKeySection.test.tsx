// ApiKeySection 단위 테스트 — 토큰 입력/저장 (부트스트랩 역설 회피).
//
// 비협상 AC verbatim 인용 (EXT-003 — Plan AC 본문 그대로):
//   AC-04-1: "ApiKeySection 에서 토큰을 입력·저장하면 sessionStorage 에 보관된다(localStorage 미사용)." (비협상)
//   AC-04-2: "토큰 저장 시 어떤 보호 API 도 호출되지 않는다(sessionStorage 쓰기만 — 부트스트랩 역설 회피)." (비협상)
//   AC-04-4: "토큰이 이미 저장되어 있으면 입력란이 아닌 '설정됨'(마스킹 표시)으로 보인다." (비협상)
//
// 정방향 동사 명시 (EXT-003 lock-in 가드 — 반대 방향 동사 hides*/rejects*/denies*/skips* 0건):
//   storesTokenToSessionStorageOnSave / savesTokenWithoutCallingProtectedApi /
//   showsMaskedSettledStateWhenTokenAlreadyStored / enablesSaveButtonWhenTokenDirtyAndNonEmpty /
//   showsSuccessToastOnSave / showsMismatchErrorWhen401
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ApiKeySection } from '../components/settings/ApiKeySection';
import { ToastProvider } from '../components/Toast';

function renderSection(props: { showMismatchError?: boolean } = {}): void {
  render(
    <ToastProvider>
      <ApiKeySection {...props} />
    </ToastProvider>,
  );
}

describe('ApiKeySection — 토큰 입력/저장 (부트스트랩 역설 회피, AC-04-1/2/4 비협상)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('storesTokenToSessionStorageOnSave — 저장 시 sessionStorage 에 토큰 보관 (AC-04-1)', () => {
    renderSection();
    fireEvent.change(screen.getByLabelText('토큰'), { target: { value: 'my-token' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(sessionStorage.getItem('apilens.apiKey')).toBe('my-token');
  });

  it('savesTokenWithoutCallingProtectedApi — 저장 시 어떤 보호 API 도 호출하지 않는다 (AC-04-2 부트스트랩 역설 회피)', () => {
    // fetch 가 한 번도 호출되지 않아야 함 (sessionStorage 쓰기만).
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    renderSection();
    fireEvent.change(screen.getByLabelText('토큰'), { target: { value: 'no-server-call' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('apilens.apiKey')).toBe('no-server-call');
  });

  it('showsMaskedSettledStateWhenTokenAlreadyStored — 이미 저장되면 입력란 대신 "설정됨" 마스킹 표시 (AC-04-4)', () => {
    sessionStorage.setItem('apilens.apiKey', 'pre-saved');
    renderSection();
    // "설정됨 (••••••••)" 마스킹 표시 + 변경 버튼. 토큰 평문 노출 0.
    expect(screen.getByText('설정됨 (••••••••)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '변경' })).toBeInTheDocument();
    expect(screen.queryByText(/pre-saved/)).toBe(null);
  });

  it('enablesSaveButtonWhenTokenDirtyAndNonEmpty — 비어있지 않고 dirty 일 때만 저장 버튼 활성 (C-A02 논리식)', () => {
    renderSection();
    const saveButton = screen.getByRole('button', { name: '저장' });
    // 빈 입력 → 비활성
    expect(saveButton).toBeDisabled();
    // 공백만 → 비활성 (trim 후 빈값)
    fireEvent.change(screen.getByLabelText('토큰'), { target: { value: '   ' } });
    expect(saveButton).toBeDisabled();
    // 유효 입력 → 활성
    fireEvent.change(screen.getByLabelText('토큰'), { target: { value: 'valid' } });
    expect(saveButton).toBeEnabled();
  });

  it('showsSuccessToastOnSave — 저장 시 성공 토스트 노출 (T-A09)', async () => {
    renderSection();
    fireEvent.change(screen.getByLabelText('토큰'), { target: { value: 'tok' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    expect(await screen.findByText('토큰을 저장했어요.')).toBeInTheDocument();
  });

  it('showsMismatchErrorWhen401 — 401 컨텍스트 시 토큰 불일치 인라인 에러 노출 (AC-05-3, T-A11)', () => {
    renderSection({ showMismatchError: true });
    expect(screen.getByText('토큰이 일치하지 않아요. 다시 확인해 주세요.')).toBeInTheDocument();
  });
});
