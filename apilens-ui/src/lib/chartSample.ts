// [R27] 흐르는 차트의 Y 상한 · [R28] 차트 자리 분기 판정 · 말풍선 칸 표 · 범례 안내 문구 — 화면 없는 순수 함수(시험 대상).
//
// [R28/DD-28-17] 화면이 응답을 다시 고르던 함수와 그 한도 알림 판정은 지웠다 — 서버가 같은 규칙(칸마다 가장 느린
//   정상 하나 + 오류)으로 대표 점을 골라 보낸다(설계 §2.3). 남은 것: Y 상한 · 분기 판정 · 칸 표 · 안내 문구 · 숨은 알림 글.
import { ApiError } from '../api/client';
import type { TracePoint, TracePointsResponse } from '../types/api';
import { statusColorKey } from './colors';

/** Y 상한의 바닥 — 로그 축 하한 1 위로 한 자리는 늘 보인다. */
const Y_FLOOR = 10;

/**
 * [R27/FR-27-03] Y 축 상한 = 최대 durationMs 를 10 의 거듭제곱으로 올린 값(UXD-09 · 바닥 10).
 * 예: 340 → 1,000 · 1,000 → 1,000 · 1,001 → 10,000 · 빈 배열·0·1 → 10.
 * 부동소수 log10 대신 곱셈으로 올린다(1,000 이 1,000 으로 정확히 떨어지게).
 * [R28] 입력 = 그리는 대표 점 응답의 점 전체 — 창을 밀어도 상한이 바뀌지 않는다.
 */
export function yUpperBound(points: ReadonlyArray<{ durationMs: number }>): number {
  let max = 0;
  for (const p of points) if (p.durationMs > max) max = p.durationMs;
  let bound = Y_FLOOR;
  while (bound < max) bound *= 10;
  return bound;
}

/** 오류 응답의 HTTP 상태가 이 값인가 — 401(멈춤) · 503(바쁨)을 가른다(본문 글은 보지 않는다 · 설계 §5.2 ⓐ). */
export function isApiStatus(error: unknown, status: number): boolean {
  return error instanceof ApiError && error.status === status;
}

// ── [R28/AC-28-02-6] 차트 자리 분기 판정 (설계 §4.2 · 정정 CR-28-37 분기 표) ─────────────────────────

/**
 * 차트 자리 상태 — UX 상태 정본 ST-R28-01~12 의 번호 그대로(ST-11 수신 일시정지는 폴링·넘김만 바꾸고 행이 아니다).
 * 02·03·04 는 같은 산점도이고 안내 문구만 다르다.
 */
export type ChartSlotStateId =
  | 'ST-01' // 그리는 응답 없음 · 첫 응답 전
  | 'ST-02' // 응답 · 줄여 보내지 않음
  | 'ST-03' // 응답 · 줄여 보냄
  | 'ST-04' // 응답 · 줄여 보냈는지 모름(null · 칸 없음)
  | 'ST-05' // 전체 0건
  | 'ST-06' // [차트 일시 정지]
  | 'ST-07' // 바쁨 · 같은 조건의 직전 응답 있음
  | 'ST-08' // 바쁨 · 같은 조건의 응답 없음
  | 'ST-09' // 대표 점 조회 오류(401 · 503 밖)
  | 'ST-10' // 401(어느 조회든)
  | 'ST-12'; // 조건을 바꾼 뒤 새 응답 전(다른 조건의 자리표시 데이터)

/**
 * 범례 안내 문구 종류(②-b). null = 숨김.
 * - normal UC-R28-01 · reduced UC-R28-02 · unknown UC-R28-03
 * - busy UC-R28-06(그리는 응답이 줄여 보내지 않음) / UC-R28-18(줄여 보냄 — 진한 마디를 남김)
 * - busyEmpty UC-R28-07 · reducedOnly = ST-12 동안 옛 응답의 진한 마디만(DD-28-31)
 */
export type LegendNoticeKind = 'normal' | 'reduced' | 'unknown' | 'busy' | 'busyEmpty' | 'reducedOnly';

/** 분기 판정이 읽는 그리는 응답의 값 — 꼬리표(그 요청의 조건)와 두 칸. */
export interface ChartSlotDrawn {
  requestKey: string;
  totalCount: number;
  errorsReduced?: boolean | null;
}

export interface ChartSlotInput {
  /** 401 — 두 조회 중 하나라도(같은 렌더) 또는 이미 멈춤. */
  blocked: boolean;
  chartPaused: boolean;
  /** 얼린 응답이 있는가. */
  frozen: boolean;
  /** 대표 점 조회의 오류(없으면 null). */
  pointsError: unknown;
  /** 그리는 대표 점 응답(얼린 · 자리표시 · 같은 조건 직전 성공 응답 포함 · 없으면 undefined). */
  drawn: ChartSlotDrawn | undefined;
  /** 지금 조건(서비스 · 범위 · Live · 상태 · 검색어). */
  viewKey: string;
}

export interface ChartSlotDecision {
  state: ChartSlotStateId;
  notice: LegendNoticeKind | null;
}

/** 응답 문구 종류 — 0건이면 문구 없음(빈 상자) · 줄여 보냄 참 / 거짓 / 모름. */
function responseNotice(d: ChartSlotDrawn): LegendNoticeKind | null {
  if (d.totalCount === 0) return null;
  if (d.errorsReduced === true) return 'reduced';
  if (d.errorsReduced === false) return 'normal';
  return 'unknown';
}

/**
 * [R28/AC-28-02-6] 차트 자리 상태와 범례 안내 문구 종류를 정한다.
 * 우선순위 = 401 > 일시 정지 > 바쁨·오류 > 조건 바뀜 > 0건 > 응답 > 첫 응답 전(UX 상태 정본 · 표는 판정 순서대로).
 */
export function chartSlotState(i: ChartSlotInput): ChartSlotDecision {
  if (i.blocked) return { state: 'ST-10', notice: null };
  if (i.chartPaused && i.frozen && i.drawn !== undefined) {
    // 얼린 응답의 문구 그대로(진한 마디 포함) — 숨은 알림은 0(부르는 쪽 규칙).
    return { state: 'ST-06', notice: responseNotice(i.drawn) };
  }
  if (isApiStatus(i.pointsError, 503)) {
    const d = i.drawn;
    if (d !== undefined && d.requestKey === i.viewKey) {
      // [DD-28-33] 같은 조건 직전 응답이 0건이면 빈 상자에 머문다(빈 상자 ↔ 빈 그림틀 깜빡임 0).
      if (d.totalCount === 0) return { state: 'ST-05', notice: null };
      return { state: 'ST-07', notice: 'busy' };
    }
    return { state: 'ST-08', notice: 'busyEmpty' };
  }
  if (i.pointsError !== null && i.pointsError !== undefined) return { state: 'ST-09', notice: null };
  if (i.drawn === undefined) return { state: 'ST-01', notice: null };
  if (i.drawn.requestKey !== i.viewKey) {
    // [DD-28-31] 옛 응답이 줄여 보낸 것이면 진한 마디만 남긴다(확정 ③ 「줄여 보냈다 표시를 늘」).
    return { state: 'ST-12', notice: i.drawn.errorsReduced === true ? 'reducedOnly' : null };
  }
  if (i.drawn.totalCount === 0) return { state: 'ST-05', notice: null };
  const notice = responseNotice(i.drawn);
  if (notice === 'reduced') return { state: 'ST-03', notice };
  if (notice === 'normal') return { state: 'ST-02', notice };
  return { state: 'ST-04', notice: 'unknown' };
}

// ── [R28/UXD-23 · DD-28-34] 말풍선 셋째 줄 — 칸 표(응답 때 한 번 만든다) ─────────────────────────────

/** 칸 시작(epoch ms) → 그 칸 전체 건수·오류 건수. 가장자리 칸은 처음부터 넣지 않는다. */
export interface BucketCounts {
  bucketMs: number;
  counts: ReadonlyMap<number, { count: number; errorCount: number }>;
}

/**
 * [R28/S02] 응답의 `buckets` 로 칸 표를 만든다. ★가장자리 칸 제외(DD-28-34 · B-21): 칸 시작 < 요청 창 시작 또는
 * 칸 끝(시작 + 칸 크기) > 요청 창 끝이면 그 칸의 건수는 창 안 몫만이라 「이 칸 전체」가 거짓 — 표에 넣지 않는다.
 */
export function buildBucketCounts(resp: {
  bucketMs: number;
  buckets: TracePointsResponse['buckets'];
  requestWindow: { since: number; until: number };
}): BucketCounts {
  const counts = new Map<number, { count: number; errorCount: number }>();
  const { since, until } = resp.requestWindow;
  for (const b of resp.buckets) {
    if (b.start < since || b.start + resp.bucketMs > until) continue;
    counts.set(b.start, { count: b.count, errorCount: b.errorCount });
  }
  return { bucketMs: resp.bucketMs, counts };
}

/** [R28/S03] 점이 대표하는 칸 = `floor(startTime / bucketMs) × bucketMs`. 표에 없으면 null(말풍선 줄 생략 · 0 으로 안 씀). */
export function lookupBucket(
  table: BucketCounts | null,
  startTime: number,
): { count: number; errorCount: number } | null {
  if (table === null || !(table.bucketMs > 0)) return null;
  const start = Math.floor(startTime / table.bucketMs) * table.bucketMs;
  return table.counts.get(start) ?? null;
}

// ── [R28/UXD-21·22·26 · DD-28-31] 범례 안내 문구의 값 · 숨은 알림 글 ─────────────────────────────

/** 범례 안내 문구 — 종류와 그 문구를 낸 응답의 값(바쁨 = 직전 응답 · 조건 바뀜 = 옛 응답). */
export interface LegendNotice {
  kind: LegendNoticeKind;
  /** N = 전체 건수(`totalCount`). */
  total: number;
  /** M = 그린 점 수(`points` 길이). */
  shown: number;
  /** X = 오류 건수(`errorCount`). */
  errorTotal: number;
  /** Y = 응답에 든 오류 점 수(빨간 점). */
  errorShown: number;
  /** 그 응답이 오류를 줄여 보냈는가 — 바쁨 문구에 진한 마디를 남길지(UC-R28-18). */
  reduced: boolean;
}

/** 응답에 든 오류 점 수 — 점 색과 같은 함수(빨간 점 = 오류)로 센다. */
export function errorPointCount(points: ReadonlyArray<TracePoint>): number {
  let n = 0;
  for (const p of points) if (statusColorKey(p) === 'error') n += 1;
  return n;
}

/** 범례 안내 문구 값을 응답에서 꺼낸다. 종류가 없거나(숨김) 응답이 없으면 null(바쁨 · 응답 없음은 값 0). */
export function legendNoticeFor(
  kind: LegendNoticeKind | null,
  drawn: Pick<TracePointsResponse, 'totalCount' | 'errorCount' | 'errorsReduced' | 'points'> | undefined,
): LegendNotice | null {
  if (kind === null) return null;
  if (kind === 'busyEmpty' || drawn === undefined) {
    return { kind, total: 0, shown: 0, errorTotal: 0, errorShown: 0, reduced: false };
  }
  return {
    kind,
    total: drawn.totalCount,
    shown: drawn.points.length,
    errorTotal: drawn.errorCount,
    errorShown: errorPointCount(drawn.points),
    reduced: drawn.errorsReduced === true,
  };
}

/** 숨은 알림 글 — UX 문구 정본 UC-R28-19~22(화면에 안 보이고 읽기 도구만 읽는다). */
export const ANNOUNCE_REDUCED = '오류가 많아 칸마다 가장 느린 오류 하나씩만 그렸어요'; // UC-R28-19
export const ANNOUNCE_BUSY_KEPT = '서버가 바빠 직전 응답을 보여 주고 있어요'; // UC-R28-20
export const ANNOUNCE_BUSY_EMPTY = '서버가 바빠 차트 점을 잠시 뒤 다시 불러와요'; // UC-R28-21
export const ANNOUNCE_RECOVERED = '차트 점을 다시 받았어요'; // UC-R28-22

/**
 * [R28/UXD-22] 숨은 알림 규칙 — 차트 자리 상태가 **바뀐 렌더에서만** 부른다(같은 상태가 이어지는 동안은 글 그대로).
 * 줄여 보냄으로 → UC-19(어느 상태에서 왔든 · 첫 응답 포함) · 바쁨 07 로 → UC-20 · 바쁨 08 로 → UC-21 ·
 * 바쁨에서 응답(02 · 04)으로 → UC-22 · 그 밖(평소 ↔ 모름 · 줄여 보냄 → 평소 · 첫 응답 전 · 조건 바뀜 · 일시 정지 ·
 * 오류 · 401) = 글을 비움(오류 · 401 은 기존 `role="alert"` 가 맡는다).
 */
export function announcementFor(prev: ChartSlotStateId, next: ChartSlotStateId): string {
  if (next === 'ST-03') return ANNOUNCE_REDUCED;
  if (next === 'ST-07') return ANNOUNCE_BUSY_KEPT;
  if (next === 'ST-08') return ANNOUNCE_BUSY_EMPTY;
  if ((prev === 'ST-07' || prev === 'ST-08') && (next === 'ST-02' || next === 'ST-04')) return ANNOUNCE_RECOVERED;
  return '';
}
