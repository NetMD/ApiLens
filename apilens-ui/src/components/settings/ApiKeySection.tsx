// [Phase K] (US-04, AC-04-1/AC-04-2/AC-04-4) — API Key 토큰 입력/저장 섹션.
//
// AC-04-1 verbatim: "ApiKeySection 에서 토큰을 입력·저장하면 sessionStorage 에 보관된다(localStorage 미사용)." (비협상)
// AC-04-2 verbatim: "토큰 저장 시 어떤 보호 API 도 호출되지 않는다(sessionStorage 쓰기만 — 부트스트랩 역설 회피)." (비협상)
// AC-04-4 verbatim: "토큰이 이미 저장되어 있으면 입력란이 아닌 '설정됨'(마스킹 표시)으로 보인다." (비협상)
// 사용자 명시 비협상 결정 (R14-D03 토큰 SSOT = server 起動 옵션, DB 저장 안 함). CLAUDE.md '절대 변경하지 말아야 할 결정 사항'.
//
// 패턴 출처 (재발명 0): RetentionSection.tsx 의 입력/저장 분리 골격 + blur 에러 + toast + stone 팔레트.
// ⚠️ 결정적 차이 (설계 §2.6c / planner §9 신규 패턴): RetentionSection 은 useMutation + PUT /v1/settings 로
//    서버를 호출하지만, ApiKeySection 의 저장은 setApiKey() 동기 호출만 — 보호 API 호출 0.
//    RetentionSection 의 saveSettings.mutate(PUT /v1/settings) 복사 금지 (부트스트랩 역설 회피, BL-12, AC-04-2).
//
// 문구 = planner §7.1 확정값 T-A01~T-A12 (재발명 0). 컨트롤 논리식 = planner §8.1 C-A01/C-A02 (재발명 0).
import { useState } from 'react';
import type { ReactNode } from 'react';
import { getApiKey, setApiKey, clearApiKey } from '../../api/auth';
import { useToast } from '../useToast';

interface Props {
  /**
   * 401 수신 시 "토큰 불일치" 인라인 에러(T-A11) 노출 트리거 (설계 §2.6c, AC-05-3).
   * 미지정(기본 false) = 정상 — Settings 페이지 단독 렌더 시 401 컨텍스트 없음.
   */
  showMismatchError?: boolean;
}

export function ApiKeySection({ showMismatchError = false }: Props): ReactNode {
  const toast = useToast();

  // 저장된 토큰 — 초기 1회 sessionStorage 읽기 (서버 호출 0). 저장/변경 시 갱신.
  const [savedToken, setSavedToken] = useState<string | null>(() => getApiKey());
  // 입력 raw 문자열. 이미 저장돼 있으면 입력란이 아닌 "설정됨" 표시 (AC-04-4) → editing 으로 토글.
  const [tokenInput, setTokenInput] = useState('');
  // "설정됨" 마스킹 표시 vs 입력 모드 전환 (저장 토큰 없으면 처음부터 입력 모드).
  const [editing, setEditing] = useState(() => getApiKey() === null);
  // T-A10 은 blur 이후에만 표시 (RetentionSection blurred 전례 — 첫 입력 중 에러 억제).
  const [blurred, setBlurred] = useState(false);

  // planner §8.1 파생 상태 — 조건식 그대로 (코드 앵커, isPending 항 제거 = 서버 호출 0).
  const trimmedToken = tokenInput.trim();
  const canSaveToken =
    trimmedToken.length > 0 && // C-A02: 빈 토큰 저장 금지
    trimmedToken !== savedToken; // dirty 일 때만 (이미 같은 값이면 비활성)

  // C-A01: 토큰 입력 필드 disabled — 로컬 sessionStorage 라 로딩 쿼리 없음. 항상 활성 (저장 중 개념 부재).
  const inputDisabled = false;

  // T-A10 노출: blur 후 빈 입력일 때만 (저장 동작은 즉시 동기라 서버 거부 분기 불요).
  const showEmptyError = blurred && trimmedToken.length === 0;

  // [Phase K] (US-04, AC-04-2): ⚠️ 부트스트랩 역설 회피 — 저장은 sessionStorage 쓰기만, 보호 API 호출 0
  //   (BL-12, AC-04-2). RetentionSection 의 saveSettings.mutate(PUT /v1/settings) 복사 금지.
  const handleSave = (): void => {
    if (!canSaveToken) return;
    setApiKey(trimmedToken); // 동기 sessionStorage 쓰기만 — 서버 호출 0
    setSavedToken(trimmedToken);
    setTokenInput('');
    setBlurred(false);
    setEditing(false);
    toast.success('토큰을 저장했어요.'); // T-A09
  };

  // "변경" — 저장 토큰 비우고 입력 모드 진입 (T-A07). clearApiKey 로 sessionStorage 제거.
  const handleChange = (): void => {
    clearApiKey();
    setSavedToken(null);
    setTokenInput('');
    setBlurred(false);
    setEditing(true);
  };

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      {/* T-A01 */}
      <h2 className="text-base font-medium text-stone-900">API Key</h2>

      <div className="mt-4 space-y-4">
        {!editing && savedToken !== null ? (
          // AC-04-4 — 이미 저장됨: 입력란 대신 "설정됨 (••••••••)" 마스킹 표시 + 변경 버튼.
          <div className="flex items-center justify-between gap-4">
            {/* T-A06 — 토큰 평문 노출 금지 (마스킹) */}
            <span className="text-sm text-stone-900">설정됨 (••••••••)</span>
            {/* T-A07 */}
            <button
              type="button"
              onClick={handleChange}
              className="shrink-0 rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
            >
              변경
            </button>
          </div>
        ) : (
          <div>
            {/* T-A02 — label htmlFor 연결 (a11y 의무) */}
            <label htmlFor="api-key-input" className="block text-sm font-medium text-stone-900">
              토큰
            </label>
            <div className="mt-1.5 flex items-center gap-2">
              <input
                id="api-key-input"
                type="password"
                autoComplete="off"
                // T-A03
                placeholder="발급받은 API Key 를 붙여넣어 주세요"
                value={tokenInput}
                onChange={(e) => setTokenInput(e.target.value)}
                onBlur={() => setBlurred(true)}
                disabled={inputDisabled}
                aria-invalid={showEmptyError ? true : undefined}
                aria-describedby={showEmptyError ? 'api-key-error' : undefined}
                className="w-72 rounded-md border border-stone-200 px-3 py-1.5 text-sm text-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900 disabled:opacity-50"
              />
              {/* T-A04 — C-A02: disabled = !canSaveToken */}
              <button
                type="button"
                onClick={handleSave}
                disabled={!canSaveToken}
                className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
              >
                저장
              </button>
            </div>
            {showEmptyError && (
              // T-A10
              <p id="api-key-error" className="mt-1.5 text-xs text-[var(--color-status-error)]">
                토큰을 입력해 주세요.
              </p>
            )}
          </div>
        )}

        {showMismatchError && (
          // T-A11 — 401 수신 시 토큰 불일치 인라인 에러 (AC-05-3 — UI 먹통 아닌 인라인 안내).
          <p role="alert" className="text-xs text-[var(--color-status-error)]">
            토큰이 일치하지 않아요. 다시 확인해 주세요.
          </p>
        )}

        {/* T-A08 — 안내 문구 (섹션 하단) */}
        <p className="text-xs text-stone-500">
          이 토큰은 이 브라우저 탭에만 저장돼요(새로고침까지 유지, 탭을 닫으면 사라져요). 관리·조회 API
          호출에 자동으로 첨부돼요.
        </p>

        {/* T-A12 — 보안 잔여 위험 안내(작게, NFR-07 HTTP 평문 전송) */}
        <p className="text-xs text-stone-400">
          평문(HTTP) 전송이라 운영망/방화벽 격리 환경에서만 사용하세요. 공용망 노출은 금지예요.
        </p>
      </div>
    </section>
  );
}
