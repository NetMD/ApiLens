// [Phase H] U1 Step 3 — row 안 토글 컴포넌트 (LiveToggle 와 분리).
//
// UX (§3.4): row 안 작은 switch + 라벨 + 부연 설명. row 보더 border-t border-stone-200.
// 활성 = 우측 + bg-stone-900. role="switch" + aria-checked + Space/Enter 키 지원.
import type { ReactNode } from 'react';

interface Props {
  label: string;
  description?: string;
  checked: boolean;
  onChange: (next: boolean) => void;
  /** ID — htmlFor 연결용. 미지정 시 자동 생성 안 함, 외부에서 줄 것. */
  id?: string;
}

export function Toggle({ label, description, checked, onChange, id }: Props): ReactNode {
  return (
    <div className="flex items-start justify-between gap-4 py-3">
      <div className="flex-1">
        <label
          htmlFor={id}
          className="text-sm font-medium text-stone-900"
        >
          {label}
        </label>
        {description !== undefined && (
          <p className="mt-1 text-xs text-stone-500">{description}</p>
        )}
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={
          checked
            ? 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full bg-stone-900 transition-colors focus:outline-none focus:ring-2 focus:ring-stone-900 focus:ring-offset-2'
            : 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full bg-stone-200 transition-colors focus:outline-none focus:ring-2 focus:ring-stone-900 focus:ring-offset-2'
        }
      >
        <span
          aria-hidden
          className={
            checked
              ? 'inline-block h-5 w-5 translate-x-5 transform rounded-full bg-white shadow transition-transform'
              : 'inline-block h-5 w-5 translate-x-0.5 transform rounded-full bg-white shadow transition-transform'
          }
          style={{ marginTop: 2 }}
        />
      </button>
    </div>
  );
}
