// 시간 범위 셀렉터.
// [inter-pipeline] 고밀도 운영용 1m / 5m 짧은 프리셋 추가 (총 6).
// 라벨은 영어 (NFR-07).
import type { ReactNode } from 'react';
import type { RangePreset } from '../lib/time';

interface Props {
  value: RangePreset;
  onChange: (next: RangePreset) => void;
}

const OPTIONS: ReadonlyArray<{ value: RangePreset; label: string }> = [
  { value: '1m',  label: 'Last 1 min' },
  { value: '5m',  label: 'Last 5 min' },
  { value: '10m', label: 'Last 10 min' },
  { value: '1h',  label: 'Last 1 hour' },
  { value: '24h', label: 'Last 24 hours' },
  { value: '7d',  label: 'Last 7 days' },
] as const;

export function TimeRangeSelector({ value, onChange }: Props): ReactNode {
  return (
    <div role="group" aria-label="Time range" className="inline-flex rounded-md border border-stone-200 bg-white p-0.5">
      {OPTIONS.map((opt) => {
        const active = opt.value === value;
        return (
          <button
            key={opt.value}
            type="button"
            aria-pressed={active}
            onClick={() => onChange(opt.value)}
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
  );
}
