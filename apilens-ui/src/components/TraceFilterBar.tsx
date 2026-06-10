// Phase R12 (FR-C1/C2, AC-C1-2/AC-C2-3) — Dashboard TraceList 상단 필터 바 (UX §3.5).
//
// [R12] D-03 비협상 — "필터는 status + operation 검색 — duration 필터는 작업 외".
// AC-C1-2 verbatim: "Dashboard TraceList 상단 세그먼트 (전체/OK/ERROR) — '에러만 보기' 1차 시나리오.
// BE param·FE 타입 기존재 (G-20) — Dashboard 가 값을 넘기기만."
//
// 구성:
//   - status 세그먼트 (T-28 전체/OK/ERROR) — TimeRangeSelector 동형 (role="group" + aria-pressed).
//     ERROR 활성에 적색 미사용 — 기존 세그먼트와 동일한 중성 활성색 (UX §3.5 색 과잉 회피).
//   - operation 검색 input (T-29) — 디바운스 300ms (SEARCH_DEBOUNCE_MS) + IME compositionend 후
//     시작 (NP-3 — 조합 중 URL 미갱신) + maxLength 200 + 클리어 × 버튼 (UX §7.2 발의 #2).
//   - LIKE escape 는 BE 단일 책임 (E-07) — FE 에서 이스케이프 금지 (이중 처리 방지, UX §8.4).
//
// 노출 조건은 Dashboard 가 결정 (service !== null — 로딩/에러/0건 분기에서도 유지, 필터 해제 경로 보장).
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { StatusFilter } from '../hooks/useDashboardState';
import { OPERATION_QUERY_MAX_LENGTH, SEARCH_DEBOUNCE_MS } from '../lib/constants';

interface Props {
  status: StatusFilter | null;
  q: string;
  onStatusChange: (next: StatusFilter | null) => void;
  onQChange: (next: string) => void;
}

/** T-28 세그먼트 옵션 — 전체 = null (키 부재), OK/ERROR 는 데이터 값 표기 톤. */
const STATUS_OPTIONS: ReadonlyArray<{ value: StatusFilter | null; label: string }> = [
  { value: null, label: '전체' },
  { value: 'OK', label: 'OK' },
  { value: 'ERROR', label: 'ERROR' },
];

export function TraceFilterBar({ status, q, onStatusChange, onQChange }: Props): ReactNode {
  // 검색 input 로컬 상태 — 디바운스 전 중간값은 URL 미반영 (NP-3).
  const [input, setInput] = useState(q);
  // IME 조합 중 플래그 — 조합 중 URL 갱신 금지, compositionend 후 디바운스 시작 (NP-3 한글 필수 처리).
  const [composing, setComposing] = useState(false);
  // 마지막으로 본 훅이 URL 에 push 한 값 — 외부 변경(뒤로가기/링크 진입)과 자기 echo 구분.
  const lastPushedRef = useRef(q);

  // URL → input 역방향 동기 (뒤로가기/공유 링크 복원). 자기 push echo 는 무시 — 타이핑 역행 방지.
  useEffect(() => {
    if (q !== lastPushedRef.current) {
      lastPushedRef.current = q;
      setInput(q);
    }
  }, [q]);

  // 디바운스: input 변경 후 SEARCH_DEBOUNCE_MS 경과 시 URL push. 조합 중에는 시작하지 않음.
  useEffect(() => {
    if (composing) return;
    const timer = setTimeout(() => {
      // trim 후 '' → 키 제거 (setQ 내부에서 처리 — AC-C2-3).
      const next = input.trim() === '' ? '' : input;
      if (next !== lastPushedRef.current) {
        lastPushedRef.current = next;
        onQChange(next);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [input, composing, onQChange]);

  const clearSearch = (): void => {
    setInput('');
    lastPushedRef.current = '';
    onQChange(''); // 클리어는 즉시 반영 (디바운스 없음 — UX §7.2 발의 #2)
  };

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      {/* status 세그먼트 — TimeRangeSelector 동형. 그룹 aria-label = UX §7.2 발의 #3 'Status filter'. */}
      <div
        role="group"
        aria-label="Status filter"
        className="inline-flex rounded-md border border-stone-200 bg-white p-0.5"
      >
        {STATUS_OPTIONS.map((opt) => {
          const active = opt.value === status;
          return (
            <button
              key={opt.label}
              type="button"
              aria-pressed={active}
              onClick={() => onStatusChange(opt.value)}
              className={
                active
                  ? 'rounded px-3 py-1.5 text-sm font-medium bg-stone-900 text-white'
                  : 'rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900'
              }
            >
              {opt.label}
            </button>
          );
        })}
      </div>

      {/* operation 검색 input — placeholder T-29 + aria-label (발의 #3, placeholder 단독 금지). */}
      <div className="relative">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onCompositionStart={() => setComposing(true)}
          onCompositionEnd={(e) => {
            // 조합 확정 후 디바운스 시작 — 확정 문자열로 input 동기.
            setComposing(false);
            setInput(e.currentTarget.value);
          }}
          maxLength={OPERATION_QUERY_MAX_LENGTH}
          placeholder="operation 검색 (전체 경로 기준)"
          aria-label="operation 검색"
          className="w-72 rounded-md border border-stone-200 bg-white px-3 py-1.5 pr-8 text-sm text-stone-900 placeholder:text-stone-400 focus:outline-none focus:ring-1 focus:ring-stone-900"
        />
        {input !== '' && (
          <button
            type="button"
            onClick={clearSearch}
            aria-label="검색어 지우기"
            className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded px-1 text-sm text-stone-400 hover:text-stone-900"
          >
            ×
          </button>
        )}
      </div>
    </div>
  );
}
