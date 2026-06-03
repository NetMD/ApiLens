Phase E: ByteBuddy instrumentation — agent의 본업

## 컨텍스트
CLAUDE.md 먼저 읽고 시작. Phase D까지 완료, 64/64 통과, Gradle wrapper로
shadow jar + test runner 검증 완료 (사용자가 직접 검증).

agent의 premain은 hello span을 보내는 자리만 마련해뒀음. 이번 phase는 진짜
instrumentation — 사용자 Spring Boot 앱의 controller/service/repository/JDBC
호출을 자동 캡처해서 server로 전송한다.

## 이 phase의 정의 (성공 기준)

샘플 Spring Boot 앱(controller 1개 → service → JpaRepository → SQL)에
agent를 -javaagent로 붙였을 때:
1. HTTP 요청 1건 보내면 trace 1개 + span 4개 (controller, service, repo, SQL) 캡처
2. 각 span의 attributes에 OTel 표준 키 정확히 채워짐 (docs/otel-attributes.md 참조)
3. controller의 request body가 payload_in으로, response body가 payload_out으로 저장됨
4. 마스킹 default 룰 (주민번호, 카드번호, password) 자동 적용 — 평문 절대 server 도달 안 됨
5. server의 GET /v1/traces로 trace 보임, GET /v1/traces/{id}로 4개 span 트리 보임

이 phase가 v0.1 핵심. 끝나면 ApiLens가 "쓸 수 있는 도구"가 됨.

## 절대 지킬 원칙 (재강조)

### 1. 사용자 앱 절대 죽이지 말 것
모든 advice 메서드는 try-catch로 감싸고, 어떤 예외든 무시. advice가 죽어도
원래 메서드는 정상 실행돼야 함. ByteBuddy의 @Advice.OnMethodExit에서
suppress = Throwable.class 명시.

### 2. shaded ByteBuddy만 사용
agent가 -Dio.apilens.agent.shaded.bytebuddy.* 패키지를 쓰는 건 OK.
원본 net.bytebuddy.* 패키지 import 절대 금지 (사용자 앱이 ByteBuddy 쓰는 경우 충돌).
import 시 IDE가 자동 추천하는 net.bytebuddy.*를 io.apilens.agent.shaded.bytebuddy.*로 바꿔야 함.

### 3. 마스킹은 advice 안에서 적용
controller advice가 request body 캡처할 때 즉시 마스킹. 평문이 SpanQueue에
들어가는 순간 메모리에 평문 잔존. apilens-common의 MaskingEngine 재사용.

### 4. payload truncation 적용
apilens.payload.max-bytes 초과 시 자르고 truncated=true 표시. Phase D에서
옵션은 받았지만 적용 지점이 advice였음. 이번에 처리.

### 5. trace context 전파
HTTP 인커밍에서 traceparent 헤더 추출 시도. 없으면 새 trace_id 생성.
v0.1은 단일 서비스라 outgoing 헤더 주입은 v0.3 (MSA), 인커밍 추출만.

## Instrument 대상 (우선순위 순)

### 1. @RestController, @Controller 메서드 (SERVER span — root)
- 매처: hasAnnotation(RestController) or hasAnnotation(Controller)
- attributes: http.method, http.url, http.route, http.status_code
- payload_in: request body (HttpServletRequest의 InputStream — 한 번만 읽을 수 있어 wrapping 필요)
- payload_out: response body (ContentCachingResponseWrapper 패턴)
- 이게 root span이고 trace 시작점

### 2. @Service 메서드 (INTERNAL span)
- 매처: hasAnnotation(Service)
- attributes: code.namespace, code.function
- payload 캡처 안 함 (메서드 인자/반환값 모두 캡처 시 폭발적, v0.2에 명시 옵션으로)

### 3. @Repository 메서드 (INTERNAL span)
- 매처: hasAnnotation(Repository) or 부모 인터페이스가 JpaRepository/CrudRepository
- attributes: code.namespace, code.function

### 4. JDBC Statement (DB span — leaf)
- 매처: java.sql.PreparedStatement#execute*, Statement#execute*
- attributes: db.statement (SQL), db.parameters (파라미터 — 마스킹 적용), db.rows_affected
- shaded ByteBuddy로 인터페이스 instrument는 까다로움. 가장 확실한 건
  데이터소스 wrapper 패턴 (datasource-proxy 라이브러리 패턴 참조하되 의존성은
  추가 안 함 — 직접 구현)

## 코드 구조

apilens-agent/src/main/java/io/apilens/agent/instrument/
├── InstrumentationInstaller.java   -- AgentBuilder 셋업, premain에서 호출
├── advice/
│   ├── ControllerAdvice.java       -- @OnMethodEnter, @OnMethodExit
│   ├── ServiceAdvice.java
│   ├── RepositoryAdvice.java
│   └── JdbcAdvice.java
├── context/
│   ├── TraceContext.java           -- ThreadLocal trace_id/span_id stack
│   └── TraceContextHolder.java
├── capture/
│   ├── HttpRequestCapture.java     -- Servlet wrapping
│   ├── HttpResponseCapture.java
│   └── PayloadTruncator.java       -- max-bytes 적용
└── matcher/
    └── SpringMatchers.java         -- ElementMatcher 헬퍼 (annotated 매처들)

## ThreadLocal trace context

advice 간 trace_id/span_id 공유는 ThreadLocal stack:
- controller 진입 → push root span
- service 진입 → push child span (parent = stack top)
- service 이탈 → pop, span 완성, queue로
- controller 이탈 → pop root, queue로

WebFlux/@Async는 v0.2 (CLAUDE.md 명시).

## advice 패턴 (ByteBuddy)

@Advice.OnMethodEnter(suppress = Throwable.class)
public static long enter(@Advice.Origin Method method) {
    try {
        // span 시작, ThreadLocal에 push
        return System.nanoTime();
    } catch (Throwable t) {
        return 0;
    }
}

@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
public static void exit(@Advice.Enter long startNanos,
                        @Advice.Thrown Throwable thrown,
                        @Advice.Origin Method method) {
    try {
        // span 완료, exception이면 status=ERROR + exception attributes
        // ThreadLocal에서 pop, SpanQueue에 enqueue
    } catch (Throwable t) {
        // silent
    }
}

advice 메서드는 반드시 public static. ByteBuddy가 inline.

## InstrumentationInstaller

premain의 hello span enqueue 직후에 호출:

```java
new AgentBuilder.Default()
    .ignore(none())  // 자기 자신은 skip — io.apilens.* 패키지 ignore 필요
    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
    .type(SpringMatchers.annotatedWithRestController())
    .transform((builder, type, classLoader, module, pd) ->
        builder.method(any()).intercept(Advice.to(ControllerAdvice.class)))
    .type(SpringMatchers.annotatedWithService())
    .transform(...)
    // ... 4개 advice
    .installOn(instrumentation);
```

## 테스트 전략

ByteBuddy advice 테스트는 까다로워. 두 층으로:

### 단위 테스트
- advice 클래스의 정적 메서드 자체를 호출해서 ThreadLocal 동작 검증
- 마스킹 적용 검증 (페이크 request body 넘겨서 출력 확인)
- truncation 검증

### 통합 테스트
apilens-agent/src/test/.../integration/InstrumentationIT.java:
- Spring Boot 미니 앱을 테스트 안에서 띄움 (SpringBootTest with random port)
- premain 대신 InstrumentationInstaller 직접 호출 (java.lang.instrument.Instrumentation을
  ByteBuddy의 ByteBuddyAgent.install()로 얻음)
- HTTP 호출하고 SpanQueue에서 span 꺼내 검증
- 4개 span (controller, service, repo, JDBC) 모두 잡혔는지
- parent_span_id 트리 정확한지
- attributes 정확한지

이 통합 테스트가 phase E의 진짜 검증.

## 샘플 앱 분리

루트에 examples/sample-app/ 디렉터리 만들기:
- 단순 Spring Boot 앱 (User REST CRUD, JPA + H2)
- 빌드는 별도 (settings.gradle.kts에서 include 안 함)
- README에 수동 검증 절차:
  1. ./gradlew :apilens-agent:shadowJar
  2. ./gradlew :apilens-server:bootRun
  3. (다른 터미널) cd examples/sample-app && ./mvnw spring-boot:run \
     -Dspring-boot.run.jvmArguments="-javaagent:../../apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar -Dapilens.service.name=sample"
  4. curl http://localhost:8080/users/1
  5. curl http://localhost:8765/v1/traces

이게 ApiLens의 진짜 첫 사용 사례. 자동화 테스트 + 수동 smoke test 둘 다 갖춤.

## 마무리

1. docs/progress.md에 Phase E 마일스톤 추가
2. CLAUDE.md "다음 즉시 할 작업" 업데이트:
   - Phase E 완료 → UI 작업이 다음 (Phase F)
3. examples/sample-app/README.md — 수동 검증 절차

## 주의사항

- Phase A~D 코드 절대 수정 금지
- ByteBuddy import는 반드시 io.apilens.agent.shaded.bytebuddy.* (relocate 후 경로)
  IDE가 net.bytebuddy.*로 import 추천하는데 그대로 두면 컴파일은 되지만 런타임에
  ClassNotFoundException. test runner에선 안 잡히고 실제 -javaagent: 시 터짐.
- Spring 클래스(@RestController 등)를 직접 import 하지 말 것 — agent는 Spring 의존성
  없음. ByteBuddy ElementMatcher의 named("...") 또는 isAnnotatedWith(named("..."))
  패턴으로 문자열 매칭.
- JDBC instrumentation은 인터페이스 매처 + 모든 구현체 retransform이 정석.
  v0.1은 PreparedStatement만 우선, Statement는 v0.2.
- 막히는 결정은 사용자에게 물을 것.