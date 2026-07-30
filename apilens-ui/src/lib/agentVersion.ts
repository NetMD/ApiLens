// [Phase R19] agent 버전이 계측 제외 옵션을 알아듣는지 판정 (설계 §2.3-E · BL-08).
//
// ⚠️ 문자열 비교 금지 — `'0.10.0' < '0.4.0'` 이 참이 되어 최신 agent 를 미지원으로 표시한다.
//    자리별 숫자로만 비교한다.
// ⚠️ 읽을 수 없는 값(자리 수가 다름 · 숫자가 아님 · 값 없음)은 **UNKNOWN** 이다.
//    UNKNOWN 을 UNSUPPORTED 로 반올림하지 않는다 — 확인하지 못한 것을 "미달" 로 단정하면
//    운영자가 멀쩡한 agent 를 올리러 간다.
import { MIN_AGENT_VERSION_FOR_EXCLUDE } from './instrumentThresholds';

/** 계측 제외 옵션 지원 여부 3분류. */
export type VersionSupport = 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN';

/** `123` 처럼 숫자만으로 이루어졌는가 (앞의 `+`/`-`/공백/소수점 전부 거부). */
function isDigits(part: string): boolean {
  return part.length > 0 && /^[0-9]+$/.test(part);
}

/**
 * agent 버전 문자열이 계측 제외 옵션을 지원하는지 판정한다.
 *
 * - `null` / 자리 수가 3 이 아님 / 숫자가 아닌 조각 포함(`0.4.0-SNAPSHOT`) → `UNKNOWN`
 * - `[0,4,0]` 이상 → `SUPPORTED` (같으면 지원)
 * - 미만 → `UNSUPPORTED`
 */
export function checkExcludeSupport(raw: string | null): VersionSupport {
  if (raw === null) return 'UNKNOWN';
  const parts = raw.trim().split('.');
  if (parts.length !== MIN_AGENT_VERSION_FOR_EXCLUDE.length) return 'UNKNOWN';

  const nums: number[] = [];
  for (const part of parts) {
    if (!isDigits(part)) return 'UNKNOWN';
    nums.push(Number(part));
  }

  for (let i = 0; i < MIN_AGENT_VERSION_FOR_EXCLUDE.length; i += 1) {
    const current = nums[i] ?? 0;
    const required = MIN_AGENT_VERSION_FOR_EXCLUDE[i] ?? 0;
    if (current > required) return 'SUPPORTED';
    if (current < required) return 'UNSUPPORTED';
  }
  // 모든 자리가 같음 = 최소 버전과 동일 → 지원.
  return 'SUPPORTED';
}
