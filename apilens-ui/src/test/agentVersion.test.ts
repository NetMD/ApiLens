// [Phase R19] agent 버전 비교 경계값 단위 테스트 (설계 §8.3 B-20~B-24).
//
// 검증 의무 (정방향 동사 명시 — lock-in 회귀 가드. 본 라운드는 사용자 명시 비협상 결정 D-1~D-14 보유):
//   reportsUnknownWhenVersionMissing        — 값 없음은 "확인 안 됨" (미달로 단정하지 않는다)
//   reportsUnsupportedBelowMinimumVersion   — 최소 버전 미만은 미지원
//   acceptsExactMinimumVersionAsSupported   — 경계(같음)는 지원 (AC-07-2 verbatim: "0.4.0 이상이면 지원")
//   acceptsDoubleDigitMinorAsSupported      — 0.10.0 > 0.4.0 (문자열 비교면 틀리는 자리)
//   reportsUnknownForUnreadableVersionForms — 자리 수 부족 · 숫자 아닌 조각 · 빈 문자열
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { describe, expect, it } from 'vitest';
import { checkExcludeSupport } from '../lib/agentVersion';

describe('checkExcludeSupport — 계측 제외 옵션 지원 판정 (자리별 숫자 비교)', () => {
  it('reportsUnknownWhenVersionMissing — B-20: null 은 UNKNOWN (미달 단정 금지)', () => {
    expect(checkExcludeSupport(null)).toBe('UNKNOWN');
  });

  it('reportsUnsupportedBelowMinimumVersion — B-21: 0.3.9 는 UNSUPPORTED', () => {
    expect(checkExcludeSupport('0.3.9')).toBe('UNSUPPORTED');
  });

  it('acceptsExactMinimumVersionAsSupported — B-22: 0.4.0 은 SUPPORTED (경계 — 같음은 지원)', () => {
    expect(checkExcludeSupport('0.4.0')).toBe('SUPPORTED');
  });

  it('acceptsDoubleDigitMinorAsSupported — B-23: 0.10.0 은 SUPPORTED (문자열 비교면 틀리는 자리)', () => {
    expect(checkExcludeSupport('0.10.0')).toBe('SUPPORTED');
  });

  it('reportsUnknownForUnreadableVersionForms — B-24: 0.4 / 0.4.0-SNAPSHOT / 빈 문자열 전부 UNKNOWN', () => {
    expect(checkExcludeSupport('0.4')).toBe('UNKNOWN');
    expect(checkExcludeSupport('0.4.0-SNAPSHOT')).toBe('UNKNOWN');
    expect(checkExcludeSupport('')).toBe('UNKNOWN');
  });

  it('acceptsHigherMajorAsSupported — 1.0.0 은 SUPPORTED (앞자리 우선 비교)', () => {
    expect(checkExcludeSupport('1.0.0')).toBe('SUPPORTED');
    expect(checkExcludeSupport('0.5.0')).toBe('SUPPORTED');
  });
});
