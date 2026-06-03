// route 전환용 search params 헬퍼.
//
// `step` 은 Setup wizard (pages/Setup.tsx) 의 내부 UI 상태로 ?step=1~4 형태로
// /setup URL 에만 박힌다 (새로고침/뒤로가기 일관성 — R3). 문제는 다른 페이지로
// 넘어갈 때 search params 보존 로직(SH-06)이 step 까지 그대로 끌고 다닌다는 점:
// 대시보드 URL 에 ?step=4 가 새고, 이후 setup 재진입 링크가 그 step 을 도로 들고
// 와서 wizard 가 마지막 step 으로 열린다. 운영자는 항상 step 1 부터 시작하길 기대.
//
// 해결: step 같은 route-local 키는 "다른 route 로 갈 때" 항상 제거한다.
// /setup 안에 머무는 동안의 ?step 은 그대로 둔다 (Setup.tsx setStep 이 직접 관리) →
// 새로고침 일관성(R3) 은 보존되고, route 를 넘는 순간에만 step 이 사라진다.

/** /setup 내부 전용 — route 경계를 넘어 전파되면 안 되는 ephemeral 키들. */
const ROUTE_LOCAL_PARAMS = ['step'] as const;

/** route-local 키를 제거한 새 URLSearchParams 복사본 (원본 불변). */
export function withoutRouteLocalParams(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of ROUTE_LOCAL_PARAMS) {
    next.delete(key);
  }
  return next;
}

/**
 * route-local 키를 제거한 search 문자열. 페이지 간 보존해야 하는 필터
 * (service / range / live 등) 는 그대로 둔다. `<Link to={{ search }}>` 및
 * navigate 시 현재 search 를 실어 보낼 때 toString() 대신 사용한다.
 */
export function searchAcrossRoutes(params: URLSearchParams): string {
  return withoutRouteLocalParams(params).toString();
}
