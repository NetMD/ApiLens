// Router + QueryClient + LazyDevtools + ToastProvider.
// React Router 7 (D-06): 'react-router' 단일 패키지에서 import.
//
// [Phase H] U5 — 라우터 신규 경로:
//   - /setup → Setup (4단계 wizard)
//   - /services → ActiveServices (테이블 + DELETE)
// FirstRunGuard 가 App root 에 박혀 모든 페이지 가드 (D-01 비협상).
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router';
import { Dashboard } from './pages/Dashboard';
import { TraceDetail } from './pages/TraceDetail';
import { Setup } from './pages/Setup';
import { ActiveServices } from './pages/ActiveServices';
import { Settings } from './pages/Settings';
import { FirstRunGuard } from './components/FirstRunGuard';
import { ToastProvider } from './components/Toast';
import { LazyDevtools } from './components/LazyDevtools';
import { MaintenanceModeBanner } from './components/MaintenanceModeBanner';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 2_000,
    },
  },
});

export function App(): ReactNode {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <FirstRunGuard>
            {/* [Phase R15] AC-B3-1 — 전역 상단 고정 배너(Routes 직전, 모든 화면 상단). 사용자 명시 비협상 결정(D03/D06). CLAUDE.md 'UI 디자인 철학'. */}
            <MaintenanceModeBanner />
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/traces/:traceId" element={<TraceDetail />} />
              <Route path="/setup" element={<Setup />} />
              <Route path="/services" element={<ActiveServices />} />
              {/* Phase R12 (FR-B4, AC-B4-1): /settings 5번째 라우트 — FirstRunGuard 자동 포함
                  (UX 흐름 4 확정: 가드 일관 유지, 별도 예외 없음). /setup 위저드와 별개 화면 — 혼동 금지.
                  BE 페어: WebMvcConfig enumerate '/settings' 추가 필요 (BL-11 — 누락 시 새로고침 404). */}
              <Route path="/settings" element={<Settings />} />
            </Routes>
          </FirstRunGuard>
        </BrowserRouter>
      </ToastProvider>
      <LazyDevtools />
    </QueryClientProvider>
  );
}
