// [Phase H] U4 — 첫 실행 라우팅 가드.
//
// 사용자 명시 비협상 결정 (D-01 / SH-07 / SH-08):
//   - mount 시 GET /v1/setup/state (queryKey ['setup','state'] / staleTime Infinity / Q-05)
//   - completed === false 이고 현재 경로가 /setup 이 아니면 /setup 으로 Navigate replace
//   - isLoading: 헤더 skeleton 만 그려 flash of authenticated content 회피 (SH-08)
//   - isError: children 통과 — 운영자가 [+] 진입점으로 wizard 재진입 가능 (SH-07)
//   - search params 보존 의무 (SH-06 / NFR-02 / R3 회귀 가드)
import type { ReactNode } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { searchAcrossRoutes } from '../lib/routeSearch';
import { getSetupState } from '../api/setup';

interface Props {
  children: ReactNode;
}

export function FirstRunGuard({ children }: Props): ReactNode {
  const location = useLocation();
  const [searchParams] = useSearchParams();

  const { data, isLoading, isError } = useQuery({
    // SH-13 — queryKey 에 시간 변수 박지 말 것.
    queryKey: ['setup', 'state'],
    queryFn: ({ signal }) => getSetupState(signal),
    // Q-05 — completed=true 도달 후 재호출 0.
    staleTime: Infinity,
    retry: 1,
    refetchOnWindowFocus: false,
  });

  // SH-08 — flash of authenticated content 회피: 헤더 skeleton 만.
  if (isLoading) {
    return <header className="h-14 border-b border-stone-200 bg-white" />;
  }

  // SH-07 — fallback: 빈 화면/무한 로딩 절대 금지. children 통과 + 운영자가 [+]로 wizard 재진입 가능.
  if (isError) {
    // NFR-05 console.log 0 와 별개로 console.error 허용 (개발자 디버깅 채널, architect §6.6 명시).
    // eslint-disable-next-line no-console
    console.error('[FirstRunGuard] /v1/setup/state failed, fallback to children');
    return <>{children}</>;
  }

  // D-01 비협상 — 첫 실행 시에만 자동, 그 후엔 명시적 진입 (재출현 X).
  // SH-06 / NFR-02 / R3 회귀 차단 — search params 보존.
  if (data?.completed === false && location.pathname !== '/setup') {
    return (
      <Navigate
        to={{ pathname: '/setup', search: searchAcrossRoutes(searchParams) }}
        replace
      />
    );
  }

  return <>{children}</>;
}
