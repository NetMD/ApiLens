// SelectedSpanCard — TraceDetail 의 4번째 row 카드 (그래프 / 범례 아래).
//
// [Phase F2 fix³] AC-04-1/2/3, AC-05-2 — F2 fix² 의 sidebar 외피 (aside 태그 + 고정 폭 360 + 좌측 border)
// 폐기. mockup line 90 카드 외피 (bg-stone-100 / rounded-lg / 14px 16px padding /
// 0 1rem margin) 로 재배치. 사용자 명시 비협상 결정.
//
// 컴포넌트 명 (직전 라운드의 Inspector 명칭) → SelectedSpanCard 재명명 (PM CL-01 옵션 b).
// span: SpanDetail | null 시그니처로 placeholder 분기 (AC-02-1/AC-02-5).
//
// CLAUDE.md "수직 레이아웃 절대 금지" 의미 분리 (AC-05-2):
//   - 노드 그래프 방향 (LR/TB) = 보존 (rankdir: 'LR' apilens-ui/src/components/TraceGraph/layout.ts:51)
//   - 페이지 레이아웃 = mockup 일치 수직 4 row (사용자 명시 비협상)
// 위 분리 선언은 본 phase 명문 (PM §0 / planner §0 / design §0.3).
//
// 내부 콘텐츠 보존 (변경 0):
//   - BL-09 그룹 분류 (HTTP / DB / Code / Other) — groupAttributes() 그대로
//   - ExceptionBox (#FEF2F2 + border-l-4 border-status-error) — 그대로
//   - ERROR 헤더 negative margin 패턴 (-mx-4 -mt-3.5 mb-3 rounded-t-lg) — 그대로
//     (새 외피 px-4 py-3.5 와 정확히 일치 검증 완료. 설계서 §6 D-07)
//   - PayloadView IN/OUT lazy load — 그대로
//
// BL-09 Attributes 그룹 분류:
//   1. HTTP : http.method / http.url / http.route / http.status_code
//   2. DB   : db.statement / db.parameters / db.rows_affected / db.connection
//   3. (exception.* 는 Other에서 제외 — ExceptionBox 전용)
//   4. Code : code.namespace / code.function
//   5. Other: 위에 매칭되지 않은 모든 키, ASC 정렬
//   빈 그룹은 숨김 (AC-06-5).
//
// ERROR 시:
//   - 헤더 #FEE2E2 배경 (NFR-05 8 토큰에 미포함이라 raw hex 허용 — mockup 박제)
//   - ExceptionBox: #FEF2F2 배경 + #E24B4A 4px left border (border-status-error 토큰 사용)
//   - exception.message 굵게 + exception.stacktrace pre
//   - stacktrace 부재 시 "(stacktrace not captured)" graceful (R5)
import type { ReactNode } from 'react';
import type { SpanDetail } from '../types/api';
import { formatDuration, shortenOperation } from '../lib/format';
import { PayloadView } from './PayloadView';

interface Props {
  traceId: string;
  // [Phase F2 fix³] AC-02-1/AC-02-5: span 미선택 시 null → placeholder 분기 (사용자 명시 비협상)
  span: SpanDetail | null;
  // [Phase F2 fix³] 외부 컨테이너 margin/spacing 제어용 className (TraceDetail 에서 mx-4 mt-1 mb-4 shrink-0 전달)
  className?: string;
  /**
   * Mockup 박제: 헤더 우측 " · 1 child slow" 같은 child 요약. TraceDetail에서 계산해 전달.
   * children 없거나 정상이면 undefined.
   */
  childSummary?: string;
}

interface AttrGroup {
  label: string;
  entries: Array<[string, unknown]>;
}

const HTTP_KEYS = ['http.method', 'http.url', 'http.route', 'http.status_code'];
const DB_KEYS = ['db.statement', 'db.parameters', 'db.rows_affected', 'db.connection'];
const CODE_KEYS = ['code.namespace', 'code.function'];

/** BL-09 그룹 분류. exception.* 는 ExceptionBox로 별도 렌더되므로 Other에서도 제외. */
function groupAttributes(attrs: Record<string, unknown>): AttrGroup[] {
  const http: Array<[string, unknown]> = [];
  const db: Array<[string, unknown]> = [];
  const code: Array<[string, unknown]> = [];
  const other: Array<[string, unknown]> = [];

  Object.entries(attrs).forEach(([k, v]) => {
    if (k.startsWith('exception.')) return;
    if (HTTP_KEYS.includes(k)) http.push([k, v]);
    else if (DB_KEYS.includes(k)) db.push([k, v]);
    else if (CODE_KEYS.includes(k)) code.push([k, v]);
    else other.push([k, v]);
  });

  // HTTP/DB/Code는 정의 순서 유지(매핑 키 배열의 indexOf), Other는 ASC.
  http.sort(([a], [b]) => HTTP_KEYS.indexOf(a) - HTTP_KEYS.indexOf(b));
  db.sort(([a], [b]) => DB_KEYS.indexOf(a) - DB_KEYS.indexOf(b));
  code.sort(([a], [b]) => CODE_KEYS.indexOf(a) - CODE_KEYS.indexOf(b));
  other.sort(([a], [b]) => a.localeCompare(b));

  return [
    { label: 'HTTP', entries: http },
    { label: 'DB', entries: db },
    { label: 'Code', entries: code },
    { label: 'Other', entries: other },
  ];
}

function formatAttrValue(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

function ExceptionBox({ attrs }: { attrs: Record<string, unknown> }): ReactNode {
  const message = attrs['exception.message'];
  const stacktrace = attrs['exception.stacktrace'];
  const messageText = typeof message === 'string' ? message : '(no message)';
  const stackText =
    typeof stacktrace === 'string' ? stacktrace : '(stacktrace not captured)';
  return (
    <div className="border-l-4 border-status-error bg-[#FEF2F2] p-3 font-mono text-xs">
      <div className="mb-2 font-bold text-status-error break-all">{messageText}</div>
      <pre className="whitespace-pre-wrap break-all text-stone-700">{stackText}</pre>
    </div>
  );
}

function AttributesSection({ attrs }: { attrs: Record<string, unknown> }): ReactNode {
  const groups = groupAttributes(attrs);
  return (
    <div className="space-y-3">
      {groups.map(
        (g) =>
          g.entries.length > 0 && (
            <div key={g.label}>
              <h3 className="mb-1 text-xs font-semibold uppercase text-stone-500">
                {g.label}
              </h3>
              <dl className="space-y-1 font-mono text-xs">
                {g.entries.map(([k, v]) => (
                  <div key={k} className="flex gap-2">
                    <dt className="shrink-0 text-stone-500">{k}</dt>
                    <dd className="break-all text-stone-900">{formatAttrValue(v)}</dd>
                  </div>
                ))}
              </dl>
            </div>
          ),
      )}
    </div>
  );
}

// [Phase F2 fix³] AC-01-4, AC-02-1/2/3/4/5, AC-04-1/2/3 — SelectedSpanCard 본체
// (사용자 명시 비협상 결정)
// 외피 통합: min-h-[200px] rounded-lg bg-stone-100 px-4 py-3.5 — placeholder/selected 양쪽 공유 (D-02/D-05).
// placeholder 분기: 컴포넌트 내부에서 처리 → 외피 클래스 공유로 자리 흔들림 0 (설계 §8.2 채택 근거 B).
export function SelectedSpanCard({
  traceId,
  span,
  className = '',
  childSummary,
}: Props): ReactNode {
  // max-h-[45vh]: payload가 길어도 카드는 viewport 45% 이내로 cap → 그래프가 잘리지 않음.
  // overflow-auto: 카드 내부에서만 스크롤. 페이지 전체 스크롤은 발생하지 않음 (사용자 보고: "확장되면서 상단 그래프가 잘림").
  const baseClass =
    'min-h-[200px] max-h-[45vh] rounded-lg bg-stone-100 px-4 py-3.5 overflow-auto';
  const wrapperClass = `${baseClass} ${className}`.trim();

  // [Phase F2 fix³] AC-02-1, AC-02-5 — span 미선택 시 placeholder 렌더 (사용자 명시 비협상)
  if (!span) {
    return (
      <section
        className={`${wrapperClass} flex items-center justify-center`}
        aria-label="Selected span placeholder"
      >
        {/* [Phase F2 fix³] AC-02-1, D-04 — placeholder 톤은 PayloadView placeholder (P-03) 와 동일 (text-xs italic text-stone-400) */}
        <p className="text-xs italic text-stone-400 text-center">
          Click a node to see details
        </p>
      </section>
    );
  }

  const isError = span.status === 'ERROR';
  const durationMs = span.endTime - span.startTime;
  // F2 fix (§8 mockup 박제 카드 디자인):
  //   회색 카드 (bg-stone-100 + rounded-lg + 14px/16px padding).
  //   ERROR 시 헤더만 빨간 톤 — negative margin + rounded-t-lg 으로 카드 둥근 모서리까지 차지.
  return (
    <section className={wrapperClass} aria-label="Selected span details">
      <header
        className={[
          '-mx-4 -mt-3.5 mb-3 rounded-t-lg px-4 py-3.5',
          isError ? 'bg-[#FEE2E2]' : '',
        ].join(' ')}
      >
        <div className="font-mono text-sm font-medium text-stone-900 break-all">
          {shortenOperation(span.operationName)}
        </div>
        <div className="mt-0.5 font-mono text-xs text-stone-500">
          {formatDuration(durationMs)} · {span.status}
          {childSummary !== undefined && <> · {childSummary}</>}
        </div>
      </header>
      <div className="space-y-4">
        {isError && <ExceptionBox attrs={span.attributes} />}
        <AttributesSection attrs={span.attributes} />
        <PayloadView traceId={traceId} spanId={span.spanId} />
      </div>
    </section>
  );
}
