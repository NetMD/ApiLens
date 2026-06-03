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
  /** 응답 시점 SELECT COUNT(*) FROM traces WHERE service_name = ? 결과. */
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
