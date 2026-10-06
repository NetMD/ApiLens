// 시간 범위 셀렉터.
// [inter-pipeline] 고밀도 운영용 1m / 5m 짧은 프리셋 추가 (총 6).
// 라벨은 영어 (NFR-07).
// [R28/UXD-32 · UC-R28-15] 보이는 글자에서 「Last 」를 뺀다(UQ-R28-18 답 전 기본값 = 뺌). 접근 이름 = 보이는 글자 그대로
//   (aria-label 을 달지 않는다 · 무리 이름 `Time range` 가 「Last」의 뜻을 진다). 거절이면 글자와 시험 두 줄을 함께 되돌린다.
// [R28/UXD-35] 무리는 한 덩이(shrink-0) · 단추 글자 줄바꿈 막기(whitespace-nowrap) — 헤더 접힘 장치와 짝으로만 넣는다.
import type { ReactNode } from 'react';
import type { RangePreset } from '../lib/time';

interface Props {
  value: RangePreset;
  onChange: (next: RangePreset) => void;
}

const OPTIONS: ReadonlyArray<{ value: RangePreset; label: string }> = [
  { value: '1m',  label: '1 min' },
  { value: '5m',  label: '5 min' },
  { value: '10m', label: '10 min' },
  { value: '1h',  label: '1 hour' },
  { value: '24h', label: '24 hours' },
  { value: '7d',  label: '7 days' },
] as const;

export function TimeRangeSelector({ value, onChange }: Props): ReactNode {
  return (
    <div role="group" aria-label="Time range" className="inline-flex shrink-0 rounded-md border border-stone-200 bg-white p-0.5">
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
                ? 'whitespace-nowrap rounded px-3 py-1.5 text-sm font-medium bg-stone-900 text-white'
                : 'whitespace-nowrap rounded px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900'
            }
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
