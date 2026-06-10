// Phase R12 (FR-B4, AC-B4-1): 로고 + 버전 라벨 + 네비 3링크 공통 컴포넌트 (UX-G-01 채택 — 설계 §3.2.1).
//
// [Phase R12] AC-B4-1/AC-D3-2 — Header.tsx 좌측부 + ActiveServices SimpleNavHeader 중복 해소.
// 사용자 명시 결정 (G-16 Settings 항목 / G-17 버전 라벨 단일 소스). UX §3.1 와이어프레임 인용:
// "ApiLens {APP_VERSION} [Dashboard] [Services] [Settings]" — Settings 는 Services 우측 말단 (T-01).
//
// 불변식:
//   - 모든 Link 는 searchAcrossRoutes(searchParams) 경유 (SH-06 — service/range/live/status/q 보존, 🔴 제약 ⑤)
//   - 활성 표시 = bg-stone-100 text-stone-900 font-medium + aria-current="page" (SH-12 전례)
//   - settingsActive = pathname === '/settings' (planner §11.2-4 / Header.tsx:43-45 전례 동형)
import type { ReactNode } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { searchAcrossRoutes } from '../lib/routeSearch';
import { APP_VERSION } from '../lib/version';

const ACTIVE_MENU_CLASS =
  'px-3 py-1.5 text-sm rounded-md text-stone-900 font-medium bg-stone-100';
const INACTIVE_MENU_CLASS =
  'px-3 py-1.5 text-sm rounded-md text-stone-500 hover:text-stone-900 hover:bg-stone-50';

export function BrandNav(): ReactNode {
  const location = useLocation();
  const [searchParams] = useSearchParams();
  // route 전환 링크는 setup 의 step 을 싣지 않는다 (routeSearch 참고).
  const search = searchAcrossRoutes(searchParams);

  // SH-12 활성 판정 — /traces/:id 는 Dashboard 하위 화면 메타.
  const dashboardActive =
    location.pathname === '/' || location.pathname.startsWith('/traces/');
  const servicesActive = location.pathname === '/services';
  const settingsActive = location.pathname === '/settings';

  return (
    <div className="flex items-center gap-3">
      <span className="text-base font-semibold text-stone-900">ApiLens</span>
      {/* Phase R12 (FR-D3, AC-D3-2): 버전 리터럴 하드코딩 → APP_VERSION 상수 (T-02) */}
      <span className="text-xs text-stone-500">{APP_VERSION}</span>
      <nav className="ml-4 flex items-center gap-1" aria-label="Main navigation">
        <Link
          to={{ pathname: '/', search }}
          className={dashboardActive ? ACTIVE_MENU_CLASS : INACTIVE_MENU_CLASS}
          aria-current={dashboardActive ? 'page' : undefined}
        >
          Dashboard
        </Link>
        <Link
          to={{ pathname: '/services', search }}
          className={servicesActive ? ACTIVE_MENU_CLASS : INACTIVE_MENU_CLASS}
          aria-current={servicesActive ? 'page' : undefined}
        >
          Services
        </Link>
        {/* Phase R12 (FR-B4, AC-B4-1): Settings 항목 신규 (T-01 — Services 우측 말단). /setup 과 별개 화면. */}
        <Link
          to={{ pathname: '/settings', search }}
          className={settingsActive ? ACTIVE_MENU_CLASS : INACTIVE_MENU_CLASS}
          aria-current={settingsActive ? 'page' : undefined}
        >
          Settings
        </Link>
      </nav>
    </div>
  );
}
