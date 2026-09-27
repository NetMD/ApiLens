// [v0.8.0] 차트 일시 정지 — 흐르는 차트(Live ON)와 한 칸 넘김(Live OFF)을 함께 멈추는 버튼.
// 사용자 결정(2026-09-27): 이름은 「차트 일시 정지」, 다시 누르면 지금 시각으로 돌아가 그동안 들어온 요청을 모두 보여 준다.
// 「수신 일시정지」(수집기가 데이터 받기를 멈추는 관리 기능)와는 다른 기능이다 — 이 버튼은 화면만 멈춘다.
import type { ReactNode } from 'react';

interface Props {
  value: boolean;
  onChange: (next: boolean) => void;
}

export function ChartPauseButton({ value, onChange }: Props): ReactNode {
  return (
    <button
      type="button"
      aria-pressed={value}
      onClick={() => onChange(!value)}
      title={value ? '다시 누르면 지금 시각으로 돌아가요' : '차트를 지금 모습으로 멈춰요'}
      className={
        value
          ? 'inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-md border border-stone-200 bg-stone-900 px-3 py-1.5 text-sm font-medium text-white'
          : 'inline-flex shrink-0 items-center gap-2 whitespace-nowrap rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-500 hover:text-stone-900'
      }
    >
      {/* 두 막대 = 일시 정지 표시(눌린 동안은 재생 삼각형) */}
      <svg aria-hidden width="10" height="10" viewBox="0 0 10 10" className="shrink-0">
        {value ? (
          <path d="M2 1 L9 5 L2 9 Z" fill="currentColor" />
        ) : (
          <>
            <rect x="1.5" y="1" width="2.5" height="8" fill="currentColor" />
            <rect x="6" y="1" width="2.5" height="8" fill="currentColor" />
          </>
        )}
      </svg>
      차트 일시 정지
    </button>
  );
}
