// [Phase H] SH-05 / SH-09 — Modal a11y 5종 + focus trap 표준 훅.
//
// 사용처: Modal.tsx (skip confirm + 삭제 confirm 공통).
// 기능:
//   1. 모달 열림 시 첫 focusable element 또는 initialFocus 로 포커스 이동
//   2. Tab / Shift+Tab 모달 안에서만 순환 (focus trap)
//   3. ESC → onClose 호출
//   4. 모달 닫힘 시 이전 active element 로 포커스 복원
//
// 사용자 명시 비협상 결정 — impeccable a11y.
import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface UseFocusTrapOptions {
  /** trap 활성화 여부 (모달 open prop 과 연결). */
  active: boolean;
  /** ESC 키 누름 시 호출. */
  onEscape?: () => void;
  /** 열림 시 첫 focus 대상. 미지정 시 첫 focusable element. SH-09 권장: [취소] 버튼. */
  initialFocusRef?: RefObject<HTMLElement | null>;
}

/**
 * Focus trap + ESC 처리 표준 훅. 반환되는 ref 를 trap 컨테이너 element 에 연결한다.
 */
export function useFocusTrap<T extends HTMLElement>(
  options: UseFocusTrapOptions,
): RefObject<T | null> {
  const { active, onEscape, initialFocusRef } = options;
  const containerRef = useRef<T | null>(null);
  const previousActiveRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!active) return;

    // 이전 active element 보존 (모달 닫힘 시 복원).
    previousActiveRef.current = document.activeElement as HTMLElement | null;

    // 첫 focus 이동 — 다음 tick 에 (모달 render 완료 후).
    const focusFirst = (): void => {
      const initial = initialFocusRef?.current;
      if (initial) {
        initial.focus();
        return;
      }
      const container = containerRef.current;
      if (!container) return;
      const focusables = container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR);
      const firstFocusable = focusables[0];
      if (firstFocusable) {
        firstFocusable.focus();
      }
    };
    const tid = window.setTimeout(focusFirst, 0);

    const handleKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        if (onEscape) onEscape();
        return;
      }
      if (e.key !== 'Tab') return;

      const container = containerRef.current;
      if (!container) return;
      const focusables = Array.from(
        container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      );
      if (focusables.length === 0) {
        e.preventDefault();
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (!first || !last) {
        e.preventDefault();
        return;
      }
      const activeEl = document.activeElement as HTMLElement | null;

      if (e.shiftKey) {
        if (activeEl === first || !container.contains(activeEl)) {
          e.preventDefault();
          last.focus();
        }
      } else {
        if (activeEl === last || !container.contains(activeEl)) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener('keydown', handleKeyDown);

    return () => {
      window.clearTimeout(tid);
      document.removeEventListener('keydown', handleKeyDown);
      // 이전 active element 로 포커스 복원 (모달 닫힘 시).
      const prev = previousActiveRef.current;
      if (prev && typeof prev.focus === 'function') {
        prev.focus();
      }
    };
  }, [active, onEscape, initialFocusRef]);

  return containerRef;
}
