// 대시보드 메인 화면 — Header + LatencyScatter + TraceList.
//
// queryKey 공유: LatencyScatter와 TraceList는 같은 queryKey로 dedupe되어 한 번만 호출.
// service==null 이면 traces query disable (BL-05).
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Header } from '../components/Header';
import { LatencyScatter } from '../components/LatencyScatter';
import { ScatterLegend } from '../components/ScatterLegend';
import { TraceList } from '../components/TraceList';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingSkeleton } from '../components/LoadingSkeleton';
import { useDashboardState } from '../hooks/useDashboardState';
import { listServices, listTraces } from '../api/traces';
import { computeWindow } from '../lib/time';

const TRACES_LIMIT = 100;

export function Dashboard(): ReactNode {
  const { service, range, live, setService, setRange, setLive } = useDashboardState();

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

  const tracesQuery = useQuery({
    queryKey: ['traces', { service, range, live, limit: TRACES_LIMIT }] as const,
    queryFn: ({ signal }) => {
      const { since, until } = computeWindow({ range, live, pinnedUntil });
      return listTraces(
        {
          ...(service !== null ? { service } : {}),
          since,
          until,
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
  });

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

    // 3) traces 로딩
    if (tracesQuery.isLoading) {
      return (
        <div className="space-y-4">
          <LoadingSkeleton variant="chart" />
          <LoadingSkeleton variant="list" />
        </div>
      );
    }

    // 4) 에러
    if (tracesQuery.isError) {
      return <ErrorState error={tracesQuery.error} onRetry={() => void tracesQuery.refetch()} />;
    }

    // 5) traces 0건
    const traces = tracesQuery.data?.traces ?? [];
    if (traces.length === 0) {
      return <EmptyState kind="no-traces" />;
    }

    // 6) 정상
    return (
      <div className="space-y-4">
        <div className="space-y-1.5">
          <LatencyScatter traces={traces} since={windowSince} until={windowUntil} />
          <ScatterLegend />
        </div>
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
