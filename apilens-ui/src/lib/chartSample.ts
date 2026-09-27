// [R27] 흐르는 차트의 표본 · Y 상한 · 한도 알림 — 화면 없는 순수 함수(시험 대상).
//
// 표본은 차트에만 쓴다. 아래 trace 목록은 응답 그대로 받는다(UXD-08 ③).
import type { TraceSummary } from '../types/api';
import { statusColorKey } from './colors';
import type { RangePreset } from './time';

/** 오류 판정 = 점 색과 같은 함수(UXD-08 ①) — 빨간 점과 「남기는 오류」가 어긋나지 않게. */
function isError(t: TraceSummary): boolean {
  return statusColorKey(t) === 'error';
}

/** 같은 칸 안에서 더 느린 쪽(같으면 traceId 사전순 큰 쪽)이 앞선다. */
function slower(a: TraceSummary, b: TraceSummary): TraceSummary {
  if (a.durationMs !== b.durationMs) return a.durationMs > b.durationMs ? a : b;
  return a.traceId > b.traceId ? a : b;
}

/**
 * [R27/FR-27-02] 초당 표본 — 칸 = `floor(startTime / 1000)`(epoch 초 정렬 · 조회 창과 무관).
 * 칸마다 가장 느린 trace 1 + 오류 trace 전부. 결과 순서는 입력 순서와 무관하다:
 * (오류 아님 먼저 · 오류 나중 — 빨간 점이 위에 그려짐) → startTime 오름차순 → traceId 오름차순.
 */
export function sampleTracesPerSecond(traces: ReadonlyArray<TraceSummary>): TraceSummary[] {
  const slowest = new Map<number, TraceSummary>();
  const picked = new Set<TraceSummary>();
  for (const t of traces) {
    const bucket = Math.floor(t.startTime / 1_000);
    const cur = slowest.get(bucket);
    slowest.set(bucket, cur === undefined ? t : slower(cur, t));
    if (isError(t)) picked.add(t);
  }
  for (const t of slowest.values()) picked.add(t);
  return [...picked].sort((a, b) => {
    const ea = isError(a) ? 1 : 0;
    const eb = isError(b) ? 1 : 0;
    if (ea !== eb) return ea - eb;
    if (a.startTime !== b.startTime) return a.startTime - b.startTime;
    return a.traceId < b.traceId ? -1 : a.traceId > b.traceId ? 1 : 0;
  });
}

/** Y 상한의 바닥 — 로그 축 하한 1 위로 한 자리는 늘 보인다. */
const Y_FLOOR = 10;

/**
 * [R27/FR-27-03] Y 축 상한 = 최대 durationMs 를 10 의 거듭제곱으로 올린 값(UXD-09 · 바닥 10).
 * 예: 340 → 1,000 · 1,000 → 1,000 · 1,001 → 10,000 · 빈 배열·0·1 → 10.
 * 부동소수 log10 대신 곱셈으로 올린다(1,000 이 1,000 으로 정확히 떨어지게).
 * 입력 = 응답 때 조회 창 전체 표본 — 창을 밀어도 상한이 바뀌지 않는다.
 */
export function yUpperBound(points: ReadonlyArray<Pick<TraceSummary, 'durationMs'>>): number {
  let max = 0;
  for (const p of points) if (p.durationMs > max) max = p.durationMs;
  let bound = Y_FLOOR;
  while (bound < max) bound *= 10;
  return bound;
}

/** 한도 알림 상태 — 'none' 자리 없음 · 'reserved' 자리만 잡고 글자 숨김 · 'shown' 보임. */
export type ChartLimitNotice = 'none' | 'reserved' | 'shown';

/**
 * [R27/UXD-07] 5m·10m 이면 응답 건수(표본 **전**)가 한도에 닿을 때 알림, 못 닿으면 자리만 잡는다.
 * 그 밖 범위는 알림 없음. Live OFF 는 부르는 쪽(Dashboard)이 막는다.
 */
export function chartLimitNotice(
  range: RangePreset,
  responseCount: number,
  limit: number,
): ChartLimitNotice {
  if (range !== '5m' && range !== '10m') return 'none';
  return responseCount >= limit ? 'shown' : 'reserved';
}
