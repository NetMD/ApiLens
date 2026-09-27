// ScatterLegend — Latency 산점도 바로 아래에 표시되는 색상 범례.
//   ● OK   ● slow   ● error   (한도 알림)          click a dot to open trace
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

/** [R27/UB-04] 한도 알림 — `shown` 이 거짓이면 글자만 숨기고 자리는 잡는다(줄 높이 불변 · UXD-07). */
export interface ScatterLimitNotice {
  text: string;
  shown: boolean;
}

interface Props {
  /** 5m·10m Live 에서만 넘어온다. 없으면 자리도 없다. */
  limitNotice?: ScatterLimitNotice | undefined;
}

export function ScatterLegend({ limitNotice }: Props = {}): ReactNode {
  return (
    <div className="flex flex-wrap items-center gap-4 px-3 text-xs text-stone-500">
      <Dot color={STATUS_HEX.ok} label="OK" />
      <Dot color={STATUS_HEX.slow} label="slow" />
      <Dot color={STATUS_HEX.error} label="error" />
      {/* [R27/UXD-07] live 영역을 두지 않는다 — 5초마다 뜨고 지면 읽기가 시끄럽다(UX §6.1). */}
      {limitNotice !== undefined && (
        <span
          data-testid="chart-limit-notice"
          aria-hidden={limitNotice.shown ? undefined : true}
          style={{ visibility: limitNotice.shown ? 'visible' : 'hidden' }}
        >
          {limitNotice.text}
        </span>
      )}
      <span className="ml-auto">click a dot to open trace</span>
    </div>
  );
}
