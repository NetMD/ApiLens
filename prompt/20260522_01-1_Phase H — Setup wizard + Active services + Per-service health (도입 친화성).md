Phase H — Setup wizard + Active services + Per-service health (도입 친화성)

## 컨텍스트
CLAUDE.md 먼저 읽고 시작. ApiLens v0.1.0 release blocker 모두 해소되어 있음
(Phase A-E3 + F1-F2 + G1 complete). 그러나 사용자 NAS dogfooding 결과 도입 사이클
복잡도가 release blocker로 새로 식별됨:
- 빌드 (가) / server 띄우기 (나) / agent 부착 (다) / 첫 trace 확인 (마) 모두 부담
- Java 21+ JEP 451 제약으로 동적 agent 부착(Attach API) 모델 불가능 — 사용자 앱
  재기동 필수 + 같은 OS 계정 + EnableDynamicAgentLoading 필요. 운영망에서 통과 어려움
- 따라서 "자동 부착" 대신 "도입 사이클의 명령어 조립 부담 0" 로 방향 선회

본 phase는 v0.1 release 전 마지막 큰 작업. 운영자가 ApiLens 처음 깔 때
docs 안 읽고도 setup wizard 따라 옵션 한 줄 생성 → 자기 앱 JVM 옵션에 복사 → 재기동.

## 사용자 결정 사항 (비협상, 변경 금지)

### 1. Setup wizard 재접근 가능
- 헤더에 "+ Add service" 또는 동등 진입점
- wizard 여러 번 진입 가능
- 첫 실행 시에만 자동 wizard, 그 후엔 명시적 진입

### 2. Services 등록 두 경로 모두 지원
- 경로 A: Setup wizard에서 명시 등록 (운영자가 운영망 토폴로지 알고 미리 등록)
- 경로 B: 첫 trace 받는 순간 자동 등록 (현재 동작 보존, agent.startup span이 이미 등록됨)
- 두 경로의 결과는 동일 — services 테이블에 항목 생성
- 중복 등록 시 무시 (service_name UNIQUE)

### 3. Health 정의 — 단순 시간 기반
- active: 최근 5분 안에 trace 받음 → 초록 점
- stale: 5~30분 trace 없음 → 노랑 점
- inactive: 30분+ trace 없음 → 회색 점
- error rate / p95 latency 같은 복잡 metric은 v0.2

### 4. Wizard skip 가능 (강제 안 함)
- "건너뛰기" 버튼
- 클릭 시 dashboard 로 이동 + 작은 toast 또는 footer 안내 "Setup 가이드: docs/setup.md"
- skip 했어도 setup_completed 상태는 마킹 (다시 자동 안 뜸)
- 운영자가 수동으로 wizard 재진입 가능

### 5. Inactive 처리
- 자동 제거 X. 30분+ trace 없으면 inactive 표시만
- Active services 목록에서 각 항목에 삭제 버튼
- 삭제 시 services 테이블에서 row 제거. 기존 trace 데이터는 그대로 유지
- 같은 service_name으로 trace 다시 들어오면 자동 재등록 (경로 B)

### 6. 운영자 페르소나 정직 인지
한국 SI 운영자. docs 깊이 안 읽음. UI에서 모든 결정 가능해야 함.
명령어 직접 조립 부담 0. wizard가 옵션 한 줄 만들어줌.

## 작업 범위 — Backend + UI 양쪽

이번 phase는 backend + UI 양쪽 작업. 작업 양 큼.
중간에 분할 보고 가능. 단, 양쪽 인터페이스 (DTO, endpoint) 한 번에 잡을 것.

## Backend 작업

### B1. 신규 테이블 services + setup_state

`apilens-server/src/main/resources/db/migration/V2__services_and_setup.sql` 신규:

````sql
CREATE TABLE services (
    service_name TEXT PRIMARY KEY,
    registered_at INTEGER NOT NULL,  -- epoch millis
    last_seen_at INTEGER,            -- 최근 trace received_at (NULL = wizard로만 등록, trace 0건)
    source TEXT NOT NULL             -- 'wizard' | 'auto'
);

CREATE INDEX idx_services_last_seen ON services(last_seen_at DESC);

CREATE TABLE setup_state (
    id INTEGER PRIMARY KEY,
    completed INTEGER NOT NULL DEFAULT 0,  -- 0/1
    completed_at INTEGER,
    server_url TEXT  -- wizard에서 입력한 server URL (운영망 IP 등)
);

INSERT INTO setup_state (id, completed) VALUES (1, 0);
````

### B2. Ingest 시 services 자동 등록 (경로 B)

위치: `apilens-server/.../ingest/IngestService.java`

기존 ingest 로직에 추가:
- span 받을 때 trace.serviceName 으로 services 테이블에 UPSERT
````sql
  INSERT INTO services (service_name, registered_at, last_seen_at, source)
  VALUES (?, ?, ?, 'auto')
  ON CONFLICT(service_name) DO UPDATE SET last_seen_at = excluded.last_seen_at
````
- source 는 처음 등록 시점 기준. wizard 로 먼저 등록되어 있으면 source='wizard' 유지, last_seen_at 만 갱신

### B3. 신규 REST endpoints

위치: `apilens-server/.../setup/` 패키지 신규

````
GET /v1/setup/state
응답: { completed: boolean, completedAt: number|null, serverUrl: string|null }

POST /v1/setup/complete
요청: { serverUrl: string, services?: string[] }
응답: { completed: true, completedAt: number }
동작:
  - setup_state.completed=1, completed_at=now, server_url=요청.serverUrl
  - services[] 있으면 각각 source='wizard'로 services 테이블 등록
  - 멱등 (이미 completed=1 이어도 200, server_url과 services 갱신)

GET /v1/services
응답: {
  services: [{
    name: string,
    registeredAt: number,
    lastSeenAt: number | null,
    source: 'wizard' | 'auto',
    traceCount: number,           // services 전체 trace 수 (기존 G E T /v1/services 와 호환)
    healthStatus: 'active' | 'stale' | 'inactive' | 'never'  // never: trace 0건
  }],
}

DELETE /v1/services/{serviceName}
응답 204
동작: services 테이블에서 row 제거. traces/spans/payloads 데이터는 보존
````

기존 `GET /v1/services` 는 G1 까지의 응답 모양(`{name, traceCount, lastSeen}`)을 확장:
- healthStatus 필드 추가
- registeredAt, source 필드 추가
- lastSeen → lastSeenAt 으로 이름 통일 (또는 둘 다 보내고 deprecation)
  → 사용자 결정 필요. 권고: 신규 lastSeenAt 단일화, dashboard UI도 lastSeenAt 으로 통일

healthStatus 계산 (server-side):
````java
long now = System.currentTimeMillis();
long lastSeen = service.lastSeenAt;
if (lastSeen == null) return "never";
long elapsedMin = (now - lastSeen) / 60000;
if (elapsedMin <= 5) return "active";
if (elapsedMin <= 30) return "stale";
return "inactive";
````

### B4. Setup wizard 가 생성하는 JVM 옵션 문자열 — server-side helper

위치: `apilens-server/.../setup/AgentOptionBuilder.java` 신규

UI 에서 사용자 입력값 받아 JVM 옵션 한 줄 만드는 헬퍼.
순수 함수, 단위 테스트 용이.

````java
public class AgentOptionBuilder {
    public static String build(AgentOptionRequest req) {
        // req: { serviceName, serverUrl, captureParams (bool), captureResultSet (bool) }
        // 반환: "-javaagent:./apilens-agent.jar \
        //        -Dapilens.service.name=my-app \
        //        -Dapilens.server=http://10.0.1.50:8765 \
        //        -Dapilens.jdbc.capture-params=true"
    }
}
````

단위 테스트 4건:
- 기본 옵션 (params ON, resultSet OFF) → 3줄 옵션
- params OFF → -Dapilens.jdbc.capture-params=false 명시
- resultSet ON → -Dapilens.jdbc.capture-result-set=true 추가
- serviceName 빈 문자열 → IllegalArgumentException

이 helper 는 클라이언트에서 호출하지 않음. wizard UI 에서 동일 로직을 TypeScript 로
중복 구현 (네트워크 왕복 안 함). server-side는 docs/문서 생성 용도.

### B5. 테스트

- IngestService 자동 등록 단위 테스트: 신규 service 첫 trace 도착 시 services 테이블 자동 INSERT
- 이미 wizard 등록된 service 도착 시 source='wizard' 유지 + last_seen_at 갱신
- healthStatus 분기 단위 테스트 (active/stale/inactive/never)
- DELETE 후 같은 service_name 으로 trace 받으면 자동 재등록
- AgentOptionBuilder 4건

기존 105+ tests + 신규 약 10건. 모두 통과 필요.

## UI 작업

### U1. SetupWizard 페이지 신규

위치: `apilens-ui/src/pages/Setup.tsx` (또는 `pages/setup/index.tsx`)

라우팅: `/setup`. 첫 실행 시 (setup_state.completed=false) `/` 접근 시 `/setup` 으로 자동 리다이렉트.

레이아웃 — 4단계 진행 stepper:

````
Step 1: ApiLens server URL
  - 입력: http://[IP/hostname]:[port]
  - 기본값 placeholder: http://localhost:8765
  - 운영망 안내 메시지 (작게): "운영망에서는 사용자 앱이 접근 가능한 IP/hostname 입력"
  
Step 2: Service 정보 (선택, 여러 개 가능)
  - "서비스 추가" 버튼 클릭 시 입력 row 추가
  - service.name 입력 (자유 입력)
  - "이 단계 건너뛰기" 가능 (경로 B로 자동 등록 가능하니까)

Step 3: 옵션
  - JDBC 파라미터 캡처: ☑ ON (권장, 기본값)
  - JDBC 결과셋 캡처:   ☐ OFF (기본값, 운영 환경 권장)
  - 안내 메시지: "운영 환경 권장 옵션입니다. 자세한 옵션은 docs/agent-options.md 참고"

Step 4: 생성된 JVM 옵션 + 다음 단계 안내
  - 박스 안에 한 줄 옵션 (모노스페이스)
  - "📋 복사" 버튼
  - 다음 단계 안내:
    1. apilens-agent.jar 를 사용자 앱 옆에 둠
    2. 위 옵션을 사용자 앱 JVM 옵션에 추가
    3. 사용자 앱 재기동
    4. "완료" 버튼 클릭 → dashboard 로 이동
  - "완료" 버튼 → POST /v1/setup/complete 호출 → dashboard 이동

상단 우측에 작게: "건너뛰기" 링크
  - 클릭 시 confirm 모달: "setup 없이 진행할까요? 나중에 헤더의 'Add service' 로 다시 열 수 있습니다. 안내 문서: docs/setup.md"
  - 확인 시 POST /v1/setup/complete 호출 (serverUrl=현재 host, services=[]) → dashboard

JVM 옵션 생성은 client-side (TypeScript) 로 동일 로직. server B4 helper 와 결과 일치 필요. 단위 테스트 4건 (B5 와 동일 케이스).

### U2. ActiveServices 페이지 신규

위치: `apilens-ui/src/pages/ActiveServices.tsx` (또는 `pages/services/index.tsx`)

라우팅: `/services`

레이아웃 — 단순 카드 또는 테이블:

````
+-------+-------------------+--------------+---------------+--------+
| 상태  | service           | last trace   | trace count   | 작업   |
+-------+-------------------+--------------+---------------+--------+
| 🟢   | sample-app        | 2분 전        | 145           | [삭제] |
| 🟡   | order-service     | 12분 전       | 89            | [삭제] |
| 🔴   | legacy-batch      | 1시간 35분 전  | 12            | [삭제] |
| ⚪   | new-service       | 미수신        | 0             | [삭제] |
+-------+-------------------+--------------+---------------+--------+

[+ Add service] (헤더 또는 페이지 상단)
````

각 row 클릭 시 Dashboard 로 이동 + ?service= 파라미터 자동 적용.

[+ Add service] 클릭 시 `/setup` 으로 이동.

삭제 버튼 → confirm 후 DELETE /v1/services/{name} → 목록에서 제거.

healthStatus 표시:
- 🟢 active (최근 5분 안)
- 🟡 stale (5-30분)
- 🔴 inactive (30분+)
- ⚪ never (trace 0건, wizard 로만 등록)

페이지 자동 새로고침: TanStack Query refetchInterval 30초 (Live 토글 없이 항상).
30초마다 GET /v1/services 호출해서 health 갱신.

### U3. Dashboard 헤더에 "Add service" 진입점

위치: `apilens-ui/src/pages/Dashboard.tsx` 의 헤더

기존 ServiceSelector 우측에 작은 [+] 버튼 추가. 클릭 시 /setup 으로 이동.
또는 ServiceSelector 옵션 가장 아래에 "+ Add new service..." 항목.

권고: 별도 [+] 버튼이 명확. 클릭 시 새 setup wizard 진입.

### U4. 첫 실행 라우팅 가드

위치: `apilens-ui/src/App.tsx` 또는 라우터 진입점

첫 마운트 시 GET /v1/setup/state 호출:
- completed === false 면 현재 경로가 /setup 이 아닌 경우 /setup 으로 리다이렉트
- completed === true 면 그대로 진행

TanStack Query 의 useQuery + Suspense 또는 단순 useEffect + navigate.

### U5. 라우터에 새 경로 추가

```typescript
{
  path: '/',
  element: <Dashboard />,
},
{
  path: '/setup',
  element: <Setup />,
},
{
  path: '/services',
  element: <ActiveServices />,
},
{
  path: '/traces/:traceId',
  element: <TraceDetail />,  // 기존
},
```

### U6. 헤더에 services 진입점 추가

기존 Dashboard 헤더의 ApiLens 로고 옆에 작은 메뉴 또는 우측 영역:
- "Dashboard" / "Services" 두 개 링크

또는 ApiLens 로고 클릭 시 dropdown menu — Dashboard / Services / Setup 진입.
권고: 단순한 좌측 가로 메뉴 ("Dashboard | Services").

## 작업 외 (v0.2 또는 v0.1.1)
- Wizard 의 다국어 (한/영)
- agent.jar 자동 다운로드 (wizard 가 jar 파일 제공)
- 운영자 권한 / 인증 (v0.3)
- Per-service error rate / p95 latency (v0.2)
- Service group 으로 묶기 (MSA 시점, v0.3)

## docs/setup.md 신규 작성 (skip 안내용)

간단한 마크다운. wizard 안 거치고 직접 옵션 조립하는 운영자를 위한 안내.

````
# ApiLens Setup (Manual)

자동 setup wizard 를 건너뛰고 직접 옵션을 조립하는 운영자용 안내.

## 1. ApiLens server 실행
java -jar apilens-server.jar

## 2. agent jar 위치
- apilens-server.jar 첫 실행 시 ~/.apilens/apilens-agent.jar 자동 추출됨
- 또는 GitHub release 페이지에서 직접 다운로드

## 3. JVM 옵션 추가
java \
  -javaagent:apilens-agent.jar \
  -Dapilens.service.name=my-app \
  -Dapilens.server=http://[ApiLens server IP]:8765 \
  -Dapilens.jdbc.capture-params=true \
  -jar my-app.jar

## 4. 옵션 전체 목록
docs/agent-options.md 참조
````

## 검증 (사용자가 수행)

자동:
1. ./gradlew clean test  -- 신규 단위 테스트 약 10건 추가, 모두 통과
2. cd apilens-ui && npm run build  -- 빌드 통과
3. cd apilens-ui && npm run test  -- wizard 옵션 생성 단위 테스트 통과

수동 smoke:
[ ] DB 초기화 후 server 첫 실행 → / 접근 시 /setup 자동 리다이렉트
[ ] Setup wizard 4단계 모두 동작
[ ] Step 4 의 JVM 옵션 박스 + 복사 버튼 동작
[ ] "완료" 버튼 → / 이동, 이후 / 접근 시 wizard 자동 안 뜸
[ ] "건너뛰기" → confirm 모달 → /로 이동
[ ] 헤더의 [+ Add service] 또는 동등 진입점 → /setup 다시 진입 가능
[ ] /services 페이지에 sample-app 표시 (5분 이내 trace 있으면 🟢)
[ ] sample-app 5분간 trace 안 보내고 페이지 새로고침 → 🟡
[ ] 30분 후 → 🔴
[ ] 삭제 버튼 → confirm → 목록에서 제거
[ ] 같은 service_name 으로 trace 다시 보내면 자동 재등록
[ ] /services 각 row 클릭 → /?service= 로 이동
[ ] 헤더 "Dashboard" / "Services" 링크 동작
[ ] DB 초기화 (apilens.db 삭제) 후 재시작 → / 다시 setup 으로 리다이렉트 (setup_state V2 마이그레이션 + 초기값 completed=0 검증)

## 주의

- 사용자 결정 5건 (a~e) 비협상. architect 가 재해석하지 말 것.
- v0.1.0 release blocker 가 본 phase. 완성도 우선. 미세 디자인은 v0.1.1 backlog.
- mockup 비교 fixture 없음. 새 화면이라 디자인은 Phase F1/F2 톤 유지 (mono font, 회색 톤, 단순함).
- 기존 Phase A-G1 코드 가능한 최소 수정 (B2 IngestService 의 services 자동 등록만 신규 라인 추가).
- TypeScript strict, any 금지, console.log 금지.
- "수동 smoke 통과 확인됨" 단정 보고 금지. 사용자 검증 항목 명시.

작업 시작 전 (Backend 작업 시):
- CLAUDE.md
- docs/api.md (기존 endpoint 패턴)
- IngestService.java (B2 수정 대상)
- 기존 V1__initial_schema.sql 패턴 (V2 마이그레이션 형식)

작업 시작 전 (UI 작업 시):
- F1/F2 컴포넌트 톤 (LatencyScatter, TraceList, SpanInspector)
- 기존 라우터 정의
- TanStack Query 패턴 (Dashboard, TraceDetail)
````