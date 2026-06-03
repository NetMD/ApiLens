// computeRange 4건 박제 (D-02 — passWithNoTests 미설정 fail 회피 + 시간 파싱 핵심 검증).
// 4 preset 각각 (until - since) 가 예상 millis인지 확인.
// computeWindow: Live ON 슬라이딩 / OFF pinned 분기 검증.
import { describe, expect, it } from 'vitest';
import { computeRange, computeWindow } from '../lib/time';

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
