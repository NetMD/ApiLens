// [Phase H] SH-06 / NFR-02 / R3 회귀 가드 — search params 보존 navigate helper.
//
// 사용 위치 4 파일 7 위치 (architect §6.4 인계 표):
//   1. Setup.tsx 완료 분기 (POST 성공)
//   2. Setup.tsx skip confirm 성공 분기
//   3. ActiveServices.tsx row 클릭 → Dashboard
//   4. ActiveServices.tsx [+ Add service] 버튼
//   5. Header.tsx 좌측 메뉴 Dashboard (Link 패턴으로 사용 가능)
//   6. Header.tsx 좌측 메뉴 Services
//   7. Header.tsx ServiceSelector 우측 [+]
//   (가드) FirstRunGuard.tsx <Navigate>
//
// 모든 페이지 전환에서 search params 누락 회귀를 자동 차단한다.
// declarative <Link> 패턴도 동일 효과 — search 보존 의무는 둘 다 동일.
import { useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { withoutRouteLocalParams } from '../lib/routeSearch';

export interface PreserveNavigateOptions {
  /** 기존 search 위에 덮어쓸 키-값. undefined 면 기존 search 그대로 보존. */
  search?: Record<string, string>;
  replace?: boolean;
}

/**
 * search params 보존 navigate helper.
 *
 * 예:
 *   const nav = useSearchPreservingNavigate();
 *   nav('/setup');                                  // 기존 search 그대로 보존
 *   nav('/', { search: { service: 'foo' } });       // 기존 search 위에 service 만 덮어쓰기
 *   nav('/services', { replace: true });             // 히스토리 replace
 */
export function useSearchPreservingNavigate(): (
  pathname: string,
  opts?: PreserveNavigateOptions,
) => void {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  return useCallback(
    (pathname: string, opts?: PreserveNavigateOptions) => {
      // route 를 넘는 순간 setup 의 step 같은 route-local 키는 떨군다 (대시보드/트레이스
      // URL 로 ?step=4 가 새고 setup 재진입 시 마지막 step 으로 열리는 회귀 차단).
      const next = withoutRouteLocalParams(searchParams);
      if (opts?.search) {
        for (const [k, v] of Object.entries(opts.search)) {
          next.set(k, v);
        }
      }
      const navigateOpts: { replace?: boolean } = {};
      if (opts?.replace === true) {
        navigateOpts.replace = true;
      }
      navigate({ pathname, search: next.toString() }, navigateOpts);
    },
    [navigate, searchParams],
  );
}
