// Phase R12 (FR-B3, AC-B3-1/2/3) — 마스킹 라이브 프리뷰 (NP-1 / P-R12-1, 설계 §3.2.4).
//
// [R12] D-02 비협상 — "결재용 신뢰 도구". AC-B3-1 verbatim: "POST /v1/masking-rules/preview —
// 샘플 페이로드 + (저장 전 토글 상태가 반영된) 룰 세트 → 마스킹 결과 반환" (비협상).
// 프리뷰 요청 본문에 화면 룰 세트 상태 동봉 — DB persisted 상태 의존 0 (race 원천 차단, UX §5.1).
// 프리뷰 계산은 서버 공유 엔진 (AC-B3-3 — FE 재구현 금지). FE 는 입력 수집/표시만.
//
// 트리거/디바운스 (설계 §2-B3 확정값 — 상수 단일 거주지 lib/constants.ts):
//   진입 즉시 1회 / 토글 200ms / 직접 입력 400ms / 소스 전환·룰 추가·삭제 성공 즉시.
// 취소: 매 요청 직전 prev AbortController.abort() — latest-wins (응답 순서 역전 차단).
// custom 빈 값: 요청 중단 + 결과 비움 (E-05 invalid 요청 사전 회피 — 서버는 최종 거부자).
// 프리뷰는 TanStack 캐시 비대상 — 훅 내 직접 fetch 상태 관리 (UX §9 채택).
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { previewMasking } from '../../api/maskingRules';
import type { PreviewResponse, PreviewRuleState } from '../../types/api';
import { PREVIEW_INPUT_DEBOUNCE_MS, PREVIEW_TOGGLE_DEBOUNCE_MS } from '../../lib/constants';

type SampleSource = 'default' | 'custom';

interface Props {
  /** 화면의 "현재" 토글 상태 전체 스냅샷 (낙관 반영분 — 부분 diff 아님, 설계 §5.4). */
  ruleStates: PreviewRuleState[];
  /** T-23 dirty: 화면 룰 세트 상태 ≠ 마지막 서버 확인 상태 (UX §5.1 정의). */
  dirty: boolean;
}

/** pre 박스 공통 토큰 — PayloadView 본문 박스 동형 (UX §3.4.4). */
const PRE_BOX_CLASS =
  'whitespace-pre-wrap break-all rounded-md border-[0.5px] border-stone-200 bg-white px-3 py-2.5 font-mono text-xs leading-relaxed text-stone-900 min-h-16';

export function MaskingPreview({ ruleStates, dirty }: Props): ReactNode {
  // 샘플 소스/입력 — 전부 useState (URL 키 0건, UX §4). 기본 선택 '기본 샘플'.
  const [sampleSource, setSampleSource] = useState<SampleSource>('default');
  const [customSample, setCustomSample] = useState('');

  const [result, setResult] = useState<PreviewResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  // 직전 트리거 입력 — 어떤 축이 변했는지로 디바운스 ms 를 정한다 (설계 §2-B3 용처별 확정).
  const prevRef = useRef<{
    ruleStatesKey: string;
    idSetKey: string;
    sampleSource: SampleSource;
    customSample: string;
  } | null>(null);

  // 화면 상태 직렬화 키 — effect 의존성 (배열 identity 대신 내용 기준 비교).
  const ruleStatesKey = JSON.stringify(ruleStates);
  const idSetKey = JSON.stringify(ruleStates.map((s) => s.ruleId));

  useEffect(() => {
    // custom 빈 값 — 요청 중단 + 결과 비움 (이전 결과 잔류로 인한 오독 방지, UX §3.4.4).
    if (sampleSource === 'custom' && customSample.trim() === '') {
      abortRef.current?.abort();
      prevRef.current = { ruleStatesKey, idSetKey, sampleSource, customSample };
      setResult(null);
      setLoading(false);
      setFailed(false);
      return;
    }

    // 트리거 축 식별 → 디바운스 ms 결정.
    const prev = prevRef.current;
    let delay = 0; // 진입 즉시 1회 (prev === null)
    if (prev !== null) {
      if (prev.sampleSource !== sampleSource) {
        delay = 0; // 소스 전환 — 즉시
      } else if (prev.customSample !== customSample) {
        delay = PREVIEW_INPUT_DEBOUNCE_MS; // textarea 입력 — 400ms
      } else if (prev.idSetKey !== idSetKey) {
        delay = 0; // 룰 추가/삭제 성공 — 즉시 (목록 invalidate 와 함께)
      } else if (prev.ruleStatesKey !== ruleStatesKey) {
        delay = PREVIEW_TOGGLE_DEBOUNCE_MS; // 토글 — 200ms (연타 시 마지막 상태만)
      } else {
        return; // 입력 무변경 — 재요청 없음
      }
    }
    prevRef.current = { ruleStatesKey, idSetKey, sampleSource, customSample };

    const timer = setTimeout(() => {
      // latest-wins: 직전 요청 취소 후 발사 — 응답 순서 역전에도 결과 = 마지막 화면 상태 (NP-1).
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      // ruleStates 는 직렬화 키에서 복원 — effect 의존성 최소화 (내용 동일 보장).
      const states = JSON.parse(ruleStatesKey) as PreviewRuleState[];
      previewMasking(
        {
          sample: sampleSource === 'custom' ? customSample : null, // null = 서버 내장 기본 샘플 (AC-B3-2)
          contentType: 'application/json',
          ruleStates: states, // 저장 전 화면 상태 동봉 (AC-B3-1 비협상)
        },
        controller.signal,
      )
        .then((res) => {
          setResult(res);
          setFailed(false);
          setLoading(false);
        })
        .catch(() => {
          if (controller.signal.aborted) return; // 취소된 요청 — 무시 (latest-wins)
          setFailed(true);
          setLoading(false);
        });
    }, delay);
    return () => clearTimeout(timer);
  }, [ruleStatesKey, idSetKey, sampleSource, customSample]);

  // 마운트 해제 시 잔여 요청 취소.
  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  const segmentClass = (active: boolean): string =>
    active
      ? 'rounded px-3 py-1.5 text-sm font-medium bg-stone-900 text-white'
      : 'rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900';

  return (
    <div className="space-y-3">
      {/* T-21 */}
      <h3 className="text-sm font-medium text-stone-900">라이브 프리뷰</h3>

      {/* T-22 — 샘플 소스 세그먼트 (TimeRangeSelector 동형). 그룹 aria-label = UX §7.2 발의 #3 */}
      <div
        role="group"
        aria-label="샘플 소스"
        className="inline-flex rounded-md border border-stone-200 bg-white p-0.5"
      >
        <button
          type="button"
          aria-pressed={sampleSource === 'default'}
          onClick={() => setSampleSource('default')}
          className={segmentClass(sampleSource === 'default')}
        >
          기본 샘플
        </button>
        <button
          type="button"
          aria-pressed={sampleSource === 'custom'}
          onClick={() => setSampleSource('custom')}
          className={segmentClass(sampleSource === 'custom')}
        >
          직접 입력
        </button>
      </div>

      {/* 샘플 원문 — 기본 샘플: read-only pre (응답 sample echo = Before 표시, UX §9 요구 ② 채택) /
          직접 입력: textarea (C-06: disabled = false — 항상 입력 가능) */}
      <div>
        <p className="mb-1 text-xs font-medium text-stone-500">샘플 원문</p>
        {sampleSource === 'default' ? (
          <pre className={PRE_BOX_CLASS}>{result?.sample ?? ''}</pre>
        ) : (
          <textarea
            value={customSample}
            onChange={(e) => setCustomSample(e.target.value)}
            placeholder="마스킹을 확인할 샘플 페이로드를 붙여넣어 주세요"
            aria-label="직접 입력"
            className={`${PRE_BOX_CLASS} min-h-32 w-full resize-y placeholder:text-stone-400 focus:outline-none focus:ring-1 focus:ring-stone-900`}
          />
        )}
      </div>

      {/* T-24 — 결과 라벨 + 박스 (aria-live: 토글 시 갱신 announce) */}
      <div>
        <p className="mb-1 text-xs font-medium text-stone-500">마스킹 결과</p>
        {failed ? (
          // T-24 에러 — 결과 박스 자리 대체. 재시도는 다음 토글/입력 변경으로 자연 재요청 (E-05/네트워크 공용)
          <p className="text-sm text-stone-500">
            프리뷰를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.
          </p>
        ) : (
          // C-06 비고 — 결과 영역에서만: 직전 결과 유지 + opacity + aria-busy (깜빡임/레이아웃 점프 0)
          <pre
            aria-live="polite"
            aria-busy={loading || undefined}
            className={loading ? `${PRE_BOX_CLASS} opacity-60` : PRE_BOX_CLASS}
          >
            {result?.masked ?? ''}
          </pre>
        )}
      </div>

      {/* T-23 — dirty 상태에만 노출. 경고색 금지 — 안심시키는 안내문 (UX §3.4.4) */}
      {dirty && (
        <p className="text-xs text-stone-500">저장 전 변경 사항이 프리뷰에 반영되고 있어요.</p>
      )}
    </div>
  );
}
