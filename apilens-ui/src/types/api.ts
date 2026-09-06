// ApiLens 서버 응답 타입 정의 — docs/api.md 계약을 그대로 고정.
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
  /**
   * [Phase R19] AC-01-4 — agent 가 마지막으로 시작할 때 보고한 버전 (services.agent_version).
   *
   * 값이 없으면(null) "이 서비스가 v0.5.0 collector 로 바뀐 뒤 아직 agent 를 다시 시작하지 않았다"
   * 는 뜻 하나뿐이다 (DEFAULT 없음 — 값 없음과 기본값이 섞이지 않게). 화면은 `—` 로 그린다.
   * 선택 필드(`?`)가 아니라 **필수 필드**다 — 응답에 항상 존재하는 필드라 계약이 그렇다.
   *
   * [S-64] BE↔FE 식별자 타입 1:1 대조: BE `ServiceInfo` record 7번째 필드 `String agentVersion`
   *   (NULL 허용, V4 `services.agent_version TEXT`) → `string | null` (Jackson null 직렬화).
   *   버전 비교는 문자열 비교 금지 — lib/agentVersion.ts 의 자리별 숫자 비교를 쓴다.
   */
  agentVersion: string | null;
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

/**
 * [Phase R15] AC-A3-1/AC-A3-2 — GET/POST /v1/maintenance/{status,pause,resume} 공통 응답
 * (BE MaintenanceStatusResponse record 1:1).
 *   { "paused": true, "pausedAt": 1730000000000, "sqliteBusyEncountered": 0, "sqliteBusyDropped": 0,
 *     "traceSummaryDeferred": 0, "dbSizeBytes": 1442205696, "freePageBytes": 179621888 }
 *   { "paused": false, "pausedAt": null, "sqliteBusyEncountered": 1, "sqliteBusyDropped": 0,
 *     "traceSummaryDeferred": 1, "dbSizeBytes": 1442205696, "freePageBytes": 179621888 }
 * BE 가 paused=false 시 pausedAt=null 보장(echo 일관성). MaintenanceResult 와 별도 타입(이종 반환 회피).
 * 사용자 명시 비협상 결정(D03 in-memory 상태). CLAUDE.md '아키텍처 핵심 원칙' (수신 일시정지 단일 기능).
 *
 * [Phase T / R23] AC-06-1/AC-07-1 — 3필드 additive 확장(4 → 7). 기존 4필드의 이름·타입·순서·의미는
 *   불변이고 뒤에만 더한다(사용자 명시 비협상 결정 — 응답 계약 "추가만 허용", 설계 §4.1 / 불변식 I-11).
 *
 * [S-64] BE↔FE 식별자 타입 1:1 대조: BE MaintenanceStatusResponse record(io.apilens.server.retention)
 *   = `record MaintenanceStatusResponse(boolean paused, Long pausedAt, long sqliteBusyEncountered,
 *      long sqliteBusyDropped, long traceSummaryDeferred, long dbSizeBytes, long freePageBytes)`
 *   (R23 설계 §4.1 계약 표 — 7필드 순서 고정).
 *   - paused : boolean → boolean
 *   - pausedAt : Long(박싱, null 가능, epoch millis) → number | null (Jackson null 직렬화).
 *   - sqliteBusyEncountered : long(primitive — 항상 직렬화) → number. 적재 중 SQLITE_BUSY 를 만난 누적 횟수.
 *   - sqliteBusyDropped : long(primitive) → number. 경합으로 유실된 누적 청크 수(청크 ≈ 500 span).
 *   - traceSummaryDeferred : long(primitive) → number. 요약을 저장하지 못한 누적 흐름 수.
 *   - dbSizeBytes : long(primitive) → number. 페이지 수 × 페이지 크기 = 바이트.
 *   - freePageBytes : long(primitive) → number. 빈 페이지 수 × 페이지 크기 = 바이트.
 *   ⚠️ 단위가 서로 다르다 — 횟수 / 청크 수 / 흐름 수 / 바이트 (뭉개면 오독, T-15 선례).
 *   카운터 3종(3·4·5번)은 BE in-memory — 서버 재시작 시 0 복귀가 정상 (BE javadoc "재시작 시 0", T-16 출처).
 *   6·7번은 DB 파일에서 PRAGMA 로 읽는 값이라 재시작해도 0 이 되지 않는다 — 성질이 달라서
 *   화면 구획도 갈라 놓았다(설계 §2.5-A 비협상). PRAGMA 읽기 실패 시에만 BE 가 0 으로 폴백한다(설계 §2.4-D).
 */
export interface MaintenanceStatusResponse {
  paused: boolean;
  pausedAt: number | null; // epoch millis. BE Long → number | null.
  sqliteBusyEncountered: number; // R21/AC-03-1 — long primitive 라 항상 직렬화, `?` 불요.
  sqliteBusyDropped: number; // R21/AC-03-1 — 단위는 청크 수 (횟수 아님 — 100배 오독 주의, T-15).
  traceSummaryDeferred: number; // R23/AC-06-1 — 단위는 흐름 수(건). 인메모리 — 재시작 시 0 복귀 정상.
  dbSizeBytes: number; // R23/AC-07-1 — 바이트. PRAGMA page_count × page_size (BE 조립).
  freePageBytes: number; // R23/AC-07-1 — 바이트. PRAGMA freelist_count × page_size. 0 도 정상값.
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

// ────────────────────────────────────────────────────────────────────────────
// [Phase R19] 계측 분석 계약 (설계 §4.2 응답 JSON 예시 1:1).
//
// 두 endpoint 모두 **읽기 전용 POST** 다 (DB 를 한 줄도 쓰지 않는다):
//   POST /v1/instrument/analysis   — 서비스 하나의 계측 순위 집계 (온디맨드 · 시간 구간 필수)
//   POST /v1/instrument/simulation — 고른 대상을 뺐을 때의 예상 결과 (용량은 감산 · trace 는 재계산)
// 신규 /v1/** 는 화이트리스트에 추가되지 않는다 → 역방향 default-deny 로 토큰 필수.
// 호출은 반드시 api/client.ts 의 postJson 경유 (buildHeaders 가 Authorization 첨부).
//
// 단위 도메인 (설계 §4.4 · BL-19 — 100배 오류 차단):
//   - 비율(rootRatio · singleSpanTraceRatio) = **0.0~1.0 실수**. 화면에 % 로 보일 때만 100을 곱한다.
//   - 시간 = 밀리초(epoch). 요청의 windowHours 만 시간(hour) 정수.
//   - payload 크기 = 바이트 정수. 표시 변환은 lib/format.ts formatBytes 1곳.
//
// [S-64] BE↔FE 식별자 타입 1:1 대조:
//   spanCount/payloadCount/payloadBytes/totalSpans/totalTraces/remainingSpans/resultTraces = long → number
//   spanRank/payloadCountRank/payloadBytesRank = Integer(nullable, 고정 합계 행은 null) → number | null
//   rootRatio/avgSpansPerTrace/singleSpanTraceRatio = double → number
//   className/excludeTarget = String → string / string | null
//   fromMs/toMs/queriedAtMs = long(epoch millis) → number
//   [Phase R25] uniquePayloadBytes = long(서버는 COALESCE(...,0) 이라 v0.7.0+ 응답에 **항상** 실린다)
//     → 화면은 **선택 필드**로 받는다. 서버가 늘 준다고 필수로 적으면, 그 키가 없는 응답(구버전
//       server jar · 시험 픽스처)이 왔을 때 undefined 가 그대로 바이트 표기로 흘러 화면이 깨진다.
//       tsconfig.app.json:38 이 시험 파일을 타입 검사에서 빼므로 그 사고를 컴파일러가 못 잡는다.
//     ★ `number | undefined` 로 적지 않는다 — exactOptionalPropertyTypes:true(tsconfig.app.json:26)
// ────────────────────────────────────────────────────────────────────────────

/**
 * 제외 가능성 3분류 (설계 §7.2). ★불확실(`UNKNOWN`)을 `EXCLUDABLE` 로 반올림하지 않는다 (비협상).
 *   - EXCLUDABLE     : 뺄 수 있어요
 *   - NOT_EXCLUDABLE : 뺄 수 없어요 (계측이 걸리는 이름이 화면 이름과 다름)
 *   - UNKNOWN        : 확인 안 됨 (안전 방향 — 가능으로 단정하지 않는다)
 */
export type ExcludeStatus = 'EXCLUDABLE' | 'NOT_EXCLUDABLE' | 'UNKNOWN';

/**
 * 불가·불확실 사유 코드. 서버는 코드만 보내고 화면 문구는 FE 가 매핑한다 (설계 §4.4 식별자 정정 1건)
 * — 문구 하나 고치는 데 서버 릴리스가 필요해지지 않게, 그리고 문구 단일 거주지를 UX 문구표로 유지하려고.
 */
export type ExcludeReasonCode = 'NO_CLASS_NAME' | 'PROXY_INSTRUMENTED' | 'UNVERIFIED_PATH';

/** 두 응답 공통 — 집계 구간과 조회 시각 (화면이 구간·조회 시각을 정직하게 적기 위한 값). */
export interface InstrumentWindow {
  fromMs: number;
  toMs: number;
  queriedAtMs: number;
}

/** 순위 응답의 구간 총계 = **바꾸기 전** 값. 부작용을 "지금 → 빼고 나면" 으로 보이려면 기준값이 필요하다. */
export interface InstrumentSummary {
  totalSpans: number;
  totalTraces: number;
  avgSpansPerTrace: number;
  /** 0.0~1.0 실수. 화면 표시 시점에만 100을 곱한다. */
  singleSpanTraceRatio: number;
  /**
   * [Phase R25] AC-25-05-1/AC-25-05-4/AC-25-05-6 (D-R25-13) — 분석 창 안에서 같은 본문을 한 번만 센
   * 바이트 합. 필드 이름은 **사용자 명시 확정**이라 못 바꾼다.
   * 출처: 프리브리프 `20260905_01_… (server minor · V6·V7).md` OQ-6 사용자 확정 · 기획 §5.1 표
   * 「서버 응답 요약 필드 = uniquePayloadBytes … 사용자 확정 — 못 바꿈」.
   * (CLAUDE.md 에는 이 필드를 규정하는 줄이 없어 CLAUDE.md 를 인용하지 않는다 — 없는 근거를 지어내지
   *  않고 실제 확정 출처를 적는다.)
   *
   * ★**선택 필드**다. 값이 없으면(모름) 화면이 그 줄을 아예 안 그리고, 0 이면 `0 B` 로 그린다.
   *   `exactOptionalPropertyTypes: true`(tsconfig.app.json:26) 라 `undefined` 를 명시 대입할 수 없다 —
   *   값이 없으면 **키를 아예 넣지 않는다**.
   * ★단위는 **저장된 바이트**(자르기 전 원본 크기가 아니다). 표시 변환은 lib/format.ts formatBytes 1곳.
   */
  uniquePayloadBytes?: number;
}

/** 순위 응답 items[] 요소 — 클래스 1개(또는 이름이 하나뿐인 계측을 묶은 고정 합계 행). */
export interface InstrumentClassStat {
  /** 빈 문자열이면 **고정 합계 행** (operation_name 에 `#` 가 없는 span 묶음). 순위 경쟁 대상 아님. */
  className: string;
  spanCount: number;
  payloadCount: number;
  payloadBytes: number;
  /** 그 축에서의 순위(1부터) — **전체 클래스 집합 기준**. 고정 합계 행은 null. */
  spanRank: number | null;
  payloadCountRank: number | null;
  payloadBytesRank: number | null;
  /** 0.0~1.0 실수. */
  rootRatio: number;
  backgroundWorker: boolean;
  excludeStatus: ExcludeStatus;
  /** EXCLUDABLE 이면 null. */
  excludeReasonCode: ExcludeReasonCode | null;
  /**
   * EXCLUDABLE 일 때만 값이 있고 그 값은 className 과 같다.
   *
   * [R21/AC-05-3 — G-02 현행화] `-D` 옵션 생성기(instrument-option-generator.ts)는 도입됐지만
   * **생성기 입력은 전부 수기** (분석 값 소비 경로 없음 — UX §4.6-6) 라 이 필드를 소비하는 코드는
   * 여전히 0이다. 필드를 나눠 둔 이유는 "표시용 이름을 그대로 옵션 값으로 쓰는 코드 경로가
   * 존재하지 않는다" 를 계약으로 강제하기 위해서다. 향후 분석 결과에서 값을 흘리는 경로를 만들면
   * 반드시 이 필드(excludeTarget)만 쓰고 className 경로는 만들지 않는다.
   */
  excludeTarget: string | null;
}

/** POST /v1/instrument/analysis 요청 — windowHours 는 1 / 6 / 24 중 하나(화이트리스트, 그 외 400). */
export interface AnalysisRequest {
  serviceName: string;
  windowHours: number;
}

/** POST /v1/instrument/analysis 응답. items 정렬은 세 축 상위 N 의 합집합 + 고정 합계 행. */
export interface AnalysisResponse {
  window: InstrumentWindow;
  summary: InstrumentSummary;
  /** 구간 안 전체 클래스 수 (items 길이가 아니다). */
  totalClasses: number;
  /** 목록이 상한에 걸려 잘렸는가. */
  truncated: boolean;
  items: InstrumentClassStat[];
}

/**
 * POST /v1/instrument/simulation 요청.
 *
 * ⚠️ fromMs/toMs 는 **순위 응답의 window 값을 그대로 되돌려 보낸다.** 서버가 창을 다시 계산하면
 * 그 사이 시간이 흘러 두 결과의 기준 구간이 어긋난다.
 * ⚠️ targets 는 **언제나 클래스 이름 목록**이다. 패키지 단위를 보내지 않는다 — 패키지 선택은
 * 화면에서 "같은 패키지 클래스를 한 번에 체크하는 단축키" 일 뿐이다(패키지 평균 계산 경로 차단).
 */
export interface SimulationRequest {
  serviceName: string;
  fromMs: number;
  toMs: number;
  targets: string[];
}

/** 절감 축 — 직접 귀속분만 (자식 span 은 별도 계측이라 부모를 빼도 남는다). */
export interface SimulationSavings {
  spanDelta: number;
  payloadCountDelta: number;
  payloadBytesDelta: number;
}

/** 부작용 축 — 조상을 빼면 말단이 새 시작점이 되므로 trace 수는 오히려 늘어날 수 있다. */
export interface SimulationImpact {
  remainingSpans: number;
  resultTraces: number;
  avgSpansPerTrace: number;
  /** 0.0~1.0 실수. 경고 임계 비교는 이 도메인에서만 한다. */
  singleSpanTraceRatio: number;
}

/**
 * POST /v1/instrument/simulation 응답.
 *
 * ★ savings 와 impact 는 **언제나 한 응답에 함께 온다.** 절감만 담긴 응답을 만드는 서버 코드 경로가
 * 없다 — 절감 숫자만 보여주고 부작용을 숨기는 화면이 구조로 불가능해진다.
 */
export interface SimulationResponse {
  window: InstrumentWindow;
  savings: SimulationSavings;
  impact: SimulationImpact;
  /** 깊이 상한 때문에 못 센 경로가 있는가. */
  depthCapped: boolean;
}

/**
 * [R21/AC-03-3] BE InstrumentConfigPayload record 1:1 — 202 편승(instrumentConfig)과
 * 원격 계측 설정 GET/PUT body 의 공용 단일 타입.
 *
 * 공용 사유: BE 가 단일 record 를 양쪽에 공용(single DTO 동형 노출 — InstrumentConfigPayload.java)
 * 하므로 FE 도 단일 타입이 1:1 이다 [S-116]. 전 필드 optional = `@JsonInclude(NON_NULL)` 생략과 1:1.
 *
 * [S-64] BE↔FE 식별자 타입 1:1 대조 (설계 §4.1 실측 — record 4필드 전부 박싱/참조형 = 부재 허용):
 *   - captureParams / captureResultSet / requireEntryRoot : Boolean → boolean | 부재.
 *     **필드 부재 = "지시 없음"** (전체 교체 PUT 에서 생략이 곧 의미 — W-3 데이터 정확성).
 *     requireEntryRoot 는 방향 반전 — true 가 "줄이기" (AXIS_REDUCE_VALUE 단일 정의 참조).
 *   - gateExcludes : List<String> → string[] | 부재. FQCN 그대로 저장·전송 (불변식 13).
 *     서버가 빈 목록을 null 로 정규화하므로 GET/202 에는 빈 배열이 실리지 않는다 (설계 §4.2).
 *
 * FE 는 /v1/spans 를 호출하지 않으므로 202 편승 쪽 소비 코드는 0 — 타입만 (AC-03-3 "타입만,
 * 화면 표시 의무 없음"). 설정 화면(GET/PUT)은 api/instrumentConfig.ts 가 소비.
 */
export interface InstrumentConfigPayload {
  captureParams?: boolean;
  captureResultSet?: boolean;
  requireEntryRoot?: boolean;
  gateExcludes?: string[];
}

/** 공통 4xx/5xx 응답 본문 형태. */
export interface ApiErrorBody {
  error: string;
  traceId?: string;
  spanId?: string;
}

// ────────────────────────────────────────────────────────────────────────────
// trace 상세 (F1엔 placeholder. 실제 사용은 F2에서.)
// docs/api.md "GET /v1/traces/{traceId}" 응답 계약을 그대로 고정.
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
// Payload (F2 추가) — docs/api.md L195~213 계약 고정 정확 반영.
// direction은 소문자 'in' | 'out' (server 권위). UI 표시 라벨은 대문자로 변환.
// 응답에 spanId 필드는 없음 (요청 path로 이미 식별 가능).
// ────────────────────────────────────────────────────────────────────────────

export type PayloadDirection = 'in' | 'out';

export interface Payload {
  direction: PayloadDirection;
  contentType: string | null;
  /** utf-8, 마스킹 적용 후. server는 다시 마스킹하지 않음. */
  body: string;
  /** 마스킹 전 원본 byte 크기 (docs/api.md sizeBytes 명시 그대로). */
  sizeBytes: number;
  truncated: boolean;
}

export interface PayloadsResponse {
  payloads: Payload[];
}
