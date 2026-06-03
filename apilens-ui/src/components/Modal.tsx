// [Phase H] SH-05 / SH-09 — 표준 Modal 컴포넌트.
//
// a11y 5종 의무:
//   1. role="dialog"
//   2. aria-modal="true"
//   3. aria-labelledby (제목 id 연결)
//   4. focus trap (useFocusTrap)
//   5. backdrop click → onClose
//
// SH-09: 첫 focus = 사용자가 의도하지 않은 Enter 로 액션 실행 방지 → initialFocusRef 권장 (취소 버튼).
// backdrop: bg-stone-900/60 (글래스모피즘 금지). 모달 박스: bg-white border rounded-lg.
import { useEffect, useId } from 'react';
import type { ReactNode, RefObject } from 'react';
import { useFocusTrap } from '../hooks/useFocusTrap';

export interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** SH-09 — 모달 열림 시 첫 focus 대상 (권장: [취소] 버튼). */
  initialFocusRef?: RefObject<HTMLElement | null>;
}

export function Modal({
  open,
  onClose,
  title,
  children,
  initialFocusRef,
}: ModalProps): ReactNode {
  const titleId = useId();
  const focusTrapOptions = initialFocusRef
    ? { active: open, onEscape: onClose, initialFocusRef }
    : { active: open, onEscape: onClose };
  const containerRef = useFocusTrap<HTMLDivElement>(focusTrapOptions);

  // body 스크롤 잠금 — 모달 열림 동안 배경 스크롤 방지.
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  if (!open) return null;

  return (
    <div
      // SH-05 #5 — backdrop click → onClose
      className="fixed inset-0 z-40 flex items-center justify-center bg-stone-900/60 p-4"
      onMouseDown={(e) => {
        // backdrop 자체 클릭만 닫기. 모달 내부 mousedown 은 stopPropagation.
        if (e.target === e.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={containerRef}
        // SH-05 #1~#4
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-lg border border-stone-200 bg-white p-6 shadow-xl"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 id={titleId} className="text-base font-medium text-stone-900">
          {title}
        </h2>
        <div className="mt-3 text-sm text-stone-500">{children}</div>
      </div>
    </div>
  );
}
