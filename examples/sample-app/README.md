# ApiLens — sample-app (수동 smoke test)

ApiLens agent + server가 실제 Spring Boot 앱에 붙여졌을 때 의도한 모양으로
trace를 잡는지 사람이 직접 확인하기 위한 미니 앱.

**자동화 테스트 아님 — 사람이 직접 명령어 입력하고 응답 확인하는 용도.**

> 이 sample-app은 ApiLens 본 빌드와 분리되어 있습니다. 루트의
> `settings.gradle.kts`에 include되지 않으므로 `./gradlew build`에 영향 없음.

## 0. 사전 준비

* **JDK 21 필수** — Gradle 8.11.1 데몬이 JDK 25를 파싱 못해서 (Kotlin 내장 버전
  한계) sample-app 빌드 시 `IllegalArgumentException: 25.0.2` 가 납니다.
  → `examples/sample-app/gradle.properties` 의 `org.gradle.java.home` 경로가
  현재 본인의 JDK 21 설치 경로와 다르다면 수정. 또는 환경변수 사용:

  ```bash
  JAVA_HOME=/path/to/jdk-21 ./gradlew --project-dir examples/sample-app bootRun
  ```

  JDK 21 위치 찾기:
  ```bash
  /usr/libexec/java_home -V              # macOS
  ls /opt/homebrew/Cellar/openjdk@21/    # macOS Homebrew openjdk@21 설치 시
  ```

* ApiLens 본체 빌드 가능 상태 (루트에서 `./gradlew build` 한 번 통과)

## 1. 빌드 절차 (한 번만)

ApiLens 루트(`/Users/.../ApiLens`)에서:

```bash
# 1) agent shadow jar 빌드 (sample-app이 -javaagent로 사용)
./gradlew :apilens-agent:shadowJar
# → apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar 생성 확인

# 2) ApiLens server 실행 (port 8765)
./gradlew :apilens-server:bootRun
```

## 2. sample-app 실행 (다른 터미널)

ApiLens 루트에서 자식 디렉터리 빌드:

```bash
./gradlew --project-dir examples/sample-app bootRun
```

성공 시 콘솔 출력 예:

```
[sample-app] attaching ApiLens agent: /…/apilens-agent-0.1.0-SNAPSHOT.jar
[ApiLens] ApiLens agent started: service=sample-app, server=http://localhost:8765, …
[ApiLens] ApiLens instrumentation installed (controller/service/repository/jdbc)
…
Tomcat started on port(s): 18080 (http)
```

(에이전트가 이 로그를 안 찍으면 [디버깅 절](#디버깅) 으로)

옵션 변형:

```bash
# agent 없이 (baseline 비교용)
./gradlew --project-dir examples/sample-app bootRun -Pno-agent

# debug 로그 켜기
./gradlew --project-dir examples/sample-app bootRun -Papilens.debug=true

# 다른 ApiLens server 가리키기
./gradlew --project-dir examples/sample-app bootRun -Papilens.server=http://10.0.0.5:8765
```

---

## 3. 시나리오 1: POST /users → 4-span trace 검증

**다른 터미널** 에서 호출:

```bash
curl -i -X POST http://localhost:18080/users \
  -H "Content-Type: application/json" \
  -d '{"name":"홍길동","password":"hunter2!","ssn":"850101-1234567","email":"hong@example.com"}'
```

응답 `201 Created` + `{"id":1,"name":"홍길동","email":"…"}` 받았는지 확인.

이어서 ApiLens 응답 확인:

```bash
curl -s 'http://localhost:8765/v1/traces?service=sample-app' | jq
TRACE_ID=$(curl -s 'http://localhost:8765/v1/traces?service=sample-app' | jq -r '.traces[0].traceId')
curl -s "http://localhost:8765/v1/traces/${TRACE_ID}" | jq
```

### Trace summary 체크리스트

```
[ ] /v1/traces 응답에 trace 1개 이상 있음
[ ] trace.serviceName == "sample-app"
[ ] trace.rootOperation 안에 "create" 또는 "POST" 또는 "UserController" 포함
[ ] trace.status == "OK"
[ ] trace.spanCount == 4
```

### Span tree 체크리스트 (start_time 오름차순)

```
spans[0] — controller (root)
[ ] spanKind == "SERVER"
[ ] parentSpanId == null
[ ] operationName 안에 "UserController" + "create" 포함
[ ] attributes["code.namespace"] == "com.example.sampleapp.UserController"
[ ] attributes["code.function"] == "create"
[ ] attributes["http.method"] == "POST"            ← reflection 기반 추출 (실패 가능 시 null)
[ ] attributes["http.status_code"] == 201          ← reflection 기반
[ ] /v1/traces/{id}/spans/{spans[0].spanId}/payloads 응답에 payload 2개 (in/out)

spans[1] — service
[ ] spanKind == "INTERNAL"
[ ] parentSpanId == spans[0].spanId
[ ] operationName 안에 "UserService" + "create" 포함

spans[2] — repository
[ ] spanKind == "INTERNAL"
[ ] parentSpanId == spans[1].spanId
[ ] operationName 안에 "save" 또는 "UserRepository" 포함

spans[3] — JDBC
[ ] spanKind == "DB"
[ ] parentSpanId == spans[2].spanId
[ ] attributes["db.statement"] 존재 (insert 또는 select 시작)
[ ] attributes["db.rows_affected"] 또는 attributes["db.execute.has_resultset"] 둘 중 하나 존재
```

⚠️ HikariCP wrapper 통한 instrumentation이 깨지면 spans[3] 미생성 → spanCount=3.
이때 prompt의 [실패 시나리오 #4](20260505_03-1) 가 발현된 것.

---

## 4. 시나리오 2: 마스킹 검증 (보안의 핵심)

위 시나리오 1의 spans[0] payload(in)을 확인 — 평문 ssn / password가 server에
도달했는지가 결재 친화성의 핵심.

```bash
# 위에서 이미 받은 trace의 root span ID로 payload 조회
# 주의: spans 배열 정렬에 의존하지 말 것 — parentSpanId == null 인 root span을 명시적으로 선택.
# root가 없을 경우(이상 상태) empty 로 폴백하여 후속 curl이 의미 있는 에러를 내도록 함.
ROOT_SPAN_ID=$(curl -s "http://localhost:8765/v1/traces/${TRACE_ID}" \
  | jq -r '.spans | map(select(.parentSpanId == null))[0].spanId // empty')
curl -s "http://localhost:8765/v1/traces/${TRACE_ID}/spans/${ROOT_SPAN_ID}/payloads" | jq
```

체크리스트:

```
payload(direction=in)
[ ] body 안에 "hunter2!" (평문 password) 없음 ★ 결재의 핵심
[ ] body 안에 "***" 또는 마스킹 표시 있음 (password 필드)
[ ] body 안에 "850101-1234567" (평문 SSN) 없음 ★
[ ] body 안에 "850" 으로 시작하고 뒤가 "*" 인 부분 마스킹된 SSN 있음
[ ] body 안에 "홍길동" (마스킹 대상 아닌 name) 평문 그대로 보임
[ ] body 안에 "hong@example.com" (마스킹 대상 아닌 email) 평문 그대로 보임

payload(direction=out)
[ ] body 안에 password / ssn 필드 자체가 없음 (UserResponse에 안 포함됨)
[ ] body 안에 "id", "name", "email" 키 보임
```

⚠️ 평문이 보이면: v0.1은 server-side 마스킹 정책이라 server의 default 룰이
실제로 적용되는지 확인 필요. `apilens-server/src/main/resources/db/migration/V1__initial_schema.sql`
의 마스킹 룰 seed가 정상 들어갔는지, `MaskingConfig`가 startup에 룰을 로드했는지.

---

## 5. 시나리오 3: GET /users/{id} (3-span trace, 조회 경로)

```bash
curl -s http://localhost:18080/users/1 | jq
```

```
[ ] /v1/traces 응답에 새 trace 추가됨
[ ] spanCount == 3 (controller / service / repository)
    또는 spanCount == 4 (find with select SQL)
[ ] root span operationName 안에 "get" 포함
[ ] root span attributes["http.method"] == "GET"
```

존재하지 않는 ID 조회:

```bash
curl -i http://localhost:18080/users/999
```

```
[ ] 응답 404
[ ] /v1/traces 의 새 trace.status == "ERROR"
[ ] root span 의 attributes["exception.type"] 존재 (ResponseStatusException 또는 IllegalArgumentException)
```

---

## 6. 시나리오 4: 호스트 앱 안전성 (agent가 사용자 앱을 죽이면 안 됨)

각각 별도 실행해서 sample-app이 정상 시작 + curl 응답하는지 확인.

```
[ ] -Pno-agent 로 실행 시 sample-app 정상 시작 + POST/GET 정상 응답
[ ] -Papilens.server=http://존재하지않는호스트:9999 로 실행 시 sample-app 정상 시작
    (agent는 silent drop, sample-app은 정상 응답)
[ ] -P 없이 service.name 누락 (build.gradle.kts의 default를 빼고 시도) →
    agent disabled 메시지 + sample-app 정상 시작
[ ] sample-app 실행 중 ApiLens server 강제 종료 (Ctrl+C) →
    sample-app은 계속 정상 응답 (curl 200/201 OK)
```

---

## 7. 시나리오 5: 부하 테스트 (선택)

100건 동시 요청 시 호스트 앱 latency가 의미있게 증가하지 않는지:

```bash
# baseline (agent 없이)
./gradlew --project-dir examples/sample-app bootRun -Pno-agent &
sleep 5
time for i in $(seq 1 100); do
  curl -s -X POST http://localhost:18080/users \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"u$i\",\"password\":\"p$i\",\"ssn\":\"850101-100$i\",\"email\":\"u$i@x\"}" > /dev/null
done

# agent 붙이고 동일 부하
# (위 baseline 측정 후 다시 agent 붙여 실행)
```

```
[ ] agent 켰을 때 elapsed time 증가가 baseline 대비 10% 미만
    (이건 v0.1 기대치 — v0.2에서 더 타이트하게)
[ ] 100 trace가 ApiLens server에 모두 도달했는지: /v1/services 응답에서 sample-app traceCount 확인
```

---

<a id="디버깅"></a>
## 8. 디버깅

agent가 동작 안 할 때 순서대로 확인:

```bash
# 1) agent jar 안에 advice 클래스 들어갔는지
unzip -l /…/apilens-agent-0.1.0-SNAPSHOT.jar | grep "instrument/advice"
# → 5개 .class 파일 보여야 (Controller/Service/Repository/Jdbc/JdbcConnection)Advice

# 2) Premain-Class 박혀있는지
unzip -p /…/apilens-agent-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Premain
# → Premain-Class: io.apilens.agent.AgentMain

# 3) 단위 테스트 회귀 확인
./gradlew :apilens-agent:test
# → 42 tests passed

# 4) sample-app debug 로그 켜기
./gradlew --project-dir examples/sample-app bootRun -Papilens.debug=true

# 5) /v1/traces 가 비어있으면 agent → server HTTP 채널 이슈
curl -i -X POST http://localhost:8765/v1/spans \
  -H "Content-Type: application/json" \
  -d '{"spans":[]}'
# → 400 (spans is required) 받으면 server는 정상

# 6) sample-app stderr에 [ApiLens][ERROR] 라인 있으면 instrumentation install 단계 실패
```

## 9. 알려진 v0.1 한계

* **W3C `traceparent` 인커밍 헤더 무시** — 항상 새 trace_id 생성. MSA propagation은 v0.3.
* **Servlet body 캡처 안 함** — controller method args/return value를 JSON 직렬화해서 payload로 사용. raw bytes는 v0.2.
* **client-side 마스킹 비활성** — 마스킹은 server-side에서만. v0.2에서 토글 옵션 추가 예정.
* **WebFlux / `@Async` 미지원** — ThreadLocal 기반 trace context라 스레드 횡단 불가. v0.2+.
* **JDBC SQL 캡처는 PreparedStatement만** — 일반 Statement는 v0.2.
