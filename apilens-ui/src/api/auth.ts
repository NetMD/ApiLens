// [Phase K] (US-04, AC-04-1): API Key 토큰 보관 — sessionStorage 단일 거주지. 사용자 명시 비협상 결정 (R14-D03 토큰 SSOT = server 起動 옵션, DB 저장 안 함). CLAUDE.md '절대 변경하지 말아야 할 결정 사항'.
//
// 설계 §2.6b / §5 상수표 그대로:
//   - sessionStorage 키 = 'apilens.apiKey' (단일 확정명 §1.3). localStorage 미사용 (프로젝트 전체 grep 0 관례).
//   - 탭 종료 시 휘발 (민감정보 노출 최소화, NFR-08 sessionStorage XSS 잔여 위험 수용).
//
// ⚠️ 부트스트랩 역설 회피 핵심 (BL-12): 토큰 보관은 sessionStorage 쓰기/읽기만. 어떤 보호 API 도 호출하지 않는다.
//    (토큰 입력 화면이 인증을 요구해 영구 잠김되는 모순 차단 — ApiKeySection 저장 핸들러가 이 모듈만 호출).

/** sessionStorage 키 — 설계 §1.3 단일 확정명. 다른 키 신설 금지. */
const KEY = 'apilens.apiKey';

/** 저장된 토큰을 반환. 미설정 시 null. client.ts buildHeaders 가 매 요청 조건부 첨부에 사용. */
export function getApiKey(): string | null {
  return sessionStorage.getItem(KEY);
}

/** 토큰을 sessionStorage 에 저장 (보호 API 호출 0 — 로컬 쓰기만, BL-12). */
export function setApiKey(token: string): void {
  sessionStorage.setItem(KEY, token);
}

/** 토큰 제거 ("변경" 버튼 / 401 재입력 유도 시). */
export function clearApiKey(): void {
  sessionStorage.removeItem(KEY);
}
