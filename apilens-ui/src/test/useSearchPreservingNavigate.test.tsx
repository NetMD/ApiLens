// [Phase H] SH-06 / R3 회귀 가드 — search params 보존 navigate helper 검증.
import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { useSearchPreservingNavigate } from '../hooks/useSearchPreservingNavigate';

function ProbeLocation(): React.JSX.Element {
  const loc = useLocation();
  return (
    <div>
      <span data-testid="pathname">{loc.pathname}</span>
      <span data-testid="search">{loc.search}</span>
    </div>
  );
}

function GoToSetupNoOverride(): React.JSX.Element {
  const nav = useSearchPreservingNavigate();
  return (
    <button type="button" onClick={() => nav('/setup')}>
      go-setup
    </button>
  );
}

function GoToDashboardWithService(): React.JSX.Element {
  const nav = useSearchPreservingNavigate();
  return (
    <button
      type="button"
      onClick={() => nav('/', { search: { service: 'my-api' } })}
    >
      go-dashboard
    </button>
  );
}

describe('useSearchPreservingNavigate', () => {
  it('opts.search 없을 때 기존 search 그대로 보존', () => {
    render(
      <MemoryRouter initialEntries={['/?range=1h&live=true']}>
        <Routes>
          <Route
            path="/"
            element={
              <>
                <GoToSetupNoOverride />
                <ProbeLocation />
              </>
            }
          />
          <Route path="/setup" element={<ProbeLocation />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByText('go-setup'));
    expect(screen.getByTestId('pathname').textContent).toBe('/setup');
    // R3 회귀 가드 — 기존 search 가 그대로 보존되어야 함.
    const search = screen.getByTestId('search').textContent ?? '';
    expect(search).toContain('range=1h');
    expect(search).toContain('live=true');
  });

  it('opts.search 있을 때 기존 search 위에 덮어쓰기', () => {
    render(
      <MemoryRouter initialEntries={['/services?range=1h&live=true']}>
        <Routes>
          <Route
            path="/services"
            element={
              <>
                <GoToDashboardWithService />
                <ProbeLocation />
              </>
            }
          />
          <Route path="/" element={<ProbeLocation />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByText('go-dashboard'));
    expect(screen.getByTestId('pathname').textContent).toBe('/');
    const search = screen.getByTestId('search').textContent ?? '';
    // 기존 range/live 보존 + service 덮어쓰기
    expect(search).toContain('range=1h');
    expect(search).toContain('live=true');
    expect(search).toContain('service=my-api');
  });

  it('초기 search 가 빈 경우에도 정상 동작', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={
              <>
                <GoToSetupNoOverride />
                <ProbeLocation />
              </>
            }
          />
          <Route path="/setup" element={<ProbeLocation />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByText('go-setup'));
    expect(screen.getByTestId('pathname').textContent).toBe('/setup');
    expect(screen.getByTestId('search').textContent).toBe('');
  });

  it('route 전환 시 setup 의 step 은 떨어지고 다른 필터는 보존', () => {
    // setup step 4 에서 대시보드로 이동 — step 은 새지 않고 range 는 유지되어야 함.
    render(
      <MemoryRouter initialEntries={['/setup?step=4&range=1h']}>
        <Routes>
          <Route
            path="/setup"
            element={
              <>
                <GoToDashboardWithService />
                <ProbeLocation />
              </>
            }
          />
          <Route path="/" element={<ProbeLocation />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByText('go-dashboard'));
    expect(screen.getByTestId('pathname').textContent).toBe('/');
    const search = screen.getByTestId('search').textContent ?? '';
    expect(search).toContain('range=1h');
    expect(search).toContain('service=my-api');
    expect(search).not.toContain('step');
  });
});
