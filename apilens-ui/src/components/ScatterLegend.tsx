// ScatterLegend — Latency 산점도 바로 아래에 표시되는 범례 줄.
//   ● OK   ● slow   ● error   (안내 문구)          click a dot to open trace   (숨은 알림)
//
// mockup 박제 (docs/mockups/apilens_dashboard_latency_scatter.html line 40~45) —
// scatter 우상단 안내 + 좌측 OK/slow/error 분류. TraceGraph/Legend 와 동일한
// inline hex style 패턴(NFR-05 미러).
//
// [R28/UB-R28-01 · UXD-21·22·34] 범례 줄 = 두 마디. ②-a(점 범례 · 「click a dot to open trace」)는 차트 자리에
//   산점도가 있을 때만 보이고, ②-b(안내 문구)는 그려진 점이 지금 조건의 응답일 때 그 응답의 문구를 보인다.
//   숨길 때는 마디 단위로 visibility:hidden + aria-hidden 을 건다(줄 높이 유지 · 줄 전체에 걸면 숨은 알림까지 가려진다).
//   새 색 0 — stone 클래스만(UX 절대금지 패턴 체크 예외 = 이웃 요소와 같은 중성색).
import type { CSSProperties, ReactNode } from 'react';
import { STATUS_HEX } from '../lib/colors';
import type { LegendNotice } from '../lib/chartSample';

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

interface Props {
  /** ②-a — 차트 자리에 산점도가 그려져 있을 때만 참. */
  dotsShown: boolean;
  /** ②-b — 안내 문구(null = 숨김). 값은 그 문구를 낸 응답의 것(바쁨 = 직전 응답 · 조건 바뀜 = 옛 응답). */
  notice: LegendNotice | null;
  /** 숨은 상태 알림 글(UC-R28-19~22) — 문구 상태가 바뀔 때만 부르는 쪽이 바꾼다. 빈 글 = 읽을 것 없음. */
  announcement: string;
}

/** 마디 숨김 — 높이는 유지하고 읽기 도구에도 숨긴다(R27 한도 알림 꼴 그대로). */
function hiddenUnless(shown: boolean): { style: CSSProperties; 'aria-hidden'?: true } {
  return shown ? { style: { visibility: 'visible' } } : { style: { visibility: 'hidden' }, 'aria-hidden': true };
}

/** 숫자 = 천 단위 쉼표(UC 정본 머리). */
function n(value: number): string {
  return value.toLocaleString();
}

/** 진한 마디 — 줄여 보낸 사실만 굵게(UXD-22 · 새 색 0). */
function ReducedMark({ notice }: { notice: LegendNotice }): ReactNode {
  return (
    <span className="font-medium text-stone-900">
      오류 {n(notice.errorTotal)}건 중 {n(notice.errorShown)}점만
    </span>
  );
}

/** ②-b 문구 — 문구 정본 UC-R28-01 · 02 · 03 · 06 · 07 · 18 그대로(새 문구 0 · reducedOnly 는 UC-02 의 진한 마디). */
function NoticeText({ notice }: { notice: LegendNotice }): ReactNode {
  const head = `전체 ${n(notice.total)}건 중 ${n(notice.shown)}점 · `;
  switch (notice.kind) {
    case 'normal':
      return <>{head}칸마다 가장 느린 정상 요청 하나와 오류 전부</>;
    case 'unknown':
      return <>{head}칸마다 가장 느린 정상 요청 하나와 오류</>;
    case 'reduced':
      return (
        <>
          {head}칸마다 가장 느린 정상 요청과 오류를 하나씩 · <ReducedMark notice={notice} />
        </>
      );
    case 'busy':
      return notice.reduced ? (
        <>
          {head}
          <ReducedMark notice={notice} /> · 서버가 바빠 직전 응답을 보여 주고 있어요
        </>
      ) : (
        <>{head}서버가 바빠 직전 응답을 보여 주고 있어요</>
      );
    case 'busyEmpty':
      return <>서버가 바빠 차트 점을 잠시 뒤 다시 불러와요</>;
    case 'reducedOnly':
      // [DD-28-31] 조건을 바꾼 동안 — 옛 응답의 진한 마디만 남기고 규칙 마디는 숨긴다(자리는 그대로라 진한 마디가 옆으로 안 튄다).
      return (
        <>
          <span {...hiddenUnless(false)}>{head}칸마다 가장 느린 정상 요청과 오류를 하나씩 · </span>
          <ReducedMark notice={notice} />
        </>
      );
  }
}

export function ScatterLegend({ dotsShown, notice, announcement }: Props): ReactNode {
  return (
    <div className="flex flex-wrap items-center gap-4 px-3 text-xs text-stone-500">
      <span data-testid="legend-dots" className="inline-flex items-center gap-4" {...hiddenUnless(dotsShown)}>
        <Dot color={STATUS_HEX.ok} label="OK" />
        <Dot color={STATUS_HEX.slow} label="slow" />
        <Dot color={STATUS_HEX.error} label="error" />
      </span>
      {/* [R28/UXD-22] 숫자는 알림 밖 · 문구 상태가 바뀔 때만 숨은 알림(아래 sr-only). 숫자 폭 흔들림 = tabular-nums. */}
      <span data-testid="legend-notice" className="tabular-nums" {...hiddenUnless(notice !== null)}>
        {notice !== null && <NoticeText notice={notice} />}
      </span>
      <span className="ml-auto" {...hiddenUnless(dotsShown)}>
        click a dot to open trace
      </span>
      {/* 처음부터 그려 둔다(마디 숨김 밖) — 글이 바뀔 때만 읽힌다(WCAG 4.1.3). */}
      <span data-testid="legend-announcement" className="sr-only" role="status">
        {announcement}
      </span>
    </div>
  );
}
