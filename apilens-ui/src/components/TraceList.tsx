// 최근 trace 리스트 — 시간 역순 최대 10개.
// status별 시맨틱 칩 (OK/ERR + slow). stone 팔레트로 본문 회색 통일.
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { TraceSummary } from '../types/api';
import { searchAcrossRoutes } from '../lib/routeSearch';
import { statusColorKey } from '../lib/colors';
import { formatHms } from '../lib/time';
import { shortenOperation } from '../lib/format';

interface Props {
  traces: ReadonlyArray<TraceSummary>;
}

const CHIP_CLASS: Record<ReturnType<typeof statusColorKey>, string> = {
  ok:    'bg-stone-100 text-stone-500',
  slow:  'bg-[color:var(--color-status-slow)]/10 text-[color:var(--color-status-slow)]',
  error: 'bg-[color:var(--color-status-error)]/10 text-[color:var(--color-status-error)]',
};

const CHIP_LABEL: Record<ReturnType<typeof statusColorKey>, string> = {
  ok:    'OK',
  slow:  'SLOW',
  error: 'ERR',
};

export function TraceList({ traces }: Props): ReactNode {
  const recent = traces.slice(0, 10);
  // 현재 dashboard 필터(service/live/range)를 trace 상세 URL에도 보존 →
  // 뒤로가기 시 history stack에 search가 살아 있어 자동 복원됨.
  const [searchParams] = useSearchParams();
  const search = searchAcrossRoutes(searchParams);

  return (
    <div className="rounded-lg border border-stone-200 bg-white">
      <div className="flex items-center justify-between border-b border-stone-200 px-4 py-2">
        <h2 className="text-sm font-medium text-stone-900">Recent traces</h2>
        <span className="text-xs text-stone-500">{recent.length} / {traces.length}</span>
      </div>
      <ul className="divide-y divide-stone-200">
        {recent.map((t) => {
          const key = statusColorKey(t);
          return (
            <li key={t.traceId}>
              <Link
                to={{ pathname: `/traces/${t.traceId}`, search }}
                className="flex items-center gap-3 px-4 py-3 hover:bg-stone-50 focus:bg-stone-50 focus:outline-none"
              >
                <span
                  className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium ${CHIP_CLASS[key]}`}
                >
                  {CHIP_LABEL[key]}
                </span>
                <span
                  className="flex-1 truncate text-sm text-stone-900"
                  title={t.rootOperation}
                >
                  {shortenOperation(t.rootOperation)}
                </span>
                <span className="w-16 text-right text-xs tabular-nums text-stone-500">
                  {t.durationMs}ms
                </span>
                <span className="w-20 text-right text-xs tabular-nums text-stone-500">
                  {formatHms(t.startTime)}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
