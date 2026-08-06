// [R21] `-D` 옵션 문자열 생성기 — 로컬 조립 순수 함수 (US-05, R-U2 확정).
//
// R21/AC-05-1 (Plan verbatim): "원격 설정 화면과 같은 표면에 붙는다 — Setup wizard 화면이 아니다
// (표면 분리 — NFR-08·G-16). wizard 빌더에 새 `-D` 키 배선 0." — 그래서 이 파일은
// wizard `agent-option-builder.ts` 를 import 하지도, 수정하지도 않는다 (**무접촉**).
// R21/AC-05-5 (Plan verbatim): "생성기는 로컬 문자열 조립이다 — 서버 호출 0."
//
// 설계 §2.5 확정 — 조립 규칙은 UX §4.6 5항 그대로: 기본값과 다른 것만 출력 / exclude 값 =
// 콤마 join + MyBatis 체크 시 1회 추가 / 공백 1개 한 줄 / 전부 기본값 → 빈 문자열 →
// [복사] 비활성(C-07) + U-34 / require-entry-root 비범위(U-37).

/** R21/AC-05-2 — MyBatis 전량 제외 대상 FQCN 단일 출처 (T-22 라벨도 이 상수를 렌더). */
export const MYBATIS_MAPPER_PROXY = 'org.apache.ibatis.binding.MapperProxy';

// ⚠️ -D 키 3종은 agent 가 실제로 읽는 키와 반드시 일치해야 한다 (SSOT) —
//    AgentConfig.java 실측 리터럴 그대로: PROP_CAPTURE_PARAMS = "apilens.jdbc.capture-params" /
//    PROP_CAPTURE_RESULT_SET = "apilens.jdbc.capture-result-set" /
//    PROP_EXCLUDE_PACKAGES = "apilens.instrument.exclude-packages".
//    키가 틀리면 agent 가 옵션을 조용히 무시한다 (R5 trace 0건 버그 계열).
//    instrument-option-generator.test.ts 가 AgentConfig.java 소스를 직접 읽어
//    "출력 -D 키 ⊆ SSOT 키 집합" 을 단언한다 (agent-option-builder.test.ts FT-D1 전례 동형).
const KEY_CAPTURE_PARAMS = 'apilens.jdbc.capture-params';
const KEY_CAPTURE_RESULT_SET = 'apilens.jdbc.capture-result-set';
const KEY_EXCLUDE_PACKAGES = 'apilens.instrument.exclude-packages';

export interface InstrumentOptionInput {
  /** 화면 기본 true (옵션 기본값) — false 일 때만 토큰 출력. */
  captureParams: boolean;
  /** 화면 기본 false — true 일 때만 토큰 출력. */
  captureResultSet: boolean;
  /** 항목 ≥ 1 일 때만 토큰 출력. 검증(콤마 금지·trim)은 편집기 층 — 여기는 조립만. */
  excludePackages: string[];
  /** true 면 MYBATIS_MAPPER_PROXY 를 목록 끝에 추가 (수기 중복 시 1회만). */
  mybatisAll: boolean;
}

/** 토큰 순서 고정 (골든 테스트 대상): capture-params → capture-result-set → exclude-packages.
 *  기본값과 다른 것만 출력한다 — 기본값 그대로면 옵션 없이도 그 값이므로 출력이 거짓말이 된다 (UX §4.6-1). */
export function buildInstrumentOptionTokens(input: InstrumentOptionInput): string[] {
  const tokens: string[] = [];
  if (!input.captureParams) {
    tokens.push(`-D${KEY_CAPTURE_PARAMS}=false`);
  }
  if (input.captureResultSet) {
    tokens.push(`-D${KEY_CAPTURE_RESULT_SET}=true`);
  }
  const packages = [...input.excludePackages];
  if (input.mybatisAll && !packages.includes(MYBATIS_MAPPER_PROXY)) {
    packages.push(MYBATIS_MAPPER_PROXY); // 수기 중복 시 1회만 (UX §4.6-2)
  }
  if (packages.length >= 1) {
    tokens.push(`-D${KEY_EXCLUDE_PACKAGES}=${packages.join(',')}`);
  }
  return tokens;
}

/** 구분자 = 공백 1개, 한 줄 (UX §4.6 규칙 3). 전부 기본값이면 빈 문자열 — [복사] 비활성(C-07) + U-34. */
export function buildInstrumentOptionString(input: InstrumentOptionInput): string {
  return buildInstrumentOptionTokens(input).join(' ');
}
