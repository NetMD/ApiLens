// [Phase R19] 계측 분석 화면 — 블록 A(agent 버전 알림) · B(실행) · C(3축 순위표) · D(빼면 이렇게 돼요).
//
// ⚠️ **신규 라우트가 아니다.** 같은 `/services` 경로 안에서 목록 ↔ 분석 화면을 바꾸고, 어느 서비스를
//    보는 중인지는 `?analyze={서비스이름}` 검색 파라미터에 담는다 (App.tsx · BrandNav.tsx ·
//    WebMvcConfig.java diff 0). 경로가 안 바뀌므로 **화면 전환 시 포커스를 직접 옮긴다** —
//    브라우저가 대신 해 주지 않아서 화면 읽기 사용자가 변화를 놓친다.
//
// [Phase R19] AC-06-5/AC-08-3/AC-08-4 — 사용자 명시 비협상 결정 (D-11 옵션 생성기 보류,
// D-4 온디맨드 명시 실행, D-6 라우트 신설 0). CLAUDE.md '절대 변경하지 말아야 할 결정 사항'
// — UI 는 React + Vite + TS 단일 번들이고, 화면이 사용자 앱 설정을 대신 만들어 주지 않는다.
//
// 비협상 규약:
//   ⛔ 옵션 문자열(`-D...`)을 조립하거나 복사 버튼을 만들지 않는다 (생성기는 다음 버전으로 보류).
//   ⛔ [← Services 목록](C-22) 은 어떤 이유로도 비활성이 되지 않는다. 진행 중에도 나갈 수 있고,
//      나가면 요청을 끊는다.
//   ⛔ 자동 실행·자동 새로고침 0. 새로고침하면 결과가 비는 것이 정상이다.
//   ⛔ 패키지 선택은 "보이는 목록 안 같은 패키지 클래스를 한 번에 체크하는 단축키" 다. 패키지라는
//      별도 선택 상태를 만들지 않는다 → 서버로 가는 값은 언제나 클래스 이름 목록이라
//      패키지 평균으로 경고를 계산하는 경로가 **생길 수 없다**.
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { InstrumentClassStat, ServiceInfo } from '../../types/api';
import { ApiError } from '../../api/client';
import { useInstrumentAnalysis } from '../../hooks/useInstrumentAnalysis';
import { checkExcludeSupport } from '../../lib/agentVersion';
import {
  ANALYSIS_WINDOW_HOURS,
  WINDOW_LABELS,
} from '../../lib/instrumentThresholds';
import type { SortAxis, WindowHours } from '../../lib/instrumentThresholds';
import { classPackage } from '../../lib/format';
import { formatAnalysisWindow } from '../../lib/time';
import { ErrorState } from '../ErrorState';
import { LoadingSkeleton } from '../LoadingSkeleton';
import { InstrumentRankTable } from './InstrumentRankTable';
import { SimulationResult } from './SimulationResult';

interface Props {
  service: ServiceInfo;
  /** 목록으로 복귀 (주소에서 analyze 제거 + 눌렀던 행 버튼으로 포커스 복원). */
  onBack: () => void;
}

/** 사용자 취소는 오류가 아니다 — 진행 표시만 사라지고 이전 상태로 돌아간다 (토스트도 없음). */
function isUserAbort(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

/**
 * 오류 → 화면 문구 (상태 코드로 고른다. **서버 응답 본문을 그대로 노출하지 않는다**).
 *
 * - 409 : 이미 다른 분석이 도는 중 (T-22)
 * - 504 / 브라우저 타임아웃 : 실행 시간 초과 (T-21 — "구간을 좁혀 주세요")
 * - 그 밖 : 일반 오류 (T-25)
 * 401 은 여기 오지 않는다 — 호출부에서 기존 ErrorState(토큰 안내)로 분기한다.
 */
function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return '다른 분석이 돌고 있어요. 잠시 뒤 다시 시도해 주세요';
    if (error.status === 504) {
      return '분석이 너무 오래 걸려 멈췄어요. 구간을 좁혀 다시 시도해 주세요';
    }
    return '분석에 실패했어요. 잠시 후 다시 시도해 주세요';
  }
  if (error instanceof Error && error.name === 'TimeoutError') {
    return '분석이 너무 오래 걸려 멈췄어요. 구간을 좁혀 다시 시도해 주세요';
  }
  return '분석에 실패했어요. 잠시 후 다시 시도해 주세요';
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

/** 알림 박스 — ErrorState 와 같은 모양 (신규 시각 규격 발명 0). */
function NoticeBox({ message }: { message: string }): ReactNode {
  return (
    <div
      role="alert"
      className="rounded-lg border border-stone-200 bg-stone-50 p-8 text-center text-sm text-stone-500"
    >
      {message}
    </div>
  );
}

export function InstrumentAnalysis({ service, onBack }: Props): ReactNode {
  const {
    windowHours,
    selectWindowHours,
    analysis,
    analysisError,
    isAnalyzing,
    runAnalysis,
    simulation,
    simulationError,
    isSimulating,
    runSimulation,
    resetSimulation,
    cancel,
  } = useInstrumentAnalysis(service.name);

  const [selected, setSelected] = useState<ReadonlySet<string>>(() => new Set<string>());
  const [sortAxis, setSortAxis] = useState<SortAxis>('span');
  const [acknowledged, setAcknowledged] = useState(false);
  const [elapsedSec, setElapsedSec] = useState(0);
  const headingRef = useRef<HTMLHeadingElement | null>(null);

  // 경로가 안 바뀌는 화면 전환이라 포커스를 직접 옮긴다 (화면 읽기 사용자가 전환을 놓치지 않게).
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  // 진행 표시는 모호한 "분석 중…" 대신 경과 초를 보여 준다 (서버가 진행률을 주지 않으므로).
  useEffect(() => {
    if (!isAnalyzing) {
      setElapsedSec(0);
      return undefined;
    }
    setElapsedSec(0);
    const id = window.setInterval(() => setElapsedSec((prev) => prev + 1), 1_000);
    return () => window.clearInterval(id);
  }, [isAnalyzing]);

  // 새 순위 결과가 오면 이전 선택·확인 표시는 다른 결과의 것이라 비운다.
  useEffect(() => {
    setSelected(new Set<string>());
    setAcknowledged(false);
  }, [analysis]);

  const items: InstrumentClassStat[] = useMemo(() => analysis?.items ?? [], [analysis]);
  const hasResult = analysis !== undefined;
  const isBusy = isAnalyzing || isSimulating;
  const hasSelection = selected.size > 0;

  // 세 축의 1위가 서로 다른가 — 다를 때만 한 줄 안내한다 (항상 띄우면 잔소리가 된다).
  const axisTopDiffers = useMemo(() => {
    if (!hasResult) return false;
    const tops = new Set<string>();
    for (const item of items) {
      if (item.spanRank === 1 || item.payloadCountRank === 1 || item.payloadBytesRank === 1) {
        if (item.spanRank === 1) tops.add(item.className);
        if (item.payloadCountRank === 1) tops.add(item.className);
        if (item.payloadBytesRank === 1) tops.add(item.className);
      }
    }
    return tops.size > 1;
  }, [hasResult, items]);

  // 고른 대상 중 "확인 안 됨" 건수 (T-64).
  const uncertainSelectedCount = useMemo(
    () => items.filter((i) => selected.has(i.className) && i.excludeStatus === 'UNKNOWN').length,
    [items, selected],
  );

  /** 선택이 바뀌면 이전 시뮬레이션 결과·확인 표시는 다른 선택의 것이다. */
  const invalidateSimulation = useCallback(() => {
    resetSimulation();
    setAcknowledged(false);
  }, [resetSimulation]);

  const handleToggleClass = useCallback(
    (className: string) => {
      setSelected((prev) => {
        const next = new Set(prev);
        if (next.has(className)) next.delete(className);
        else next.add(className);
        return next;
      });
      invalidateSimulation();
    },
    [invalidateSimulation],
  );

  const handleSelectPackage = useCallback(
    (packageName: string) => {
      const names = items
        .filter(
          (i) =>
            i.className !== '' &&
            i.excludeStatus !== 'NOT_EXCLUDABLE' &&
            classPackage(i.className) === packageName,
        )
        .map((i) => i.className);
      setSelected((prev) => {
        const next = new Set(prev);
        for (const name of names) next.add(name);
        return next;
      });
      invalidateSimulation();
    },
    [items, invalidateSimulation],
  );

  const handleClearSelection = useCallback(() => {
    setSelected(new Set<string>());
    invalidateSimulation();
  }, [invalidateSimulation]);

  const handleBack = useCallback(() => {
    cancel(); // 나가면 진행 중인 요청을 끊는다.
    onBack();
  }, [cancel, onBack]);

  const handleWindowChange = useCallback(
    (hours: WindowHours) => {
      selectWindowHours(hours);
      setSelected(new Set<string>());
      setAcknowledged(false);
    },
    [selectWindowHours],
  );

  const support = checkExcludeSupport(service.agentVersion);

  // 인증 실패는 기존 ErrorState(토큰 안내 + 설정 이동) 를 그대로 쓴다.
  const authError = isUnauthorized(analysisError)
    ? analysisError
    : isUnauthorized(simulationError)
      ? simulationError
      : null;

  return (
    <div className="space-y-4">
      {/* ── 머리 ── */}
      <div className="space-y-2">
        {/* T-55 · C-22 — 비활성 조건을 붙이지 않는다. 진행 중에도 나갈 수 있다. */}
        <button
          type="button"
          onClick={handleBack}
          className="rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-stone-900"
        >
          ← Services 목록
        </button>
        <div className="flex items-baseline justify-between gap-4">
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="text-lg font-medium text-stone-900 outline-none"
          >
            {/* T-09 */}
            {service.name} 계측 분석
          </h1>
          <p className="text-xs text-stone-500">
            {/* T-01 라벨 + T-02 값 (없으면 T-03).
                [R21/AC-07-1] T-23 — "지금 버전" 단정을 피하는 라벨로 통일 (Services 표·설정 화면 동일). */}
            agent 버전 (마지막 확인 시점){' '}
            {service.agentVersion === null ? (
              <span className="text-stone-300">—</span>
            ) : (
              <span className="font-mono text-stone-900">{service.agentVersion}</span>
            )}
          </p>
        </div>
      </div>

      {/* ── 블록 A · agent 버전 알림 (둘이 동시에 뜨지 않는다) ── */}
      {support === 'UNSUPPORTED' && (
        <div
          role="status"
          className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
        >
          {/* T-48 */}
          <span aria-hidden>⚠ </span>이 서비스의 agent 는 계측 제외 옵션을 아직 몰라요. agent 를 올린
          뒤에 적용해 주세요.
        </div>
      )}
      {support === 'UNKNOWN' && (
        <div
          role="status"
          className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
        >
          {/* T-49 — 확인하지 못한 것을 "미달" 로 단정하지 않는다. */}
          <span aria-hidden>⚠ </span>이 서비스의 agent 버전을 확인하지 못했어요. agent 를 다시
          시작하면 확인돼요.
        </div>
      )}

      {/* ── 블록 B · 실행 ── */}
      <section className="rounded-lg border border-stone-200 bg-white p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            {/* T-14 */}
            <span className="text-xs text-stone-500">분석 구간</span>
            <div
              role="group"
              aria-label="분석 구간"
              className="inline-flex rounded-md border border-stone-200 bg-white p-0.5"
            >
              {ANALYSIS_WINDOW_HOURS.map((hours) => {
                const active = hours === windowHours;
                return (
                  <button
                    key={hours}
                    type="button"
                    aria-pressed={active}
                    disabled={isBusy}
                    onClick={() => handleWindowChange(hours)}
                    className={
                      active
                        ? 'rounded px-3 py-1.5 text-sm font-medium bg-stone-900 text-white disabled:opacity-50'
                        : 'rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900 disabled:opacity-50'
                    }
                  >
                    {WINDOW_LABELS[hours]}
                  </button>
                );
              })}
            </div>
          </div>
          <div className="flex items-center gap-2">
            {/* T-10 / T-11 / T-13 — 진행 중에는 경과 초를 보여 준다. */}
            <button
              type="button"
              onClick={runAnalysis}
              disabled={isBusy}
              className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
            >
              {isAnalyzing
                ? `분석 중이에요… (${elapsedSec}초 경과)`
                : hasResult
                  ? '다시 분석'
                  : '분석 실행'}
            </button>
            {/* T-12 · C-05 — 진행 중이면 항상 누를 수 있다. */}
            <button
              type="button"
              onClick={cancel}
              disabled={!isBusy}
              className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
            >
              취소
            </button>
          </div>
        </div>

        {/* C-30 — 첫 안내 (분석 전) */}
        {!hasResult && !isAnalyzing && (
          <p className="mt-3 text-xs text-stone-500">
            {/* T-69 */}
            분석 구간을 고르고 [분석 실행] 을 눌러 주세요. 분석은 서버 자원을 많이 써서 자동으로 돌지
            않아요.
          </p>
        )}
        {/* T-15 — 결과가 있을 때 구간·조회 시각을 함께 적는다. */}
        {analysis !== undefined && (
          <p className="mt-3 text-xs text-stone-500">
            {formatAnalysisWindow(
              analysis.window.fromMs,
              analysis.window.toMs,
              analysis.window.queriedAtMs,
            )}
          </p>
        )}
      </section>

      {/* ── 인증 실패는 화면 전체 안내(기존 규격) ── */}
      {authError !== null && <ErrorState error={authError} />}

      {/* ── 진행 표시 ── */}
      {isAnalyzing && <LoadingSkeleton variant="list" />}

      {/* ── 분석 오류 (취소는 오류가 아니다) ── */}
      {!isAnalyzing &&
        authError === null &&
        analysisError !== null &&
        analysisError !== undefined &&
        !isUserAbort(analysisError) && <NoticeBox message={errorMessage(analysisError)} />}

      {/* ── 블록 C · 3축 순위표 ── */}
      {!isAnalyzing && analysis !== undefined && authError === null && (
        <>
          {items.length === 0 ? (
            // E-04 · T-20 — 인라인 빈 상태 규격 (점선 박스 + 두 줄)
            <div
              role="status"
              className="flex min-h-40 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center"
            >
              <p className="text-base font-medium text-stone-900">이 구간에는 분석할 자료가 없어요</p>
              <p className="text-sm text-stone-500">구간을 넓혀 다시 시도해 보세요</p>
            </div>
          ) : (
            <section className="space-y-3 rounded-lg border border-stone-200 bg-white p-6">
              {/* T-33 — 두 축이 다른 이야기임을 표 바로 위에서 한 번 밝힌다. */}
              <p className="text-xs text-stone-500">
                절감 예측과 &quot;실제로 뺄 수 있는지&quot; 는 서로 다른 이야기예요
              </p>
              {/* T-57 (C-25) */}
              {axisTopDiffers && (
                <p className="text-xs text-stone-500">
                  세 기준의 1위가 서로 달라요. 한 기준만 보고 정하지 마세요.
                </p>
              )}

              <InstrumentRankTable
                items={items}
                truncated={analysis.truncated}
                sortAxis={sortAxis}
                onSortAxisChange={setSortAxis}
                selected={selected}
                onToggleClass={handleToggleClass}
                onSelectPackage={handleSelectPackage}
                onClearSelection={handleClearSelection}
                busy={isBusy}
              />

              <div className="flex justify-end">
                {/* T-63 · C-21 — 시뮬레이션도 명시 실행이다 (체크할 때마다 자동으로 돌지 않는다). */}
                <button
                  type="button"
                  onClick={() => runSimulation([...selected])}
                  disabled={!hasSelection || isBusy}
                  className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
                >
                  빼면 어떻게 되는지 보기
                </button>
              </div>
            </section>
          )}
        </>
      )}

      {/* ── 블록 D · 빼면 이렇게 돼요 ── */}
      {isSimulating && <LoadingSkeleton variant="list" />}
      {!isSimulating &&
        authError === null &&
        simulationError !== null &&
        simulationError !== undefined &&
        !isUserAbort(simulationError) && <NoticeBox message={errorMessage(simulationError)} />}
      {!isSimulating && simulation !== undefined && analysis !== undefined && (
        <SimulationResult
          summary={analysis.summary}
          simulation={simulation}
          uncertainSelectedCount={uncertainSelectedCount}
          acknowledged={acknowledged}
          onAcknowledge={() => setAcknowledged(true)}
        />
      )}
    </div>
  );
}
