// [Phase R19] 계측 분석 화면의 두 요청 상태(진행·결과·오류)와 취소 (설계 §2.3-C).
//
// 이 훅은 **화면 문구를 갖지 않는다.** 문구는 컴포넌트가 UX 문구표에서 고른다.
//
// ⚠️ **자동 새로고침 금지 (AC-08-4 · 기계 판정)**: 두 쿼리에 폴링 주기 옵션을 주지 않는다.
//    분석은 서버 자원을 많이 쓰는 온디맨드 작업이라 명시 실행만 한다 —
//    순위 집계는 `enabled: false` + 수동 refetch(), 시뮬레이션은 [빼면 어떻게 되는지 보기] 를
//    눌러 요청 본문이 확정될 때만 enabled 가 된다. 창 복귀·재마운트·재연결 재요청도 전부 끈다.
// ⚠️ 새로고침하면 결과가 사라지는 것이 **정상**이다 (결과를 화면 상태로만 둔다).
import { useCallback, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { runInstrumentAnalysis, runInstrumentSimulation } from '../api/instrument';
import type { AnalysisResponse, SimulationRequest, SimulationResponse } from '../types/api';
import { DEFAULT_WINDOW_HOURS } from '../lib/instrumentThresholds';
import type { WindowHours } from '../lib/instrumentThresholds';

/** 두 쿼리의 공통 루트 키 — 취소는 이 루트 하나로 둘 다 끊는다. */
const INSTRUMENT_KEY_ROOT = 'instrument';

/** 온디맨드 전용 공통 옵션 — 어떤 자동 트리거도 붙지 않게 한 자리에 모은다. */
const MANUAL_ONLY = {
  retry: false,
  staleTime: Infinity,
  gcTime: 0,
  refetchOnWindowFocus: false,
  refetchOnMount: false,
  refetchOnReconnect: false,
} as const;

export interface InstrumentAnalysisState {
  /** 선택된 분석 구간 (1 / 6 / 24). */
  windowHours: WindowHours;
  /** 구간 변경 — 이전 시뮬레이션 결과는 기준 구간이 달라지므로 함께 비운다. */
  selectWindowHours: (hours: WindowHours) => void;

  analysis: AnalysisResponse | undefined;
  analysisError: unknown;
  isAnalyzing: boolean;
  /** [분석 실행] / [다시 분석] — 명시 실행. */
  runAnalysis: () => void;

  simulation: SimulationResponse | undefined;
  simulationError: unknown;
  isSimulating: boolean;
  /** [빼면 어떻게 되는지 보기] — targets 는 언제나 클래스 이름 목록. */
  runSimulation: (targets: string[]) => void;
  /** 선택이 바뀌면 이전 시뮬레이션 결과는 다른 선택의 값이라 지운다. */
  resetSimulation: () => void;

  /** [취소] · [← Services 목록] — 진행 중인 요청을 끊는다. */
  cancel: () => void;
}

/** 같은 요청인지 (내용 비교 — 같은 내용으로 다시 누르면 refetch, 다르면 키가 바뀌어 새 요청). */
function isSameRequest(a: SimulationRequest | null, b: SimulationRequest): boolean {
  if (a === null) return false;
  return (
    a.serviceName === b.serviceName &&
    a.fromMs === b.fromMs &&
    a.toMs === b.toMs &&
    a.targets.length === b.targets.length &&
    a.targets.every((t, i) => t === b.targets[i])
  );
}

export function useInstrumentAnalysis(serviceName: string): InstrumentAnalysisState {
  const queryClient = useQueryClient();
  const [windowHours, setWindowHours] = useState<WindowHours>(DEFAULT_WINDOW_HOURS);
  // 시뮬레이션은 "요청 본문이 확정된 순간" 에만 도는 구독이다. null 이면 아예 돌지 않는다.
  const [submitted, setSubmitted] = useState<SimulationRequest | null>(null);

  const analysisQuery = useQuery({
    queryKey: [INSTRUMENT_KEY_ROOT, 'analysis', serviceName, windowHours],
    queryFn: ({ signal }) => runInstrumentAnalysis({ serviceName, windowHours }, signal),
    enabled: false, // 명시 실행 전용 — 마운트만으로 절대 돌지 않는다.
    ...MANUAL_ONLY,
  });

  const simulationQuery = useQuery({
    queryKey: [INSTRUMENT_KEY_ROOT, 'simulation', submitted],
    queryFn: ({ signal }) => {
      // enabled 가 submitted !== null 이라 이 시점에 submitted 는 반드시 있다.
      if (submitted === null) throw new Error('simulation request missing');
      return runInstrumentSimulation(submitted, signal);
    },
    enabled: submitted !== null,
    ...MANUAL_ONLY,
  });

  const resetSimulation = useCallback(() => {
    setSubmitted(null);
  }, []);

  const cancel = useCallback(() => {
    void queryClient.cancelQueries({ queryKey: [INSTRUMENT_KEY_ROOT] });
  }, [queryClient]);

  const selectWindowHours = useCallback(
    (hours: WindowHours) => {
      setWindowHours(hours);
      setSubmitted(null); // 구간이 바뀌면 이전 시뮬레이션은 다른 구간의 값이다.
    },
    [],
  );

  const runAnalysis = useCallback(() => {
    setSubmitted(null); // 새 순위 결과가 오면 이전 시뮬레이션은 기준이 달라진다.
    void analysisQuery.refetch();
  }, [analysisQuery]);

  const runSimulation = useCallback(
    (targets: string[]) => {
      const analysis = analysisQuery.data;
      if (analysis === undefined) return; // 순위 결과 없이는 기준 구간이 없다.
      const next: SimulationRequest = {
        serviceName,
        // ⚠️ 순위 응답의 구간을 그대로 되돌려 보낸다 (서버가 창을 다시 계산하면 기준이 어긋난다).
        fromMs: analysis.window.fromMs,
        toMs: analysis.window.toMs,
        targets,
      };
      if (isSameRequest(submitted, next)) {
        void simulationQuery.refetch(); // 같은 선택으로 다시 누르면 다시 계산한다.
        return;
      }
      setSubmitted(next);
    },
    [analysisQuery.data, serviceName, submitted, simulationQuery],
  );

  return {
    windowHours,
    selectWindowHours,
    analysis: analysisQuery.data,
    analysisError: analysisQuery.error,
    isAnalyzing: analysisQuery.isFetching,
    runAnalysis,
    simulation: simulationQuery.data,
    simulationError: simulationQuery.error,
    isSimulating: simulationQuery.isFetching,
    runSimulation,
    resetSimulation,
    cancel,
  };
}
