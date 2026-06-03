Phase E1 후속 — 수동 smoke 검증을 위한 sample app + 명시적 체크리스트

## 컨텍스트
Phase E1에서 ByteBuddy advice 5개 (Controller/Service/Repository/Jdbc/JdbcConnection)
와 InstrumentationInstaller 작성 완료. 79/79 단위 테스트 통과. 그러나 실제로
ByteBuddy가 Spring Boot 앱의 클래스를 transform하는 동작은 미검증.

가능한 실패 시나리오 (E1 보고에서 식별):
1. advice 클래스 inline 시 classloader 가시성 문제
2. @Advice.Return(typing = DYNAMIC) Java 21 + ByteBuddy 1.15 호환
3. SpringMatchers의 hasSuperType(named(JpaRepository)) 매칭
4. HikariCP wrapper 통한 JDBC retransform

이번 작업은 이걸 확인하기 위한 sample app 작성과 검증 체크리스트.
검증 자체는 사용자가 수동으로 돌릴 것. Claude Code는 sample app + 체크리스트만.

## 작업 1: examples/sample-app/ 작성

위치: 루트의 examples/sample-app/ (settings.gradle.kts에서 include 안 함, 별도 빌드)

### 구성 (최소)
- Spring Boot 3.4.1 + Java 21
- spring-boot-starter-web
- spring-boot-starter-data-jpa
- com.h2database:h2 (in-memory, autoconfigured)
- HikariCP (Spring Boot 기본, JDBC wrapper 검증용)

### 코드

UserController:
  - @RestController @RequestMapping("/users")
  - POST /users (@RequestBody UserRequest) → UserResponse  -- 4 span 생성 시나리오
  - GET /users/{id} -- 단순 조회 (3 span: controller→service→repo+SQL)

UserRequest record:
  - String name
  - String password   -- 마스킹 default 룰 검증
  - String ssn        -- "850101-1234567" 형태로 테스트 → 마스킹 default 룰 검증
  - String email

UserResponse record:
  - Long id
  - String name
  - String email      -- password/ssn은 응답에 포함 안 함

UserService (@Service):
  - create(UserRequest) → UserResponse
  - findById(Long) → UserResponse

UserRepository:
  - public interface UserRepository extends JpaRepository<User, Long>

User (@Entity):
  - id (auto generated), name, password, ssn, email

application.yml:
  - server.port=8080 (server는 8765 그대로)
  - spring.datasource.url=jdbc:h2:mem:testdb
  - spring.jpa.hibernate.ddl-auto=create
  - spring.jpa.show-sql=false (ApiLens가 잡으니까 중복 출력 방지)

build.gradle (Maven 또는 Gradle 둘 다 OK, 사용자 환경에 맞춰):
  - Gradle Kotlin DSL 권장 (프로젝트 일관성)

## 작업 2: examples/sample-app/README.md

수동 검증 절차 + 체크리스트.

### 빌드 절차
1. Repo 루트에서 ./gradlew :apilens-agent:shadowJar
2. Repo 루트에서 ./gradlew :apilens-server:bootRun (port 8765)
3. cd examples/sample-app
4. ./gradlew bootRun -Pjvm-args="-javaagent:../../apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar -Dapilens.service.name=sample-app"
   (정확한 명령은 build.gradle.kts에 bootRun JVM args 설정)

### 시나리오 1: POST /users (4-span trace 검증)

curl -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"name":"홍길동","password":"hunter2!","ssn":"850101-1234567","email":"hong@example.com"}'

체크리스트 (curl http://localhost:8765/v1/traces 와 detail로 확인):

[ ] /v1/traces 응답에 1개 trace
[ ] trace.spanCount == 4
[ ] trace.serviceName == "sample-app"
[ ] trace.rootOperation == "POST /users"
[ ] trace.status == "OK"

[ ] spans[0].spanKind == "SERVER"
[ ] spans[0].operationName == "POST /users"
[ ] spans[0].parentSpanId == null
[ ] spans[0].attributes["http.method"] == "POST"
[ ] spans[0].attributes["http.status_code"] == 201 (or 200)
[ ] spans[0]의 payload (in) 존재
[ ] spans[0]의 payload (out) 존재

[ ] spans[1].spanKind == "INTERNAL"
[ ] spans[1].operationName 안에 "UserService.create" 포함
[ ] spans[1].parentSpanId == spans[0].spanId

[ ] spans[2].spanKind == "INTERNAL"
[ ] spans[2].operationName 안에 "UserRepository" 또는 ".save" 포함
[ ] spans[2].parentSpanId == spans[1].spanId

[ ] spans[3].spanKind == "DB"
[ ] spans[3].attributes["db.statement"] 존재 (insert ...)
[ ] spans[3].parentSpanId == spans[2].spanId

### 시나리오 2: 마스킹 검증

위 trace의 spans[0] payload_in을 확인:
[ ] body에 "hunter2!" (평문 password) 없음 ★ 결재의 핵심
[ ] body에 "********" 또는 마스킹 표시 있음
[ ] body에 "850101-1234567" (평문 SSN) 없음 ★
[ ] body에 부분 마스킹된 SSN ("850101-*******" 형태) 있음

### 시나리오 3: 호스트 앱 안전성

[ ] agent 옵션 없이 sample-app 실행 시 정상 시작
[ ] -Dapilens.server=http://존재하지않는호스트:9999 로 실행 시 sample-app 정상 시작
[ ] -Dapilens.service.name 누락 시 sample-app 정상 시작 (agent disabled)
[ ] sample-app 실행 중 ApiLens server 강제 종료 시 sample-app 정상 동작 유지

### 실패 시 디버깅
- -Dapilens.debug=true 로 stderr 로그 확인
- ./gradlew :apilens-agent:test 로 단위 테스트 다시 통과하는지 확인
- jar에 advice 클래스 들어가있는지: unzip -l ... | grep Advice

## 작업 3: docs/progress.md 갱신

Phase E1 마일스톤 (단위 검증 + sample app 작성). 수동 smoke 통과 여부는 사용자
검증 후 추가.

## 주의사항
- Phase A/B/C/D/E1 코드 절대 수정 금지. 이번엔 examples/sample-app/ 만 추가.
- 너의 작업 범위는 sample app 작성과 README 작성까지. smoke test 자체는 사용자가 수행.
- standalone javac로 sample app 컴파일 가능한지 정도는 확인 가능 (선택).
  실제 동작 검증은 사용자 책임.
- "수동 smoke 통과 확인됨" 같은 보고 금지. 사용자가 수동 검증 후 결과를 너에게 보고할 것.

작업 시작 전 CLAUDE.md, docs/agent-options.md, instrument/ 패키지 코드 한 번 훑고 시작.