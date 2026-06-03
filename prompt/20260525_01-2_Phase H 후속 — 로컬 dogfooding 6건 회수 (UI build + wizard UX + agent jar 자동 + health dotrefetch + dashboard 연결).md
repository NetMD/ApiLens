Phase H 후속 — 로컬 dogfooding 6건 회수 (UI build + wizard UX + agent jar 자동 + health dot/refetch + dashboard 연결)

## 컨텍스트

CLAUDE.md 먼저 읽고 시작. R9 Phase H (2026-05-22 종료, Setup wizard + Active services
+ Per-service health) 완료 후 사용자가 로컬 dogfooding 으로 V-USER-1 묶음 5건 +
prompt 원본 수동 smoke 13건 + 실사용 검증을 수행. 그 결과 6 카테고리 회수 의제 발견.

본 후속 라운드는 **v0.1.0 release tag 직전 마지막 회수**. Phase H 의 본질 의도 ("도입
사이클의 명령어 조립 부담 0") 가 일부 항목에서 깨졌음이 dogfooding 으로 드러남.
6 항목 모두 release blocker 또는 Phase H 본질 의도 위배 회수이며 새 기능 추가 아님.

## R9 Phase H 본체 결정 사항 (변경 금지 — 본 후속은 회수만 수행)

본체에서 확정한 5 비협상 (D-01~D-05) + 위임 3 (W-01~W-03) + V-USER 2 (H1·H2) + architect
8 (Q-01~Q-08) 모두 그대로 유지. 본 후속은 그 위에서 dogfooding 결함만 회수.

특히 보존할 본체 결정:
- D-01 wizard 재접근 가능
- D-02 services 등록 두 경로 (wizard + 자동)
- D-03 healthStatus 시간 기반 (5분/30분)
- D-04 skip 가능 (BE-FAIL-01 영구 봉인 회귀 가드)
- D-05 inactive 자동 제거 X (삭제 시 trace 데이터 보존)
- W-01 `lastSeen → lastSeenAt` 단일화
- W-02 헤더 별도 [+] 버튼
- W-03 좌측 가로 메뉴 "Dashboard | Services"
- SH-02 [복사] 버튼 success = 라벨 변경 + toast 둘 다
- SH-03 healthStatus emoji 직접 사용 금지 (CSS dot 유지) — 본 후속 #6 (a) 에서 dot 색만 신호등 톤으로 신설
- SH-06/SH-10 search 보존 (R3 회귀 차단)
- SH-11 wizard 진행 상태 useState 단일 (URL state 안 박음)
- SH-13 queryKey 에 시간 변수 박지 말 것
- SH-14 code 박스 select-all
- SH-15 input focus:ring-1
- SH-16 두 경로 (skip / 완료) invalidate
- SH-18 색 + 한글 라벨 + aria-label 3중 (색만으로 표현 금지)
- SH-19 [삭제] 버튼 event.stopPropagation()
- Q-08 cross-stack parity (BE AgentOptionBuilder ↔ FE agent-option-builder.ts golden output 동일)

## 사용자 결정 사항 (본 후속 비협상, 변경 금지)

### 1. agent jar 경로 = server 자동 추출 + 절대경로 (방향 B 채택)

방향 (a) `${APILENS_AGENT_JAR}` 변수 placeholder 와 (c) wizard step 신설 모두 기각.

채택: **ApiLens server 가 startup 시 임베드된 `resources/main/agent/apilens-agent.jar`
를 사용자 home 의 `~/.apilens/apilens-agent.jar` 로 자동 추출 → wizard 가 신규 endpoint
`GET /v1/setup/agent-jar-path` 로 그 절대경로를 받아 JVM 옵션 박스에 직접 박음**.

이유:
- 운영자는 복사 → 붙여넣기 → 재기동만 하면 됨. 경로 치환 부담 0
- docs/setup.md 가 이미 `apilens-server.jar 첫 실행 시 ~/.apilens/apilens-agent.jar
  자동 추출됨` 으로 약속 — 현재 추출 로직이 없음 (또는 placeholder) → 약속과
  실제 동작 정합 회수
- Phase H 본질 의도 "도입 사이클의 명령어 조립 부담 0" 완전 보존

### 2. dashboard 진입 시 service 자동 selection

Wizard 완료 후 dashboard 진입 시 사용자가 wizard 에서 등록한 service 가 default
selection 으로 박혀야 함. 현재는 "상단에서 서비스를 선택하세요" 안내 후 헤더
ServiceSelector 에서 수동 선택 강요 → wizard 등록의 의미 단절.

채택:
- wizard 완료 mutation onSuccess 에서 `nav('/', { search: { service: serviceName } })`
- 자동 등록 (경로 B) 의 경우는 dashboard 의 `useDashboardState` 가 services 목록
  최초 1건이 있으면 default selection 으로 잡음 (services.length === 1 일 때만 자동)
- 다중 service 환경 (≥ 2건) 에서는 기존 "상단에서 서비스를 선택하세요" 유지

### 3. dot 색 = 신호등 톤 별도 health 토큰 신설

기존 디자인 토큰 (`--color-status-ok=#888780` 회색-올리브 / `slow=#BA7517` 어두운 주황
/ `error=#E24B4A` 빨강) 은 trace status 의미 (OK/SLOW/ERROR). dot 이 갈색으로 보여
"신호등 멘탈모델" 과 어긋남.

채택:
- 신규 토큰 4개 신설 (index.css):
  - `--color-health-active`: `#22C55E` (Tailwind green-500, 신호등 초록)
  - `--color-health-stale`:  `#F59E0B` (Tailwind amber-500, 신호등 노랑)
  - `--color-health-inactive`: `#EF4444` (Tailwind red-500, 신호등 빨강)
  - `--color-health-never`:  `#A8A29E` (회색 — never 만 기존 idle 톤 유지)
- `STATUS_HEX_HEALTH` 매핑을 신규 토큰으로 미러
- 기존 `--color-status-*` 4토큰은 trace status 전용으로 보존 (TraceList / TraceGraph
  영향 0)
- SH-18 3중 표현 (색 + 한글 라벨 + aria-label) 보존

### 4. ActiveServices auto-refetch 강화

현재 `refetchInterval: 30_000` + `refetchOnWindowFocus: false` + `refetchIntervalInBackground`
default false → 사용자가 다른 탭 갔다 돌아오면 자동 갱신 안 됨 → 새로고침 강요.

채택:
- `refetchOnWindowFocus: true` 활성화 (탭 복귀 시 즉시 갱신)
- `refetchIntervalInBackground` 는 명시 안 함 (default false 유지 — background 트래픽
  절약, 활성 탭에서만 30초 polling)
- `staleTime: 0` 으로 낮춰 focus refetch 가 무조건 동작하도록

### 5. Server URL default 자동 입력

`window.location.origin` 을 Step 1 default 로 박음. 사용자가 변경 가능 (운영망 IP
다를 수 있음). placeholder 텍스트는 default 가 박혔으므로 의미 약화 — 안내 메시지로 흡수.

### 6. Step 2 안내 운영자 어휘로 강화

"사용자 앱을 구분할 이름을 입력해 주세요" → 사용자 입장에서 wizard 자체가 ApiLens
설정인지 외부 서비스 등록인지 모호.

채택 (V-USER-H1 "친절하되 과도하지 않게" 톤 유지):
- 라벨: "Service Name" 유지 (변경 없음)
- 안내: "**ApiLens 가 모니터링할 사용자 앱(서비스/시스템) 의 이름이에요**" (해요체)
- 보조: "영문/숫자/하이픈/언더스코어. 예: `my-api`, `order-service`, `vams`"

## 작업 범위 — Backend + UI 양쪽

### 회수 항목 6 카테고리 묶음 (BE 4 / UI 5 — 일부 양쪽)

| # | 영역 | BE | UI | release blocker |
|---|------|-----|------|------------------|
| 1 | UI build 깨짐 (tsc 가 test 컴파일) | — | tsconfig.app.json | ★ |
| 2 | dashboard service 자동 selection | — | Dashboard.tsx / useDashboardState | (UX 단절) |
| 3 | Step 1 server URL default | — | Setup.tsx Step 1 | (사용자 마찰) |
| 4 | Step 2 안내 강화 | — | Setup.tsx Step 2 | (사용자 마찰) |
| 5 | agent jar 자동 추출 + 절대경로 endpoint | server-side jar 추출 + 신규 endpoint + AgentOptionBuilder 갱신 | Setup.tsx Step 4 + agent-option-builder.ts + 신규 hook useAgentJarPath | ★★★ (본질 의도) |
| 6 | health dot 색 + auto-refetch 강화 | — | index.css health 토큰 신설 + colors.ts + ActiveServices.tsx query 옵션 | ★ |

---

## Backend 작업

### B1. Agent jar 자동 추출 — startup 시 ~/.apilens/apilens-agent.jar 로 풀기

위치: `apilens-server/.../setup/AgentJarExtractor.java` (신규)

Spring `@PostConstruct` 또는 `ApplicationRunner` 로 server startup 시 1회 실행:

```java
@Component
public class AgentJarExtractor {
    private static final String EMBEDDED_RESOURCE = "/agent/apilens-agent.jar";
    private static final String TARGET_FILENAME = "apilens-agent.jar";

    @Value("${apilens.agent.jar.target-dir:#{systemProperties['user.home'] + '/.apilens'}}")
    private String targetDir;

    private Path extractedPath;

    @PostConstruct
    public void extract() throws IOException {
        Path target = Path.of(targetDir, TARGET_FILENAME);
        // resources/main/agent/apilens-agent.jar 가 임베드되어 있는지 확인
        try (InputStream in = getClass().getResourceAsStream(EMBEDDED_RESOURCE)) {
            if (in == null) {
                // CLAUDE.md: "UI 미빌드 시 server는 경고만 띄우고 정상 기동" 동일 패턴.
                // agent jar 미임베드 시 경고 + extractedPath=null 로 유지
                log.warn("ApiLens agent jar not embedded (build :apilens-server first). " +
                         "Setup wizard will fall back to placeholder path.");
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        extractedPath = target;
        log.info("ApiLens agent jar extracted: {}", target);
    }

    public Optional<Path> getExtractedPath() {
        return Optional.ofNullable(extractedPath);
    }
}
```

요구사항:
- 임베드 위치 `apilens-server/build.gradle.kts:29-55` 의 `processResources` 가 이미
  `apilens-agent/build/libs/apilens-agent-*.jar` 를 `agent/apilens-agent.jar` 로
  복사 — 확인하고 안 되어 있으면 보정
- 같은 SHA-256 일 때 추출 skip 최적화는 v0.1.1 backlog (지금은 단순 overwrite)
- 추출 실패 (디스크 권한 등) 시 silent log warning + extractedPath=null. agent
  자체 동작에 영향 없음 (사용자 앱이 agent 부착해서 쓰는 것이고 server 가 직접
  agent jar 를 실행하는 것 아님)

단위 테스트 3건:
- 임베드된 리소스 정상 추출 → 파일 존재 + size > 0
- 리소스 없을 때 extractedPath=null + 로그 경고 (RuntimeException 던지지 않음)
- target 디렉터리 이미 존재 시 재실행 → overwrite 정상

### B2. 신규 endpoint GET /v1/setup/agent-jar-path

위치: `apilens-server/.../setup/SetupController.java` (기존 파일 메서드 추가)

```java
@GetMapping("/agent-jar-path")
public AgentJarPathResponse getAgentJarPath() {
    return new AgentJarPathResponse(
        extractor.getExtractedPath()
            .map(Path::toAbsolutePath)
            .map(Path::toString)
            .orElse(null)
    );
}

public record AgentJarPathResponse(String path) {}
```

- 응답: `{ "path": "/Users/foo/.apilens/apilens-agent.jar" }` 또는 `{ "path": null }`
- path=null 일 때 wizard 는 placeholder `/path/to/apilens-agent.jar` fallback
- 인증/권한: 기존 setup 4 endpoint 와 동일 (W-S01 backlog 동일 카테고리)

단위 테스트 2건:
- 추출 성공 시 절대경로 반환
- 추출 실패 시 path=null 반환 (200, 404 아님)

### B3. AgentOptionBuilder 갱신 — placeholder 대신 실 경로 받기

위치: `apilens-server/.../setup/AgentOptionBuilder.java` (기존 파일 수정)

기존 `AGENT_JAR_PATH = "/path/to/apilens-agent.jar"` 상수를 제거하고 `build` 시그니처에
`String agentJarPath` 파라미터 추가:

```java
public static String build(AgentOptionRequest req, String agentJarPath) {
    String jarPath = (agentJarPath == null || agentJarPath.isBlank())
        ? "/path/to/apilens-agent.jar"  // fallback placeholder
        : agentJarPath;
    // ... 기존 토큰 5개 조립, -javaagent:{jarPath} 만 변경
}
```

- Breaking: 메서드 시그니처 변경. caller 1건 (server-side helper, docs 용도) 동시 수정
- 기존 단위 테스트 4건 모두 두 번째 인자로 `null` 또는 명시 경로 받도록 갱신
- 신규 단위 테스트 1건: agentJarPath 명시 시 그 경로 사용
- Q-08 cross-stack parity 보존 — FE 도 동일 시그니처/동일 출력

### B4. UPSERT 동시성 정합 확인 (회귀 가드)

본 후속은 ingest 자동 등록 로직 변경 없음. 다만 R9 본체 BE-FAIL-01 회귀 가드 (단위
테스트 `rejectsBlankServerUrl` 가 D-04 반대 방향으로 lock-in 됐던 사건) 와 동일 카테고리
회귀를 차단하기 위해, 본 후속 BE 4 신규 파일에 대해서도 review-arch 자기증명 grep
9축 (R9 의제 6 §B/C 신청 채택 권장) 적용 시도.

### B5. 테스트

- AgentJarExtractor 3건 (B1)
- /v1/setup/agent-jar-path 2건 (B2)
- AgentOptionBuilder 시그니처 변경 후 기존 4건 갱신 + 신규 1건 = 5건 (B3)
- 기존 234 tests 회귀 없음

모두 통과 필요.

---

## UI 작업

### U1. tsconfig.app.json 에 src/test/** exclude (build 깨짐 fix)

위치: `apilens-ui/tsconfig.app.json`

현재 `tsc -b` 가 `src/test/FirstRunGuard.test.tsx` 를 컴파일 대상에 포함 → `global`
식별자 (node global) 미인식 → TS2304 에러 5건 → `npm run build` 실패.

수정:
- `"exclude": ["src/**/*.test.ts", "src/**/*.test.tsx", "src/test/**"]` 추가
- 또는 `tsconfig.json` 의 references 패턴 점검 → vitest 전용 tsconfig (예:
  `tsconfig.vitest.json`) 분리하고 거기에 `"types": ["vitest/globals", "node"]`
- 선택은 architect 위임. 단순한 쪽 (exclude 추가) 권장

검증:
- `npm run build` → tsc -b 통과 + vite build 통과
- `npm run test` → 기존 67 FE 테스트 모두 PASS 보존 (vitest 가 test 파일 여전히 인식)

### U2. dashboard service 자동 selection

위치: `apilens-ui/src/hooks/useDashboardState.ts` + `apilens-ui/src/pages/Setup.tsx`

경로 A (wizard 완료):
- Setup.tsx `completeMutation.onSuccess` 에서 `nav('/', { search: { service: serviceName } })`
- 기존 `nav('/')` 호출을 service 박은 search 와 함께 호출
- skip 경로 (services=[]) 는 service 박지 않음 (현재 동작 유지)

경로 B (자동 등록, services.length === 1):
- `useDashboardState` 에서 `?service=` 없고 services 목록 1건일 때 자동 선택
- services.length >= 2 일 때는 기존 동작 (수동 선택 강요) — 다중 환경 운영자 의도 보존
- SH-13 보존 (queryKey 에 시간 변수 박지 말 것)
- SH-06 search 보존 (R3 회귀 차단)

검증:
- wizard 완료 → dashboard 진입 시 trace 즉시 보임 (헤더 ServiceSelector 자동 선택)
- 자동 등록 (sample-app 1건만) → dashboard 진입 시 자동 선택
- 2건 이상 시 "상단에서 서비스를 선택하세요" 유지

### U3. Setup.tsx Step 1 — Server URL default 자동 입력

위치: `apilens-ui/src/pages/Setup.tsx` Step 1 입력 default

```typescript
// 기존
const [serverUrl, setServerUrl] = useState('');
// 변경 후
const [serverUrl, setServerUrl] = useState(window.location.origin);
```

- placeholder 는 `http://your-apilens-host:8765` 유지 (값 지웠을 때 표시)
- 안내 메시지 유지 — "운영망에서는 사용자 앱이 접근 가능한 IP/hostname 을 입력해 주세요"
- 사용자가 변경 가능 (운영망 IP 다를 수 있음)
- 단위 테스트 신규 1건: window.location.origin default 박힘 (vitest jsdom)

### U4. Setup.tsx Step 2 — 안내 운영자 어휘 강화

위치: `apilens-ui/src/pages/Setup.tsx` Step 2

```tsx
// 기존
<p className="text-xs text-stone-500">
  사용자 앱을 구분할 이름을 입력해 주세요 (영문/숫자/하이픈/언더스코어)
</p>

// 변경 후
<p className="text-xs text-stone-500">
  ApiLens 가 모니터링할 사용자 앱(서비스/시스템) 의 이름이에요
</p>
<p className="text-xs text-stone-400">
  영문/숫자/하이픈/언더스코어. 예: <code className="font-mono">my-api</code>,{' '}
  <code className="font-mono">order-service</code>, <code className="font-mono">vams</code>
</p>
```

- V-USER-H1 톤 (해요체) 유지
- placeholder `my-api` 유지 (영문 명사형 — V-USER-H1 와 정합)

### U5. Setup.tsx Step 4 — agent-jar-path 받아 JVM 옵션에 박기

위치: `apilens-ui/src/api/setup.ts` (신규 helper) + `apilens-ui/src/hooks/useAgentJarPath.ts` (신규)
+ `apilens-ui/src/lib/agent-option-builder.ts` (수정) + `apilens-ui/src/pages/Setup.tsx` (Step 4 prop 추가)

신규 hook:
```typescript
// hooks/useAgentJarPath.ts
export function useAgentJarPath() {
  return useQuery({
    queryKey: ['setup', 'agent-jar-path'],
    queryFn: ({ signal }) => fetchAgentJarPath(signal),
    staleTime: Infinity,  // server startup 시 한 번 추출, 변경 없음
    retry: 1,
  });
}
```

agent-option-builder.ts 시그니처 변경:
```typescript
export interface AgentOptionInput {
  serviceName: string;
  serverUrl: string;
  captureParams: boolean;
  captureResultSet: boolean;
  agentJarPath: string | null;  // null 이면 placeholder fallback
}

export function buildAgentOption(input: AgentOptionInput): string {
  const jarPath = input.agentJarPath ?? '/path/to/apilens-agent.jar';
  return [
    `-javaagent:${jarPath}`,
    `-Dapilens.service.name=${input.serviceName}`,
    // ... 기존 토큰 4개 유지
  ].join(' ');
}
```

- Q-08 cross-stack parity 보존 — BE AgentOptionBuilder 와 시그니처 동기, golden output 동일
- 기존 단위 테스트 4건 갱신 + 신규 1건 (agentJarPath=null 시 placeholder, 값 있을 때 실제 경로)
- Setup.tsx Step 4 진입 시 useAgentJarPath() 호출 → jvmOption 계산 시 path 주입
- jar 추출 실패 (path=null) 시 Step 4 안내 박스에 작은 경고: "agent jar 자동 추출
  안 됨 — server 재빌드 후 다시 시도해 주세요" (작은 stone-400 텍스트)

### U6. health dot 색 신호등 토큰 신설 + ActiveServices auto-refetch 강화

위치: `apilens-ui/src/index.css` + `apilens-ui/src/lib/colors.ts` + `apilens-ui/src/pages/ActiveServices.tsx`

index.css 신규 토큰 4개:
```css
@theme {
  /* ... 기존 --color-status-* 4토큰 보존 (trace status 전용) */
  --color-health-active:   #22C55E;  /* green-500 */
  --color-health-stale:    #F59E0B;  /* amber-500 */
  --color-health-inactive: #EF4444;  /* red-500 */
  --color-health-never:    #A8A29E;  /* stone-400 (기존 idle 톤 미러) */
}
```

colors.ts 갱신:
```typescript
export const STATUS_HEX_HEALTH: Record<HealthStatus, string> = {
  active:   '#22C55E',  // --color-health-active
  stale:    '#F59E0B',  // --color-health-stale
  inactive: '#EF4444',  // --color-health-inactive
  never:    '#A8A29E',  // --color-health-never
};
```

ActiveServices.tsx query 옵션:
```typescript
const servicesQuery = useQuery({
  queryKey: ['services', 'detailed'],
  queryFn: ({ signal }) => listServicesDetailed(signal),
  staleTime: 0,                        // 변경: 30_000 → 0 (focus refetch 무조건)
  refetchInterval: 30_000,
  refetchOnWindowFocus: true,          // 변경: false → true
  retry: 1,
});
```

검증:
- 다른 탭 갔다 돌아오면 즉시 갱신 (수동 새로고침 불필요)
- 30초 polling 활성 탭에서만 (background 트래픽 절약 — refetchIntervalInBackground default false)
- dot 색이 명확한 신호등 (초록/노랑/빨강/회색) 으로 보임
- SH-18 3중 표현 보존 (색 + 한글 라벨 + aria-label)

### U7. 테스트

- FT 갱신: STATUS_HEX_HEALTH 색 hex 변경에 따른 기존 테스트 1건 갱신 (예상)
- FT 신규: agentJarPath null/값 미러 (FE buildAgentOption 시그니처 변경 1건)
- FT 신규: window.location.origin default 박힘 (Setup.test.tsx 1건)
- FT 신규: dashboard 자동 selection 분기 (Dashboard.test.tsx 또는 useDashboardState.test.ts 1건)
- 기존 67 FE 테스트 회귀 없음

---

## docs/setup.md 갱신 — 약속과 실제 정합 회수

기존 docs/setup.md §2 "agent jar 위치" 항목이 이미 `apilens-server.jar 첫 실행 시
~/.apilens/apilens-agent.jar 자동 추출됨` 으로 약속하고 있음. 본 후속 B1 으로 이
약속이 실제 동작하므로 명문 갱신 없음 (이미 정합).

다만 §3 JVM 옵션 예제는 `-javaagent:apilens-agent.jar` 상대경로로 적혀 있는데, 본
후속에서 wizard 가 절대경로를 박기로 한 결정과 정합 회수:

```
# 변경 전
java \
  -javaagent:apilens-agent.jar \

# 변경 후
java \
  -javaagent:~/.apilens/apilens-agent.jar \
```

(또는 사용자가 jar 를 어디 두든 상관없도록 양쪽 다 예시. wizard 가 박는 경로 한 번
짚어주면 충분)

---

## 검증 (사용자가 수행)

자동:
1. `./gradlew clean test` — BE 234 + 신규 약 10건 모두 통과
2. `cd apilens-ui && npm run build` — **이전 회수 #1 — tsc -b + vite build 모두 통과**
3. `cd apilens-ui && npm run test` — FE 67 + 신규 약 4건 모두 통과

수동 smoke (R9 본체 13건 + 본 후속 6 카테고리):

R9 본체 13건 회귀 없음 우선 확인.

본 후속 추가:
- [ ] **#1**: `npm run build` 에러 없음 (이전 TS2304 5건 사라짐)
- [ ] **#2-경로A**: DB 초기화 → / 진입 → /setup 자동 → wizard 4단계 → 완료 →
      dashboard 진입 시 등록한 service 자동 선택 (헤더 ServiceSelector 비어있지 않고
      trace 즉시 보임)
- [ ] **#2-경로B**: DB 초기화 → setup wizard skip → sample-app 첫 trace → dashboard
      재진입 시 sample-app 자동 선택 (services.length === 1)
- [ ] **#2-다중**: 2개 이상 service 등록 시 "상단에서 서비스를 선택하세요" 유지
      (자동 선택 안 함)
- [ ] **#3**: Step 1 진입 시 입력란에 `http://localhost:8765` (또는 현재 origin) 자동 박힘
- [ ] **#4**: Step 2 안내가 "ApiLens 가 모니터링할 사용자 앱(서비스/시스템) 의 이름이에요"
      + 예시 (my-api / order-service / vams) 노출
- [ ] **#5-동작**: Step 4 JVM 옵션 박스에 절대경로 `/Users/xxx/.apilens/apilens-agent.jar`
      박힘 → 그대로 복사 → 사용자 앱 (sample-app 또는 VAMS) JVM 옵션에 붙여넣기 →
      재기동 → agent 정상 부착 + trace 도달
- [ ] **#5-fallback**: server 가 agent jar 임베드 안 된 상태 (`apilens-server` 만 빌드,
      `apilens-agent:shadowJar` 안 함) → Step 4 박스에 placeholder `/path/to/apilens-agent.jar`
      + 작은 경고 안내 노출
- [ ] **#6-색**: /services 의 dot 이 명확한 초록/노랑/빨강/회색 (갈색 X)
- [ ] **#6-refetch**: /services 열어둔 채 다른 탭 가서 5분 보낸 후 다시 돌아오면
      자동으로 active → stale 전이 (수동 새로고침 없이)

## 주의

- R9 Phase H 본체 5 비협상 (D-01~D-05) + 위임 3 + V-USER-H1·H2 + Q-01~Q-08
  모두 보존. architect 가 재해석 금지
- v0.1.0 release tag 직전 마지막 회수. 미세 디자인은 v0.1.1 backlog
- 본 후속 결정 6건 (위 §사용자 결정 사항) 비협상
- 기존 Phase A-H 코드 가능한 최소 수정
- TypeScript strict, any 금지, console.log 금지
- "수동 smoke 통과 확인됨" 단정 보고 금지 — 사용자 검증 항목 명시
- BE-FAIL-01 회귀 가드 의제 6 (R9 핸드오프 §2 의제 6) 본 후속에 적용 시도 권장:
  - A안: AC-ID ↔ 단위 lock-in 매핑 표 (각 비협상 결정 마다 정/반방향 grep 패턴)
  - B안: Dev anchor 표에 Plan AC verbatim 직접 인용
  - C안: review-arch 자기증명 grep 9축 확장 (잘못된 lock-in 패턴 0 hit 9번째 축)

작업 시작 전 (Backend 작업 시):
- CLAUDE.md
- docs/api.md (기존 endpoint 패턴)
- apilens-server/build.gradle.kts (processResources 패턴 — agent jar 임베드 위치)
- 기존 setup 패키지 4 클래스 (SetupController / SetupService / SetupRepository / AgentOptionBuilder)
- R9 본체 design 산출물 (~/.claude/pmo/ApiLens/R9/2026-05-22_23-15_design_phase-h-*.md)

작업 시작 전 (UI 작업 시):
- 기존 Setup.tsx / ActiveServices.tsx / Dashboard.tsx / useDashboardState.ts
- 기존 lib/colors.ts + index.css @theme 토큰
- 기존 lib/agent-option-builder.ts + test
- R9 본체 UX 산출물 (~/.claude/pmo/ApiLens/R9/2026-05-22_22-30_ux_phase-h-*.md)
