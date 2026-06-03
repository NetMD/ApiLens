// [Phase H] U1 — Setup wizard 4단계 stepper UI.
//
// UX (§3.1): 활성 점은 bg-stone-900, 미진입은 bg-stone-200. 연결선 h-px bg-stone-200.
// 라벨: 활성 text-stone-900 font-medium, 미진입 text-stone-500.
import type { ReactNode } from 'react';

interface Props {
  /** 현재 step (1-based). */
  current: number;
  /** 단계 라벨 (예: ["Server URL", "Service Name", "Capture Options", "JVM 옵션"]). */
  steps: ReadonlyArray<string>;
}

export function Stepper({ current, steps }: Props): ReactNode {
  return (
    <ol
      className="mx-auto flex w-full max-w-2xl items-center justify-between"
      aria-label="Setup steps"
    >
      {steps.map((label, idx) => {
        const stepNum = idx + 1;
        const isActive = stepNum === current;
        const isDone = stepNum < current;
        const isLast = stepNum === steps.length;
        const dotClass = isActive
          ? 'bg-stone-900'
          : isDone
            ? 'bg-stone-700'
            : 'bg-stone-200';
        const labelClass = isActive
          ? 'mt-2 text-xs font-medium text-stone-900'
          : 'mt-2 text-xs text-stone-500';
        return (
          <li
            key={label}
            className="flex flex-1 items-center"
            aria-current={isActive ? 'step' : undefined}
          >
            <div className="flex flex-col items-center">
              <span
                aria-hidden
                className={`inline-flex h-3 w-3 items-center justify-center rounded-full ${dotClass}`}
              />
              <span className={labelClass}>
                Step {stepNum} · {label}
              </span>
            </div>
            {!isLast && (
              <span aria-hidden className="mx-2 h-px flex-1 bg-stone-200" />
            )}
          </li>
        );
      })}
    </ol>
  );
}
