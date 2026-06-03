// TanStack Query devtools — dev 전용 lazy import + Suspense (D-03).
//
// production 번들에는 포함되지 않음 (import.meta.env.DEV로 분기 + lazy).
// require() 변형 절대 사용 금지 — Vite ESM 환경에서 빌드 실패.
import { lazy, Suspense } from 'react';
import type { ReactNode } from 'react';

const ReactQueryDevtools = lazy(() =>
  import('@tanstack/react-query-devtools').then((m) => ({
    default: m.ReactQueryDevtools,
  })),
);

export function LazyDevtools(): ReactNode {
  if (!import.meta.env.DEV) return null;
  return (
    <Suspense fallback={null}>
      <ReactQueryDevtools initialIsOpen={false} />
    </Suspense>
  );
}
