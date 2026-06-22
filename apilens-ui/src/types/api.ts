// ApiLens 서버 응답 타입 정의 — docs/api.md 박제 그대로.
// any 사용 0건 (NFR-03). attributes는 unknown으로 안전 분기.

/** trace 상태 — server 응답값 그대로. HTTP status code 아님. */
export type TraceStatus = 'OK' | 'ERROR';

/** GET /v1/traces 응답의 traces[] 요소. */
export interface TraceSummary {
  traceId: string;
  rootOperation: string;
  serviceName: string;
  startTime: number; // epoch millis
  durationMs: number;
  status: TraceStatus;
  spanCount: number;
  hasError: boolean;
}

/** GET /v1/traces 응답 body. */
export interface TracesResponse {
  traces: TraceSummary[];
  nextCursor: string | null;
}

/**
 * Service healthStatus — D-03 비협상 4분기 (sliding window 5분 / 30분 임계).
 *   - active   : ≤ 5분 전 마지막 trace
 *   - stale    : 5분~30분 전 마지막 trace
 *   - inactive : > 30분 전 마지막 trace
 *   - never    : last_seen_at IS NULL (wizard 등록 후 trace 미수신)
 */
export type HealthStatus = 'active' | 'stale' | 'inactive' | 'never';

/** Service 등록 출처 — D-02 비협상 (경로 A wizard / 경로 B auto). */
export type ServiceSource = 'wizard' | 'auto';

/**
 * GET /v1/services 응답의 services[] 요소.
 *
 * [Phase H] W-01 — 기존 `lastSeen` 즉시 제거 → `lastSeenAt` 단일화 (nullable).
 * `registeredAt` / `source` / `healthStatus` 3 필드 신규 (사용자 명시 비협상 결정).
 * docs/api.md GET /v1/services 응답 모양 명시 그대로.
 */
export interface ServiceInfo {
  name: string;
  /** services row 가 처음 등록된 시점 (epoch millis). UPSERT ON CONFLICT 시에도 보존. */
  registeredAt: number;
  /** 마지막 trace 도착 시점 (epoch millis). wizard 등록 후 trace 미수신 시 null. */
  lastSeenAt: number | null;
  /** 처음 등록 시점 출처. UPSERT 시에도 보존 (wizard 가 먼저 들어오면 'wizard' 유지). */
  source: ServiceSource;
  /**
   * Phase R12 (FR-A3, AC-A3-3) — 의미 변경: "누적 전수" → "최근 24시간 trace 수"
   * (start_time >= now − 24h 윈도우 카운트, 설계 §2-A3). 필드명·타입은 무변경 (계약 파손 0).
   */
  traceCount: number;
  /** server-side 계산 (D-03 비협상 single source `now`). */
  healthStatus: HealthStatus;
}

/** GET /v1/services 응답 body. */
export interface ServicesResponse {
  services: ServiceInfo[];
}

/**
 * GET /v1/setup/state 응답.
 *
 * [Phase H] U4 (FirstRunGuard) — staleTime Infinity 로 첫 마운트 1회만 호출.
 * setup_state 테이블 단일 row 정책 (id=1 fixed).
 */
export interface SetupStateResponse {
  completed: boolean;
  completedAt: number | null;
  serverUrl: string | null;
}

/** POST /v1/setup/complete 요청의 services[] 요소. */
export interface SetupServiceRegistration {
  name: string;
}

/**
 * POST /v1/setup/complete 요청 body.
 *
 * services 가 빈 배열이거나 omit 되면 skip 경로 (D-04 비협상).
 * serverUrl 빈 문자열도 skip 경로에서 허용 (Q-01 architect 결정).
 */
export interface SetupCompleteRequest {
  serverUrl: string;
  services?: SetupServiceRegistration[];
}

/**
 * POST /v1/setup/complete 응답.
 *
 * 이미 completed=1 이어도 200 + completed_at 갱신 (NFR-04 멱등).
 */
export interface SetupCompleteResponse {
  completed: true;
  completedAt: number;
}

/**
 * [R10] AC-05-4 (D-H10-01 비협상) — GET /v1/setup/agent-jar-path 응답.
 *
 * server 가 startup 시 임베드된 apilens-agent.jar 를 ~/.apilens/apilens-agent.jar 로
 * 자동 추출한 절대경로. 추출 실패 시 path=null (NFR-02 silent fallback) — HTTP 200 유지.
 * UI 는 path=null 시 fallback placeholder (/path/to/apilens-agent.jar) 사용 + 작은 경고.
 */
export interface AgentJarPathResponse {
  path: string | null;
}

/** GET /v1/traces 호출 파라미터 (도메인 레이어). */
export interface ListTracesParams {
  service?: string;
  since?: number;
  until?: number;
  status?: TraceStatus;
  limit?: number;
  cursor?: string;
  /**
   * Phase R12 (FR-C2, AC-C2-3): operation 검색어 — root_operation 풀 FQCN 부분 일치.
   * LIKE escape 는 BE 단일 책임 (E-07) — FE 에서 이스케이프 금지 (이중 처리 방지).
   */
  q?: string;
}

// ────────────────────────────────────────────────────────────────────────────
// Phase R12 (FR-B1~B3) — settings + masking-rules 계약 (설계 §5.2~5.4 응답 JSON 예시 1:1).
// 식별자 단일명: 설계 §5.6 그대로 (settings/lastCleanupAt/ruleId/enabled 등 — drift 금지).
// S-64 타입 대조: ruleId = V1 masking_rules.rule_id INTEGER PK → number /
//                 lastCleanupAt = retention_meta.last_cleanup_at INTEGER (epoch ms) → number.
// ────────────────────────────────────────────────────────────────────────────

/**
 * GET /v1/settings 응답 (= PUT /v1/settings 갱신 후 응답과 동일 형태 — 설계 §5.2).
 *
 * settings 값 = resolve 된 유효값 (DB 없으면 yml fallback 값이 그대로 내려감 — FE prefill 단순화).
 */
export interface SettingsResponse {
  /** v0.2 노출 키 = 'retention.days' 단 1개 (설계 §2-B1 — 과다 노출 금지). */
  settings: { 'retention.days': number };
  /** retention_meta.last_cleanup_at (epoch ms). 0 = 이력 없음 → T-11 분기. */
  lastCleanupAt: number;
}

/** PUT /v1/settings 요청 body — { "retention.days": 14 } 형태 (설계 §5.2). */
export interface SettingsUpdateRequest {
  'retention.days': number;
}

/**
 * POST /v1/maintenance/cleanup · POST /v1/maintenance/purge 공통 응답 (BE 계약 1:1).
 *
 * 두 엔드포인트 응답 형태 동일:
 *   { "deletedTraces": 12345, "freedBytes": 53687091200, "dbSizeBytes": 41943040 }
 * - deletedTraces : 삭제된 trace 건수 (정수).
 * - freedBytes    : 이번 정리로 확보한 디스크 용량 (바이트). 사람이 읽는 단위로 포맷해 표시.
 * - dbSizeBytes   : 정리 후 DB 파일 크기 (바이트).
 * 에러 응답은 공통 { "error": "<message>" } (ApiErrorBody) — 서버 본문 직접 노출 금지.
 *
 * [S-64] 식별자·수치 타입 대조: 세 필드 모두 BE 응답에서 정수(long/int) → number.
 */
export interface MaintenanceResult {
  deletedTraces: number;
  freedBytes: number;
  dbSizeBytes: number;
  /**
   * [Phase K] (US-07, AC-07-3/AC-07-4/AC-07-5) — optimize 전체락 부분 실패(SQLITE_BUSY) /
   * 디스크 부족 거부 / SQLITE_FULL 비전파 시 true (설계 §4.4 — busy optional 추가).
   * cleanup/purge 응답에는 false 고정(또는 부재) — optional 이라 기존 파싱 회귀 0.
   * [S-64] 타입 대조: BE MaintenanceResult record 4번째 필드 boolean → boolean.
   */
  busy?: boolean;
}

/** 마스킹 룰 타입 — V1 rule_type 컬럼 값 그대로. */
export type MaskingRuleType = 'field_name' | 'regex';

/** 마스킹 전략 — V1 mask_strategy 컬럼 값 그대로. */
export type MaskStrategy = 'full' | 'partial' | 'hash' | 'length_only';

/**
 * GET /v1/masking-rules 응답 rules[] 요소 (설계 §5.3).
 *
 * V1 컬럼 snake_case 의 camelCase 1:1 매핑 (PLAN §9 단일명 — active/status 등 다른 명 금지).
 */
export interface MaskingRule {
  ruleId: number;
  name: string;
  ruleType: MaskingRuleType;
  pattern: string;
  maskStrategy: MaskStrategy;
  enabled: boolean;
  /** true = 빌트인 4종 — 삭제 불가 (비활성만 가능). 삭제 버튼 렌더 자체 제거 (C-05). */
  isDefault: boolean;
}

/** GET /v1/masking-rules 응답 body (정렬: is_default DESC, rule_id ASC — 설계 §5.3). */
export interface MaskingRulesResponse {
  rules: MaskingRule[];
}

/**
 * POST /v1/masking-rules 요청 body (설계 §5.3).
 *
 * isDefault 필드 자체가 없음 — 서버가 is_default=0 강제 (PLAN §5-2).
 */
export interface CreateMaskingRuleRequest {
  name: string;
  ruleType: MaskingRuleType;
  pattern: string;
  maskStrategy: MaskStrategy;
  /** 생략 시 true. */
  enabled?: boolean;
}

/** PATCH /v1/masking-rules/{id} 요청 body — enabled 외 필드 포함 시 서버 400 (설계 §2-B2). */
export interface ToggleMaskingRuleRequest {
  enabled: boolean;
}

/** preview 요청의 화면 토글 상태 스냅샷 요소 (설계 §5.4). */
export interface PreviewRuleState {
  ruleId: number;
  enabled: boolean;
}

/**
 * POST /v1/masking-rules/preview 요청 body (설계 §5.4).
 *
 * R12 (AC-B3-1): "샘플 페이로드 + (저장 전 토글 상태가 반영된) 룰 세트 → 마스킹 결과 반환" (비협상)
 * — ruleStates 는 화면의 "현재" 토글 상태 전체 스냅샷 (DB persisted 상태 의존 0, race 원천 차단).
 */
export interface PreviewRequest {
  /** null/생략 = 서버 내장 기본 샘플 (AC-B3-2). */
  sample: string | null;
  /** 생략 시 application/json. */
  contentType: string;
  /** 생략 = DB 저장 상태 그대로 / 미존재 ruleId 는 서버가 무시 (stale 화면 관용). */
  ruleStates: PreviewRuleState[];
}

/**
 * POST /v1/masking-rules/preview 응답 (설계 §5.4).
 *
 * sample = 입력 원문 echo — 기본 샘플 모드의 Before/After 동시 표시 성립 (UX §9 요구 ② 채택).
 * contentType echo 는 FE 미표시 (계약 동봉 필드 — 표시 소비는 sample/masked 2종만, 의도된 비렌더).
 */
export interface PreviewResponse {
  sample: string;
  masked: string;
  contentType: string;
}

/** 공통 4xx/5xx 응답 본문 형태. */
export interface ApiErrorBody {
  error: string;
  traceId?: string;
  spanId?: string;
}

// ────────────────────────────────────────────────────────────────────────────
// trace 상세 (F1엔 placeholder. 실제 사용은 F2에서.)
// docs/api.md "GET /v1/traces/{traceId}" 응답 박제.
// ────────────────────────────────────────────────────────────────────────────

export type SpanKind = 'SERVER' | 'CLIENT' | 'INTERNAL' | 'DB' | 'EXTERNAL';

export interface SpanDetail {
  spanId: string;
  parentSpanId: string | null;
  serviceName: string;
  operationName: string;
  spanKind: SpanKind;
  startTime: number;
  endTime: number;
  status: TraceStatus;
  /**
   * server에서 parse된 object. parse 실패 시 { _raw: "<원본>" } fallback.
   * 키/값이 자유 형식이라 unknown으로 둠 — 사용처에서 안전하게 좁힐 것.
   */
  attributes: Record<string, unknown>;
}

/**
 * GET /v1/traces/{traceId} 응답.
 *
 * NOTE: rootSpanId 필드는 server v0.1에는 없을 수 있다.
 *       UI는 다음 fallback으로 root를 식별:
 *         const root = spans.find(s => s.parentSpanId === null);
 *       parentSpanId === null 인 span이 정확히 1개 — 정렬에 의존 금지.
 *       자세한 가이드는 docs/api.md "root span 식별" 단락 참고.
 */
export interface TraceDetailResponse {
  trace: TraceSummary;
  spans: SpanDetail[];
  /** server v0.2+에서 명시 응답될 가능성 (forward-compat). */
  rootSpanId?: string;
}

// ────────────────────────────────────────────────────────────────────────────
// Payload (F2 추가) — docs/api.md L195~213 박제 정확 반영.
// direction은 소문자 'in' | 'out' (server 권위). UI 표시 라벨은 대문자로 변환.
// 응답에 spanId 필드는 없음 (요청 path로 이미 식별 가능).
// ────────────────────────────────────────────────────────────────────────────

export type PayloadDirection = 'in' | 'out';

export interface Payload {
  direction: PayloadDirection;
  contentType: string | null;
  /** utf-8, 마스킹 적용 후. server는 다시 마스킹하지 않음. */
  body: string;
  /** 마스킹 전 원본 byte 크기 (docs/api.md sizeBytes 박제). */
  sizeBytes: number;
  truncated: boolean;
}

export interface PayloadsResponse {
  payloads: Payload[];
}
