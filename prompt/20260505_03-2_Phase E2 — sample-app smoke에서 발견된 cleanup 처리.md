Phase E2 — sample-app smoke에서 발견된 cleanup 처리

## 컨텍스트
Phase E1에서 ByteBuddy advice 5개 + InstrumentationInstaller 작성, sample-app
수동 smoke 검증 통과. 마스킹 동작 + trace 트리 + parent-child 다 OK.
다만 두 가지 주요 문제 + 작은 노이즈들 발견.

## 작업 1: db.statement 캡처 정상화 (최우선)

현재 동작: JDBC span에 db.statement attribute 누락. JdbcConnectionAdvice 매처가
실제 Spring Boot + HikariCP 환경의 Connection 구현체에 안 맞고 있음.

진단 방향 (단정 금지, 한 단계씩 확인):
1. apilens.debug=true 로 sample-app 실행 후 stderr에서 InstrumentationInstaller가
   transform 시도하는 클래스 목록 확인
   - HikariProxyConnection이 transform되는지
   - prepareStatement(String) 오버로드가 매칭되는지
2. JdbcConnectionAdvice의 매처를 읽어서 어떤 Connection을 노리는지 파악
3. 실제 sample-app에서 호출되는 Connection 구현체 체인 확인
   (HikariProxyConnection → ProxyConnection → JdbcConnection 같은 wrapping)
4. 매처를 java.sql.Connection 인터페이스 + 모든 prepareStatement 오버로드를 잡도록
   보강
5. JdbcSqlCache에 stash가 잘 되는지 stderr 로그 추가해서 확인

수정 후 sample-app smoke 재검증:
- POST /users 호출 시 spans[].attributes["db.statement"]에 INSERT SQL 존재
- GET /users/{id} 호출 시 SELECT SQL 존재
- 파라미터(?)는 디버그용 첫 단계엔 SQL만, 파라미터 캡처는 다음 작업

## 작업 2: JDBC 3중 wrapper 중복 제거 (옵션 결정 명시)

현재: HikariProxy / ProxyPS / H2 driver 셋 다 DB span으로 캡처되어 같은 SQL이
3번 노드로 보임.

설계 결정 (사용자 명시): **가장 바깥쪽 wrapper에서만 캡처** (앱 코드가 직접
호출하는 layer 기준).

이유: 운영자 관점에서 "내 코드가 호출한 SQL"이 보여야 함. HikariCP 내부의
connection pool 대기 같은 건 별도 metric (v0.2)으로 분리.

구현 방향:
- JDBC instrument 시 가장 바깥 layer만 advice 적용
- 안쪽 layer는 ignore() 매처로 제외
- 또는 ThreadLocal flag로 "이미 DB span 활성"이면 스킵 (재진입 방지)

후자(ThreadLocal flag)가 더 안전. wrapping 구조는 라이브러리마다 달라서 매처로
완벽 제외 어려움. ThreadLocal flag는 모든 환경에서 동작.

검증 후 sample-app smoke:
- POST /users 시 trace 트리에 DB span 1개만 (3개 X)
- spanCount == 4 (controller, service, repository, DB)

## 작업 3: 작은 cleanup들

### 3-1. RepositoryAdvice의 setter/getter 노이즈 제외
현재 매처가 SimpleJpaRepository#setProjectionFactory 등 setter도 instrument 시도.
무해하지만 trace에 가끔 보임. 매처에 not(nameStartsWith("set").or(nameStartsWith("get"))) 같은 추가.

### 3-2. README jq filter 정정
현재: .spans[0]
정정: .spans | map(select(.parentSpanId == null))[0]
(parent 없는 게 root span. 순서로 가정 위험)

### 3-3. TraceDetailResponse에 rootSpanId 편의 필드 (선택)
운영자/UI가 "root span이 뭐냐"를 매번 spans 배열 순회하지 않게.
TraceDetailResponse에 rootSpanId 필드 추가. server query 단의 작은 변경.

작업 1, 2가 핵심. 3은 시간 남으면 같이.

## 작업 외 (이번엔 안 함)
- Spring CGLIB UserRepository proxy 매칭 — 운영망에서 거의 안 쓰이고 트리 한 단계 차이만. v0.2.
- @RestController annotation 자체가 transform 시도 — 무해, 로그만 노이즈. v0.2.
- 자동화 통합 테스트 (Plan C) — 수동 smoke 안정화 후 별도 작업.

## 검증 (사용자가 수행)

너는 코드 수정 + 단위 테스트만. 실제 동작 검증은 사용자가 sample-app으로 수행.

검증 절차:
1. ./gradlew clean test  -- 79+ 단위 테스트 다 통과
2. ./gradlew :apilens-agent:shadowJar
3. ./gradlew :apilens-server:bootRun  (다른 터미널)
4. cd examples/sample-app && ./gradlew bootRun (-javaagent 적용)
5. POST /users 호출 후 trace 확인:
   - 4 spans (1 controller + 1 service + 1 repository + 1 DB)
   - DB span의 db.statement에 INSERT SQL 존재
   - DB span 중복 없음
6. GET /users/{id} 호출 후 trace 확인:
   - DB span의 db.statement에 SELECT SQL
   - 마찬가지 중복 없음

## 주의사항

- Phase A/B/C/D/E1 코드는 가능한 수정 최소화. 매처 보강은 instrument/ 안에서.
- 위 작업 1의 진단은 단정 금지. apilens.debug=true 로그를 보면서 한 단계씩 확인.
- "수동 smoke 통과 확인됨" 단정 보고 금지. 사용자가 직접 검증할 항목 명시.
- bootstrap classloader injection 제거 결정은 Phase E1 fix #1으로 박혀있음. 다시 시도하지 말 것.

작업 시작 전 instrument/jdbc/, instrument/advice/JdbcConnectionAdvice.java,
JdbcAdvice.java 코드를 읽고, sample-app의 의존성 트리(특히 HikariCP wrapping)를
파악하고 시작.