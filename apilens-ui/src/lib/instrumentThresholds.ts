// [Phase R19] 계측 분석 화면 임계 상수 단일 거주지 (설계 §2.3-D — 인라인 매직 넘버 금지).
//
// ⚠️⚠️ 비율은 **어디서도 100 을 곱한 값으로 비교하지 않는다.** 100 을 곱하는 자리는 화면 표시
//      시점 1곳(lib/format.ts formatRatioPercent)뿐이다. 백분율 숫자(50·80)와 비교하도록 쓰면
//      0.0~1.0 실수와 견주게 되어 **경고가 영원히 안 뜬다** — 그것도 "경고가 안 뜨는" 위험한
//      방향으로 100배 틀린다. 회귀 가드: 백분율 직접 비교 0건 / 임계 실수는 이 파일에만 산다.

/**
 * 약한 경고(주의) 임계 — 빼고 나면 "span 이 하나뿐인 trace" 비율이 이 값을 **초과**할 때.
 * 0.0~1.0 실수 도메인.
 */
export const ORPHAN_WARN_RATIO = 0.50;

/**
 * 강한 경고 임계 — 이 값을 **초과**하면 [영향을 확인했어요] 확인 한 단계를 거친다.
 * 확인은 "읽었다는 표시" 일 뿐 **막지 않는다**(확인 버튼은 어떤 이유로도 비활성이 되지 않는다).
 */
export const ORPHAN_SEVERE_RATIO = 0.80;

/**
 * 계측 제외 옵션을 알아듣는 최소 agent 버전 (v0.4.0 에서 옵션이 들어왔다).
 * 자리별 숫자 비교용 [major, minor, patch] — 문자열 비교 금지(`0.10.0` 이 `0.4.0` 보다 작다고 나온다).
 */
export const MIN_AGENT_VERSION_FOR_EXCLUDE = [0, 4, 0] as const;

/** 분석 구간 선택지 — 서버 화이트리스트(1 / 6 / 24)와 같은 값. "전체" 선택지는 만들지 않는다. */
export const ANALYSIS_WINDOW_HOURS = [1, 6, 24] as const;

/** 분석 구간 기본값 = 최근 1시간. */
export const DEFAULT_WINDOW_HOURS = 1;

/** 분석 구간 값 타입 — 서버가 받는 세 값만 존재한다. */
export type WindowHours = (typeof ANALYSIS_WINDOW_HOURS)[number];

/** 구간 세그먼트 라벨 (UX 와이어프레임 §4.2 그대로). */
export const WINDOW_LABELS: Readonly<Record<WindowHours, string>> = {
  1: '최근 1시간',
  6: '최근 6시간',
  24: '최근 24시간',
};

/** 순위표 정렬 축 — 서버가 준 축별 순위(spanRank 등)를 그대로 정렬 키로 쓴다. */
export type SortAxis = 'span' | 'payloadCount' | 'payloadBytes';

/** 정렬 세그먼트 라벨 = 컬럼 머리와 **같은 문자열** (T-16 / T-17). */
export const SORT_AXIS_LABELS: Readonly<Record<SortAxis, string>> = {
  span: 'span 수',
  payloadCount: 'payload 건수',
  payloadBytes: 'payload 크기',
};
