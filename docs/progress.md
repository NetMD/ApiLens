# ApiLens 진행 상태

각 phase의 완료 정의(Definition of Done)와 검증 방법을 기록.

> 수동 검증 항목은 **사용자가** 결과를 적어 넣음. Claude Code가 "통과 확인됨"
> 자동 기록 금지.

## Phase A — 마스킹 엔진 (`apilens-common`)

* `MaskingEngine`, `MaskingRule`, `MaskingRuleType`, `MaskingStrategy`
* JSON tree walk + non-JSON regex fallback
* 4개 strategy: FULL / PARTIAL / HASH / LENGTH_ONLY
* **검증**: `./gradlew :apilens-common:test` → **14/14 통과**

## Phase B — Ingest endpoint (`apilens-server`)

* `POST /v1/spans` — IngestController + IngestService + JdbcTemplate 영속화
* server-side masking 적용 후 저장
* `INSERT OR REPLACE` 로 spans/traces 멱등 처리
* **검증**: `./gradlew :apilens-server:test` → ingest 9개 통과

## Phase C — Query endpoints (`apilens-server`)

* `GET /v1/traces`, `GET /v1/traces/{id}`, `GET /v1/traces/{id}/spans/{spanId}/payloads`, `GET /v1/services`
* keyset cursor 페이지네이션 (offset 안 씀)
* attributes_json server-side parse
* response gzip compression 활성
* **검증**: `./gradlew :apilens-server:test` → query 14개 통과

## Phase D — Agent transport (`apilens-agent`)

* `AgentMain` premain + `AgentConfig` 9개 옵션 + `AgentLogger`
* `SpanQueue` (LinkedBlockingQueue + offer-drop) + `SpanSender` (daemon worker, 1회 재시도)
* `HttpTransport` (JDK `HttpClient`)
* hello span enqueue로 channel 검증
* shadow jar — Premain-Class manifest, ByteBuddy/Jackson relocate
* **검증**:
  * `./gradlew :apilens-agent:test` → 25개 통과
  * `./gradlew :apilens-agent:shadowJar` → manifest + relocate clean (확인 명령은 README 참조)

## Phase D 후속 — Jackson 캡슐화 + shadow jar 의존성 충돌 해결

* agent의 public API에서 `ObjectMapper` 노출 제거 — 외부 caller가 relocated package에 의존 안 함
* `apilens-server`의 `tasks.jar { enabled = true; archiveClassifier = "plain" }` — cross-module project() 의존 가능하도록
* 통합 테스트를 server → agent 모듈로 이동 (shadow jar consumer로 두면 stale class 흡수 함정)
* CLAUDE.md "Build 설정 lessons" 기록

## Phase E1 — ByteBuddy advice (단위 검증까지)

**Foundation (3 + 17 tests)**
* `instrument/context/TraceContext` — ThreadLocal span 스택, parent 자동 연결
* `instrument/capture/PayloadTruncator` — UTF-8 boundary-safe
* `instrument/matcher/SpringMatchers` — Spring 의존 없는 string 기반 annotation/super 매처

**Advice 5종 + helper**
* `instrument/advice/ControllerAdvice` — SERVER root span, args/return 직렬화 → payload, HTTP attrs reflection
* `instrument/advice/ServiceAdvice` — INTERNAL span
* `instrument/advice/RepositoryAdvice` — INTERNAL span (JpaRepository/CrudRepository 매처 포함)
* `instrument/advice/JdbcConnectionAdvice` — `Connection.prepareStatement(sql)` 결과 → `JdbcSqlCache` stash
* `instrument/advice/JdbcAdvice` — DB span, `db.statement` lookup, `db.rows_affected`
* `instrument/AdviceSupport` — try/catch(Throwable) 전구간, masking + truncation
* `instrument/jdbc/JdbcSqlCache` — WeakHashMap

**Wire-up**
* `instrument/InstrumentationInstaller` — AgentBuilder 5종 transform, 자기-instrument 회피, RETRANSFORMATION
* `AgentMain.premain` 에서 `InstrumentationInstaller.install(...)` 호출

**검증 — 단위만**
* `./gradlew :apilens-agent:test` → **42개 통과** (Phase D 25 + Phase E1 17)
* shadow jar 빌드 통과 + 5개 advice class 임베드됨 + relocate leak 0
* **다음 단계**: 실제 Spring Boot 앱에 -javaagent로 붙였을 때 동작 — `examples/sample-app/README.md` 의 체크리스트 (수동)

## Phase E1 후속 — sample-app + 검증 체크리스트

* `examples/sample-app/` — Spring Boot 3.4 + JPA + H2 미니 앱 (User REST CRUD)
* `examples/sample-app/build.gradle.kts` — `bootRun` 이 agent jar 자동 attach (`-Pno-agent` 로 끔)
* `examples/sample-app/README.md` — 5개 시나리오 체크리스트
* **검증**: 사용자가 README 따라 수동으로 실행. 결과 보고는 사용자 → Claude.

```
[ ] 시나리오 1: POST /users → 4-span trace
[ ] 시나리오 2: 평문 password / SSN 마스킹
[ ] 시나리오 3: GET /users/{id} 조회 trace
[ ] 시나리오 4: 호스트 앱 안전성 (agent 옵션 부재 / server 다운 시)
[ ] 시나리오 5: 부하 테스트 (선택)
```

## 누적 자동 검증

| 시점 | total tests | failures |
|---|---|---|
| Phase A 후 | 14 | 0 |
| Phase B 후 | 23 | 0 |
| Phase C 후 | 37 | 0 |
| Phase D 후 | 64 | 0 |
| Phase E1 후 | **79** | 0 |
| Phase H 후속 #2 (R11, 2026-05-26) | **247** | 0 (R11 회고 기준 — agent 150 + server/common) |

이후 2026-06-03 NAS 원격 dogfooding 에서 회귀 테스트 +5 추가 (CapturedResultSet getLong NULL 3 + pass-through tee 2).
정확한 누적 총계는 `./gradlew clean build` 재실행 시 확정 (사용자 검증).

## 이후 진행 (Phase E2 ~ G2)

> 아래는 E1 이후 실제로 진행된 phase 요약. 상세는 `prompt/` 의 각 phase md + `~/.claude/pmo/ApiLens/` 참조.

* **Phase E2** — sample-app smoke 에서 발견된 cleanup 처리 (HTTP 404 status ERROR 분기 등)
* **Phase E3** — JDBC PreparedStatement parameter capture (opt-in, v0.1 release blocker 해소). `JdbcParamCache` + setter blacklist 매처 + ResultSet capture (`CapturedResultSet`)
* **Phase F1** — UI 프로젝트 셋업 + 대시보드 (`apilens-ui/`, Vite + React 18 + TS + Tailwind). 응답시간 산점도 + 서비스 필터
* **Phase F2** — Trace 상세 화면 (노드 그래프, React Flow mind-map · 수평 시간 흐름 · edge hover payload popup)
* **Phase G1** — Release polish (1): docs + Apache 2.0 라이선스 헤더 + CI
* **Phase H** — Setup wizard 4단계 + Active services + Per-service health dot (도입 친화성)
* **Phase H 후속 / 후속 #2 (R10 / R11)** — 로컬 dogfooding 회수 6건 + agent serializer P0 file truncate fix (multi-layer 3 layer defense + 회귀 테스트 5건)
* **inter-pipeline (5/26 vams · 6/3 NAS)** — 외부/원격 dogfooding. 6/3 NAS 원격에서 신규 P0 2건 (getLong NULL ClassCast / capture-result-set truncate+누수 → tee 재설계) + 출시차단 1건 (wizard `-D` 키 불일치) fix · 재배포 검증

## 현재 상태 — Phase G2 (v0.1.0 release tag) 진행 중

* v0.1 기능 11항목 코드 구현 완료. `build.gradle.kts` 버전 `0.1.0` 으로 unmark, CHANGELOG `[0.1.0]` (Added / Changed / Fixed / Security / Known limitations) 작성 완료
* **남은 release 블로커**:
  * [ ] **A1 — V-USER 10화면 시각 sign-off** (사용자 수행. 자동 "통과 확인됨" 기록 금지)
  * [ ] **A8 — `JAVA_HOME=21 ./gradlew clean build` 전체 PASS + shadowJar relocate javap 재검증** (사용자 환경 검증)
  * [ ] **A7 — git init + `git tag -a v0.1.0`** (A1 sign-off 통과 후)
* 상세 실행 스펙: `prompt/20260603_01_Phase G2 — v0.1.0 Release tag (...).md`
