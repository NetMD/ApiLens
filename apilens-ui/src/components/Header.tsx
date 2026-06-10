// 대시보드 헤더 — 로고 + 좌측 메뉴 + ServiceSelector + [+] 버튼 + TimeRangeSelector + LiveToggle.
//
// [Phase H] W-02 / W-03 — 사용자 명시 비협상 결정:
//   - W-03: 좌측 가로 메뉴 "Dashboard | Services" (사이드바 / 상단 가로 메뉴 금지)
//   - W-02: ServiceSelector 우측 별도 [+] 버튼 (dropdown 안에 메뉴 항목으로 끼우지 말 것)
//   - SH-06 / NFR-02 / R3 회귀 가드 — 모든 Link 에서 search params 보존
//   - SH-12 활성 표시 = bg-stone-100 text-stone-900 font-medium (밑줄 톤 회피)
//   - SH-17 [+] 버튼 aria-label="Add service" + tooltip "Service 를 추가해요"
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { searchAcrossRoutes } from '../lib/routeSearch';
import type { RangePreset } from '../lib/time';
import { BrandNav } from './BrandNav';
import { ServiceSelector } from './ServiceSelector';
import { TimeRangeSelector } from './TimeRangeSelector';
import { LiveToggle } from './LiveToggle';

interface Props {
  service: string | null;
  range: RangePreset;
  live: boolean;
  onServiceChange: (next: string | null) => void;
  onRangeChange: (next: RangePreset) => void;
  onLiveChange: (next: boolean) => void;
  /** Dashboard 페이지 외부에서 헤더만 재사용할 때 우측 컨트롤 숨김 (Services 페이지 등 향후 확장 대비). */
  hideDashboardControls?: boolean;
}

export function Header({
  service,
  range,
  live,
  onServiceChange,
  onRangeChange,
  onLiveChange,
  hideDashboardControls = false,
}: Props): ReactNode {
  const [searchParams] = useSearchParams();
  // route 전환 링크는 setup 의 step 을 싣지 않는다 (routeSearch 참고).
  const search = searchAcrossRoutes(searchParams);

  return (
    <header className="flex h-14 items-center justify-between border-b border-stone-200 bg-white px-6">
      {/* Phase R12 (FR-B4, AC-B4-1): 좌측부 (로고+버전+네비) → BrandNav 공통 승격 (UX-G-01 / 설계 §3.2.1).
          W-03 가로 메뉴 / SH-06 search 보존 / SH-12 활성 표시는 BrandNav 내부로 이동 — 의미 변경 0. */}
      <div className="flex items-center gap-3">
        <BrandNav />
        <div aria-hidden className="mx-2 h-6 border-r border-stone-200" />
      </div>
      {!hideDashboardControls && (
        <div className="flex items-center gap-3">
          <ServiceSelector value={service} onChange={onServiceChange} />
          {/* W-02: 별도 [+] 버튼 (ServiceSelector dropdown 안 진입 금지) / SH-17 a11y + tooltip / SH-06 search 보존 */}
          <Link
            to={{ pathname: '/setup', search }}
            aria-label="Add service"
            title="Service 를 추가해요"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md border border-stone-200 bg-white text-base font-medium text-stone-500 hover:bg-stone-50 hover:text-stone-900"
          >
            +
          </Link>
          <TimeRangeSelector value={range} onChange={onRangeChange} />
          <LiveToggle value={live} onChange={onLiveChange} />
        </div>
      )}
    </header>
  );
}
