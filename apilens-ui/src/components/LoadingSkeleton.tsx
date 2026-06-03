// 로딩 스켈레톤. 산점도/리스트 영역에 회색 블록.
import type { ReactNode } from 'react';

interface Props {
  /** 표시 영역 종류. 'chart' → 가로로 넓은 박스. 'list' → 세로로 여러 줄. */
  variant?: 'chart' | 'list';
}

export function LoadingSkeleton({ variant = 'chart' }: Props): ReactNode {
  if (variant === 'list') {
    return (
      <div role="status" aria-label="Loading" className="space-y-2">
        {Array.from({ length: 6 }, (_, i) => (
          <div
            key={i}
            className="h-12 animate-pulse rounded border border-stone-200 bg-stone-50"
          />
        ))}
      </div>
    );
  }
  return (
    <div
      role="status"
      aria-label="Loading"
      className="h-full min-h-64 w-full animate-pulse rounded-lg border border-stone-200 bg-stone-50"
    />
  );
}
