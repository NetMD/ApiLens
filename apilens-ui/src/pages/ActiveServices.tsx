// [Phase H] U2 — Active services 페이지 (/services).
//
// 사용자 명시 비협상 결정:
//   D-03: healthStatus 시간 기반 (5분/30분) — server-side single source
//   D-05: services row 만 제거, trace 데이터 보존 (cascade 금지)
//   W-01: lastSeenAt 단일화 (lastSeen 제거)
//   SH-03: healthStatus emoji 직접 사용 금지 — CSS dot 사용
//   SH-06: row 클릭 → search 보존 + service 만 덮어쓰기 (R3 회귀 차단)
//   SH-10: row link `to.search` 계산 — 기존 search 보존
//   SH-13: queryKey 에 시간 변수 박지 말 것
//   SH-18: 색 + 한글 라벨 + aria-label 3중 (색만으로 표현 금지)
//   SH-19: [삭제] 버튼 event.stopPropagation() — row link 흡수 차단
import { useRef, useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { searchAcrossRoutes } from '../lib/routeSearch';
import { deleteService, listServicesDetailed } from '../api/services';
import type { HealthStatus, ServiceInfo } from '../types/api';
import { STATUS_HEX_HEALTH, STATUS_LABEL_KO } from '../lib/colors';
import { useSearchPreservingNavigate } from '../hooks/useSearchPreservingNavigate';
import { ErrorState } from '../components/ErrorState';
import { LoadingSkeleton } from '../components/LoadingSkeleton';
import { Modal } from '../components/Modal';
import { useToast } from '../components/useToast';
import { formatHms } from '../lib/time';

export function ActiveServices(): ReactNode {
  const [searchParams] = useSearchParams();
  const search = searchAcrossRoutes(searchParams);
  const nav = useSearchPreservingNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  // [R10] AC-06-6 / AC-06-7 / AC-06-8 (D-H10-04 비협상 — auto-refetch 강화).
  // SH-13 정합 — queryKey 정적 ['services', 'detailed'] 보존 (시간 변수 박지 말 것).
  // staleTime: 0 + refetchOnWindowFocus: true → 탭 복귀 시 즉시 갱신 (V-USER-R10-04 sign-off).
  // refetchIntervalInBackground 미명시 → default false 유지 (background 트래픽 절약, D-H10-04 verbatim).
  // 회귀 가드 grep (반대): background polling 명시 옵션 0 hit / queryKey:.*Date\.now 0 hit
  const servicesQuery = useQuery({
    queryKey: ['services', 'detailed'],
    queryFn: ({ signal }) => listServicesDetailed(signal),
    staleTime: 0,                       // [R10] AC-06-7 — focus refetch 무조건 동작 (30_000 → 0)
    refetchInterval: 30_000,            // [R10] 유지 — 활성 탭 30초 polling
    refetchOnWindowFocus: true,         // [R10] AC-06-6 — 탭 복귀 시 즉시 갱신 (false → true)
    retry: 1,
  });

  // 삭제 confirm 모달 상태.
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const deleteCancelRef = useRef<HTMLButtonElement | null>(null);

  // D-05 비협상: services row 만 제거, trace 데이터 보존.
  const deleteMutation = useMutation({
    mutationFn: async (name: string) => deleteService(name),
    onSuccess: async (_data, name) => {
      // 30초 refetch 즉시 트리거.
      await queryClient.invalidateQueries({ queryKey: ['services'] });
      await queryClient.invalidateQueries({ queryKey: ['services', 'detailed'] });
      toast.success(`${name} 을 삭제했어요`);
      setDeleteTarget(null);
    },
    onError: () => {
      toast.error('삭제 실패 — 잠시 후 다시 시도해 주세요');
    },
  });

  const services = servicesQuery.data?.services ?? [];

  const renderBody = (): ReactNode => {
    if (servicesQuery.isLoading) {
      return <LoadingSkeleton variant="list" />;
    }
    if (servicesQuery.isError) {
      return (
        <ErrorState
          error={servicesQuery.error}
          onRetry={() => void servicesQuery.refetch()}
        />
      );
    }
    if (services.length === 0) {
      return (
        <div
          role="status"
          className="flex h-full min-h-40 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center"
        >
          <p className="text-base font-medium text-stone-900">
            아직 등록된 service 가 없어요
          </p>
          <p className="text-sm text-stone-500">
            우상단 [+ Add service] 로 wizard 를 시작해 주세요
          </p>
        </div>
      );
    }
    return (
      <div className="overflow-hidden rounded-lg border border-stone-200">
        <table className="w-full text-left">
          <thead className="bg-stone-50">
            <tr className="text-xs font-medium text-stone-500">
              <th className="px-4 py-2">상태</th>
              <th className="px-4 py-2">Service</th>
              <th className="px-4 py-2">마지막 trace</th>
              <th className="px-4 py-2 text-right">Trace 수</th>
              <th className="px-4 py-2 text-right">작업</th>
            </tr>
          </thead>
          <tbody>
            {services.map((svc) => (
              <ServiceRow
                key={svc.name}
                svc={svc}
                searchString={search}
                deleting={deleteMutation.isPending && deleteTarget === svc.name}
                onDeleteClick={(name) => setDeleteTarget(name)}
                onRowClick={(name) => nav('/', { search: { service: name } })}
              />
            ))}
          </tbody>
        </table>
      </div>
    );
  };

  return (
    <div className="flex h-full flex-col bg-stone-50">
      {/* 헤더 — Dashboard 컨트롤 없이 좌측 메뉴만 노출 (Services 페이지 단독). */}
      <SimpleNavHeader currentPath="/services" />

      <main className="flex-1 overflow-auto px-6 py-6">
        <div className="mx-auto max-w-5xl space-y-4">
          <div className="flex items-center justify-between">
            <h1 className="text-lg font-medium text-stone-900">Services</h1>
            {/* [+ Add service] 버튼 — search 보존 (SH-06) */}
            <Link
              to={{ pathname: '/setup', search }}
              className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
            >
              + Add service
            </Link>
          </div>
          {renderBody()}
        </div>
      </main>

      {/* 삭제 confirm 모달 */}
      <Modal
        open={deleteTarget !== null}
        onClose={() => {
          if (!deleteMutation.isPending) setDeleteTarget(null);
        }}
        title="Service 삭제"
        initialFocusRef={deleteCancelRef}
      >
        {deleteTarget !== null && (
          <p>
            <code className="font-mono text-stone-900">{deleteTarget}</code> 을 삭제하시겠어요?
            trace 데이터는 보존됩니다
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button
            ref={deleteCancelRef}
            type="button"
            onClick={() => setDeleteTarget(null)}
            disabled={deleteMutation.isPending}
            className="rounded-md border border-stone-200 bg-white px-4 py-2 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={() => {
              if (deleteTarget !== null) deleteMutation.mutate(deleteTarget);
            }}
            disabled={deleteMutation.isPending}
            className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
          >
            삭제
          </button>
        </div>
      </Modal>
    </div>
  );
}

// ── Header (좌측 메뉴만 노출) ───────────────────────────────────────
function SimpleNavHeader({ currentPath }: { currentPath: string }): ReactNode {
  const [searchParams] = useSearchParams();
  const search = searchAcrossRoutes(searchParams);
  const dashboardActive = currentPath === '/' || currentPath.startsWith('/traces/');
  const servicesActive = currentPath === '/services';
  const activeMenuClass =
    'px-3 py-1.5 text-sm rounded-md text-stone-900 font-medium bg-stone-100';
  const inactiveMenuClass =
    'px-3 py-1.5 text-sm rounded-md text-stone-500 hover:text-stone-900 hover:bg-stone-50';
  return (
    <header className="flex h-14 items-center justify-between border-b border-stone-200 bg-white px-6">
      <div className="flex items-center gap-3">
        <span className="text-base font-semibold text-stone-900">ApiLens</span>
        <span className="text-xs text-stone-500">v0.1</span>
        <nav className="ml-4 flex items-center gap-1" aria-label="Main navigation">
          <Link
            to={{ pathname: '/', search }}
            className={dashboardActive ? activeMenuClass : inactiveMenuClass}
            aria-current={dashboardActive ? 'page' : undefined}
          >
            Dashboard
          </Link>
          <Link
            to={{ pathname: '/services', search }}
            className={servicesActive ? activeMenuClass : inactiveMenuClass}
            aria-current={servicesActive ? 'page' : undefined}
          >
            Services
          </Link>
        </nav>
      </div>
    </header>
  );
}

// ── ServiceRow ────────────────────────────────────────────────────
interface RowProps {
  svc: ServiceInfo;
  searchString: string;
  deleting: boolean;
  onDeleteClick: (name: string) => void;
  onRowClick: (name: string) => void;
}
function ServiceRow({
  svc,
  deleting,
  onDeleteClick,
  onRowClick,
}: RowProps): ReactNode {
  const handleRowClick = (): void => onRowClick(svc.name);
  const handleRowKey = (e: React.KeyboardEvent<HTMLTableRowElement>): void => {
    if (e.key === 'Enter') {
      onRowClick(svc.name);
    }
  };
  const handleDelete = (e: MouseEvent<HTMLButtonElement>): void => {
    // SH-19 — event.stopPropagation() 의무 (row link 흡수 차단).
    e.stopPropagation();
    onDeleteClick(svc.name);
  };
  return (
    <tr
      role="link"
      tabIndex={0}
      onClick={handleRowClick}
      onKeyDown={handleRowKey}
      className="cursor-pointer border-t border-stone-200 text-sm text-stone-900 hover:bg-stone-50"
    >
      <td className="px-4 py-3">
        <HealthStatusBadge status={svc.healthStatus} />
      </td>
      <td className="px-4 py-3 font-mono">{svc.name}</td>
      <td className="px-4 py-3 text-stone-500">
        {svc.lastSeenAt === null ? (
          <span className="text-stone-300">—</span>
        ) : (
          <span title={new Date(svc.lastSeenAt).toLocaleString()}>
            {formatHms(svc.lastSeenAt)}
          </span>
        )}
      </td>
      <td className="px-4 py-3 text-right font-mono">
        {svc.traceCount.toLocaleString()}
      </td>
      <td className="px-4 py-3 text-right">
        <button
          type="button"
          onClick={handleDelete}
          disabled={deleting}
          aria-label={`${svc.name} 삭제`}
          className="rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-[var(--color-status-error)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          삭제
        </button>
      </td>
    </tr>
  );
}

// ── HealthStatus 색 + 한글 라벨 + aria-label 3중 (SH-18) ─────────
function HealthStatusBadge({ status }: { status: HealthStatus }): ReactNode {
  const label = STATUS_LABEL_KO[status];
  const hex = STATUS_HEX_HEALTH[status];
  return (
    <span
      className="inline-flex items-center gap-1.5"
      aria-label={label}
    >
      <span
        aria-hidden
        className="inline-block h-2 w-2 rounded-full"
        style={{ background: hex }}
      />
      <span className="text-sm text-stone-900">{label}</span>
    </span>
  );
}
