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

// [Phase R19] AC-08-3 — 'analyze' 추가 (계측 분석 화면). 사용자 명시 비협상 결정 (D-6 신규 라우트 금지).
// CLAUDE.md '절대 변경하지 말아야 할 결정 사항' 4번(UI 는 npm 빌드 산출물을 static 으로 임베드) —
// 경로를 늘리면 server 쪽 SPA 전달 목록도 함께 늘어나 두 곳이 어긋날 자리가 생긴다.
//   /services?analyze={서비스이름} 은 Services 화면 안의 목록 ↔ 계측 분석 전환 상태다
//   (신규 route 아님 — App.tsx / WebMvcConfig diff 0). ?step 과 정확히 같은 성격이라
//   같은 목록에 넣어 route 를 넘는 순간 자동으로 떨어뜨린다. 이 한 줄이 빠지면 분석
//   파라미터가 대시보드·설정 URL 로 샌다 (SH-06 보존 로직이 그대로 끌고 감).
// [R21/AC-02] 'config' 추가 (원격 계측 설정 화면 — /services?config={서비스이름}, ?analyze= 완전
//   동형의 경로 안 화면 전환). 빠지면 config 파라미터가 대시보드·설정 URL 로 샌다 (위와 동일 성격).
/** route 경계를 넘어 전파되면 안 되는 ephemeral 키들 (/setup 의 step · /services 의 analyze·config). */
const ROUTE_LOCAL_PARAMS = ['step', 'analyze', 'config'] as const;

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
