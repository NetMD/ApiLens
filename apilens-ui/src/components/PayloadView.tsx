// PayloadView — span 선택 시 IN/OUT payload lazy load.
//
// 데이터 페치 (BL-07):
//   queryKey ['payloads', traceId, spanId]
//   staleTime 60_000  → 같은 span 두 번 토글 시 첫 1회만 fetch (TanStack Query cache hit).
//   enabled !!spanId
//
// 표시 정책:
//   - IN 펼침 / OUT 접힘 (default)
//   - contentType이 application/json 또는 *+json → formatJsonPretty
//   - body.length > 5120 → truncateBody + "더 보기" 토글
//   - payload truncated === true → 회색 "truncated" 배지
//   - payloads 빈 / 해당 direction 없음 → "No payload"
//   - fetch 실패 → 인라인 "payload를 불러올 수 없습니다." (BL-08, 페이지 다른 영역 정상)
//
// BE 본문 노출 정책 (NFR-07): error.message 사용자에게 노출 0건. 인라인 메시지 고정.
import type { ReactNode } from 'react';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { Payload, PayloadDirection } from '../types/api';
import { fetchPayloads } from '../api/traces';
import { formatJsonPretty, truncateBody } from '../lib/format';

interface Props {
  traceId: string;
  spanId: string;
}

/** application/json 계열 contentType 판단. */
function isJsonContentType(ct: string | null): boolean {
  if (!ct) return false;
  return ct.startsWith('application/json') || /\+json($|;)/.test(ct);
}

interface SectionProps {
  payload: Payload | undefined;
  direction: PayloadDirection;
  defaultOpen: boolean;
}

function PayloadSection({ payload, direction, defaultOpen }: SectionProps): ReactNode {
  const [open, setOpen] = useState(defaultOpen);
  const [expanded, setExpanded] = useState(false);

  // F2 fix (§9 mockup 박제):
  //   라벨 uppercase + tracking-wider + text-stone-500 + 6px gap (mb-1.5)
  //   payload 본문은 흰 배경 + 0.5px stone-200 테두리 + rounded-md
  //   placeholder: direction 별로 (no request body) / (no response body)
  const label = direction === 'in' ? 'PAYLOAD IN' : 'PAYLOAD OUT';
  const placeholderText =
    direction === 'in' ? '(no request body)' : '(no response body)';

  if (!payload) {
    return (
      <details
        open={open}
        onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}
      >
        <summary className="mb-1.5 flex cursor-pointer list-none items-center gap-1 text-xs uppercase tracking-wider text-stone-500 [&::-webkit-details-marker]:hidden">
          <span aria-hidden="true" className="text-[10px] text-stone-400">{open ? '▼' : '▶'}</span>
          {label}
        </summary>
        {/*
         * Phase F2 fix² (US-04, AC-04-1/2/3): F2 fix 박제 토큰 보존 + italic 1축만 추가.
         * 텍스트 `(no request body)` / `(no response body)` 영문 유지 (AC-04-2).
         * OUT 존재 span 에서는 이 placeholder 분기 자체가 도달 안 됨 (AC-04-3 보존).
         */}
        <p className="mt-1.5 text-xs italic text-stone-400">{placeholderText}</p>
      </details>
    );
  }

  const pretty = isJsonContentType(payload.contentType)
    ? formatJsonPretty(payload.body)
    : payload.body;
  const { display, truncated } = expanded
    ? { display: pretty, truncated: false }
    : truncateBody(pretty);

  return (
    <details
      open={open}
      onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}
    >
      <summary className="mb-1.5 flex cursor-pointer list-none items-center gap-2 text-xs uppercase tracking-wider text-stone-500 [&::-webkit-details-marker]:hidden">
        <span aria-hidden="true" className="text-[10px] text-stone-400">{open ? '▼' : '▶'}</span>
        <span>{label}</span>
        {payload.truncated && (
          <span className="rounded bg-stone-200 px-1.5 py-0.5 text-[10px] tracking-normal text-stone-500">
            truncated
          </span>
        )}
      </summary>
      <pre className="whitespace-pre-wrap break-all rounded-md border-[0.5px] border-stone-200 bg-white px-3 py-2.5 font-mono text-xs leading-relaxed text-stone-900">
        {display}
      </pre>
      {truncated && !expanded && (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          className="mt-1 text-xs text-blue-600 hover:underline"
        >
          더 보기
        </button>
      )}
    </details>
  );
}

export function PayloadView({ traceId, spanId }: Props): ReactNode {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['payloads', traceId, spanId],
    queryFn: ({ signal }) => fetchPayloads(traceId, spanId, signal),
    enabled: !!spanId,
    staleTime: 60_000,
  });

  if (isLoading) {
    return <p className="text-xs text-stone-500">불러오는 중...</p>;
  }
  if (isError) {
    return (
      <p className="text-xs text-status-error">payload를 불러올 수 없습니다.</p>
    );
  }
  const payloads = data?.payloads ?? [];
  const inPayload = payloads.find((p) => p.direction === 'in');
  const outPayload = payloads.find((p) => p.direction === 'out');

  return (
    <div className="space-y-3">
      <PayloadSection payload={inPayload} direction="in" defaultOpen={true} />
      <PayloadSection payload={outPayload} direction="out" defaultOpen={false} />
    </div>
  );
}
