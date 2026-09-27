// computeRange 4건 박제 (D-02 — passWithNoTests 미설정 fail 회피 + 시간 파싱 핵심 검증).
// 4 preset 각각 (until - since) 가 예상 millis인지 확인.
// computeWindow: Live ON 슬라이딩 / OFF pinned 분기 검증.
import { describe, expect, it } from 'vitest';
import {
  computePageTicks,
  formatPageTick,
  PAGE_SETTLE_MS,
  PAGE_SLIDE_MS,
  advancePinnedUntil,
  isNextPage,
  nextPageAt,
  slideWindow,
  COLLECT_DELAY_BUDGET_MS,
  FETCH_DELTA_BUDGET_MS,
  LIVE_POLL_MS,
  SCROLL_LAG_MS,
  computeRange,
  computeScrollWindow,
  computeTimeTicks,
  computeWindow,
  isClockRange,
  scrollStepMs,
} from '../lib/time';
import type { RangePreset } from '../lib/time';

describe('computeRange', () => {
  const NOW = 1_730_000_000_000;

  it('10m → 600,000ms', () => {
    const { since, until } = computeRange('10m', NOW);
    expect(until - since).toBe(10 * 60_000);
    expect(until).toBe(NOW);
  });

  it('1h → 3,600,000ms', () => {
    const { since, until } = computeRange('1h', NOW);
    expect(until - since).toBe(60 * 60 * 1_000);
    expect(until).toBe(NOW);
  });

  it('24h → 86,400,000ms', () => {
    const { since, until } = computeRange('24h', NOW);
    expect(until - since).toBe(24 * 60 * 60 * 1_000);
    expect(until).toBe(NOW);
  });

  it('7d → 604,800,000ms', () => {
    const { since, until } = computeRange('7d', NOW);
    expect(until - since).toBe(7 * 24 * 60 * 60 * 1_000);
    expect(until).toBe(NOW);
  });
});

describe('computeWindow', () => {
  const NOW = 2_000_000_000;
  const PINNED = 1_000_000_000;

  it('Live ON: until = now (sliding) — pinnedUntil 무시', () => {
    const w = computeWindow({ range: '10m', live: true, pinnedUntil: PINNED, now: NOW });
    expect(w.until).toBe(NOW);
    expect(w.since).toBe(NOW - 10 * 60_000);
  });

  it('Live OFF: until = pinnedUntil (frozen) — now 무시', () => {
    const w = computeWindow({ range: '10m', live: false, pinnedUntil: PINNED, now: NOW });
    expect(w.until).toBe(PINNED);
    expect(w.since).toBe(PINNED - 10 * 60_000);
  });

  it('Live OFF: range가 1h이면 since = pinnedUntil - 1h', () => {
    const w = computeWindow({ range: '1h', live: false, pinnedUntil: PINNED, now: NOW });
    expect(w.until).toBe(PINNED);
    expect(w.since).toBe(PINNED - 60 * 60 * 1_000);
  });

  it('Live ON: range가 7d이면 since = now - 7d', () => {
    const w = computeWindow({ range: '7d', live: true, pinnedUntil: PINNED, now: NOW });
    expect(w.until).toBe(NOW);
    expect(w.since).toBe(NOW - 7 * 24 * 60 * 60 * 1_000);
  });
});

// [R27/FR-27-04] 흐르는 차트의 창 함수 — 조회 창(보낸 시각 F) ⊇ 표시 창(프레임 시계 T).
// 시간은 전부 주입한다(실시간 대기 0 · EXT-006).
describe('computeScrollWindow — 두 부등식과 멈추지 않는 창', () => {
  const F = 1_730_000_000_000;
  const R_1M = 60_000;

  function expectBothInequalities(w: ReturnType<typeof computeScrollWindow>): void {
    expect(w.querySince).toBeLessThanOrEqual(w.viewSince);
    expect(w.viewUntil).toBeLessThanOrEqual(w.queryUntil);
  }

  // TZ-01 · BV-01 — 셋째 행 F + 6,500 = P + 2·δ 예산. L = 5,000 이면 이 행이 빨강(기록 · 결정 요약).
  it.each([
    ['T = F + 1', F + 1],
    ['T = F + 2,500', F + 2_500],
    ['T = F + 6,500', F + 6_500],
  ])('keepsTheViewMovingAndBothInequalitiesAtNormalPoints — %s', (_label, t) => {
    const w = computeScrollWindow({ range: '1m', requestedAt: F, nowMs: t, lagMs: SCROLL_LAG_MS });
    expect(w.viewUntil).toBe(t - SCROLL_LAG_MS);
    expect(w.viewUntil - w.viewSince).toBe(R_1M);
    expectBothInequalities(w);
  });

  // TZ-02 · BV-02 — 다음 응답이 예산보다 늦으면 창은 조회 창 끝에 선다(ST-13 · 표시만 멈춤).
  it('holdsTheViewAtQueryUntilWhenTheNextResponseIsLate', () => {
    const w = computeScrollWindow({
      range: '1m',
      requestedAt: F,
      nowMs: F + 30_000,
      lagMs: SCROLL_LAG_MS,
    });
    expect(w.viewUntil).toBe(F);
    expectBothInequalities(w);
  });

  // TZ-03 · BV-03 — 보낸 순간(nowMs = F) 의 조회 창.
  it('derivesTheQueryWindowFromTheSendTime', () => {
    const w = computeScrollWindow({ range: '1m', requestedAt: F, nowMs: F, lagMs: SCROLL_LAG_MS });
    expect(w.querySince).toBe(F - R_1M - SCROLL_LAG_MS);
    expect(w.queryUntil).toBe(F);
    // 보낸 순간 표시 창 왼쪽 = 조회 창 왼쪽(딱 맞물림).
    expect(w.viewSince).toBe(w.querySince);
    expectBothInequalities(w);
  });

  // TZ-04 — T·F 가 앞으로만 가면 창 끝도 앞으로만(UXD-05 대체의 식 쪽 몫). 늦게 온 응답 · 시계 역행 입력 포함.
  it('neverMovesTheViewBackward', () => {
    const steps: Array<{ f: number; t: number }> = [
      { f: F, t: F },
      { f: F, t: F + 3_000 },
      { f: F, t: F + 9_000 }, // 예산 초과 — 창이 선다
      { f: F + 6_000, t: F + 9_000 }, // 다음 응답 도착 — 따라잡음
      { f: F + 6_000, t: F + 9_000 }, // 같은 값
      { f: F + 11_000, t: F + 11_500 },
      { f: F + 11_000, t: F + 20_000 },
    ];
    let prev = -Infinity;
    for (const { f, t } of steps) {
      const w = computeScrollWindow({ range: '5m', requestedAt: f, nowMs: t, lagMs: SCROLL_LAG_MS });
      expect(w.viewUntil).toBeGreaterThanOrEqual(prev);
      expectBothInequalities(w);
      prev = w.viewUntil;
    }
    // 호출자가 T < F 를 넘겨도 함수 안의 max 가 왼쪽 부등식을 세운다.
    const early = computeScrollWindow({ range: '5m', requestedAt: F, nowMs: F - 5_000, lagMs: SCROLL_LAG_MS });
    expectBothInequalities(early);
  });

  // TZ-05 · BV-04 — L ≥ P + 2·δ + D (예산 둘은 리터럴 · 항등식이 아니다).
  it('keepsTheLagAtLeastPollPlusDeltaBudget', () => {
    expect(SCROLL_LAG_MS).toBeGreaterThanOrEqual(
      LIVE_POLL_MS + 2 * FETCH_DELTA_BUDGET_MS + COLLECT_DELAY_BUDGET_MS,
    );
  });

  // TZ-06 — 흐르는 범위와 다시 그리기 걸음(1m 0 = 식 밖 예외 · RP-06).
  it.each([
    ['1m', true, 0],
    ['5m', true, 150],
    ['10m', true, 300],
    ['1h', false, 0],
    ['24h', false, 0],
    ['7d', false, 0],
  ] as const)('clockRangeAndStep — %s', (range, clock, step) => {
    expect(isClockRange(range)).toBe(clock);
    expect(scrollStepMs(range)).toBe(step);
  });

  // TZ-07 — 눈금 = 간격 배수 전부 · 양 끝 포함. 전제: 창 폭 ≥ 간격(눈금 0개로 빈 통과 금지).
  it.each([
    ['1m', 60_000, 10_000],
    ['5m', 300_000, 60_000],
    ['10m', 600_000, 120_000],
  ] as const)('placesTicksOnRoundTimes — %s', (range: RangePreset, span, interval) => {
    // 양 끝이 배수에 떨어지는 창.
    const until = 1_730_000_040_000; // 120,000 의 배수
    const since = until - span;
    const ticks = computeTimeTicks(since, until, range);
    expect(span).toBeGreaterThanOrEqual(interval);
    expect(ticks.length).toBe(span / interval + 1);
    expect(ticks[0]).toBe(since);
    expect(ticks.at(-1)).toBe(until);
    for (const t of ticks) expect(t % interval).toBe(0);
    // 배수가 아닌 끝 — 창 안의 배수만.
    const shifted = computeTimeTicks(since + 1, until - 1, range);
    expect(shifted.length).toBe(span / interval - 1);
    for (const t of shifted) {
      expect(t % interval).toBe(0);
      expect(t).toBeGreaterThanOrEqual(since + 1);
      expect(t).toBeLessThanOrEqual(until - 1);
    }
  });
});

// [v0.8.0] Live OFF 한 칸 넘김 계산.
describe('한 칸 넘김', () => {
  const P = 1_730_000_000_000;
  const MIN = 60_000;

  it('다음 넘김 시각 = 창 끝 + 한 칸 + 여유(수집 지연 예산의 두 배)', () => {
    expect(PAGE_SETTLE_MS).toBe(2 * COLLECT_DELAY_BUDGET_MS);
    expect(nextPageAt(P, '1m')).toBe(P + MIN + PAGE_SETTLE_MS);
    expect(nextPageAt(P, '1h')).toBe(P + 60 * MIN + PAGE_SETTLE_MS);
  });

  it('제때 돌면 한 칸 · 늦게 돌면 다 찬 칸만큼 · 조금 일찍 돌아도 최소 한 칸', () => {
    expect(advancePinnedUntil(P, '1m', nextPageAt(P, '1m'))).toBe(P + MIN);
    expect(advancePinnedUntil(P, '1m', nextPageAt(P, '1m') + 2 * MIN + 10)).toBe(P + 3 * MIN);
    expect(advancePinnedUntil(P, '1m', nextPageAt(P, '1m') - 5)).toBe(P + MIN);
  });

  it('바로 다음 칸일 때만 미끄러진다', () => {
    const a = { since: P - MIN, until: P };
    expect(isNextPage(a, { since: P, until: P + MIN })).toBe(true);
    expect(isNextPage(a, { since: P + MIN, until: P + 2 * MIN })).toBe(false); // 건너뜀
    expect(isNextPage(a, { since: P, until: P + 5 * MIN })).toBe(false); // 길이가 다름(범위 바뀜)
    expect(isNextPage(a, { since: P - 10, until: P + MIN - 10 })).toBe(false);
  });

  it('미끄러짐: 시작 = 옛 창 · 가운데 = 반 칸 · 끝 이후 = 새 창 · 시작 전은 옛 창', () => {
    const from = { since: P - MIN, until: P };
    const to = { since: P, until: P + MIN };
    expect(slideWindow(from, to, 0, 0)).toEqual({ viewSince: from.since, viewUntil: from.until });
    const mid = slideWindow(from, to, 0, PAGE_SLIDE_MS / 2);
    expect(mid.viewSince).toBeCloseTo(P - MIN / 2, 3);
    expect(mid.viewUntil - mid.viewSince).toBeCloseTo(MIN, 3); // 창 길이는 그대로
    expect(slideWindow(from, to, 0, PAGE_SLIDE_MS + 100)).toEqual({ viewSince: to.since, viewUntil: to.until });
    expect(slideWindow(from, to, 100, 0)).toEqual({ viewSince: from.since, viewUntil: from.until });
    // 천천히 출발 — 처음 10% 시간에 10% 보다 적게 간다.
    const early = slideWindow(from, to, 0, PAGE_SLIDE_MS * 0.1);
    expect(early.viewSince - from.since).toBeLessThan(MIN * 0.1);
  });
});

// [v0.8.0] Live OFF 차트 눈금 — 정해진 시각에 붙어 창이 밀려도 같은 시각을 가리킨다.
describe('Live OFF 눈금', () => {
  const P = 1_730_000_000_000; // 10초의 배수
  it('1분 창은 10초 배수 · 창이 밀려도 같은 시각 눈금은 그대로', () => {
    const a = computePageTicks(P - 60_000, P, '1m');
    expect(a).toHaveLength(7);
    expect(a.every((t) => t % 10_000 === 0)).toBe(true);
    const b = computePageTicks(P - 60_000 + 3_333, P + 3_333, '1m');
    expect(b.filter((t) => a.includes(t)).length).toBe(6); // 한 개만 빠지고 나머지는 같은 시각
  });
  it('24시간 창은 현지 시각 0·4·8시 · 7일 창은 날마다 현지 0시', () => {
    const day = computePageTicks(P - 24 * 3_600_000, P, '24h');
    expect(day.length).toBeGreaterThanOrEqual(6);
    for (const t of day) {
      const d = new Date(t);
      expect(d.getMinutes()).toBe(0);
      expect(d.getHours() % 4).toBe(0);
    }
    const week = computePageTicks(P - 7 * 86_400_000, P, '7d');
    expect(week.length).toBeGreaterThanOrEqual(7);
    for (const t of week) expect(new Date(t).getHours()).toBe(0);
  });
  it('글자 — 7일은 날짜 · 1시간·24시간은 시:분 · 그 밖은 시:분:초', () => {
    const t = new Date(2026, 8, 27, 9, 5, 7).getTime();
    expect(formatPageTick(t, '7d')).toBe('9/27');
    expect(formatPageTick(t, '24h')).toBe('09:05');
    expect(formatPageTick(t, '1h')).toBe('09:05');
    expect(formatPageTick(t, '1m')).toBe('09:05:07');
  });
});
