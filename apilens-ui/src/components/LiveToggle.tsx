// Live 토글. ON 시 5초마다 refetch. OFF=스냅샷.
import type { ReactNode } from 'react';

interface Props {
  value: boolean;
  onChange: (next: boolean) => void;
}

export function LiveToggle({ value, onChange }: Props): ReactNode {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={value}
      aria-label="Live refresh"
      onClick={() => onChange(!value)}
      className={
        value
          ? 'inline-flex items-center gap-2 rounded-md border border-stone-200 bg-stone-900 px-3 py-1.5 text-sm font-medium text-white'
          : 'inline-flex items-center gap-2 rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900'
      }
    >
      <span
        aria-hidden
        className={
          value
            ? 'inline-block h-2 w-2 rounded-full bg-white'
            : 'inline-block h-2 w-2 rounded-full bg-stone-500'
        }
      />
      Live
    </button>
  );
}
