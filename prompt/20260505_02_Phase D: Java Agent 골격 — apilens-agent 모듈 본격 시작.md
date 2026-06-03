Phase D: Java Agent 골격 — apilens-agent 모듈 본격 시작

## 컨텍스트
CLAUDE.md를 먼저 읽고 시작. Phase A/B/C 모두 완료 (37/37 테스트 통과).
server는 ingest와 query 양쪽 다 동작. 이제 server에 진짜 trace 데이터를
보내는 agent를 만든다.

agent는 사용자 Spring Boot 앱에 -javaagent로 붙어서 동작. 사용자 앱과 같은
JVM에서 돌기 때문에 제약이 server보다 훨씬 까다롭다. 이 phase는 "Hello World"
수준의 검증 가능한 agent까지가 목표 — 실제 instrumentation은 다음 phase.

## 이 phase의 정의 (성공 기준)

샘플 Spring Boot 앱에 agent를 -javaagent로 붙였을 때:
1. 앱이 정상 시작 (agent 때문에 시작 실패 절대 금지)
2. agent가 시작 시 옵션을 읽고 로그 한 줄 남김
3. agent가 server에 1건의 hello span을 비동기로 전송
4. server의 GET /v1/traces로 그 span이 들어왔는지 확인 가능

이게 끝나면 "agent → server 파이프라인이 살아있다"가 검증됨. ByteBuddy
instrumentation은 Phase E에서.

## 절대 지킬 원칙 (CLAUDE.md에 이미 있지만 재강조)

### 1. agent 자체가 사용자 앱을 죽이면 안 됨
- 모든 public 진입점 (premain, span 전송, 옵션 파싱)을 try-catch로 감쌈
- catch 후엔 agent 비활성화 모드로 전환 (앱은 계속 돌아감)
- System.err에 한 줄만 남기고 silent
- 절대 사용자 앱 thread를 block하지 말 것

### 2. 외부 의존성 클래스 충돌 금지
- ByteBuddy, Jackson은 build.gradle.kts에서 이미 relocate 설정됨
  (io.apilens.agent.shaded.*)
- 이 phase에서 새 의존성 추가 금지. 추가 필요 시 사용자에게 먼저 물을 것
- HTTP client는 JDK 내장 java.net.http.HttpClient 사용 (의존성 0)

### 3. 비동기 전송, 호스트 thread 안 막기
- agent 전용 daemon thread 1~2개로 처리
- 메인 ingest는 LinkedBlockingQueue + 백그라운드 worker
- 큐가 가득 차면 silent drop (호스트 앱 보호 우선)

### 4. premain 진입점은 가벼울 것
- premain에서 무거운 작업 금지. 옵션 파싱 + worker thread 시작만.
- ByteBuddy AgentBuilder는 다음 phase에 추가 (이번엔 빈 자리만 마련)

## 옵션 명세

JVM 시스템 프로퍼티로 받음. agent args가 아니라 -D 옵션 (일관성 + 디버깅 편의):

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| apilens.server | http://localhost:8765 | server URL |
| apilens.service.name | (필수) | 서비스 식별자 |
| apilens.enabled | true | false면 agent 완전 비활성 |
| apilens.sampling.rate | 1.0 | head-based, 0.0~1.0 |
| apilens.batch.max-size | 100 | 한 번에 보낼 span 최대 |
| apilens.batch.flush-interval-ms | 1000 | 강제 flush 주기 |
| apilens.queue.capacity | 10000 | 내부 버퍼 |
| apilens.payload.max-bytes | 65536 | payload truncate 임계 |
| apilens.debug | false | true면 stderr에 상세 로그 |

service.name 누락 시: 경고 한 줄 stderr에 + agent 비활성. 앱은 정상 시작.

## 이 phase에서 만들 것

apilens-agent/src/main/java/io/apilens/agent/
├── AgentMain.java          -- premain 진입점, 옵션 파싱, worker 시작
├── config/
│   └── AgentConfig.java    -- 옵션 record + 파싱 + 검증
├── transport/
│   ├── SpanQueue.java      -- LinkedBlockingQueue 래퍼
│   ├── SpanSender.java     -- 백그라운드 worker, batch HTTP POST
│   └── HttpTransport.java  -- JDK HttpClient 래퍼, 재시도 1회
├── model/
│   └── (필요하면 추가, 가능한 apilens-common 재사용)
└── util/
    └── AgentLogger.java    -- stderr 로그, debug 모드 토글

apilens-agent/src/test/java/io/apilens/agent/
├── config/AgentConfigTest.java       -- 옵션 파싱 단위 테스트
├── transport/SpanQueueTest.java      -- 큐 가득 시 drop 동작
└── transport/HttpTransportTest.java  -- 재시도 + 실패 시 silent

## 동작 플로우 (premain)

1. AgentMain.premain(args, instrumentation) 진입
2. 전체를 try-catch로 감쌈. 어떤 예외든 stderr 한 줄 + return
3. AgentConfig.fromSystemProperties() — 옵션 파싱
4. apilens.enabled=false 또는 service.name 없음 → 한 줄 로그 + return (앱은 정상)
5. SpanQueue 생성 (capacity=옵션)
6. SpanSender daemon thread 시작 (서버 URL + 큐 + flush interval)
7. shutdown hook 등록 — JVM 종료 시 큐 drain 시도 (max 2초)
8. ★ Hello span 1건 enqueue (이 phase의 검증 포인트)
9. AgentLogger.info("ApiLens agent started: service={...}, server={...}")

Hello span 형식:
{
  traceId: "agent-startup-" + UUID 일부,
  spanId: random,
  parentSpanId: null,
  serviceName: 옵션값,
  operationName: "agent.startup",
  spanKind: "INTERNAL",
  startTime: now,
  endTime: now,
  status: "OK",
  attributes: { "apilens.agent.version": "0.1.0", ... }
}

이게 server의 IngestRequest 포맷에 맞아야 함. apilens-common의 DTO 재사용할 것.

## 전송 프로토콜

POST {server}/v1/spans
Content-Type: application/json
Body: { spans: [...], serviceName: "..." }  -- 기존 IngestController가 받는 포맷

응답 코드:
- 2xx: 성공, 큐에서 제거
- 4xx: payload 잘못된 것. 로그 남기고 drop (재시도 의미 없음)
- 5xx 또는 IO 실패: 1회 재시도 (1초 후). 그래도 실패면 silent drop

재시도 정책 단순 유지. exponential backoff는 v0.2.

## SpanSender 동작 (worker thread)

while (running) {
  try {
    List<Span> batch = drain queue up to batch.max-size, timeout = flush-interval
    if (batch empty) continue
    httpTransport.send(batch)
  } catch (any) {
    AgentLogger.error("send failed", e)  -- silent, no rethrow
  }
}

- daemon thread (앱 종료 막지 않음)
- shutdown hook이 running=false + queue drain 마지막 시도

## 테스트 전략

### AgentConfigTest
- 시스템 프로퍼티 set/clear 후 파싱 검증
- 필수 옵션 누락 시 disabled config 반환
- sampling.rate 범위 외 (음수, >1.0) → 1.0으로 fallback + 경고 (예외 던지지 말 것 — agent 안전 원칙)
- 잘못된 URL → disabled로 fallback

### SpanQueueTest
- offer 정상
- capacity 초과 시 false 반환 (drop)
- drainTo로 batch 추출

### HttpTransportTest
- WireMock 또는 간단한 테스트용 HTTP 서버 띄워서 검증
  - 새 의존성 추가 필요하면 com.github.tomakehurst:wiremock-standalone 있긴 한데
    무겁다. JDK 내장 com.sun.net.httpserver.HttpServer로 충분.
- 200 응답 → 성공
- 500 응답 → 1회 재시도 후 실패 처리
- 연결 실패 → silent (예외 새지 않음)

### 통합 테스트 (선택, 시간 되면)
apilens-server/src/test/...에 IntegrationTest 추가:
- TestRestTemplate으로 server 띄움
- agent의 SpanSender 직접 인스턴스화 (premain 거치지 않고)
- hello span 보내고 GET /v1/traces로 확인

이 통합 테스트가 핵심 — phase 정의 (성공 기준)를 자동화함.

## standalone 검증

기존 javac + JUnit standalone 흐름 유지.
- agent: 새 N개
- common 14, server ingest 9, server query 14는 변동 없음 확인
- 합계 37 + N

## 마무리

1. docs/agent-options.md (신규) — 위 옵션 표 + 사용 예시