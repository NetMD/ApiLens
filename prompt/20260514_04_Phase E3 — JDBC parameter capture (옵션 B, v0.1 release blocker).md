Phase E3 — JDBC parameter capture (옵션 B, v0.1 release blocker)

## 컨텍스트
CLAUDE.md 를 먼저 읽고 시작. 직전 dogfooding 12라운드 (2026-05-14 01:32 저널) 의
"다음 작업 (미완료)" 섹션에 사용자가 명시한 v0.1 release blocker 작업.

사용자 명시 (저널 line 455 인용): **"B까지 해야 v0.1이 완성"**.
즉 본 phase 는 release 직전 비협상 결정 영역. 범위 축소/이연 금지.

## 배경 (저널 line 457~461 요약 + 보강)

VAMS dogfooding 시점에 노출된 운영자 가치 결손:
- `mapper.findByStatuses("PENDING")` 호출 → MyBatis 내부에서
  `ps.setString(1, "PENDING")` → `ps.executeQuery()` 순서로 진행.
- 현재 agent 의 `JdbcAdvice` 는 `execute*` 호출만 intercept → 사이의 `setString`
  시점에 bound 된 값을 잡지 않음.
- 결과: jdbc.execute 노드의 PAYLOAD IN 본문이 비어 있음. SQL `?` 가 어떤 값으로
  채워졌는지 운영자가 확인 불가.

운영자 가치: SQL 의 `?` 가 무엇으로 채워졌는지 한 화면에 보이는 것. ApiLens 의
"어디서 끊겼나 / 뭐가 흘렀나" 정체성과 직결.

## 핵심 결정 (비협상)

| # | 결정 | 근거 |
|---|------|------|
| D-01 | 본 phase 는 **v0.1 release blocker** — 옵션 B 완료 전 G1 (release polish) 진입 금지 | 사용자 명시 인용 (저널 line 455) |
| D-02 | `JdbcParamCache` = `WeakHashMap<PreparedStatement, Map<Integer, Object>>` — JdbcSqlCache / JdbcResultSetCache 와 **동일 패턴** | 저널 line 446-449 + line 31 (`WeakHashMap stash 패턴`) — GC 안전 + thread-local 없음 + framework 의존성 없음 |
| D-03 | param capture 는 **default ON + kill switch** — `apilens.jdbc.capture-params=true\|false`, 기본 `true`. `execute*` 시점에 attribute/payload 함께 emit | (1) ResultSet capture 와 위험 카테고리 다름 — setXxx 는 인자를 캐시에만 unmodified 저장, driver state 변경 0. (2) 운영자 핵심 가치 (SQL `?` 값) 자체가 이 기능에 박힘 → opt-in 두면 default 운영자 99% 가 가치를 못 봄. (3) PII 는 server-side masking default 룰로 처리. (4) kill switch 는 의심 상황 escape hatch — 즉시 비활성 가능. |
| D-04 | 표준 JDBC API (`setString`/`setInt`/…/`setObject`/`setNull`) 전부 cover. 비표준 driver 확장은 미지원 | 저널 R6 교훈 (line 28, "opt-in 위험 고지는 표준 API 호환성을 면제하지 않는다") — 표준 API 는 default 호환 충족 |
| D-05 | host 앱 영향 0 — advice 본문 전구간 try-catch, capture 실패 시 silent drop | CLAUDE.md "Agent 자체 장애가 호스트 앱에 영향 0" 원칙 + 저널 line 25 |

## 작업 1 — `JdbcParamCache` 신규 (apilens-agent)

`apilens-agent/src/main/java/io/apilens/agent/instrument/jdbc/JdbcParamCache.java`.

- 시그니처: `WeakHashMap<PreparedStatement, List<Map<Integer, Object>>>` —
  **List 의 마지막 원소가 현재 진행 중인 batch slot**. `addBatch` 시점에 현재
  Map 을 List 에 push (그대로 보존) + 새 Map 을 끝에 append → 다음 set 호출은
  새 Map 에 누적. `execute*` 시점에 List 전체가 payload 원본.
- 비 batch 호출 (`execute` / `executeQuery` / `executeUpdate` 직접 호출) 은 List
  size = 1 — 마지막 단일 Map 만 들어 있음. UI 표시는 size 분기로 처리 (size==1 →
  단일 객체, size>1 → 배열).
- API:
  - `put(PreparedStatement ps, int idx, Object value)` — 마지막 slot Map 에 누적
    (slot 없으면 자동 생성).
  - `commitBatchSlot(PreparedStatement ps)` — 현재 Map 보존 후 새 Map append
    (`addBatch` 시점 호출).
  - `List<Map<Integer, Object>> get(PreparedStatement ps)` — `execute*` 시점
    lookup. null 가능, 빈 List 가능.
  - `clear(PreparedStatement ps)` — `execute*` 종료 후 정리 (재사용 statement 의
    다음 호출 사이클 격리).
- `Collections.synchronizedMap(new WeakHashMap<>())` 동일. inner List/Map 도
  `synchronizedList` / `synchronizedMap` 으로 thread-safety 확보 (같은
  PreparedStatement 가 멀티스레드에서 동시 사용은 드물지만 안전망).
- DEBUG flag 로 put/commitBatchSlot/get 추적 (JdbcSqlCache 패턴).

## 작업 2 — `PreparedStatementParamAdvice` 신규

`apilens-agent/src/main/java/io/apilens/agent/instrument/advice/PreparedStatementParamAdvice.java`.

- `@Advice.OnMethodEnter(suppress = Throwable.class)` 형태. 또는 `OnMethodExit`
  (binding 성공 후 캐시 — set 호출 자체가 예외 던지면 캐시 안 함). exit 권장.
- `@Advice.This Object self` + `@Advice.AllArguments Object[] args` + `@Advice.Origin("#m") String methodName`.
- 본문 — setXxx 분기:
  ```java
  if (!InstrumentationInstaller.CAPTURE_PARAMS) return;
  if (self instanceof PreparedStatement ps && args != null && args.length >= 1) {
      try {
          if (args[0] instanceof Integer idx) {
              Object value = (args.length >= 2) ? args[1] : null;
              JdbcParamCache.put(ps, idx, value);
          }
          // setNull(int, int [, String]) — value=null, sqlType 인자는 무시
      } catch (Throwable ignore) {
          // best-effort, host 앱 영향 0
      }
  }
  ```
- 별도 `PreparedStatementAddBatchAdvice` (신규) — `addBatch()` no-arg 인터셉트 →
  `JdbcParamCache.commitBatchSlot(ps)` 호출. 현재 누적 Map 보존 + 새 slot 시작.
  본문은 try-catch 가드 + `InstrumentationInstaller.CAPTURE_PARAMS` 분기.
- ByteBuddy advice 함정 (저널 line 23, 24) 주의:
  - `@Advice.Origin` 은 String 만 사용 (Method 객체 금지).
  - advice 클래스 안 private static helper 금지 — 본문 인라인 또는
    `AdviceSupport` static 호출만.
  - 전구간 try-catch + silent drop (저널 line 25).

### 추가 — `AgentConfig` + `InstrumentationInstaller` 플래그

- `AgentConfig` record 에 `captureParams` 필드 신규 — default `true`. 시스템
  프로퍼티 `apilens.jdbc.capture-params` 가 명시 `false` 일 때만 비활성.
- `InstrumentationInstaller.CAPTURE_PARAMS` static 필드 (기존
  `CAPTURE_RESULT_SET` 패턴 그대로). advice 본문에서 `if (!CAPTURE_PARAMS) return`
  분기로 빠르게 회피 (kill switch 작동 시 advice 진입 비용 거의 0).
- `docs/agent-options.md` 에 `apilens.jdbc.capture-params` 항목 추가 — default
  값 + 비활성 절차 + R6 교훈 인용 ("표준 API 호환성은 default 충족, 의심 시
  즉시 false 로 재기동").

## 작업 3 — `SpringMatchers` 매처 2종 신규

`SpringMatchers.java` 에 신규 matcher 2종:

```java
public static ElementMatcher.Junction<MethodDescription> preparedStatementSetParamMethods() {
    return ElementMatchers.nameStartsWith("set")
            .and(ElementMatchers.takesArgument(0, int.class))   // parameterIndex
            .and(ElementMatchers.isPublic())
            .and(ElementMatchers.not(ElementMatchers.isStatic()))
            // Statement 의 cursor/timeout/fetch 설정 메서드 명시 제외
            .and(ElementMatchers.not(ElementMatchers.named("setFetchDirection")))
            .and(ElementMatchers.not(ElementMatchers.named("setFetchSize")))
            .and(ElementMatchers.not(ElementMatchers.named("setMaxRows")))
            .and(ElementMatchers.not(ElementMatchers.named("setMaxFieldSize")))
            .and(ElementMatchers.not(ElementMatchers.named("setQueryTimeout")))
            .and(ElementMatchers.not(ElementMatchers.named("setEscapeProcessing")))
            .and(ElementMatchers.not(ElementMatchers.named("setCursorName")))
            .and(ElementMatchers.not(ElementMatchers.named("setPoolable")));
}

public static ElementMatcher.Junction<MethodDescription> preparedStatementAddBatchMethod() {
    return ElementMatchers.named("addBatch")
            .and(ElementMatchers.takesArguments(0))
            .and(ElementMatchers.isPublic())
            .and(ElementMatchers.not(ElementMatchers.isStatic()));
}
```

`PreparedStatement` 의 모든 표준 `setXxx(int parameterIndex, …)` overload 를 한
번에 cover. `setNull(int, int)`, `setNull(int, int, String)`, `setString(int, String)`,
`setObject(int, Object)`, `setObject(int, Object, int)`, `setObject(int, Object, int, int)`,
`setBlob(int, Blob)`, `setBlob(int, InputStream)`, `setBlob(int, InputStream, long)`,
… 까지 자연 cover.

회귀 가드 단위 테스트 필수:
- `setString` / `setInt` / `setObject` / `setNull` / `setBlob` / `setLong` /
  `setDate` / `setTimestamp` / `setBytes` / `setBoolean` / `setShort` / `setByte` /
  `setDouble` / `setFloat` / `setBigDecimal` / `setURL` / `setArray` / `setRowId` /
  `setNString` / `setNClob` / `setSQLXML` / `setClob` 22 종 모두 match 단언.
- `getString` 같은 setter 가 아닌 메서드 match=0 단언.
- `setFetchDirection(int)` / `setFetchSize(int)` 같이 parameterIndex 가 아닌
  setXxx 도 매처에 잡힘 — advice 본문에서 `PreparedStatement` 인지 + parameterIndex
  의미 확인이 필요. 또는 matcher 에서 명시 제외.

**주의**: `setFetchDirection`, `setFetchSize`, `setMaxRows`, `setMaxFieldSize`,
`setQueryTimeout`, `setEscapeProcessing`, `setCursorName`, `setPoolable` 같은
PreparedStatement / Statement 설정 메서드도 매처에 걸릴 수 있음. matcher 에서
`.and(not(named("setFetchDirection")).and(not(named("setFetchSize")))…)` 명시
제외 또는 advice 본문에서 parameterIndex 양의 정수 단언으로 필터.

매처에서 명시 제외했으므로 advice 진입 비용 0. 단언 테스트로 회귀 차단.

## 작업 4 — `InstrumentationInstaller` 등록

기존 `prepareStatement` (JdbcConnectionAdvice) / `execute*` (JdbcAdvice) /
`getResultSet` (JdbcGetResultSetAdvice) 등록 패턴 따라 transform 2종 신규 등록:

```java
// setXxx — parameter binding capture
.type(SpringMatchers.implementsPreparedStatement())
.transform((b, td, cl, jm, pd) -> b.visit(
    Advice.to(PreparedStatementParamAdvice.class)
        .on(SpringMatchers.preparedStatementSetParamMethods())))

// addBatch — batch slot commit
.type(SpringMatchers.implementsPreparedStatement())
.transform((b, td, cl, jm, pd) -> b.visit(
    Advice.to(PreparedStatementAddBatchAdvice.class)
        .on(SpringMatchers.preparedStatementAddBatchMethod())))
```

## 작업 5 — `JdbcAdvice.exit` 에서 payload_in 본문 직렬화 (batch 인지)

`JdbcAdvice.exit` 의 attributes 채우는 try-block 에:

```java
if (self instanceof PreparedStatement ps && InstrumentationInstaller.CAPTURE_PARAMS) {
    List<Map<Integer, Object>> slots = JdbcParamCache.get(ps);
    if (slots != null && !slots.isEmpty()) {
        // size==1 → 단일 객체 {"1":"PENDING","2":5}
        // size>1  → 배열 [{"1":"a","2":1},{"1":"b","2":2}]  (executeBatch)
        Object body;
        if (slots.size() == 1) {
            body = normalize(slots.get(0));   // Map<Integer,?> → LinkedHashMap<String,?>, key ASC sort
            attributes.put("db.params_count", slots.get(0).size());
        } else {
            List<Map<String, Object>> normalized = new ArrayList<>(slots.size());
            for (Map<Integer, Object> slot : slots) normalized.add(normalize(slot));
            body = normalized;
            attributes.put("db.params_count", slots.get(slots.size() - 1).size());
            attributes.put("db.batch_size", slots.size());
        }
        // serialize + masking + truncate via AdviceSupport.payloadsOf(Kind.IN, body)
        // payloads 누적 (기존 ResultSet capture payload 와 공존)
    }
    JdbcParamCache.clear(ps);  // 재사용 statement 다음 사이클 격리
}
```

- payload kind 는 `Payload.Kind.IN` (또는 기존 PAYLOAD IN/OUT 코드 컨벤션 따름).
- `AdviceSupport` 의 직렬화 + masking + truncation 헬퍼 재사용 (저널 line 25 —
  agent 안전망).
- batch 분기 attribute: `db.batch_size` (slots.size() > 1 일 때만). UI 의
  PayloadView 는 JSON 그대로 표시하므로 본문이 배열인 사실만으로 운영자에게
  batch 임이 명시됨 (UI 변경 0).
- `executeBatch` sequence: `(set…, addBatch, set…, addBatch, …, executeBatch)`.
  `addBatch` 시점에 `commitBatchSlot(ps)` 가 현재 Map 보존 + 새 slot 시작 →
  `executeBatch` 시점에 slots List 가 (commit 횟수) + (마지막 진행 slot) 만큼
  존재. 마지막 slot 이 빈 Map 인 경우 (addBatch 후 set 호출 없이 바로 executeBatch)
  는 직렬화 전 제거.
- 비 batch 호출 (`execute` / `executeQuery` / `executeUpdate` 직접) 은 slots.size() == 1
  → 기존 형식 그대로.

## 작업 6 — 단위 테스트

`apilens-agent/src/test/java/io/apilens/agent/instrument/...`:

1. `JdbcParamCacheTest` — put/commitBatchSlot/get/clear, 멀티스레드 안전
   (synchronizedMap + synchronizedList + synchronizedMap), batch slot 누적
   순서 보존, 빈 slot 처리.
2. `SpringMatchersTest` 보강:
   - `preparedStatementSetParamMethods()` 가 22종 setter 모두 match.
   - `setFetchDirection` / `setFetchSize` / `setMaxRows` / `setQueryTimeout` /
     `setEscapeProcessing` / `setCursorName` / `setPoolable` / `setMaxFieldSize`
     8종 NOT match (명시 제외 회귀 가드).
   - `getString` / `executeQuery` / `prepareStatement` NOT match.
   - `preparedStatementAddBatchMethod()` 가 `addBatch()` no-arg 만 match,
     `addBatch(String)` (Statement override) NOT match.
3. `PreparedStatementParamAdviceTest` / `PreparedStatementAddBatchAdviceTest` —
   advice 본문을 직접 호출하면 inline 우회되어 정상처럼 보이는 ByteBuddy 함정
   (저널 line 25, F1차 후속 인사이트) 주의. 대신 `JdbcParamCache` 효과만 단위
   검증 + 통합은 sample-app smoke 로 분리.
4. `JdbcAdviceTest` 보강 — 단일 호출 / batch 호출 payload 본문 형태 (객체 vs 배열),
   idx ASC 정렬, `db.batch_size` attribute, masking 적용, 빈 slots 분기,
   `CAPTURE_PARAMS=false` kill switch 시 payload 미생성.

저널 R6 교훈 (line 28): **표준 API 호환성은 default 충족** — `setNull(int, int)`
처럼 인자 2 개 setter 에서 host 앱 throw 가 절대 발생 안 함을 단언.

## 작업 7 — sample-app smoke 검증 + 사용자 dogfooding

`examples/sample-app/README.md` 의 시나리오 6 신규 (또는 시나리오 1 확장):

```
[ ] 시나리오 6: PreparedStatement parameter binding 표시 (단일 + batch)
  - 6-a 단일: POST /users {"name":"Alice","email":"a@a"} → jdbc.execute PAYLOAD IN
            에 `{"1":"Alice","2":"a@a"}` 노출
  - 6-b batch: POST /users/bulk (또는 동등 endpoint) 로 N건 → PAYLOAD IN 본문이
            배열 `[{...},{...}]` 형태 + attribute `db.batch_size=N` 노출
  - 6-c kill switch: `-Dapilens.jdbc.capture-params=false` 재기동 → PAYLOAD IN
            본문 미생성 (default ON 안전망 확인)
  - 결과 기록은 사용자가 직접 (NFR-06 자동화 영역 아님)
```

sample-app 에 batch 시나리오 endpoint 가 없으면 6-b 검증을 위해 `POST /users/bulk`
같은 간단한 batch insert 핸들러 + JdbcTemplate.batchUpdate / Spring Data
saveAll 1개 추가 (UserController + UserService 작은 변경).

VAMS 재부착 검증은 사용자 직접:
```
[ ] VAMS dashboardMapper.selectRecentJobs 호출 시 jdbc.execute PAYLOAD IN 본문에
    바인딩 값 노출 (예: status 컬럼 "PENDING")
[ ] batch insert/update 시 본문이 배열 + db.batch_size 노출
[ ] LocalDateTime / BigDecimal / null 값도 직렬화 깨지지 않음
[ ] password / token 같은 마스킹 룰 매칭 값이 server-side masking 으로 가려짐
[ ] 호스트 앱 throw 0건 (R6 회귀 가드)
[ ] `-Dapilens.jdbc.capture-params=false` 시 PAYLOAD IN 본문 미생성 (kill switch)
```

## 검증 (자동 / 수동 분리)

자동 (Claude 가 단정 보고 가능):
1. `./gradlew :apilens-agent:test` — 신규 테스트 포함 전부 PASS.
2. `./gradlew :apilens-agent:shadowJar` — relocate clean (relocate leak 0 확인 명령).
3. `./gradlew :apilens-server:test` — 회귀 0.
4. `./gradlew clean test` — 전체 회귀 0.
5. `cd apilens-ui && npm run build` — 회귀 0 (본 phase 는 UI 변경 0 이지만 회귀 확인).

수동 (사용자 직접 — Claude 단정 보고 금지, NFR-06):
- 시나리오 6 (sample-app POST /users PAYLOAD IN 시각 확인).
- VAMS 재부착 후 dashboardMapper 호출 PAYLOAD IN 본문.
- 호스트 앱 throw 0건 + log noise 0건.

## 주의 (CLAUDE.md / 저널 인용)

- agent advice 본문은 try-catch 가드 + silent drop. host 앱 영향 0 (CLAUDE.md
  핵심 + 저널 line 25).
- `@Advice.Origin` String 만, advice 클래스 안 private static helper 금지 (저널
  line 23, 24).
- WeakHashMap stash 패턴 일관 적용 — `JdbcSqlCache` / `JdbcResultSetCache` 와
  동일 API/주석 톤 (저널 line 31, line 446-449).
- opt-in 옵션 고지 ≠ 표준 API 호환성 면제. setNull(int, int) 등 호스트 앱 throw
  단언 (저널 line 28, R6 교훈).
- ResponseEntity / Mono / Optional 등 framework wrapping 풀기 (저널 line 29) —
  본 phase 범위 외 (param 캡처는 PreparedStatement 표준 API 만).
- 사용자 검증 영역에 "확인됨" 단정 보고 금지 (NFR-06, 저널 line 66, 150).
- 산출물 텍스트에 "박제" 금지, 한국어 존댓말 (CLAUDE.md 최상단, 저널 line 70-71).
- v0.2 후보로 명시 보관 (이번 phase 외):
  - `JdbcParamCache` 패턴이 잡은 batch 별 분리 표시.
  - `useSearchPreservingNavigate()` (저널 line 59).
  - `Mono<T>` / `CompletableFuture<T>` reflection unwrap (저널 line 29).
- 사용자 명시 비협상 결정 (저널 line 455 "B까지 해야 v0.1이 완성") — PM §0 /
  planner §0 / architect §0 / dev anchor 4 위치 동일 명문화 (next-project-checklist
  의 사용자 명시 비협상 영역 단방향 봉인 패턴).

작업 시작 전 점검:
- 현재 `JdbcAdvice.exit` 의 payload 누적 흐름 (ResultSet capture 가 추가한 payload
  와 공존).
- `AdviceSupport.payloadsOf` (또는 동등 헬퍼) 의 시그니처.
- `SpringMatchers.preparedStatementExecuteMethods()` 와 신규 `preparedStatementSetParamMethods()`
  의 NOT-overlap.
- `InstrumentationInstaller` 의 transform 등록 순서 (기존 5종 → 6종).
