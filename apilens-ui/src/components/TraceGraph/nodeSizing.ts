// nodeSizing — span duration → SVG circle radius (px) 순수 함수.
//
// 공식 (CL-02 architect 결정 박제):
//   raw     = 4 + log10(max(durationMs, 0) + 1) * 1.5
//   clamped = min(12, max(6, raw))   ◀── Phase F2 fix² US-03: MIN_R 5 → 6
//   final   = isError ? clamped + 2 : clamped
//
// 비정상 입력 안전망:
//   NaN      → 6 (base, error 시 8)   ◀── Phase F2 fix² US-03
//   Infinity → 12 (max, error 시 14)  ◀── error 가산은 clamp 적용 후
//   음수     → 0 으로 보정 후 공식 적용
//
// 결과 범위:
//   정상 노드: [6, 12] (px)           ◀── Phase F2 fix² US-03
//   error 노드: [8, 14] (px)          ◀── error +2 가산은 clamp 적용 후
//
// 운영 trace 1ms~1초 범위에서 6px → 9px 변화로 시각 분별 가능.
// 1ms 이하 구간이 raw≈4.5 가 되어 raw-eye 식별 어려웠던 회귀를 차단 (US-03 So that).
//
// 단위 테스트: __tests__/nodeSizing.test.ts (T-01~T-05 강제, NFR-04).
// Phase F2 fix² 추가 단언: d=0/1 → r=6 (floor 회귀 가드).

const BASE = 4;
const SLOPE = 1.5;
const MIN_R = 6; // Phase F2 fix² (US-03, AC-03-1): 5 → 6 (사용자 명시 결정)
const MAX_R = 12;
const ERROR_BONUS = 2;

/**
 * span duration → SVG circle radius (px). 순수 함수, side-effect 0.
 *
 * @param durationMs - span duration (ms). NaN / Infinity / 음수는 안전 fallback.
 * @param isError    - span.status === 'ERROR' 여부. true 시 +2px 가산 (clamp 후).
 * @returns radius in px. 정상: [6, 12], error: [8, 14].   ◀── Phase F2 fix² US-03
 */
export function radius(durationMs: number, isError: boolean): number {
  // NaN / Infinity guard
  if (!Number.isFinite(durationMs)) {
    if (Number.isNaN(durationMs)) {
      return isError ? MIN_R + ERROR_BONUS : MIN_R;
    }
    // Infinity (양/음수) → max clamp 적용 후 error 가산
    return isError ? MAX_R + ERROR_BONUS : MAX_R;
  }

  const safeMs = Math.max(0, durationMs);
  const raw = BASE + Math.log10(safeMs + 1) * SLOPE;
  const clamped = Math.min(MAX_R, Math.max(MIN_R, raw));
  return isError ? clamped + ERROR_BONUS : clamped;
}
