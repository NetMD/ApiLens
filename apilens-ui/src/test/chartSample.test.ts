// [R27/FR-27-03 · UXD-09] Y 상한 · [R28/AC-28-02-6 · UXD-23 · DD-28-34] 차트 자리 분기 판정 · 말풍선 칸 표 — 순수 함수 시험.
//
// [R28/AC-28-02-12 · 설계 §4.7] 이 파일에서 지운 시험 9건(화면 표본 5 · 한도 알림 4행)은 서버 대표 점 시험이
//   결과로 대신한다(설계 부록 D P01 · P02 · P04 · P12 · P20 · P21 · 범례 안내 줄 시험 U07~U09). 이름은 여기 다시 적지 않는다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   roundsTheYCeilingUpToAPowerOfTen · decidesTheChartSlotStateByPriority · mapsBucketsByStart · findsThePointBucketOrNothing
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import {
  buildBucketCounts,
  chartSlotState,
  lookupBucket,
  yUpperBound,
} from '../lib/chartSample';
import type { ChartSlotDrawn, ChartSlotInput, ChartSlotStateId, LegendNoticeKind } from '../lib/chartSample';

describe('yUpperBound — Y 축 상한', () => {
  // TZ-13 · BV-06 — 10 의 거듭제곱으로 올림 · 바닥 10.
  it.each([
    ['340 → 1,000', [340], 1_000],
    ['1,000 → 1,000', [1_000], 1_000],
    ['1,001 → 10,000', [1_001], 10_000],
    ['[0] → 10', [0], 10],
    ['[] → 10', [], 10],
  ] as const)('roundsTheYCeilingUpToAPowerOfTen — %s', (_label, durations, expected) => {
    expect(yUpperBound(durations.map((durationMs) => ({ durationMs })))).toBe(expected);
  });
});

// [R28/S01] 분기 표(설계 정정 CR-28-37) 판 13 — 우선순위 401 > 일시 정지 > 바쁨·오류 > 조건 바뀜 > 0건 > 응답 > 첫 응답 전.
describe('chartSlotState — 차트 자리 분기 판정', () => {
  const NOW_KEY = JSON.stringify(['svc', '1m', true, null, '']);
  const OLD_KEY = JSON.stringify(['svc', '10m', true, null, '']);
  const busy = new ApiError(503, 'trace points query is busy — retry shortly', {
    error: 'trace points query is busy — retry shortly',
  });
  const boom = new ApiError(500, 'boom', { error: 'boom' });
  const drawn = (
    totalCount: number,
    errorsReduced: boolean | null | undefined,
    requestKey: string = NOW_KEY,
  ): ChartSlotDrawn =>
    errorsReduced === undefined ? { requestKey, totalCount } : { requestKey, totalCount, errorsReduced };
  const base: ChartSlotInput = {
    blocked: false,
    chartPaused: false,
    frozen: false,
    pointsError: null,
    drawn: undefined,
    viewKey: NOW_KEY,
  };

  it.each<[string, Partial<ChartSlotInput>, ChartSlotStateId, LegendNoticeKind | null]>([
    ['ST-01 첫 응답 전', {}, 'ST-01', null],
    ['ST-02 줄여 보내지 않음', { drawn: drawn(12, false) }, 'ST-02', 'normal'],
    ['ST-03 줄여 보냄', { drawn: drawn(12, true) }, 'ST-03', 'reduced'],
    ['ST-04 줄여 보냈는지 모름(칸 없음)', { drawn: drawn(12, undefined) }, 'ST-04', 'unknown'],
    ['ST-05 응답 0건', { drawn: drawn(0, false) }, 'ST-05', null],
    ['ST-05 바쁨 + 같은 조건 직전 0건(DD-28-33)', { pointsError: busy, drawn: drawn(0, false) }, 'ST-05', null],
    ['ST-06 일시 정지(얼린 응답 문구 그대로)', { chartPaused: true, frozen: true, drawn: drawn(12, true) }, 'ST-06', 'reduced'],
    ['ST-07 바쁨 + 같은 조건 직전 응답', { pointsError: busy, drawn: drawn(12, false) }, 'ST-07', 'busy'],
    ['ST-08 바쁨 + 다른 조건의 옛 점', { pointsError: busy, drawn: drawn(12, false, OLD_KEY) }, 'ST-08', 'busyEmpty'],
    ['ST-09 오류(401·503 밖)', { pointsError: boom, drawn: drawn(12, false) }, 'ST-09', null],
    ['ST-10 401 이 일시 정지·바쁨보다 이김', { blocked: true, chartPaused: true, frozen: true, pointsError: busy, drawn: drawn(12, false) }, 'ST-10', null],
    ['ST-12 옛 응답 거짓 → 문구 숨김', { drawn: drawn(12, false, OLD_KEY) }, 'ST-12', null],
    ['ST-12 옛 응답 참 → 진한 마디만(DD-28-31)', { drawn: drawn(12, true, OLD_KEY) }, 'ST-12', 'reducedOnly'],
  ])('decidesTheChartSlotStateByPriority — %s', (_label, input, state, notice) => {
    expect(chartSlotState({ ...base, ...input })).toEqual({ state, notice });
  });
});

// [R28/S02 · S03 · DD-28-34 · B-18 · B-21] 말풍선 셋째 줄의 칸 표 — 응답 때 한 번 만들고 · 가장자리 칸은 처음부터 뺀다.
describe('buildBucketCounts · lookupBucket — 말풍선 칸 표', () => {
  const B = 1_000; // 칸 1초
  const T0 = 1_730_000_000_000; // 칸 경계

  it.each<[string, { since: number; until: number }, number[], number[]]>([
    // 판 ① 창이 칸 경계와 맞음 → 세 칸 모두 표에 있다(칸 시작 = 창 시작 · 칸 끝 = 창 끝 포함).
    ['창이 칸 경계와 맞으면 칸마다 표에 있다', { since: T0, until: T0 + 3 * B }, [T0, T0 + B, T0 + 2 * B], []],
    // 판 ② 창 시작이 칸 한가운데 → 왼쪽 끝 칸(시작 < 창 시작) · 오른쪽 끝 칸(끝 > 창 끝)은 빠진다.
    ['창 시작이 칸 한가운데면 가장자리 두 칸을 뺀다', { since: T0 + 500, until: T0 + 2 * B + 500 }, [T0 + B], [T0, T0 + 2 * B]],
  ])('mapsBucketsByStart — %s', (_label, requestWindow, present, absent) => {
    const buckets = [
      { start: T0, count: 12, errorCount: 0 },
      { start: T0 + B, count: 9, errorCount: 1 },
      { start: T0 + 2 * B, count: 4, errorCount: 2 },
    ];
    // 전제: 고정값에 판마다 기대한 칸이 실제로 들어 있다(빈 통과 0 · EXT-003 9항).
    for (const s of [...present, ...absent]) expect(buckets.some((b) => b.start === s)).toBe(true);
    const table = buildBucketCounts({ bucketMs: B, buckets, requestWindow });
    for (const s of present) expect(table.counts.has(s)).toBe(true);
    for (const s of absent) expect(table.counts.has(s)).toBe(false);
    expect(table.counts.get(T0 + B)).toEqual({ count: 9, errorCount: 1 });
  });

  it.each<[string, number[], { count: number; errorCount: number } | null]>([
    // 판 ① 칸 시작 정확히 · 칸 끝 −1 ms → 같은 칸.
    ['칸 시작 정확히 · 끝 −1 ms → 같은 칸', [T0 + B, T0 + 2 * B - 1], { count: 9, errorCount: 1 }],
    // 판 ② 표에 없는 칸(행이 없던 칸 · 바로 다음 칸 시작) → null = 말풍선 줄 생략(0 으로 안 씀).
    ['칸 없음 → 줄 생략', [T0 + 2 * B, T0 - 1], null],
  ])('findsThePointBucketOrNothing — %s', (_label, startTimes, expected) => {
    const table = buildBucketCounts({
      bucketMs: B,
      buckets: [{ start: T0 + B, count: 9, errorCount: 1 }],
      requestWindow: { since: T0, until: T0 + 3 * B },
    });
    for (const startTime of startTimes) expect(lookupBucket(table, startTime)).toEqual(expected);
  });
});
