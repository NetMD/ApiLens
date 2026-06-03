// 에러 상태 — 본문 영역에서 큰 박스로 표시.
// 헤더는 silent (산만함 회피). 도움말은 한국어, 제목은 영어.
import type { ReactNode } from 'react';
import { ApiError } from '../api/client';

interface Props {
  /** TanStack Query의 error는 unknown — instanceof로 안전 분기. */
  error: unknown;
  onRetry?: () => void;
}

function describe(error: unknown): { title: string; detail: string } {
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
  const { title, detail } = describe(error);
  return (
    <div
      role="alert"
      className="flex flex-col items-center justify-center gap-3 rounded-lg border border-stone-200 bg-stone-50 p-8 text-center"
    >
      <p className="text-base font-medium text-stone-900">{title}</p>
      <p className="max-w-md text-sm text-stone-500">{detail}</p>
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
    </div>
  );
}
