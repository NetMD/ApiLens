// [R27/FR-27-02·03 · UXD-07·08] 흐르는 차트의 표본 · Y 상한 · 한도 알림 — 순수 함수 시험.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   keepsEveryErrorTrace · picksTheSlowestTracePerSecond · returnsTheSameSampleForTheSameInputInAnyOrder
//   alignsBucketsToEpochSeconds · returnsEmptyForEmptyInput · roundsTheYCeilingUpToAPowerOfTen
//   decidesTheLimitNoticeByRange
import { describe, expect, it } from 'vitest';
import { chartLimitNotice, sampleTracesPerSecond, yUpperBound } from '../lib/chartSample';
import type { TraceSummary } from '../types/api';

const BASE = 1_730_000_000_000; // epoch 초 경계

function trace(
  traceId: string,
  startTime: number,
  durationMs: number,
  status: 'OK' | 'ERROR' = 'OK',
): TraceSummary {
  return {
    traceId,
    rootOperation: `GET /${traceId}`,
    serviceName: 'svc',
    startTime,
    durationMs,
    status,
    spanCount: 1,
    hasError: status === 'ERROR',
  };
}

const ids = (ts: ReadonlyArray<TraceSummary>): string[] => ts.map((t) => t.traceId);

describe('sampleTracesPerSecond — 초당 표본', () => {
  // TZ-08 — 전제: 입력 오류 ≥ 2 이고 같은 칸에 더 느린 정상 trace 가 함께 있다(빈 통과 금지).
  it('keepsEveryErrorTrace', () => {
    const input = [
      trace('slow-ok', BASE + 100, 900),
      trace('err-a', BASE + 200, 5, 'ERROR'),
      trace('err-b', BASE + 300, 7, 'ERROR'),
      trace('fast-ok', BASE + 400, 3),
    ];
    const errorsIn = input.filter((t) => t.status === 'ERROR').length;
    expect(errorsIn).toBeGreaterThanOrEqual(2);
    const out = sampleTracesPerSecond(input);
    expect(out.filter((t) => t.status === 'ERROR').length).toBe(errorsIn);
    // 오류는 뒤(빨간 점이 위에 그려짐).
    expect(ids(out)).toEqual(['slow-ok', 'err-a', 'err-b']);
  });

  // TZ-09 — 같은 입력 두 번 · 뒤섞은 입력 → 같은 출력.
  it('returnsTheSameSampleForTheSameInputInAnyOrder', () => {
    const input = [
      trace('a', BASE + 10, 50),
      trace('b', BASE + 20, 50), // 같은 지속시간 → traceId 사전순 큰 쪽(b)
      trace('c', BASE + 1_500, 30),
      trace('e', BASE + 1_600, 4, 'ERROR'),
      trace('d', BASE + 2_100, 80),
    ];
    const first = sampleTracesPerSecond(input);
    const again = sampleTracesPerSecond(input);
    const shuffled = sampleTracesPerSecond([input[3]!, input[0]!, input[4]!, input[2]!, input[1]!]);
    expect(ids(again)).toEqual(ids(first));
    expect(ids(shuffled)).toEqual(ids(first));
    expect(ids(first)).toEqual(['b', 'c', 'd', 'e']);
  });

  // TZ-10 — 칸마다 가장 느린 1.
  it('picksTheSlowestTracePerSecond', () => {
    const input = [
      trace('s0-fast', BASE + 0, 10),
      trace('s0-slow', BASE + 999, 400),
      trace('s0-mid', BASE + 500, 120),
      trace('s1-only', BASE + 1_000, 5),
      trace('s2-mid', BASE + 2_000, 60),
      trace('s2-slow', BASE + 2_001, 61),
    ];
    expect(ids(sampleTracesPerSecond(input))).toEqual(['s0-slow', 's1-only', 's2-slow']);
  });

  // TZ-11 — 칸은 epoch 초 정렬이라 조회 창을 소수 초 민 두 입력에서도 겹치는 칸의 대표점이 같다.
  //   전제: 겹치는 칸 ≥ 1.
  it('alignsBucketsToEpochSeconds', () => {
    const all = [
      trace('x0', BASE + 100, 20),
      trace('x1', BASE + 700, 90),
      trace('y0', BASE + 1_200, 15),
      trace('y1', BASE + 1_900, 40),
      trace('z0', BASE + 2_300, 70),
    ];
    // 창 A = [BASE+0, BASE+2,000) · 창 B = [BASE+650, BASE+2,650) — 0.65초 민 창.
    const inA = all.filter((t) => t.startTime >= BASE && t.startTime < BASE + 2_000);
    const inB = all.filter((t) => t.startTime >= BASE + 650 && t.startTime < BASE + 2_650);
    const a = sampleTracesPerSecond(inA);
    const b = sampleTracesPerSecond(inB);
    const bucketOf = (t: TraceSummary): number => Math.floor(t.startTime / 1_000);
    const shared = a.filter((ta) => b.some((tb) => bucketOf(tb) === bucketOf(ta)));
    expect(shared.length).toBeGreaterThanOrEqual(1);
    for (const ta of shared) {
      const tb = b.find((x) => bucketOf(x) === bucketOf(ta));
      expect(tb?.traceId).toBe(ta.traceId);
    }
  });

  // TZ-12 — 빈 입력 → 빈 출력(기대값이 빈 배열인 정답 자리).
  it('returnsEmptyForEmptyInput', () => {
    expect(sampleTracesPerSecond([])).toEqual([]);
  });
});

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

describe('chartLimitNotice — 한도 알림', () => {
  const LIMIT = 500; // TRACES_LIMIT_SCROLL 과 같은 값(한도 인자)
  // TZ-14 · BV-07 — 5m·10m 만 · 판정 = 표본 전 응답 길이. Live OFF 는 부르는 쪽이 막는다(TZ-24).
  it.each([
    ['5m 한도 도달', '5m', LIMIT, 'shown'],
    ['5m 한도 − 1', '5m', LIMIT - 1, 'reserved'],
    ['10m 한도 도달', '10m', LIMIT, 'shown'],
    ['1h 한도 도달', '1h', LIMIT, 'none'],
  ] as const)('decidesTheLimitNoticeByRange — %s', (_label, range, count, expected) => {
    expect(chartLimitNotice(range, count, LIMIT)).toBe(expected);
  });
});
