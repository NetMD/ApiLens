// [R21] 3상태 직렬화·역매핑 (P-R21-1) — 설계 §7.3 경계값 표 B-01~B-11 · B-18 · B-19.
//
// R21/AC-02-3 verbatim: "저장 API 는 전체 교체이므로 '지시 없음' 축은 PUT payload 에서 필드
// 생략으로 표현한다 — 화면이 '지시 없음' 을 표현하지 못하면 한 축만 바꿔도 나머지가 의도치
// 않게 확정된다 (미관이 아니라 데이터 정확성 문제 W-3)." (비협상 계열 — 설계 §7.4 최위험 회귀 경로)
//
// EXT-003 lock-in 회귀 가드 — 정방향 동사(serializes/maps/keeps/roundTrips/detects) 만 사용.
// B-11 은 undefined 대입이 아니라 `in` 연산자로 **키 자체 부재**를 증명한다 (S-127 지정 경로).
import { describe, expect, it } from 'vitest';
import {
  AXIS_REDUCE_VALUE,
  formsEqual,
  fromGetPayload,
  isEmptyDirective,
  toPutPayload,
} from '../lib/instrument-config-directive';
import type {
  AxisDirective,
  BooleanAxis,
  InstrumentConfigForm,
} from '../lib/instrument-config-directive';

function makeForm(patch: Partial<InstrumentConfigForm> = {}): InstrumentConfigForm {
  return {
    captureParams: 'none',
    captureResultSet: 'none',
    requireEntryRoot: 'none',
    gateExcludes: [],
    ...patch,
  };
}

describe('toPutPayload / fromGetPayload — 3축 × 3상태 전수 매핑 (B-01~B-09)', () => {
  // UX §4.3 매핑 표 그대로 — 특히 requireEntryRoot 는 방향 반전 (reduce→true·restore→false).
  const CASES: ReadonlyArray<{
    axis: BooleanAxis;
    directive: AxisDirective;
    expected: boolean | undefined; // undefined = 필드 생략
  }> = [
    { axis: 'captureParams', directive: 'none', expected: undefined },
    { axis: 'captureParams', directive: 'reduce', expected: false },
    { axis: 'captureParams', directive: 'restore', expected: true },
    { axis: 'captureResultSet', directive: 'none', expected: undefined },
    { axis: 'captureResultSet', directive: 'reduce', expected: false },
    { axis: 'captureResultSet', directive: 'restore', expected: true },
    { axis: 'requireEntryRoot', directive: 'none', expected: undefined },
    { axis: 'requireEntryRoot', directive: 'reduce', expected: true }, // 방향 반전
    { axis: 'requireEntryRoot', directive: 'restore', expected: false }, // 방향 반전
  ];

  for (const { axis, directive, expected } of CASES) {
    it(`serializes ${axis}=${directive} → ${expected === undefined ? '필드 생략' : String(expected)}`, () => {
      const payload = toPutPayload(makeForm({ [axis]: directive }));
      if (expected === undefined) {
        // "지시 없음" = 키 자체 부재 (undefined 값 대입이 아니다).
        expect(axis in payload).toBe(false);
      } else {
        expect(payload[axis]).toBe(expected);
      }
    });

    it(`maps GET ${axis}=${expected === undefined ? '부재' : String(expected)} → ${directive}`, () => {
      const payload = expected === undefined ? {} : { [axis]: expected };
      expect(fromGetPayload(payload)[axis]).toBe(directive);
    });
  }

  it('keepsReduceValueSingleSource — AXIS_REDUCE_VALUE 가 반전 축을 단일 정의한다 (AC-02-4)', () => {
    expect(AXIS_REDUCE_VALUE.captureParams).toBe(false);
    expect(AXIS_REDUCE_VALUE.captureResultSet).toBe(false);
    expect(AXIS_REDUCE_VALUE.requireEntryRoot).toBe(true); // 켜는 쪽이 줄이는 방향
  });
});

describe('왕복 (B-10) · none 직렬화 (B-11)', () => {
  it('roundTripsArbitraryForm — fromGetPayload(toPutPayload(f)) 가 폼과 동등 (B-10)', () => {
    const form = makeForm({
      captureParams: 'reduce',
      requireEntryRoot: 'restore',
      gateExcludes: ['com.acme.mapper.NoisyMapper', 'com.acme.batch.OrderSyncJob'],
    });
    expect(formsEqual(fromGetPayload(toPutPayload(form)), form)).toBe(true);
  });

  it('roundTripsEmptyGateExcludes — 빈 배열 → 서버 정규화로 GET 부재 → [] 역매핑 = 동등 (B-10)', () => {
    // 서버가 빈 목록을 null 정규화 + NON_NULL 생략하므로 GET 에는 필드 부재로 돌아온다 (설계 §4.2).
    const form = makeForm({ captureParams: 'reduce' });
    const echoed = toPutPayload(form);
    delete echoed.gateExcludes; // 서버 정규화 모사 — 부재 → [] 역매핑
    expect(formsEqual(fromGetPayload(echoed), form)).toBe(true);
  });

  it('serializesAllNoneWithBooleanKeysAbsent — 전 축 none → boolean 키 3종 자체 부재 + gateExcludes 존재 (B-11)', () => {
    const payload = toPutPayload(makeForm());
    // S-127 최위험 회귀 경로 — `in` 연산자로 부재를 증명 (undefined 대입 검출).
    expect('captureParams' in payload).toBe(false);
    expect('captureResultSet' in payload).toBe(false);
    expect('requireEntryRoot' in payload).toBe(false);
    // gateExcludes 는 항상 명시 전송 (빈 배열 포함 — 설계 §4.2-(a)).
    expect('gateExcludes' in payload).toBe(true);
    expect(payload.gateExcludes).toEqual([]);
  });

  it('mapsNullPayloadToEmptyForm — null(404) = 전 축 none + 빈 목록 (빈 상태가 곧 기본 폼)', () => {
    expect(fromGetPayload(null)).toEqual(makeForm());
  });
});

describe('isEmptyDirective (B-18) · formsEqual (B-19)', () => {
  it('detectsEmptyDirective — 전 축 none + 빈 목록 → true', () => {
    expect(isEmptyDirective(makeForm())).toBe(true);
  });

  it('acceptsListOnlyAsNonEmpty — 목록 1개만 있어도 false', () => {
    expect(isEmptyDirective(makeForm({ gateExcludes: ['com.acme.A'] }))).toBe(false);
  });

  it('acceptsSingleAxisAsNonEmpty — 축 1개만 reduce 여도 false', () => {
    expect(isEmptyDirective(makeForm({ captureParams: 'reduce' }))).toBe(false);
  });

  it('keepsSameFormAsClean — 스냅샷과 동일 폼 → isDirty 아님 (formsEqual true)', () => {
    const a = makeForm({ captureParams: 'reduce', gateExcludes: ['com.acme.A', 'com.acme.B'] });
    const b = makeForm({ captureParams: 'reduce', gateExcludes: ['com.acme.A', 'com.acme.B'] });
    expect(formsEqual(a, b)).toBe(true);
  });

  it('keepsReorderedListAsClean — 목록 순서만 다른 폼 → 집합 비교로 동등 (B-19 채택안)', () => {
    // 채택 사유 (설계 §2.3): UI 에 재정렬 표면이 없고 중복은 추가 시점 차단(U-38)이라 집합 동등이 안전.
    const a = makeForm({ gateExcludes: ['com.acme.A', 'com.acme.B'] });
    const b = makeForm({ gateExcludes: ['com.acme.B', 'com.acme.A'] });
    expect(formsEqual(a, b)).toBe(true);
  });

  it('detectsAxisChangeAsDirty — 축 하나만 달라도 다름', () => {
    expect(formsEqual(makeForm(), makeForm({ requireEntryRoot: 'reduce' }))).toBe(false);
  });

  it('detectsListChangeAsDirty — 목록 내용이 다르면 다름', () => {
    expect(
      formsEqual(makeForm({ gateExcludes: ['com.acme.A'] }), makeForm({ gateExcludes: [] })),
    ).toBe(false);
  });
});
