// Phase R12 — FE 상수 단일 거주지 (설계 §3.1.7 — 인라인 매직 넘버 금지).
//
// 디바운스 3종은 architect 확정값 (설계 §2-B3 — UX §5.2 권고 채택 확정).
// RETENTION_MIN/MAX 는 서버 SettingsRegistry 와 4표면 동일값 의무 (설계 §2-B1 — SSOT 는 서버):
//   ① 서버 상수 ② 본 파일 ③ T-08 보간 {min}=1,{max}=3650 ④ 경계 테스트 입력.

/** Phase R12 (FR-B1, AC-B1-3): retention.days 하한 — 서버 RETENTION_DAYS_MIN 과 동일값. */
export const RETENTION_MIN = 1;

/** Phase R12 (FR-B1, AC-B1-3): retention.days 상한 — 서버 RETENTION_DAYS_MAX 와 동일값 (architect 확정 3650). */
export const RETENTION_MAX = 3650;

/** Phase R12 (FR-C2, AC-C2-3): operation 검색 input → URL 디바운스 (설계 §2-B3 확정 300ms). */
export const SEARCH_DEBOUNCE_MS = 300;

/** Phase R12 (FR-B3, AC-B3-4): 룰 토글 → 프리뷰 재요청 디바운스 (설계 §2-B3 확정 200ms — 시연 즉답성, 300ms 초과 금지). */
export const PREVIEW_TOGGLE_DEBOUNCE_MS = 200;

/** Phase R12 (FR-B3, AC-B3-2): 직접 입력 textarea → 프리뷰 디바운스 (설계 §2-B3 확정 400ms — 타이핑 과요청 방지). */
export const PREVIEW_INPUT_DEBOUNCE_MS = 400;

/** Phase R12 (FR-C2): 검색어 길이 cap (설계 §6.3 — FE maxLength. BE 는 파라미터 바인딩이라 길이 무해). */
export const OPERATION_QUERY_MAX_LENGTH = 200;
