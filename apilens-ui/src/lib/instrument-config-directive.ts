// [R21] 원격 계측 설정 — 3상태 지시 직렬화·역매핑 순수 함수 (신규 패턴 P-R21-1).
//
// R21/AC-02-1 (US-02): "4축 편집이 된다 — boolean 3축(captureParams·captureResultSet·requireEntryRoot)
// 각각 3상태(지시 없음 / 줄이기 / 기동값으로 되돌리기) + gateExcludes 목록 편집(추가·삭제)." (Plan verbatim)
//
// 설계 §2.3 코드 앵커 그대로 — 3상태를 boolean 2값으로 뭉개면 한 축만 바꿔도 나머지가
// 의도치 않게 확정된다(W-3 데이터 정확성 — 이 라운드 본체의 심장, 설계 §7.4 최위험 회귀 경로).
import type { InstrumentConfigPayload } from '../types/api';

/** 축 지시 3상태 — UI 내부 상태명이며 API 전송값 아님 (전송은 boolean 또는 필드 생략). planner §5.2 단일명. */
export type AxisDirective = 'none' | 'reduce' | 'restore';

/** boolean 3축 필드명 (BE InstrumentConfigPayload record 필드명 그대로). */
export type BooleanAxis = 'captureParams' | 'captureResultSet' | 'requireEntryRoot';

/** R21/AC-02-4 — 축별 "줄이기" 가 보내는 boolean 값의 단일 정의 (방향 반전 단일 출처).
 *  근거 실측: RemoteConfigGate.java `applyBestEffort` 3분지 —
 *    params: `!desired ‖ desired == 기동값` → 적용 / resultSet 동형 /
 *    entryRoot: `desired ‖ desired == 기동값` → 적용 — **반전** (켜는 쪽이 줄이는 방향). */
export const AXIS_REDUCE_VALUE: Record<BooleanAxis, boolean> = {
  captureParams: false,
  captureResultSet: false,
  requireEntryRoot: true, // 방향 반전 — 켜는 쪽이 줄이는 방향
};

/** 화면 폼 상태 — 3축 AxisDirective + gateExcludes 목록 (설계 §4.3 단일명). */
export interface InstrumentConfigForm {
  captureParams: AxisDirective;
  captureResultSet: AxisDirective;
  requireEntryRoot: AxisDirective;
  gateExcludes: string[];
}

/** R21/AC-02-3 — "지시 없음" = 키 자체 부재(conditional spread). `captureParams: undefined` 대입 금지
 *  (JSON.stringify 가 undefined 를 떨구긴 하지만, "명시 생략" 계약을 코드 모양으로도 드러낸다).
 *  Plan AC-02-3 verbatim: "저장 API 는 전체 교체이므로 '지시 없음' 축은 PUT payload 에서 필드 생략으로
 *  표현한다" (비협상 계열 — W-3 데이터 정확성). */
export function toPutPayload(form: InstrumentConfigForm): InstrumentConfigPayload {
  return {
    ...axisEntry('captureParams', form.captureParams),
    ...axisEntry('captureResultSet', form.captureResultSet),
    ...axisEntry('requireEntryRoot', form.requireEntryRoot),
    // 항상 명시 전송(빈 배열 포함) — 화면 상태 ↔ payload 1:1 + 빈/부재 정규화 책임을 server
    // 한 곳에 고정 (설계 §4.2-(a) 정정 사유. 빈 배열은 server 정규화로 agent 에 도달하지 않는다 — U-43).
    gateExcludes: form.gateExcludes,
  };
}

function axisEntry(axis: BooleanAxis, d: AxisDirective): Partial<InstrumentConfigPayload> {
  if (d === 'none') return {}; // 필드 생략
  const reduce = AXIS_REDUCE_VALUE[axis];
  return { [axis]: d === 'reduce' ? reduce : !reduce };
}

/** GET 역매핑 — null(404) = 전 축 none + 빈 목록 (빈 상태가 곧 기본 폼 — §0.7-3 봉인). 필드 부재 → none. */
export function fromGetPayload(payload: InstrumentConfigPayload | null): InstrumentConfigForm {
  const axis = (name: BooleanAxis): AxisDirective => {
    const v = payload?.[name];
    if (v === undefined) return 'none';
    return v === AXIS_REDUCE_VALUE[name] ? 'reduce' : 'restore';
  };
  return {
    captureParams: axis('captureParams'),
    captureResultSet: axis('captureResultSet'),
    requireEntryRoot: axis('requireEntryRoot'),
    gateExcludes: payload?.gateExcludes ?? [],
  };
}

/** [S-117 적용] 빈 지시 단일 술어 — 정의 1 · 소비 3 (canSave / U-32 렌더 조건 / 저장 핸들러 방어 1줄).
 *  R21/AC-02 (BL-09 확정 — UX §4.5): 전부 "지시 없음" + 빈 목록의 저장은 비활성 + 철회 유도. */
export function isEmptyDirective(form: InstrumentConfigForm): boolean {
  return (
    form.captureParams === 'none' &&
    form.captureResultSet === 'none' &&
    form.requireEntryRoot === 'none' &&
    form.gateExcludes.length === 0
  );
}

/** isDirty 판정 — 폼 ↔ 스냅샷 대조. gateExcludes 는 집합 비교(순서 무관) — 채택 사유(설계 §2.3 주석 의무):
 *  UI 에 재정렬 표면이 없고(추가·삭제만) 중복은 추가 시점에 차단(U-38)되므로 집합 동등이 안전하다. */
export function formsEqual(a: InstrumentConfigForm, b: InstrumentConfigForm): boolean {
  if (
    a.captureParams !== b.captureParams ||
    a.captureResultSet !== b.captureResultSet ||
    a.requireEntryRoot !== b.requireEntryRoot
  ) {
    return false;
  }
  if (a.gateExcludes.length !== b.gateExcludes.length) return false;
  const setB = new Set(b.gateExcludes);
  return a.gateExcludes.every((item) => setB.has(item));
}
