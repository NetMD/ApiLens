// Legend — 그래프 하단 가로 색상 범례 + size 안내 라벨.
//
// raw hex 직접 사용 영역 (설계서 §3.3 박제):
//   inline style={{ background: hex }} — Tailwind 클래스가 SVG attribute hex 와
//   동일 hex 인지 강제 시각 일치를 위해 raw hex lookup. SpanNode 의
//   SPAN_KIND_FILL_HEX 와 hex 일치 필수 (NFR-05 미러).
//
// 순서 (mockup 박제, AC-06): browser → service → db → external.
// "왼쪽=시작점(브라우저), 오른쪽=DB/외부 API" 의 수평 시간 흐름 (CLAUDE.md 박제) 부합.
//
// 미니맵 추가 안 함 (FR-07, AC-06-5).
import type { ReactNode } from 'react';

interface LegendDotProps {
  color: string;
  label: string;
}

function LegendDot({ color, label }: LegendDotProps): ReactNode {
  return (
    <span className="inline-flex items-center gap-1">
      <span
        className="inline-block h-1.5 w-1.5 rounded-full"
        style={{ background: color }}
      />
      {label}
    </span>
  );
}

export function Legend(): ReactNode {
  return (
    <div className="flex flex-wrap items-center gap-3.5 px-4 py-1 text-xs text-stone-500">
      <LegendDot color="#7F77DD" label="browser" />
      <LegendDot color="#1D9E75" label="service" />
      <LegendDot color="#378ADD" label="db" />
      <LegendDot color="#EF9F27" label="external" />
      <span className="ml-auto">node size = duration</span>
    </div>
  );
}
