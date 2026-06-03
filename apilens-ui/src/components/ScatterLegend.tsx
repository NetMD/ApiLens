// ScatterLegend — Latency 산점도 바로 아래에 표시되는 색상 범례.
//   ● OK   ● slow   ● error                       click a dot to open trace
//
// mockup 박제 (docs/mockups/apilens_dashboard_latency_scatter.html line 40~45) —
// scatter 우상단 안내 + 좌측 OK/slow/error 분류. TraceGraph/Legend 와 동일한
// inline hex style 패턴(NFR-05 미러).
import type { ReactNode } from 'react';
import { STATUS_HEX } from '../lib/colors';

interface DotProps {
  color: string;
  label: string;
}

function Dot({ color, label }: DotProps): ReactNode {
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

export function ScatterLegend(): ReactNode {
  return (
    <div className="flex flex-wrap items-center gap-4 px-3 text-xs text-stone-500">
      <Dot color={STATUS_HEX.ok} label="OK" />
      <Dot color={STATUS_HEX.slow} label="slow" />
      <Dot color={STATUS_HEX.error} label="error" />
      <span className="ml-auto">click a dot to open trace</span>
    </div>
  );
}
