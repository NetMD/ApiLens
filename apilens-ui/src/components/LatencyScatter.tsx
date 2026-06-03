// Latency 산점도 — Recharts ScatterChart + log scale Y.
// 점 색상은 STATUS_HEX 미러를 통해 시맨틱 컬러와 단일 출처 유지.
//
// NOTE: NFR-06 — 시각화 컴포넌트는 unit test 작성 대상 아님 (사용자 시각 검증 영역).
//
// F2 LOW-1 시맨틱 토큰 정책 적용:
//   - 점 fill 색상은 이미 STATUS_HEX (status-ok / status-error / status-slow) 사용 중.
//   - axis stroke '#888780'은 STATUS_HEX.ok와 우연 일치 — chart 축 색상은 시맨틱 의미 아님 (중성 톤).
//   - grid stroke '#e7e5e4', tooltip cursor '#d6d3d1'은 Tailwind stone-200/stone-300 미러 — 시맨틱 의미 아님.
//   - 따라서 raw hex 직접 사용은 시맨틱 컬러 정책(NFR-05) 위반 아님.
//   - 토큰 치환을 강제하지 않는 사유: 시맨틱 의미가 아닌 위치에 시맨틱 토큰을 넣으면 의미 혼선.
import type { ReactNode } from 'react';
import { useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { searchAcrossRoutes } from '../lib/routeSearch';
import {
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { TraceSummary } from '../types/api';
import { STATUS_HEX, statusColorKey } from '../lib/colors';
import { formatHms } from '../lib/time';

interface Props {
  traces: ReadonlyArray<TraceSummary>;
  /** X축 도메인 = 선택한 시간 윈도우 (epoch ms). dataMin/dataMax 자동 스케일의 출렁임 차단. */
  since: number;
  until: number;
}

interface Point {
  x: number; // startTime (epoch ms)
  y: number; // durationMs
  trace: TraceSummary;
}

// log scale은 0 이하 값 처리 못 함. 0ms 들어오면 최소 1ms로 클램프.
function clampForLog(ms: number): number {
  return ms <= 0 ? 1 : ms;
}

interface TooltipPayloadItem {
  payload?: Point;
}

function CustomTooltip({
  active,
  payload,
}: {
  active?: boolean | undefined;
  payload?: ReadonlyArray<TooltipPayloadItem> | undefined;
}): ReactNode {
  if (!active || !payload || payload.length === 0) return null;
  const first = payload[0];
  if (!first || !first.payload) return null;
  const t = first.payload.trace;
  return (
    <div className="rounded border border-stone-200 bg-white p-2 text-xs text-stone-900 shadow">
      <div className="font-medium">{t.rootOperation}</div>
      <div className="text-stone-500">
        {formatHms(t.startTime)} · {t.durationMs}ms · {t.status}
      </div>
    </div>
  );
}

export function LatencyScatter({ traces, since, until }: Props): ReactNode {
  const navigate = useNavigate();
  // dashboard 필터를 trace 상세로 가져갈 때 보존 — 뒤로가기 시 history 복원 위함.
  const [searchParams] = useSearchParams();

  const points = useMemo<Point[]>(
    () =>
      traces.map((t) => ({
        x: t.startTime,
        y: clampForLog(t.durationMs),
        trace: t,
      })),
    [traces],
  );

  const handleClick = (data: unknown): void => {
    // Recharts onClick은 이벤트 객체를 unknown으로 받아 안전하게 좁힘.
    if (
      typeof data === 'object' &&
      data !== null &&
      'payload' in data &&
      typeof (data as { payload: unknown }).payload === 'object' &&
      (data as { payload: { trace?: TraceSummary } }).payload?.trace
    ) {
      const trace = (data as { payload: { trace: TraceSummary } }).payload.trace;
      navigate({ pathname: `/traces/${trace.traceId}`, search: searchAcrossRoutes(searchParams) });
    }
  };

  return (
    <div
      role="img"
      aria-label="Latency scatter"
      className="h-80 w-full rounded-lg border border-stone-200 bg-white p-3"
    >
      <ResponsiveContainer width="100%" height="100%">
        <ScatterChart margin={{ top: 12, right: 16, bottom: 12, left: 16 }}>
          <CartesianGrid stroke="#e7e5e4" strokeDasharray="3 3" />
          <XAxis
            type="number"
            dataKey="x"
            // 선택한 시간 윈도우로 고정 — dataMin/dataMax 자동 스케일의 rubber-banding 차단.
            // allowDataOverflow: 시계 어긋난 윈도우 밖 trace 가 축을 늘리지 못하게 strict 적용.
            domain={[since, until]}
            allowDataOverflow
            tickFormatter={(v: number) => formatHms(v)}
            stroke="#888780"
            fontSize={11}
          />
          <YAxis
            type="number"
            dataKey="y"
            scale="log"
            domain={[1, 'dataMax']}
            padding={{ top: 16, bottom: 12 }}
            tickFormatter={(v: number) => `${v}ms`}
            allowDataOverflow
            stroke="#888780"
            fontSize={11}
          />
          <Tooltip content={<CustomTooltip />} cursor={{ stroke: '#d6d3d1' }} />
          {/* fillOpacity — 고밀도(초당 수십 trace)에서 점 겹침을 농담으로 드러냄. */}
          <Scatter data={points} onClick={handleClick} fillOpacity={0.7}>
            {points.map((p) => (
              <Cell
                key={p.trace.traceId}
                fill={STATUS_HEX[statusColorKey(p.trace)]}
                cursor="pointer"
              />
            ))}
          </Scatter>
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}
