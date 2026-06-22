// 에러 상태 — 본문 영역에서 큰 박스로 표시.
// 헤더는 silent (산만함 회피). 도움말은 한국어, 제목은 영어.
//
// [Phase K] (US-05, AC-05-2) — 401 분기 추가: 토큰 미설정/불일치 시 "토큰이 필요해요" + 설정 이동 안내
//   (설계 §2.6d / GT-3). 사용자 명시 비협상 결정 (R14-D02 인증 = API Key 헤더 토큰).
//   CLAUDE.md '아키텍처 핵심 원칙'. 401 은 일반 서버 에러(Retry)와 달리 토큰 입력으로 해소 → navigate.
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { ApiError } from '../api/client';

interface Props {
  /** TanStack Query의 error는 unknown — instanceof로 안전 분기. */
  error: unknown;
  onRetry?: () => void;
}

/** 401 여부 — 토큰 미설정/불일치는 일반 에러와 다른 안내·액션 (설계 §2.6d). */
function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

function describe(error: unknown): { title: string; detail: string } {
  // [Phase K] (US-05, AC-05-2): 401 우선 분기 — T-A13/T-A14 (planner §7.2 확정값).
  if (isUnauthorized(error)) {
    return {
      title: '토큰이 필요해요',
      detail: '이 화면을 보려면 API Key 가 필요해요. 설정에서 토큰을 입력해 주세요.',
    };
  }
  if (error instanceof ApiError) {
    return {
      title: `Server error (HTTP ${error.status})`,
      detail: error.message,
    };
  }
  if (error instanceof Error) {
    return {
      title: 'Network error',
      detail: error.message,
    };
  }
  return { title: 'Unknown error', detail: String(error) };
}

export function ErrorState({ error, onRetry }: Props): ReactNode {
  const navigate = useNavigate();
  const { title, detail } = describe(error);
  const unauthorized = isUnauthorized(error);
  return (
    <div
      role="alert"
      className="flex flex-col items-center justify-center gap-3 rounded-lg border border-stone-200 bg-stone-50 p-8 text-center"
    >
      <p className="text-base font-medium text-stone-900">{title}</p>
      <p className="max-w-md text-sm text-stone-500">{detail}</p>
      {unauthorized ? (
        // [Phase K] (US-05, AC-05-2): 401 — Retry 대신 "설정으로 이동" 버튼 (T-A15). 토큰 입력으로 해소.
        <button
          type="button"
          onClick={() => void navigate('/settings')}
          className="rounded border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
        >
          설정으로 이동
        </button>
      ) : (
        <>
          <p className="max-w-md text-xs text-stone-500">
            서버가 떠있는지 확인하거나, 잠시 후 다시 시도해 보세요.
          </p>
          {onRetry !== undefined && (
            <button
              type="button"
              onClick={onRetry}
              className="rounded border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
            >
              Retry
            </button>
          )}
        </>
      )}
    </div>
  );
}
