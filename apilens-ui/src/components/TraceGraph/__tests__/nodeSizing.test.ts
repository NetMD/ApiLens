// nodeSizing.radius 회귀 가드 (NFR-04 강제 — T-01~T-05).
//
// 공식 (설계서 §4.1 박제 + Phase F2 fix² US-03 MIN_R 갱신):
//   raw     = 4 + log10(max(durationMs, 0) + 1) * 1.5
//   clamped = min(12, max(6, raw))   ◀── Phase F2 fix² US-03: floor 5 → 6
//   final   = isError ? clamped + 2 : clamped
//
// 부동소수점 오차 회피를 위해 toBeCloseTo(_, 2) 사용 (NFR-04 박제).
import { describe, expect, it } from 'vitest';
import { radius } from '../nodeSizing';

describe('nodeSizing.radius', () => {
  // T-01: 최소 clamp — duration 0 일 때 raw 4 → 6 으로 lift (Phase F2 fix² US-03 갱신).
  it('T-01: radius(0, false) → 6 (clamp 최소, Phase F2 fix² floor 6)', () => {
    expect(radius(0, false)).toBe(6);
  });

  // T-02: 매우 짧은 호출 — duration 1 일 때 raw ≈ 4.45 → 6 으로 clamp (Phase F2 fix² US-03 갱신).
  it('T-02: radius(1, false) → 6 (raw 4.45 → clamp 6)', () => {
    expect(radius(1, false)).toBe(6);
  });

  // T-03: 중간 범위 정확값 — duration 100 → raw = 4 + 1.5 * log10(101) ≈ 4 + 1.5 * 2.0043 ≈ 7.0064
  it('T-03: radius(100, false) ≈ 7 (정확값 7.006...)', () => {
    expect(radius(100, false)).toBeCloseTo(7.006, 2);
  });

  // T-04: 1초 노드 — duration 1000 → raw = 4 + 1.5 * log10(1001) ≈ 4 + 1.5 * 3.00043 ≈ 8.5006
  it('T-04: radius(1000, false) ≈ 8.5 (정확값 8.5006...)', () => {
    expect(radius(1000, false)).toBeCloseTo(8.501, 2);
  });

  // T-05: error 가산 — T-03 + 2 = 9.006...
  it('T-05: radius(100, true) === radius(100, false) + 2', () => {
    expect(radius(100, true)).toBeCloseTo(radius(100, false) + 2, 5);
  });

  // 추가 안전망 (architect 권장 — 강제 아님)
  it('NaN 안전망: radius(NaN, false) === 6 (Phase F2 fix² floor 6)', () => {
    expect(radius(Number.NaN, false)).toBe(6);
  });

  it('음수 안전망: radius(-50, false) === 6 (Phase F2 fix² floor 6)', () => {
    expect(radius(-50, false)).toBe(6);
  });

  it('max clamp: radius(1e6, false) === 12', () => {
    expect(radius(1_000_000, false)).toBe(12);
  });

  it('error 가산은 clamp 후: radius(1e6, true) === 14', () => {
    expect(radius(1_000_000, true)).toBe(14);
  });

  // Phase F2 fix² 신규 floor 단언 케이스 (US-03 AC-03-1/2 박제 grep 가드):
  // floor 회귀 발생 시 한 곳만 grep 으로 식별 가능하도록 명시적 케이스 추가.
  it('floor 회귀 가드: radius(0, false) === 6 (Phase F2 fix² US-03 MIN_R=6)', () => {
    expect(radius(0, false)).toBe(6);
  });

  it('floor 회귀 가드: radius(1, false) === 6 (Phase F2 fix² US-03 MIN_R=6)', () => {
    expect(radius(1, false)).toBe(6);
  });

  // 중간 범위 공식값 회귀 가드 (d=10): raw = 4 + 1.5 * log10(11) ≈ 4 + 1.5 * 1.0414 ≈ 5.5621
  // → MIN_R=6 clamp 적용 → 6 (이전 floor=5 시점에는 5.5621 통과).
  it('floor 회귀 가드: radius(10, false) === 6 (raw 5.56 → clamp 6, Phase F2 fix² US-03)', () => {
    expect(radius(10, false)).toBe(6);
  });

  // error 가산 + MAX_R clamp 경계: radius(10000, true) — raw=4+1.5*log10(10001)≈10.0007
  // → clamp 적용 (raw < MAX_R 이므로 raw 그대로) → +ERROR_BONUS=2 → ≈12.0007
  it('error 가산 경계: radius(10000, true) ≈ 12.0 (clamp 후 +2)', () => {
    expect(radius(10_000, true)).toBeCloseTo(12.0, 2);
  });
});
