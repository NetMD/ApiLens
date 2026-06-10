// 대시보드 메인 화면 — Header + LatencyScatter + TraceList.
//
// queryKey 공유: LatencyScatter와 TraceList는 같은 queryKey로 dedupe되어 한 번만 호출.
// service==null 이면 traces query disable (BL-05).
//
// [R12] D-03 비협상 — 필터는 status + operation 검색(q)만. duration 필터 추가 금지.
// 필터는 listTraces 쿼리 파라미터로 적용 → LatencyScatter 와 TraceList 가 동시 필터됨 (의도된
// 동작 — "에러만 보기" 시 산점도도 에러만, UX §3.5).
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { LatencyScatter } from '../components/LatencyScatter';
import { ScatterLegend } from '../components/ScatterLegend';
import { TraceList } from '../components/TraceList';
import { TraceFilterBar } from '../components/TraceFilterBar';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingSkeleton } from '../components/LoadingSkeleton';
import { useDashboardState } from '../hooks/useDashboardState';
import { listServices, listTraces } from '../api/traces';
import { computeWindow } from '../lib/time';

const TRACES_LIMIT = 100;

export function Dashboard(): ReactNode {
  const { service, range, live, status, q, setService, setRange, setLive, setStatus, setQ } =
    useDashboardState();

  // 시간 윈도우 처리:
  //   Live ON  → queryFn 안에서 매 호출마다 Date.now() 사용 (슬라이딩 윈도우)
  //   Live OFF → pinnedUntil 사용 (range 선택/Live OFF 토글 시점에 freeze)
  // queryKey에 시간을 직접 넣지 않는다 — 매 호출마다 키 바뀌면 캐시 의미 없음.
  const [pinnedUntil, setPinnedUntil] = useState<number>(() => Date.now());
  useEffect(() => {
    setPinnedUntil(Date.now());
  }, [range, live]);

  // 서비스 목록 — 빈 상태 분기용 (헤더 ServiceSelector도 같은 queryKey로 dedupe)
  const servicesQuery = useQuery({
    queryKey: ['services'],
    queryFn: ({ signal }) => listServices(signal),
    staleTime: 30_000,
    retry: 1,
  });

  // [R12] AC-C1-2/AC-C2-3 — queryKey 에 status/q 포함 (캐시 분리) + listTraces 전달.
  // placeholderData: keepPreviousData 채택 (UX §5.3 — 세그먼트 전환 시 스켈레톤 깜빡임 방지).
  const tracesQuery = useQuery({
    queryKey: ['traces', { service, range, live, status, q, limit: TRACES_LIMIT }] as const,
    queryFn: ({ signal }) => {
      const { since, until } = computeWindow({ range, live, pinnedUntil });
      return listTraces(
        {
          ...(service !== null ? { service } : {}),
          since,
          until,
          ...(status !== null ? { status } : {}),
          ...(q.trim() !== '' ? { q } : {}),
          limit: TRACES_LIMIT,
        },
        signal,
      );
    },
    enabled: service !== null,
    staleTime: 2_000,
    retry: 1,
    refetchOnWindowFocus: false,
    refetchInterval: live ? 5_000 : false,
    refetchIntervalInBackground: false,
    placeholderData: keepPreviousData,
  });

  // [R12] 🔴 회귀 가드 — LatencyScatter X축 도메인은 computeWindow 시간 윈도우 고정 유지 (diff 0).
  // 필터(status/q)는 since/until 에 무관여 — 점이 줄어도 축은 윈도우 그대로 (자동스케일 회귀 금지).
  // Latency 산점도 X축 도메인 = 선택한 시간 윈도우 (고정). XAxis 가 dataMin/dataMax 로
  // 자동 스케일하면 매 polling 마다 이상치(오래된/시계 어긋난 trace)에 축이 출렁여(rubber-band)
  // 점이 몰렸다 퍼졌다 한다. 윈도우로 고정하면 점이 진짜 시각 위치에 안정적으로 박힌다.
  // Live 면 until = 마지막 fetch 시각(dataUpdatedAt)으로 데이터와 정렬 (Date.now() per-render jitter 회피).
  const { since: windowSince, until: windowUntil } = computeWindow({
    range,
    live,
    pinnedUntil,
    // dataUpdatedAt 은 첫 fetch 전 0 → 그때만 Date.now() 폴백.
    now: tracesQuery.dataUpdatedAt || Date.now(),
  });

  // [R12] UX §3.5 — 필터 활성 여부 (0건 이중 분기 + T-30 노출 판단).
  const filterActive = status !== null || q.trim() !== '';

  // [R12] 필터 바 — service !== null 일 때 항상 (로딩/에러/0건 분기에서도 유지 — 필터 해제 경로 보장).
  // no-services / 서비스 미선택 분기에서는 비노출 (필터 대상 자체 부재, UX §3.5).
  const filterBar = (
    <TraceFilterBar status={status} q={q} onStatusChange={setStatus} onQChange={setQ} />
  );

  // ── 본문 분기 결정 ──────────────────────────────────────────────────────
  const renderBody = (): ReactNode => {
    // 1) 서비스 자체가 없는 경우 — 빈 상태 (no-services)
    if (
      !servicesQuery.isLoading &&
      servicesQuery.data &&
      servicesQuery.data.services.length === 0
    ) {
      return <EmptyState kind="no-services" />;
    }

    // 2) 서비스 미선택 — 안내
    if (service === null) {
      return (
        <div
          role="status"
          className="flex h-80 items-center justify-center rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center text-sm text-stone-500"
        >
          상단에서 서비스를 선택하세요.
        </div>
      );
    }

    // 3) traces 로딩 — 필터 바는 유지 (UX §3.5 회귀 가드)
    if (tracesQuery.isLoading) {
      return (
        <div className="space-y-4">
          {filterBar}
          <LoadingSkeleton variant="chart" />
          <LoadingSkeleton variant="list" />
        </div>
      );
    }

    // 4) 에러 — 기존 ErrorState 분기 유지 (필터 바는 유지)
    if (tracesQuery.isError) {
      return (
        <div className="space-y-4">
          {filterBar}
          <ErrorState error={tracesQuery.error} onRetry={() => void tracesQuery.refetch()} />
        </div>
      );
    }

    // 5) traces 0건 — 이중 분기 (T-30 vs 기존 EmptyState, UX §3.5):
    //    필터 활성 + 0건 → T-30 (필터 바 유지 — 해제 가능해야 함) / 비활성 + 0건 → 기존 no-traces.
    const traces = tracesQuery.data?.traces ?? [];
    if (traces.length === 0) {
      return (
        <div className="space-y-4">
          {filterBar}
          {filterActive ? (
            <div
              role="status"
              className="flex h-full min-h-40 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center"
            >
              {/* T-30 (E-11) — 기존 no-traces 빈 상태와 구분 (필터 활성 시에만) */}
              <p className="max-w-md text-sm text-stone-500">
                조건에 맞는 trace 가 없어요. 필터를 확인해 주세요.
              </p>
            </div>
          ) : (
            <EmptyState kind="no-traces" />
          )}
        </div>
      );
    }

    // 6) 정상 — 필터 바는 TraceList 카드 바로 위 독립 행 (AC-C1-2 "TraceList 상단" 준수)
    return (
      <div className="space-y-4">
        <div className="space-y-1.5">
          <LatencyScatter traces={traces} since={windowSince} until={windowUntil} />
          <ScatterLegend />
        </div>
        {filterBar}
        <TraceList traces={traces} />
      </div>
    );
  };

  return (
    <div className="flex h-full flex-col bg-stone-50">
      <Header
        service={service}
        range={range}
        live={live}
        onServiceChange={setService}
        onRangeChange={setRange}
        onLiveChange={setLive}
      />
      <main className="flex-1 overflow-auto px-6 py-4">
        <div className="mx-auto max-w-6xl">{renderBody()}</div>
      </main>
    </div>
  );
}
