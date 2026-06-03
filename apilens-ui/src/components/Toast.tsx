// [Phase H] SH-20 — 자체 Toast 컴포넌트 (외부 라이브러리 0).
//
// 우측 상단 fixed (top-4 right-4) + 3초 자동 닫힘. F1/F2 톤 (회색 / mono / 단순함) 유지.
// Provider + useToast() API. success / error 톤 분리.
import { useCallback, useState } from 'react';
import type { ReactNode } from 'react';
import { ToastContext } from './toast-context';
import type { ToastApi, ToastItem, ToastKind } from './toast-context';

export type { ToastKind } from './toast-context';

/** SH-20 자동 닫힘 3초. */
const AUTO_DISMISS_MS = 3000;

export function ToastProvider({ children }: { children: ReactNode }): ReactNode {
  const [items, setItems] = useState<ToastItem[]>([]);

  const remove = useCallback((id: number) => {
    setItems((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, message: string) => {
      const id = Date.now() + Math.random();
      setItems((prev) => [...prev, { id, kind, message }]);
      window.setTimeout(() => remove(id), AUTO_DISMISS_MS);
    },
    [remove],
  );

  const api: ToastApi = {
    success: (m) => push('success', m),
    error: (m) => push('error', m),
  };

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastViewport items={items} onClose={remove} />
    </ToastContext.Provider>
  );
}

function ToastViewport({
  items,
  onClose,
}: {
  items: ToastItem[];
  onClose: (id: number) => void;
}): ReactNode {
  if (items.length === 0) return null;
  return (
    <div
      // SH-20: fixed top-4 right-4. ARIA live region 으로 스크린리더 인지.
      className="pointer-events-none fixed top-4 right-4 z-50 flex flex-col gap-2"
      role="region"
      aria-label="Notifications"
    >
      {items.map((t) => (
        <Toast key={t.id} item={t} onClose={() => onClose(t.id)} />
      ))}
    </div>
  );
}

function Toast({ item, onClose }: { item: ToastItem; onClose: () => void }): ReactNode {
  const { kind, message } = item;
  // success / error 색 분리. error 는 status-error hex 사용.
  const className =
    kind === 'success'
      ? 'pointer-events-auto rounded-md bg-stone-900 px-4 py-2 text-sm text-white shadow-lg'
      : 'pointer-events-auto rounded-md bg-[#E24B4A] px-4 py-2 text-sm text-white shadow-lg';
  return (
    <div
      role={kind === 'error' ? 'alert' : 'status'}
      aria-live={kind === 'error' ? 'assertive' : 'polite'}
      className={className}
    >
      <div className="flex items-start gap-3">
        <span className="flex-1 whitespace-pre-line">{message}</span>
        <button
          type="button"
          aria-label="Close"
          onClick={onClose}
          className="-mt-0.5 text-white/80 hover:text-white"
        >
          ×
        </button>
      </div>
    </div>
  );
}

