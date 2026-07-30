// [Phase R19] 블록 D "빼면 이렇게 돼요" — 경고 → 부작용 → 절감 → 적용 전 확인.
//
// ★ 이 순서가 이 화면의 본체다. 이 기능은 "용량이 줄어드는 숫자를 보여주는 화면" 이 아니라
//   "빼면 무슨 일이 나는지 알려 주는 화면" 이다. 절감부터 그리면 경고가 나중에 붙어 순서가 뒤집힌다.
//   그래서 이 파일은 위에서 아래로 정확히 그 순서로 쓰여 있다.
//
// [Phase R19] AC-05-1/AC-05-2/AC-05-6 — 사용자 명시 비협상 결정 (S-3 절감·부작용 동시 표시,
// D-12 불확실 반올림 금지, D-13 차단 0). CLAUDE.md 'UI 디자인 철학' — 마스킹 라이브 프리뷰와 같은
// 결재용 신뢰 도구다. 신뢰는 좋은 숫자가 아니라 나쁜 숫자를 먼저 보여줄 때 생긴다.
//
// 비협상 규약:
//   ⛔ 절감만 그리는 경로를 만들지 않는다. 부작용 지표는 절감과 **언제나 함께** 뜬다
//      (서버도 savings 와 impact 를 한 응답에 묶어 보내 절감만 받는 경로가 아예 없다).
//   ⛔ 절감을 확인 전에 숨기지 않는다 — 결과를 인질로 잡는 모양이 되고 그것도 정직하지 않다.
//   ⛔ 확인 버튼은 **어떤 이유로도 비활성이 되지 않는다** (막는 관문이 아니라 "읽었다는 표시").
//   ⛔ 비율 임계 비교는 0.0~1.0 실수 도메인에서만 한다. 100 을 곱하는 자리는 표시 시점 1곳뿐.
//   ⛔ 옵션 문자열을 조립하거나 복사 버튼을 만들지 않는다 (옵션이 있다는 사실과 되돌리는 길만 문장으로).
//   ⛔ 등장 애니메이션 금지 — 경고가 서서히 나타나면 경고가 늦게 보인다.
import type { ReactNode } from 'react';
import type { InstrumentSummary, SimulationResponse } from '../../types/api';
import { ORPHAN_SEVERE_RATIO, ORPHAN_WARN_RATIO } from '../../lib/instrumentThresholds';
import { formatBytes, formatRatioPercent } from '../../lib/format';
import { formatAnalysisWindow } from '../../lib/time';

interface Props {
  /** 순위 응답의 구간 총계 = **바꾸기 전** 기준값. `12% → 83%` 를 보이려면 필요하다. */
  summary: InstrumentSummary;
  simulation: SimulationResponse;
  /** 고른 대상 가운데 "확인 안 됨" 이 몇 건인가 (T-64 표시 조건). */
  uncertainSelectedCount: number;
  /** [영향을 확인했어요] 를 눌렀는가. */
  acknowledged: boolean;
  onAcknowledge: () => void;
}

export function SimulationResult({
  summary,
  simulation,
  uncertainSelectedCount,
  acknowledged,
  onAcknowledge,
}: Props): ReactNode {
  const { impact, savings, window: win } = simulation;

  // ⚠️ 비율은 실수(0.0~1.0)로만 비교한다. 백분율 숫자(50·80)와 비교하면 경고가 영원히 안 뜬다.
  const warnOrphan = impact.singleSpanTraceRatio > ORPHAN_WARN_RATIO;
  const severeOrphan = impact.singleSpanTraceRatio > ORPHAN_SEVERE_RATIO;
  const needsConfirm = severeOrphan && !acknowledged;

  // trace 수가 오히려 늘어나는 경우 (조상을 빼면 말단이 새 시작점이 되므로 정상 동작이다).
  const traceIncreased = impact.resultTraces > summary.totalTraces;
  // 조상을 빼서 아래 호출이 새 시작점이 됐는가 = 단일 span trace 비율이 기준보다 커졌는가.
  // (선택 안 조상-자손 관계 자체는 응답에 없다 — 그 결과로 실제 관측되는 값으로 판정한다.)
  const ancestorFragmented = impact.singleSpanTraceRatio > summary.singleSpanTraceRatio;

  const orphanPercent = formatRatioPercent(impact.singleSpanTraceRatio);
  const windowText = formatAnalysisWindow(win.fromMs, win.toMs, win.queriedAtMs);

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      {/* ───────────────────────────── ① 경고 ───────────────────────────── */}
      {(warnOrphan || ancestorFragmented) && (
        <div className="space-y-2">
          {/* 약한 경고 (C-13) — 제목 단어 `주의` · amber 틴트 · 확인 절차 없음 */}
          {warnOrphan && !severeOrphan && (
            <div
              role="status"
              className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
            >
              <p>
                <span aria-hidden>⚠ </span>
                {/* T-40 */}
                주의 — 빼고 나면 span 이 하나뿐인 trace 가 약 {orphanPercent} 가 돼요. 목록에서
                흐름을 따라가기 어려워져요.
              </p>
            </div>
          )}

          {/* 강한 경고 (C-14) — 제목 단어 `경고` · 흰 배경 + 빨강 4면 테두리 · 확인 한 단계 */}
          {severeOrphan && (
            <div
              role="alert"
              className="rounded-lg border border-[var(--color-status-error)] bg-white p-4 text-sm text-stone-900"
            >
              <p className="font-medium text-[var(--color-status-error)]">
                <span aria-hidden>⛔ </span>
                {/* T-41 */}
                경고 — 빼고 나면 span 이 하나뿐인 trace 가 약 {orphanPercent} 가 돼요. 지금 쓰는
                trace 목록이 사실상 쓸모없어져요.
              </p>
              {needsConfirm && (
                <div className="mt-3 flex justify-end">
                  {/* T-42 · C-10 — 비활성 조건을 붙이지 않는다. 경고가 아무리 세도 막지 않는다. */}
                  <button
                    type="button"
                    onClick={onAcknowledge}
                    className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700"
                  >
                    영향을 확인했어요
                  </button>
                </div>
              )}
            </div>
          )}

          {/* 조상 제외 경고 (C-28) — 실제로 조각이 늘었을 때만. 항상 뜨면 배경 소음이 된다. */}
          {ancestorFragmented && (
            <p role="status" className="text-xs text-amber-900">
              {/* T-47 */}
              이 대상의 조상을 함께 빼면 아래 호출이 새 시작점이 돼요
            </p>
          )}
        </div>
      )}

      {/* ───────────────────────── ② 부작용 지표 (C-12) ───────────────────────── */}
      {/* 카드 안 카드 금지 — 하위 영역은 구분선으로만 나눈다. */}
      <div className={warnOrphan || ancestorFragmented ? 'mt-4 border-t border-stone-200 pt-4' : ''}>
        {/* T-37 */}
        <h3 className="text-sm font-medium text-stone-900">빼면 이렇게 돼요</h3>
        <div className="mt-3 grid grid-cols-2 gap-4 sm:grid-cols-4">
          {/* T-38 — 라벨 4종. "지금 → 빼고 나면" 을 함께 보여야 그 값이 나쁜 값인지 읽힌다. */}
          <ImpactMetric
            label="남는 span 수"
            before={summary.totalSpans.toLocaleString()}
            after={impact.remainingSpans.toLocaleString()}
          />
          <ImpactMetric
            label="결과 trace 수"
            before={summary.totalTraces.toLocaleString()}
            after={impact.resultTraces.toLocaleString()}
          />
          <ImpactMetric
            label="trace 당 평균 span"
            before={summary.avgSpansPerTrace.toFixed(1)}
            after={impact.avgSpansPerTrace.toFixed(1)}
          />
          <ImpactMetric
            label="span 이 하나뿐인 trace 비율"
            before={formatRatioPercent(summary.singleSpanTraceRatio)}
            after={orphanPercent}
          />
        </div>

        <div className="mt-2 space-y-1 text-xs text-stone-500">
          {/* T-39 (C-27) — 오류가 아니라 재계산 결과다. */}
          {traceIncreased && <p>trace 수는 오히려 늘어날 수 있어요</p>}
          {/* T-24 (C-18) */}
          {simulation.depthCapped && <p>이보다 깊은 흐름은 포함되지 않았어요</p>}
          {/* T-64 (C-26) — 불확실을 고른 만큼은 예측대로 줄지 않을 수 있다. */}
          {uncertainSelectedCount > 0 && (
            <p>
              고른 대상 가운데 {uncertainSelectedCount}건은 실제로 빠지는지 확인되지 않았어요.
              그만큼은 예측대로 줄지 않을 수 있어요.
            </p>
          )}
        </div>
      </div>

      {/* ─────────────────────────── ③ 절감 (C-11) ─────────────────────────── */}
      <div className="mt-4 border-t border-stone-200 pt-4">
        {/* T-34 */}
        <h3 className="text-sm font-medium text-stone-900">예상 절감 (저장 기준)</h3>
        {/* T-35 — 두 축을 한 줄에. 큰 숫자를 화면 가득 키우지 않는다. */}
        <p className="mt-2 text-base font-medium text-stone-900">
          payload 약 {formatBytes(savings.payloadBytesDelta)} 줄어요 · span 약{' '}
          {savings.spanDelta.toLocaleString()}건 줄어요
        </p>
        <div className="mt-1 space-y-1 text-xs text-stone-500">
          {/* T-36 — 필수. 적용 후 단정이 아니라 "이 구간 자료를 다시 흘려보낼 때" 의 값이다. */}
          <p>이 구간의 자료를 그대로 다시 흘려보낼 때의 값이에요.</p>
          {/* T-15 — 절감·부작용 두 블록 모두에 구간·조회 시각을 붙인다. */}
          <p>{windowText}</p>
        </div>
      </div>

      {/* ──────────────────── ④ 적용 전에 알아 두세요 (C-16) ──────────────────── */}
      {!needsConfirm && (
        <div className="mt-4 rounded-lg border border-stone-200 bg-stone-50 p-4">
          {/* T-43 */}
          <h3 className="text-sm font-medium text-stone-900">적용 전에 알아 두세요</h3>
          <ul className="mt-2 space-y-1 text-xs text-stone-500">
            {/* T-44 */}
            <li>· 계측 제외 옵션은 앱을 다시 시작해야 적용돼요.</li>
            {/* T-45 — 되돌리는 길은 필수. */}
            <li>· 되돌리려면 옵션을 빼고 앱을 다시 시작해야 해요.</li>
            {/* T-46 — 실제 버튼 이름으로 안내 (설정 화면의 [지난 데이터 정리]). */}
            <li>
              · 그 사이 쌓인, span 이 하나뿐인 trace 는 보존 기간이 지날 때까지 남아요. 설정 화면의
              [지난 데이터 정리] 로 앞당길 수 있어요.
            </li>
          </ul>
        </div>
      )}
    </section>
  );
}

interface ImpactMetricProps {
  label: string;
  before: string;
  after: string;
}

/** 지표 한 칸 — 라벨 · 지금 값 · 빼고 나면 값. */
function ImpactMetric({ label, before, after }: ImpactMetricProps): ReactNode {
  return (
    <div>
      <p className="text-xs text-stone-500">{label}</p>
      <p className="mt-1 font-mono text-sm text-stone-500">{before}</p>
      <p className="font-mono text-base font-medium text-stone-900">
        <span aria-hidden>→ </span>
        {after}
      </p>
    </div>
  );
}
