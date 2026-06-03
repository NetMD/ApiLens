Phase F1 후속 — sample-app smoke로 발견된 실제 버그 수정

## 컨텍스트
F1 dashboard 빌드 + sample-app smoke 진행 중 진단 완료. 실제 발견된 문제는 3개:

1. agent의 status 결정 — HTTP 4xx/5xx 응답이 status=OK로 라벨링됨 (의미상 ERROR)
2. UI의 LatencyScatter — Y축 padding 부족, 점이 차트 경계에 잘림
3. UI의 Recent traces — rootOperation 풀 패키지 경로가 길어 가독성 떨어짐

(처음에 의심했던 sample-app path mapping 문제는 사용자 호출 실수였음. 
sample-app은 README/controller/javadoc 모두 /users로 일관됨. 변경 금지.)

## 작업 1: agent의 HTTP status_code 기반 ERROR 판정

현재 ControllerAdvice는 thrown != null 일 때만 status=ERROR로 잡음.
HTTP 응답 status_code >= 400 인 경우도 ERROR로 마킹해야 운영자 가치 보존
(빨간 점이 진짜 빨간 점이어야 함).

수정 위치: apilens-agent/src/main/java/io/apilens/agent/instrument/advice/ControllerAdvice.java
또는 그 advice가 사용하는 helper.

로직:
- exit advice에서 HttpServletResponse.getStatus() 추출 (이미 추출하고 있으면 재사용)
- thrown != null → status = ERROR (기존)
- thrown == null && status_code >= 400 → status = ERROR (신규)
- 그 외 → status = OK

attributes의 http.status_code는 이미 캡처되고 있으므로 그 값 재사용 가능.
별도 reflection 추가 X.

검증:
- 단위 테스트 1개 추가: status_code 404 시 status=ERROR 검증 (200, 500도 케이스로)
- sample-app smoke (사용자 책임): 잘못된 path 호출 시 trace의 status가 ERROR로 보임

## 작업 2: LatencyScatter Y축 padding

위치: apilens-ui/src/components/LatencyScatter.tsx

현재: 점들이 차트 상단/하단 경계에 닿아 잘려보임.

수정: <YAxis> 에 padding={{ top: 16, bottom: 12 }} 추가.

이유: 도메인 자체를 손대면 log scale 라벨 (1ms, 2ms, ...) 이 어색해질 수 있음.
padding은 시각적 여백만 늘리고 데이터 범위는 보존.

다른 props는 변경 금지.

검증: 사용자가 dev server 띄우고 시각적 확인.

## 작업 3: TraceList의 rootOperation 가독성

위치: apilens-ui/src/components/TraceList.tsx

현재: "org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController#error"
       전체 표시.

수정: 표시할 때 패키지 경로 제거. simple name + method만:
- "org.springframework.boot.../BasicErrorController#error" → "BasicErrorController#error"
- "com.example.sampleapp.UserController#create" → "UserController#create"

구현:
- src/lib/format.ts (신규) 또는 lib/string.ts 같은 곳에 헬퍼:
  function shortenOperation(op: string): string {
    const hashIdx = op.indexOf('#')
    if (hashIdx === -1) return op
    const className = op.substring(0, hashIdx)
    const method = op.substring(hashIdx)
    const lastDot = className.lastIndexOf('.')
    return lastDot === -1 ? op : className.substring(lastDot + 1) + method
  }
- TraceList.tsx에서 rootOperation을 shortenOperation()으로 감싸 표시
- 원본은 title attribute (호버 시 툴팁)로 유지 — 운영자가 풀 경로 필요할 때 호버

이 헬퍼는 F2의 노드 그래프 operationName 표시에도 재사용 예정. 미리 분리 가치.

테스트: shortenOperation 단위 테스트 4건:
- 일반 클래스: "com.example.UserController#create" → "UserController#create"
- # 없는 경우: "agent.startup" → "agent.startup" (그대로)
- . 없는 경우: "FooBar#x" → "FooBar#x" (그대로)
- 빈 문자열: "" → ""

## 작업 외 (이번엔 안 함)
- BasicErrorController를 agent에서 ignore — 결정 보류. 작업 1로 ERROR 라벨링되면 운영자에게 의미 있는 데이터 (4xx/5xx 통계). 운영망 가치 있음. Recent traces가 너무 noise면 v0.2에서 필터 옵션 추가.
- agent.startup span 노이즈 — Recent traces에 보이지만 agent 시작 시 1회뿐. v0.2에서 service_name="apilens-agent" 같은 별도 분류로 dashboard 필터.
- sample-app에 slow endpoint (Thread.sleep) 추가 — slow 점 검증용. 별도 작업.
- sample-app path mapping 변경 — 변경 금지. /users로 일관됨.

## 검증 (사용자가 수행)
너는 코드 수정과 단위 테스트까지. 실제 동작 검증은 사용자가:

1. ./gradlew clean test  -- 모든 단위 테스트 통과 (agent 단위 테스트 1개 추가됨)
2. ./gradlew :apilens-agent:shadowJar
3. ./gradlew :apilens-server:bootRun (별도 터미널)
4. cd examples/sample-app && ./gradlew bootRun -Papilens.server=http://127.0.0.1:8765
5. curl -X POST http://localhost:18080/users -H 'Content-Type: application/json' \
     -d '{"name":"test","email":"t@e.com","ssn":"850101-1234567","password":"hunter2"}' -i
   → HTTP 201 또는 200 + UserController#create trace 생성
6. curl -X GET http://localhost:18080/users/99999 -i
   → HTTP 404 + trace의 status=ERROR 라벨링 확인
7. cd apilens-ui && npm run dev
8. http://localhost:5173 접속:
   - Recent traces가 "UserController#create" 같이 짧게 표시되는지
   - 호버 시 풀 경로 툴팁
   - 4xx 호출이 빨간 점 + 빨간 ERR 배지로 보이는지
   - Y축 점들이 경계에 안 잘리는지

## 주의사항
- Phase A~E2 코드는 작업 1 외엔 수정 금지. F1 UI 코드는 작업 2, 3만.
- agent advice 수정 시 try-catch 가드 + try-finally 누수 방지 패턴 그대로 유지
  (Phase E2 retrospective의 sentinel 패턴 lessons).
- sample-app /users path는 변경 금지 (README, controller, javadoc 모두 일관됨).
- "수동 smoke 통과 확인됨" 단정 보고 금지.

작업 시작 전 CLAUDE.md, docs/agent-options.md, examples/sample-app/README.md,
ControllerAdvice.java를 한 번씩 훑고 시작.