Phase C: Query endpoints — apilens-server에 trace 조회 API 4개 추가

## 컨텍스트
CLAUDE.md를 먼저 읽어서 프로젝트 정체성, 데이터 모델, v0.1 범위 파악해.
Phase A (마스킹 엔진)와 Phase B (ingest endpoint)는 완료됐고 23/23 테스트 통과 상태.
이번 Phase C는 ingest된 데이터를 UI가 읽을 수 있게 조회 API를 만드는 단계.

## 목표
대시보드(latency scatter)와 trace 상세(노드 그래프) UI가 필요한 데이터를 모두
제공하는 4개 endpoint 구현. UI는 아직 없지만 API 계약을 먼저 못 박는 게 목적.

## 설계 결정 (변경 금지, 이유 함께 명시)

### 1. Span은 평면 리스트로 반환 (트리 변환은 UI 책임)
- 이유: React Flow가 평면 노드+엣지를 받음. server가 트리 만들면 UI에서 다시 풀어야 함.
- parent_span_id 포함한 평면 배열로 응답.

### 2. Payload는 lazy load (별도 endpoint)
- 이유: trace 하나에 span 50~200개. 모든 payload eager 로드 시 첫 화면 느려짐.
  운영자는 보통 1~3개 span만 들여다봄. 안 보는 거 다 보내는 건 낭비.
- trace 상세는 spans만 반환, payload는 별도 endpoint로.

### 3. Cursor 페이지네이션 (offset 금지)
- 이유: 운영에서 trace는 계속 들어옴. offset은 새 데이터 들어오면 어긋남.
- cursor = 마지막 trace의 (start_time, trace_id) 조합. start_time DESC로 정렬.

### 4. attributes_json은 server에서 parse 후 object로 응답
- 이유: UI에서 또 parse하면 실수 나기 쉬움.
- parse 실패 시 raw string으로 fallback (방어적).

### 5. OpenTelemetry semantic conventions로 attributes 키 표준화
agent가 채울 키, server는 이 키들을 어떻게 다뤄야 하는지 인지하고만 있으면 됨:
  - http.method, http.url, http.status_code, http.route
  - db.statement, db.parameters, db.rows_affected, db.connection
  - exception.type, exception.message, exception.stacktrace
  - code.function, code.namespace

이번 Phase C에서 새로 검증할 건 없음 (agent가 아직 없으므로). 단 이 키 명세를
docs/ 또는 CLAUDE.md에 기록해서 agent 작업 시 참조 가능하게 할 것.

### 6. Response 압축
application.yml에 `server.compression.enabled: true`, mime-types에
application/json 추가. 사내 프록시 환경 고려한 결정.

## API 명세

### GET /v1/traces
대시보드 산점도 + 하단 trace list용.

Query params (모두 optional):
  - service: string (서비스명 정확 일치)
  - since: long (epoch millis, start_time >= since)
  - until: long (epoch millis, start_time < until)
  - status: "OK" | "ERROR"
  - limit: int (기본 100, 최대 500, 초과 시 500으로 cap)
  - cursor: string (이전 응답의 nextCursor)

Response 200:
  {
    "traces": [TraceSummary],
    "nextCursor": "string | null"
  }
  TraceSummary = {
    traceId, rootOperation, serviceName,
    startTime: long, durationMs: long,
    status: "OK"|"ERROR", spanCount: int, hasError: boolean
  }

cursor 인코딩: base64("{startTime}:{traceId}") 권장. 디코딩 실패 시 400.

정렬: start_time DESC, trace_id DESC (tie-breaker).

### GET /v1/traces/{traceId}
trace 상세 화면 (노드 그래프) 진입 시 호출.

Response 200:
  {
    "trace": TraceSummary,
    "spans": [Span]
  }
  Span = {
    spanId, parentSpanId: string|null,
    serviceName, operationName,
    spanKind: "SERVER"|"CLIENT"|"INTERNAL"|"DB"|"UI_EVENT",
    startTime: long, endTime: long,
    status: "OK"|"ERROR",
    attributes: object  // parse된 JSON. 실패 시 {"_raw": "<원본>"}
  }

trace 없으면 404 with { "error": "trace not found", "traceId": "..." }.
spans는 start_time ASC로 정렬.

### GET /v1/traces/{traceId}/spans/{spanId}/payloads
노드 클릭 시 호출. 보통 in/out 1~2개 반환.

Response 200:
  {
    "payloads": [{
      "direction": "in"|"out",
      "contentType": "string|null",
      "body": "string",  // 마스킹 적용된 본문
      "sizeBytes": int,
      "truncated": boolean
    }]
  }

span 없으면 404. payload 0개면 200 with empty array (404 아님).

### GET /v1/services
대시보드 서비스 셀렉터용.

Response 200:
  {
    "services": [{
      "name": "string",
      "traceCount": long,
      "lastSeen": long  // epoch millis
    }]
  }

쿼리: SELECT service_name, COUNT(*), MAX(start_time) FROM traces GROUP BY service_name.
v0.1은 매번 집계해도 무방. 캐싱 최적화는 v0.2.

## 코드 구조

apilens-server/src/main/java/io/apilens/server/query/
  ├── TraceQueryController.java    -- 4개 endpoint
  ├── TraceQueryService.java       -- attributes_json parse 등 비즈니스 로직
  ├── TraceQueryRepository.java    -- JdbcTemplate으로 DB 접근
  ├── dto/
  │   ├── TraceSummary.java        -- record
  │   ├── SpanDto.java             -- record (attributes는 Map<String,Object>)
  │   ├── PayloadDto.java          -- record
  │   └── ServiceInfo.java         -- record
  └── CursorCodec.java             -- base64 인코딩/디코딩 + 검증

기존 ingest 패키지의 컨벤션 그대로 따라가.
JdbcTemplate 일관성 유지 (Phase B 결정).

## 테스트

apilens-server/src/test/java/io/apilens/server/query/
  └── TraceQueryServiceTest.java   -- 기존 IngestServiceTest와 동일한 SQLite 패턴

검증 시나리오 (최소):
  1. ingest 후 GET /v1/traces로 읽기 (라운드트립 — 가장 중요)
  2. ingest 후 GET /v1/traces/{id}로 읽기, span 평면 배열 + parse된 attributes 확인
  3. payload lazy load — span 클릭 endpoint로 ingest한 payload 그대로 (마스킹 적용된 채로) 읽히는지
  4. service 필터, status 필터, since/until 필터 각각
  5. cursor 페이지네이션 — 200개 ingest, limit 100으로 두 번 조회 시 200개 모두 받기 + 중복 없음
  6. 404 — 존재하지 않는 traceId, spanId
  7. limit 초과 (501) → 500으로 cap
  8. cursor 디코딩 실패 → 400

테스트는 IngestService와 함께 사용해서 라운드트립 검증할 것. 별도 테스트 데이터
INSERT 헬퍼 만들지 말고 ingest endpoint를 통해 데이터 넣을 것 (실제 흐름과 동일).

## 마무리 작업

1. application.yml에 `server.compression.enabled: true` + mime-types 추가
2. CLAUDE.md "현재 진행 상태" 업데이트:
   - Phase C 완료 표시
   - "다음 즉시 할 작업"에서 끝난 것 제거하고 agent 골격을 최우선으로
3. docs/api.md (신규) — 4개 endpoint의 request/response 예시 (한국어 주석 + JSON 샘플)
4. docs/otel-attributes.md (신규) — 위 attributes 키 명세. agent 작업 시 참조용

## 검증

기존처럼 standalone javac + JUnit standalone으로 통과 확인.
- common 14/14 (변동 없음)
- server 9 + 신규 N개

## 주의사항

- 기존 Phase A/B 코드 절대 수정하지 말 것. 추가만.
- 마스킹 엔진을 다시 호출하지 말 것. payload는 이미 ingest 시점에 마스킹된 상태로 저장됨.
- JPA 엔티티 만들지 말 것 (Phase B에서 JdbcTemplate으로 결정).
- 새 의존성 추가 시 libs.versions.toml 사용. 직접 implementation("...") 금지.
- 한국어 주석 OK, javadoc은 영어, 로그 메시지는 영어.

작업 시작 전 CLAUDE.md를 다시 읽고 시작해. 막히는 결정 있으면 거기서
판단하지 말고 사용자에게 물어볼 것.