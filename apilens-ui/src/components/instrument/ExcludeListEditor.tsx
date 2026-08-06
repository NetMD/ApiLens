// [R21] 목록 편집기 — gateExcludes(설정 화면) 와 exclude-packages(생성기) 가 검증 규칙만
// props 로 달리해 **공유**하는 컴포넌트 (UX §12-3 — 이름 변경 시 침묵 교체 금지).
//
// R21/AC-02-9 (Plan verbatim): "입력 검증은 서버 규칙 그대로 선반영 — 서비스명 200자·목록 100개·
// 항목 512자·항목 안 콤마 금지 (G-18). 서버 400 문구와 화면 안내 문구가 어긋나지 않는다."
//   → gateExcludes 쪽: maxItems=100 · maxItemLength=512 · 콤마 금지.
//   → 생성기 쪽: 콤마 금지 + trim 만 — 개수·길이 상한 없음 (서버 검증 없는 로컬 조립이라
//     규칙이 다른 것이 **의도** — UX §4.6·설계 §2.5).
// R21/AC-02-10 (Plan verbatim): "gateExcludes 는 FQCN(클래스 전체 이름) 보존 — 줄여 보여주되
// 저장·전송 값은 전체 이름, 호버 시 원본 복원 (불변식 13 / NFR-13)." — 표시만 CSS truncate +
// title 복원 (T-12). 축약 규칙을 발명하지 않는다 — 표시 문자열 = 저장 문자열.
import { useId, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';

interface Props {
  /** 현재 항목 목록 (부모 소유 — 저장·조립 값은 언제나 이 원본 전체 이름). */
  items: string[];
  onItemsChange: (next: string[]) => void;
  /** 입력·[추가] 잠금 (C-03 의 configLoading || mutating 몫 — 상한 도달 잠금은 내부 판정). */
  disabled: boolean;
  /** 항목 개별 [삭제] 잠금 (C-04 — mutating 만. 검증 위반 중에도 삭제는 가능해야 한다). */
  removeDisabled: boolean;
  /** 입력 placeholder. */
  placeholder: string;
  /** 입력 접근성 라벨. */
  inputLabel: string;
  /** 목록 개수 상한 — 미지정 시 상한 없음 (생성기). */
  maxItems?: number;
  /** 항목 길이 상한(자) — 미지정 시 상한 없음 (생성기). */
  maxItemLength?: number;
  /** T-13 상시 카운터 줄 표시 여부 (gateExcludes 만 — "n / 100개 · 항목당 512자 이내 · …"). */
  showCounterLine?: boolean;
  /** 입력 검증 오류 존재 여부를 부모에 배선 (canSave 의 validationErrors — gateExcludes 만). */
  onInputErrorChange?: (hasError: boolean) => void;
}

export function ExcludeListEditor({
  items,
  onItemsChange,
  disabled,
  removeDisabled,
  placeholder,
  inputLabel,
  maxItems,
  maxItemLength,
  showCounterLine = false,
  onInputErrorChange,
}: Props): ReactNode {
  const inputId = useId();
  const errorId = useId();
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);

  const atCapacity = maxItems !== undefined && items.length >= maxItems;

  const updateError = (next: string | null): void => {
    setError(next);
    onInputErrorChange?.(next !== null);
  };

  /** 추가 시점 검증 — 위반 시 목록 불변 + 인라인 오류 (T-13 문구 확정값 그대로). */
  const validate = (value: string): string | null => {
    if (value.includes(',')) {
      // V — 서버 400 문구와 동일 ("항목에는 콤마를 쓸 수 없습니다").
      return '항목에는 콤마를 쓸 수 없습니다';
    }
    if (maxItemLength !== undefined && value.length > maxItemLength) {
      return `항목은 ${maxItemLength}자 이내여야 해요.`;
    }
    if (items.includes(value)) {
      return '이미 목록에 있어요.'; // U-38 (표시층 편의 — 서버 규칙 아님)
    }
    return null;
  };

  const handleAdd = (): void => {
    const value = draft.trim(); // trim 후 저장 (B-17)
    if (value === '') return; // 빈 입력은 무시 (오류 아님 — Enter 실수 흡수)
    const violation = validate(value);
    if (violation !== null) {
      updateError(violation);
      return;
    }
    onItemsChange([...items, value]);
    setDraft('');
    updateError(null);
  };

  const handleRemove = (target: string): void => {
    onItemsChange(items.filter((item) => item !== target));
  };

  // Enter 지원 (Setup onEnter 전례).
  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>): void => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleAdd();
    }
  };

  // blur 시점에도 위반을 미리 알린다 (UX §4.4 — "blur/추가 시점 표시"). 목록은 불변.
  const handleBlur = (): void => {
    const value = draft.trim();
    if (value === '') return;
    updateError(validate(value));
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <label htmlFor={inputId} className="sr-only">
          {inputLabel}
        </label>
        <input
          id={inputId}
          type="text"
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            if (error !== null) updateError(null); // 입력을 고치기 시작하면 오류 해제
          }}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          placeholder={placeholder}
          disabled={disabled || atCapacity}
          aria-invalid={error !== null}
          aria-describedby={error !== null ? errorId : undefined}
          className="w-full max-w-md rounded-md border border-stone-200 px-3 py-1.5 font-mono text-sm text-stone-900 placeholder:font-sans placeholder:text-stone-400 focus:border-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900 disabled:bg-stone-50 disabled:opacity-50"
        />
        <button
          type="button"
          onClick={handleAdd}
          disabled={disabled || atCapacity}
          className="shrink-0 rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
        >
          추가
        </button>
      </div>
      {/* 검증 오류 — role=alert 인라인 (UX §8). */}
      {error !== null && (
        <p id={errorId} role="alert" className="text-xs text-[var(--color-status-error)]">
          {error}
        </p>
      )}
      {atCapacity && (
        // T-13 — 100개 도달 시 추가 입력·버튼 비활성 + 안내.
        <p role="status" className="text-xs text-stone-500">
          최대 {maxItems}개까지예요.
        </p>
      )}
      {items.length > 0 && (
        <ul className="space-y-1">
          {items.map((item) => (
            <li
              key={item}
              className="flex items-center justify-between gap-2 rounded border border-stone-200 bg-stone-50 px-2 py-1"
            >
              {/* T-12 — FQCN 전체 표시, 넘치면 말줄임 + title 호버 복원. 저장 값은 항상 전체 이름. */}
              <span title={item} className="min-w-0 truncate font-mono text-xs text-stone-900">
                {item}
              </span>
              <button
                type="button"
                onClick={() => handleRemove(item)}
                disabled={removeDisabled}
                aria-label={`${item} 삭제`}
                className="shrink-0 rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-[var(--color-status-error)] disabled:cursor-not-allowed disabled:opacity-50"
              >
                삭제
              </button>
            </li>
          ))}
        </ul>
      )}
      {showCounterLine && maxItems !== undefined && maxItemLength !== undefined && (
        // T-13 — 상시 카운터 줄.
        <p className="text-xs text-stone-500">
          {items.length} / {maxItems}개 · 항목당 {maxItemLength}자 이내 · 콤마는 쓸 수 없어요
        </p>
      )}
    </div>
  );
}
