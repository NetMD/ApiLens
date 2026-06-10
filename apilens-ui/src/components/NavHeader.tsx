// Phase R12 (FR-B4, AC-B4-1): 네비 전용 헤더 — header 골격 + BrandNav (설계 §3.2.1).
//
// 사용처: ActiveServices (기존 SimpleNavHeader 대체) + Settings 페이지.
// Dashboard 우측 컨트롤(ServiceSelector/[+]/TimeRange/Live)이 없는 페이지용 — UX §3.1
// "/settings 에서 우측 Dashboard 컨트롤 비노출 — ActiveServices 와 동일한 네비 전용 헤더 형태".
import type { ReactNode } from 'react';
import { BrandNav } from './BrandNav';

export function NavHeader(): ReactNode {
  return (
    <header className="flex h-14 items-center justify-between border-b border-stone-200 bg-white px-6">
      <BrandNav />
    </header>
  );
}
