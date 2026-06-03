Phase D 후속 — agent API에서 jackson 타입 캡슐화

## 문제
clean test 시 server의 통합 테스트(AgentToServerIntegrationTest)에서 컴파일 실패:
  incompatible types: com.fasterxml.jackson.databind.ObjectMapper
  cannot be converted to io.apilens.agent.shaded.jackson.databind.ObjectMapper

## 진짜 원인
agent jar는 shadow jar로 빌드되면서 com.fasterxml.jackson 패키지를
io.apilens.agent.shaded.jackson으로 relocate함 (의도된 동작, 사용자 앱과 충돌 회피).
그런데 agent의 public 클래스 (HttpTransport, AgentMain 등)가 ObjectMapper를
public 시그니처에 노출하고 있음. relocate 후엔 외부 코드(server, 사용자 코드)가
이 시그니처를 호출할 수 없음 — 타입 불일치.

agent의 진짜 문제는 API 경계에서 jackson을 노출한 것. 이걸 캡슐화로 풀어야 함.

## 해야 할 일

### 1. HttpTransport 생성자에서 ObjectMapper 인자 제거

변경 전:
  public HttpTransport(String serverUrl, ObjectMapper mapper, AgentLogger logger)

변경 후:
  public HttpTransport(String serverUrl, AgentLogger logger) {
      this.mapper = new ObjectMapper();
  }

테스트 전용 package-private 생성자도 ObjectMapper 인자 빼기:
  HttpTransport(URI ingestUri, HttpClient client, AgentLogger logger)

### 2. AgentMain에서 ObjectMapper 생성하지 말 것
HttpTransport가 자기 안에서 만듦. AgentMain은 더 이상 ObjectMapper 필요 없음.
import도 제거.

### 3. agent 패키지 전체에서 ObjectMapper 노출 검사
  grep -rn "ObjectMapper\|JsonNode\|com.fasterxml.jackson" apilens-agent/src/main/java/
public/protected 시그니처에 jackson 타입 있으면 모두 캡슐화. private 필드와 메서드
내부에서만 쓰는 건 OK.

### 4. 단위 테스트 (HttpTransportTest 등) 수정
ObjectMapper 인자 받던 부분 제거. 테스트는 raw classes로 도니까 컴파일은 통과하던
상태였지만 시그니처 변경에 맞춰 정리.

### 5. 통합 테스트 (AgentToServerIntegrationTest) 수정
새 생성자 시그니처에 맞춰 호출부 정리. 더 이상 ObjectMapper 안 넘김.

## 검증
1. ./gradlew :apilens-agent:test  -- agent 단위 25 tests 통과
2. ./gradlew :apilens-agent:shadowJar  -- shadow jar 빌드 성공
3. ./gradlew :apilens-server:test  -- server 28 tests 통과 (ingest 9 + query 14 + integration 2 + 기타 3)
4. ./gradlew clean test  -- 전체 64 tests 통과
5. unzip -p apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
   -- Premain-Class 박혀있는지 확인
6. unzip -l apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar | grep -E '(bytebuddy|jackson)' | head -10
   -- io/apilens/agent/shaded/bytebuddy/...와 io/apilens/agent/shaded/jackson/...만 보여야 함
   -- net/bytebuddy/..., com/fasterxml/jackson/...이 보이면 relocate 실패

## 짚어둘 것
- 이번 수정의 본질: agent의 public API를 외부에서 호출 가능한 형태로 정돈하는 것.
  jackson을 인자로 받지 않는다 = 사용자(server, 사용자 앱)가 jackson 타입에
  의존하지 않는다.
- 이걸 안 잡고 Phase E 가면 ByteBuddy advice 코드에서도 같은 함정에 빠짐.
- Phase A/B/C 코드는 절대 수정 금지. agent 모듈만 수정.
- 시그니처 변경이라 기존 테스트 호출부도 같이 바뀜. 동작 자체는 그대로.

작업 시작 전 CLAUDE.md, docs/agent-options.md 한 번 훑고, 현재 HttpTransport와
AgentMain 코드 보고 시작.