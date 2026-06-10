// Phase R12 (FR-B4, AC-B4-4) — FT-12: Header/BrandNav 네비 단위 테스트 (#29 — Header.tsx 동일 touch).
//
// AC-B4-4 verbatim: "Header.test.tsx FT-12 작성 (#29 — Header.tsx 동일 touch)."
// AC-B4-1 verbatim: "신규 라우트 /settings (App.tsx 4→5개, G-15) + Header 네비 Settings 항목 (G-16)
// + WebMvcConfig enumerate 에 /settings 추가 (G-14 — 누락 시 새로고침 Whitelabel 404).
// ⚠ /setup(위저드)과 별개 화면 — 혼동 금지."
//
// 검증 의무 (설계 §7.3 FT-12 — 정방향 동사 명시, EXT-003 lock-in 회귀 가드):
//   ① showsThreeNavItemsWithSettingsLink — 네비 3항목 + Settings 링크 /settings
//   ② marksSettingsLinkActiveOnSettingsPath — /settings 활성 시 aria-current
//   ③ showsAddServiceEntryPoint — W-02 [+] 진입점 (aria-label="Add service" + /setup 링크) 회귀 가드
//   ④ preservesSearchParamsAcrossNavLinks — Link search 보존 (searchAcrossRoutes 경유 — ?service= 유지)
//   ⑤ showsAppVersionLabelFromSingleConstant — APP_VERSION 표면 (v0.x 리터럴 금지 — 상수 import 대조)
//
// 회귀 가드 (반대 방향 lock-in 차단): dropsSearchParams / removesAddServiceButton 같은 반대 방향 동사 0건
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Header } from '../components/Header';
import { APP_VERSION } from '../lib/version';

function renderHeader(initialPath: string): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Header
          service={null}
          range="10m"
          live={false}
          onServiceChange={() => undefined}
          onRangeChange={() => undefined}
          onLiveChange={() => undefined}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Header — FT-12 (R12 AC-B4-4)', () => {
  beforeEach(() => {
    // ServiceSelector 의 services 쿼리 — 빈 목록 응답 mock.
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ services: [] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('showsThreeNavItemsWithSettingsLink — 네비 3항목 (Dashboard/Services/Settings) + Settings → /settings', () => {
    renderHeader('/');
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Services' })).toHaveAttribute('href', '/services');
    // T-01 — Settings 는 Services 우측 말단. /setup 과 별개 화면 (AC-B4-1 혼동 금지).
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings');
  });

  it('marksSettingsLinkActiveOnSettingsPath — /settings 에서 Settings 항목 aria-current="page"', () => {
    renderHeader('/settings');
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveAttribute('aria-current', 'page');
    // 다른 항목은 비활성 (SH-12 활성 판정 단일 — pathname === '/settings')
    expect(screen.getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: 'Services' })).not.toHaveAttribute('aria-current');
  });

  it('showsAddServiceEntryPoint — W-02 [+] 버튼 (aria-label="Add service") → /setup 진입점 유지 (#29 회귀 가드)', () => {
    renderHeader('/');
    const addLink = screen.getByRole('link', { name: 'Add service' });
    expect(addLink.getAttribute('href')).toMatch(/^\/setup/);
  });

  it('preservesSearchParamsAcrossNavLinks — 네비 Link 가 service/status/q search 보존 (SH-06 — searchAcrossRoutes 경유)', () => {
    renderHeader('/?service=my-api&status=ERROR&q=OrderApi');
    const settingsHref = screen.getByRole('link', { name: 'Settings' }).getAttribute('href') ?? '';
    expect(settingsHref).toContain('service=my-api');
    expect(settingsHref).toContain('status=ERROR'); // [R12] status/q 도 search-persist (BL-10)
    expect(settingsHref).toContain('q=OrderApi');
    const servicesHref = screen.getByRole('link', { name: 'Services' }).getAttribute('href') ?? '';
    expect(servicesHref).toContain('service=my-api');
  });

  it('showsAppVersionLabelFromSingleConstant — 버전 라벨 = APP_VERSION 상수 (DG-01 단일 거주지)', () => {
    renderHeader('/');
    // v0.x 리터럴을 본 테스트에 직접 쓰지 않는다 — D3 grep (apilens-ui *.tsx v0.x 0 hit) 대상 정합.
    expect(screen.getByText(APP_VERSION)).toBeInTheDocument();
  });
});
