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
//
// [Phase R19] AC-01-4 / AC-08-3 — agent 버전 컬럼 + [계측 분석] 액션 + 같은 경로 안 화면 전환.
//   · agent 버전 컬럼은 좁은 화면에서도 **숨기지 않는다**. 값 없음(—) 자체가 뜻을 갖는 컬럼이라
//     (= 아직 agent 를 다시 시작하지 않았다) 숨기면 "값이 없는 것" 과 "숨겨진 것" 을 구분할 수 없다.
//     그래서 컬럼 은닉 대신 표 컨테이너의 넘침 처리를 가로 스크롤로 바꿨다.
//   · 분석 화면은 신규 라우트가 아니라 `?analyze={서비스이름}` 검색 파라미터로 같은 경로 안에서
//     바꾼다 (App.tsx / BrandNav.tsx / WebMvcConfig.java diff 0).
//   사용자 명시 비협상 결정 (D-6 라우트·메뉴 신설 금지 / D-13 차단하지 않는다).
//   CLAUDE.md 'UI 디자인 철학' — 운영자는 "흐름과 끊긴 지점"이 궁금하다. 값 없음도 정보다.
import { useCallback, useEffect, useRef, useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { searchAcrossRoutes } from '../lib/routeSearch';
import { deleteService, listServicesDetailed } from '../api/services';
import type { HealthStatus, ServiceInfo } from '../types/api';
import { InstrumentAnalysis } from '../components/instrument/InstrumentAnalysis';
import { STATUS_HEX_HEALTH, STATUS_LABEL_KO } from '../lib/colors';
import { useSearchPreservingNavigate } from '../hooks/useSearchPreservingNavigate';
import { useMaintenanceStatus } from '../hooks/useMaintenanceStatus';
import { NavHeader } from '../components/NavHeader';
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
  // [Phase R15] AC-B4-1 — 전역 일시정지 배지(h1 우측 inline). 공유 queryKey ['maintenance','status'] (health dot 별개).
  const { paused } = useMaintenanceStatus();

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

  // [Phase R19] 목록 ↔ 계측 분석 전환. 주소에 이름이 있으면 분석 화면을 그린다.
  // 값이 없으면 기존 목록 그대로 (신규 라우트 0 — 이 파라미터는 route 를 넘을 때 자동으로 떨어진다).
  const analyzeName = searchParams.get('analyze');
  // 복귀 시 눌렀던 행의 [계측 분석] 버튼으로 포커스를 되돌리기 위한 등록부.
  const analyzeButtonRefs = useRef<Map<string, HTMLButtonElement>>(new Map());
  const [pendingFocus, setPendingFocus] = useState<string | null>(null);

  const registerAnalyzeButton = useCallback((name: string, el: HTMLButtonElement | null): void => {
    if (el === null) analyzeButtonRefs.current.delete(name);
    else analyzeButtonRefs.current.set(name, el);
  }, []);

  useEffect(() => {
    if (pendingFocus === null || analyzeName !== null) return;
    analyzeButtonRefs.current.get(pendingFocus)?.focus();
    setPendingFocus(null);
  }, [pendingFocus, analyzeName]);

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
      <>
        {/* [Phase R19] 넘침 처리 = 가로 스크롤. 좁은 폭에서 오른쪽이 조용히 잘려 작업 버튼이
            사라지던 것을 막는다(컬럼 은닉 0). 마우스 없이도 굴릴 수 있게 초점 가능 영역으로 둔다. */}
        <div
          role="region"
          aria-label="Services 표"
          tabIndex={0}
          className="overflow-x-auto rounded-lg border border-stone-200"
        >
          {/* [Phase R19] min-w 는 컬럼 6개 + 작업 버튼 2개가 눌릴 수 있는 최소 폭이다.
              760px 로 잡았더니 좁은 창에서 가로 스크롤이 생기기 전에 컬럼만 압축돼
              버튼에 닿지 못했다(릴리스 전 사용자 확인에서 발견). 값을 줄이지 말 것. */}
          <table className="w-full min-w-[920px] text-left">
            <thead className="bg-stone-50">
              <tr className="text-xs font-medium text-stone-500">
                <th className="px-4 py-2">상태</th>
                <th className="px-4 py-2">Service</th>
                {/* [Phase R19] T-01 — 서비스 정체성 정보끼리 붙인다 (Service 바로 오른쪽). */}
                <th className="px-4 py-2">agent 버전</th>
                <th className="px-4 py-2">마지막 trace</th>
                {/* Phase R12 (FR-A3, AC-A3-3): traceCount 의미 변경 "누적 전수" → "최근 24h" 동기 라벨 (설계 §1.2 footprint ①) */}
                <th className="px-4 py-2 text-right">Trace 수 (24h)</th>
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
                  onAnalyzeClick={(name) => nav('/services', { search: { analyze: name } })}
                  registerAnalyzeButton={registerAnalyzeButton}
                />
              ))}
            </tbody>
          </table>
        </div>
        {/* [Phase R19] T-53 · T-54 — 표 컨테이너 **바깥**에 둔다. 안에 넣으면 가로 스크롤을 따라
            움직여서 정작 못 읽는다. 툴팁이 아닌 이유: 값이 왜 비었는지는 모든 운영자가 한 번은
            읽어야 하는 설명이라 마우스를 올려야 보이는 자리에 숨기면 안 된다. */}
        <div className="space-y-1 text-xs text-stone-500">
          <p>
            agent 버전은 agent 가 마지막으로 시작할 때 보고한 값이에요. 지금 도는 버전과 다를 수 있고,
            값이 없으면(—) agent 를 다시 시작할 때 표시돼요.
          </p>
          <p>
            여기 보이는 버전은 각 서비스에 붙은 agent 의 버전이에요. 왼쪽 위에 보이는 제품 버전과 다를
            수 있어요.
          </p>
        </div>
      </>
    );
  };

  /** [Phase R19] 분석 화면 — 주소의 서비스가 목록에 없으면 돌아가는 길을 준다(E-13). */
  const renderAnalyze = (name: string): ReactNode => {
    if (servicesQuery.isLoading) {
      return <LoadingSkeleton variant="list" />;
    }
    if (servicesQuery.isError) {
      return (
        <ErrorState error={servicesQuery.error} onRetry={() => void servicesQuery.refetch()} />
      );
    }
    const target = services.find((svc) => svc.name === name);
    if (target === undefined) {
      return (
        <div
          role="alert"
          className="flex min-h-40 flex-col items-center justify-center gap-3 rounded-lg border border-stone-200 bg-stone-50 p-8 text-center"
        >
          {/* T-68 */}
          <p className="text-sm text-stone-500">그 서비스를 찾을 수 없어요. 목록으로 돌아가 주세요.</p>
          <button
            type="button"
            onClick={() => nav('/services')}
            className="rounded border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
          >
            ← Services 목록
          </button>
        </div>
      );
    }
    return (
      <InstrumentAnalysis
        service={target}
        onBack={() => {
          nav('/services'); // analyze 는 route-local 키라 자동으로 떨어진다.
          setPendingFocus(name); // 눌렀던 행의 [계측 분석] 버튼으로 포커스 복원.
        }}
      />
    );
  };

  return (
    <div className="flex h-full flex-col bg-stone-50">
      {/* Phase R12 (FR-B4, AC-B4-1): SimpleNavHeader 중복 제거 → NavHeader 공통 (UX-G-01 / 설계 §3.2.1). */}
      <NavHeader />

      <main className="flex-1 overflow-auto px-6 py-6">
        {/* [Phase R19] 분석 화면도 목록과 같은 폭(max-w-5xl)을 쓴다 — 같은 경로 안 화면 전환이라 감각이 이어져야 한다. */}
        <div className="mx-auto max-w-5xl space-y-4">
          {analyzeName !== null ? (
            renderAnalyze(analyzeName)
          ) : (
            <>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <h1 className="text-lg font-medium text-stone-900">Services</h1>
                  {/* [Phase R15] AC-B4-1/T-02 — 전역 일시정지 배지(neutral amber, health dot 별개). 사용자 명시 비협상 결정(D03). CLAUDE.md 'UI 디자인 철학'. */}
                  {paused && (
                    <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-xs text-amber-900">
                      수신 일시정지 중
                    </span>
                  )}
                </div>
                {/* [+ Add service] 버튼 — search 보존 (SH-06) */}
                <Link
                  to={{ pathname: '/setup', search }}
                  className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
                >
                  + Add service
                </Link>
              </div>
              {renderBody()}
            </>
          )}
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

// ── ServiceRow ────────────────────────────────────────────────────
interface RowProps {
  svc: ServiceInfo;
  searchString: string;
  deleting: boolean;
  onDeleteClick: (name: string) => void;
  onRowClick: (name: string) => void;
  /** [Phase R19] 계측 분석 화면으로 전환 (같은 경로 + ?analyze=). */
  onAnalyzeClick: (name: string) => void;
  /** [Phase R19] 복귀 시 포커스 복원용 버튼 등록. */
  registerAnalyzeButton: (name: string, el: HTMLButtonElement | null) => void;
}
function ServiceRow({
  svc,
  deleting,
  onDeleteClick,
  onRowClick,
  onAnalyzeClick,
  registerAnalyzeButton,
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
  const handleAnalyze = (e: MouseEvent<HTMLButtonElement>): void => {
    // SH-19 전례 그대로 — 아래 한 줄(행 클릭 흡수 차단)이 의무다. 빠지면 [계측 분석] 을
    // 눌렀을 때 행 클릭이 흡수해 대시보드로 튄다.
    e.stopPropagation();
    onAnalyzeClick(svc.name);
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
      {/* [Phase R19] T-02 / T-03 — 값 없음은 lastSeenAt 과 **완전히 같은 모양**(`—`). 기본값을 주지
          않았으므로 값 없음은 "아직 agent 를 다시 시작하지 않았다" 한 뜻만 갖는다. */}
      <td className="px-4 py-3 font-mono">
        {svc.agentVersion === null ? (
          <span className="text-stone-300">—</span>
        ) : (
          <span className="text-stone-900">{svc.agentVersion}</span>
        )}
      </td>
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
      {/* [Phase R19] 작업 셀 — [계측 분석] [삭제] 순서. 되돌릴 수 없는 [삭제] 를 오른쪽 끝에 그대로
          두고 새 액션을 그 왼쪽 안쪽에 둔다. 시각 급은 완전히 같게 한다(새 버튼에 색 강조 금지 —
          색을 주면 [삭제] 보다 강해져서 되돌릴 수 없는 동작이 상대적으로 약해 보인다). */}
      <td className="px-4 py-3 text-right whitespace-nowrap">
        <span className="inline-flex items-center gap-1">
          {/* T-06 · T-07 · C-01 — 어떤 이유로도 비활성이 되지 않는다 (비활성 조건을 붙이지 않는다). */}
          <button
            ref={(el) => registerAnalyzeButton(svc.name, el)}
            type="button"
            onClick={handleAnalyze}
            aria-label={`${svc.name} 계측 분석`}
            className="shrink-0 rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-stone-900"
          >
            계측 분석
          </button>
          <button
            type="button"
            onClick={handleDelete}
            disabled={deleting}
            aria-label={`${svc.name} 삭제`}
            className="shrink-0 rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-[var(--color-status-error)] disabled:cursor-not-allowed disabled:opacity-50"
          >
            삭제
          </button>
        </span>
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
