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
import { FirstRunGuard } from './components/FirstRunGuard';
import { ToastProvider } from './components/Toast';
import { LazyDevtools } from './components/LazyDevtools';

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
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/traces/:traceId" element={<TraceDetail />} />
              <Route path="/setup" element={<Setup />} />
              <Route path="/services" element={<ActiveServices />} />
            </Routes>
          </FirstRunGuard>
        </BrowserRouter>
      </ToastProvider>
      <LazyDevtools />
    </QueryClientProvider>
  );
}
