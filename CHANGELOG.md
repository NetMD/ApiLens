# Changelog

All notable changes to ApiLens will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

다음 release 후보가 누적되는 영역입니다.

### Added

- (candidate) **API 문서 자동화 (OpenAPI / Swagger UI)** — 손으로 쓰는 마크다운 API 문서가 코드와 어긋나는 문제를 근본 해소하기 위해 springdoc-openapi 로 컨트롤러에서 스펙을 생성하고 `/swagger-ui` 인터랙티브 문서를 단일 jar 에 임베드 검토 (server 전용 의존성 — agent 무관). 운영 서사(503 부작용·마스킹 의미 등)는 별도 산문 문서로 병행.
- (candidate) `JdbcParamSerializer` 의 `java.time` 타입 확장 (LocalDate / LocalDateTime / LocalTime / ZonedDateTime).

### Changed

- (candidate) **거대 trace 의 ingest 잠금 독점 완화** — 한 trace 가 수만 개의 span 을 만들면 단일 트랜잭션의 INSERT 가 DB write 잠금을 오래 점유해 동시 적재가 `SQLITE_BUSY` 로 유실되던 문제를, span 배치를 청크로 나눠 커밋하는 방식으로 완화 검토. 근본 해소는 agent 계측량 제어(다음 agent 라운드).

### Security

- (candidate) **공유 마스킹 엔진 ReDoS 근본 차단** — 신규 룰 저장뿐 아니라 이미 저장된 룰·ingest 마스킹 경로까지 linear-time 매칭(RE2/j 류)으로 보호. 엔진 변경이 agent 재빌드를 동반해 다음 agent 라운드로 묶음.
- (candidate) JDBC PreparedStatement 파라미터의 이름 기반 마스킹 보강 — 현재 PAYLOAD IN 키가 parameterIndex 라 이름 기반 룰이 매칭되지 않는 한계 개선.

## [0.3.1] - 2026-06-23

> 유지보수 모드(수신 일시정지). 디스크 최적화(VACUUM)·정리를 잠금 경합 없이 끝내도록 **collector 의 적재만 잠시 멈추는** 기능입니다. server 와 UI 만 바뀌고 **agent·common 모듈은 변경 없음** (v0.1~v0.3.0 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가 — 상태는 in-memory).

### Added

- **유지보수 모드 (수신 일시정지)** — 설정 페이지 "데이터 관리" 섹션의 [수신 일시정지]/[수신 재개] 버튼으로 collector 의 적재를 잠시 끊습니다. 운영 중인 서비스는 멈추지 않고 collector 쪽 수신만 일시 차단해, 디스크 최적화(VACUUM)·정리(purge/cleanup)를 SQLite write 잠금 경합 없이 안전하게 수행합니다.
- **수신 일시정지 API 3종** (`io.apilens.server.retention.MaintenanceController`) — `GET /v1/maintenance/status`(상태 조회) · `POST /v1/maintenance/pause`(일시정지) · `POST /v1/maintenance/resume`(재개). 모두 `MaintenanceStatusResponse { paused, pausedAt }` 를 echo 하고 **멱등**합니다. `/v1/**` default-deny 로 보호됩니다 (v0.3.0 의 API Key 인증 자동 계승 — 키 설정 시 토큰 필수).
- **max-pause cap (30분 자동 재개)** — 운영자가 일시정지를 켜둔 채 잊어도 30분 뒤 server 가 자동으로 수신을 재개합니다 (안전장치). 별도 스케줄러 없이 요청 시점 lazy 판정으로 동작합니다.
- **유지보수 모드 UI** — 화면 상단 고정 배너("수신 일시정지 중 — 이 동안 들어온 데이터는 저장되지 않습니다") · Services 화면 배지 · 일시정지 중 대시보드 실시간 갱신 자동 중단. 정리(최적화·삭제) 전 수신 일시정지를 권장하는 안내 텍스트 (강제 아님).

### Changed

- **`POST /v1/spans` ingest 응답** — 유지보수 모드(수신 일시정지) 중에는 `503 Service Unavailable` + `Retry-After: 60` 헤더로 응답하고, validate/mask/DB write 를 전부 건너뜁니다. 일시정지 중 들어온 trace 는 **저장되지 않습니다** (agent 는 해당 batch 를 drop). 정상 수신 시 응답은 기존과 동일(`202` + `{ accepted, traces }`).

### Notes

- **상태는 in-memory 입니다** — server 를 재시작하면 항상 수신 중(`paused=false`)으로 복귀합니다 (DB 에 저장하지 않음, 스키마 변경 0).
- **agent·common 모듈 무변경** — agent jar 재빌드가 필요 없습니다. jar 만 0.3.1 로 교체하고 재기동하면 됩니다.
- **업그레이드/롤백** — 0.3.0 jar 와 DB 가 그대로 호환됩니다 (스키마 미변경). 0.3.0 으로 롤백해도 데이터 영향 0.

## [0.3.0] - 2026-06-22

> v0.3 첫 릴리스. **API Key 인증 + 마스킹 정규식 ReDoS 가드 + 온라인 디스크 최적화(VACUUM)**. server 와 UI 만 바뀌고 **agent 모듈은 변경 없음** (v0.1/v0.2 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가).

### BREAKING CHANGES

- **관리/조회 API 에 선택적 API Key 인증이 신설됐습니다.** `APILENS_AUTH_API_KEY` 환경변수(또는 `-Dapilens.auth.api-key` 시스템 프로퍼티)로 토큰을 설정하면, 그 시점부터 settings / masking-rules / maintenance / traces / services 등 `/v1/**` 관리·조회 API 는 `Authorization: Bearer <토큰>` 헤더를 요구합니다. 헤더가 없거나 틀리면 401 `{"error":"unauthorized"}` 를 반환합니다.
  - **업그레이드 경로**: jar 만 0.3.0 으로 교체하고 재기동하면 됩니다 (스키마·agent 무변경). **토큰을 설정하지 않으면 0.2.x 와 똑같이 무인증으로 동작**하고 기동 시 경고 로그를 1회 남깁니다 — 기존 환경이 그대로 깨지지 않으니, 준비되면 토큰을 켜세요.
  - **롤백**: 0.2.x jar 로 되돌려도 DB 가 그대로 호환됩니다 (이 릴리스는 스키마를 바꾸지 않습니다).
  - **agent(ingest) 영향 0**: agent 가 trace 를 보내는 `POST /v1/spans` 는 **인증에서 제외**됩니다. 토큰을 켜도 NAS 등 원격 agent 의 적재는 끊기지 않습니다 (신뢰 네트워크 전제).
  - **사용 예시**:
    ```bash
    # 토큰을 켜서 기동 (관리·조회 API 보호)
    APILENS_AUTH_API_KEY="your-secret-token" java -jar apilens-server-0.3.0.jar

    # API 호출 시 헤더로 토큰 전달
    curl -H "Authorization: Bearer your-secret-token" http://localhost:8765/v1/traces

    # 토큰을 빼면 0.2.x 와 동일 (무인증 + 기동 경고 1회)
    java -jar apilens-server-0.3.0.jar
    ```
    토큰 생성·UI 입력·Docker·키 교체 등 자세한 사용법은 [README 의 '인증 | Authentication' 절](./README.md#인증--authentication-v03)을 참조하세요.

### Added

- **API Key 인증** (`io.apilens.server.auth`) — Spring Security 의존성을 추가하지 않은 경량 서블릿 필터(`OncePerRequestFilter`)로 단일 토큰을 검증합니다. 토큰 비교는 타이밍 공격을 막기 위해 상수 시간(`MessageDigest.isEqual`)으로 수행합니다. **인증 면제(무토큰 허용) 경로**: setup wizard(`/v1/setup/**`) · agent 적재(`POST /v1/spans`) · 헬스체크(`/actuator/health`) · 정적 자산과 SPA 화면(`/`, `/index.html`, `/assets/**`). 그 외 모든 `/v1/**` 는 토큰이 설정돼 있으면 보호됩니다. 토큰은 server 기동 옵션으로만 두며 **DB 에 저장하지 않습니다** (키 교체 = server 재시작).
- **인증 토큰 입력 UI** — 설정 페이지에서 토큰을 입력하면 브라우저 세션(`sessionStorage`)에 보관하고 이후 모든 요청에 헤더로 붙입니다. 토큰이 없거나 틀려 401 이 나면 자동 새로고침(polling)을 멈추고 토큰 입력을 유도합니다 (잘못된 토큰으로 401 이 반복되며 화면이 먹통이 되는 것을 막습니다). 토큰 저장은 브라우저 보관만 하고 어떤 보호 API 도 호출하지 않습니다 (토큰 입력 화면 자체가 인증에 막혀 영구 잠기는 일이 없게).
- **마스킹 정규식 ReDoS 가드** — 사용자가 마스킹 룰(custom 정규식)을 추가/수정할 때, 저장 직전에 제한 시간(100ms) 안에 시험 매칭이 끝나는지 검사합니다. catastrophic backtracking(입력 길이에 지수적으로 느려지는 정규식)이 의심되면 저장을 거부하고 400 `pattern is too complex` 를 반환합니다. 별도 스레드를 띄우지 않고 매칭 도중 경과 시간을 검사해 스스로 빠져나오는 방식이라 스레드 누수가 없습니다.
- **온라인 디스크 최적화(VACUUM)** (`POST /v1/maintenance/optimize`) — 설정 페이지 "데이터 관리" 에 "최적화" 버튼이 추가됐습니다. 데이터를 지우지 않고 SQLite `VACUUM` 으로 파일 내부의 빈 공간(free page)을 회수해 DB 파일 크기를 줄입니다. `cleanup`/`purge` 가 행을 지워도 파일 크기가 잘 안 줄던 한계(삭제된 빈 page 가 파일에 남음)를 보완합니다. **VACUUM 은 운영 DB 파일을 삭제/재생성하지 않습니다** (같은 파일을 내부 재구성). 응답 `{ deletedTraces: 0, freedBytes, dbSizeBytes, busy }`.

### Changed

- **버전 0.2.1 → 0.3.0** (server jar / UI `package.json` / UI 표시 라벨).
- **`MaintenanceResult` 응답에 `busy` 필드 추가** — `cleanup`/`purge`/`optimize` 응답에 `busy`(boolean)가 추가됐습니다. `optimize` 가 라이브 적재와 경합해 잠금을 못 얻으면 예외를 던지지 않고 `busy=true` 로 부분 실패를 알립니다 (`cleanup`/`purge` 는 항상 `false`). 기존 필드(`deletedTraces`/`freedBytes`/`dbSizeBytes`)는 그대로입니다.
- **`application.yml` 에 `apilens.auth.api-key` 설정 추가** (기본 비어 있음 = 무인증 폴백). env/시스템 프로퍼티 주입을 권장합니다.

### Security

- **maintenance·설정·마스킹 룰·조회 API 를 인증으로 보호할 수 있습니다** — v0.2.x 까지 무인증이던 파괴적/상태변경 동작(전체 삭제 `purge`, 설정 변경, 룰 추가/삭제, 서비스 삭제)을 토큰으로 막을 수 있게 됐습니다.
- **온라인 VACUUM 디스크 여유 가드** — `VACUUM` 은 작업 중 원본 크기만큼 임시 파일을 만들어 디스크를 일시적으로 약 2배 점유합니다. 실행 전 가용 디스크가 DB 크기보다 작으면 거부하고, 실행 중 `SQLITE_FULL`/`SQLITE_BUSY` 가 나도 예외를 던지지 않고 상태로만 알립니다 (디스크가 꽉 차는 2차 사고 방지).
- **남은 한계 (모두 신뢰 네트워크 전제의 의도된 제약)**:
  - **agent 적재(`/v1/spans`)는 무인증** — agent 모듈을 바꾸지 않기 위한 선택입니다. 신뢰망 밖에서 접근하면 가짜 trace 주입이 가능하니, 방화벽/망 격리가 유일한 방어입니다.
  - **토큰이 평문(HTTP)으로 전송됩니다** — v0.3 은 TLS 를 내장하지 않습니다. 같은 망의 패킷 스니핑에 토큰이 노출될 수 있으니, 운영망/방화벽 격리 환경에서만 쓰고 필요하면 리버스 프록시(nginx 등)로 TLS 를 종단하세요. 공용망에 직접 노출하지 마세요.
  - **ReDoS 가드는 신규 룰 저장 시점만 검사** — 이미 저장된 룰이나, 무인증 적재(`/v1/spans`)로 들어온 payload 가 마스킹을 거치는 핫패스는 가드 대상이 아닙니다. 근본 차단(공유 마스킹 엔진의 linear-time 매칭)은 agent 와 함께 바꿔야 해 다음 agent 라운드로 미뤘습니다.

## [0.2.1] - 2026-06-18

> server-only 유지보수 릴리스. **agent 모듈은 변경 없음** (v0.1/v0.2 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가).

### Added

- **payload 크기 가드 (server-side)** — ingest 저장 직전, 개별 payload body 가 `apilens.ingest.max-payload-bytes`(기본 1MB)를 넘으면 server 가 한도까지 잘라 저장하고 `truncated=true` 로 기록합니다. agent 가 정상 흐름에서 먼저 64KB 로 자르므로 보통은 동작하지 않는 안전망이며, agent 우회·오작동·대형 payload 폭증을 server 저장 시점에 차단합니다. 마스킹 적용 후 측정·절단하므로(mask → truncate) 마스킹을 우회하지 않고, UTF-8 문자 경계를 보존해 멀티바이트 문자를 쪼개지 않습니다.
- **수동 데이터 정리 API** (`POST /v1/maintenance/cleanup` · `POST /v1/maintenance/purge`) — 설정 페이지 "데이터 관리"에서 보관 기간 즉시 적용 / 전체 삭제를 트리거합니다. 두 동작 모두 `payloads → spans → traces` 순 행 단위 배치 DELETE + PRAGMA 로만 공간을 회수하며 **DB 파일을 삭제/이동/재생성하지 않습니다**. purge 는 되돌릴 수 없으며, 이 버전 시점에는 **인증이 없으므로** server 를 신뢰 네트워크에만 노출해야 합니다. 응답은 `{ deletedTraces, freedBytes, dbSizeBytes }`.

### Changed

- **ingest 설정 키 이름 교정** — `apilens.ingest.max-batch-size-bytes` → `apilens.ingest.max-payload-bytes`. 옛 키는 "batch 총합 거부"를 암시했지만 v0.1 부터 읽는 코드가 없는 dead key 였고, 실제 정책은 위 payload 가드의 **개별 payload body 한도**입니다. yml override 에 옛 키를 적어 둔 환경은 새 키로 바꿔 주세요 (안 바꿔도 기본 1MB 로 동작).
- **수동 정리 시 WAL 회수 보강 + 한계 명문화** — `cleanup`/`purge` 후 `incremental_vacuum → wal_checkpoint(TRUNCATE) → ANALYZE` 순서로 `-wal` 파일을 0바이트로 잘라 디스크를 회수합니다. 다만 `incremental_vacuum` 은 파일 끝(tail)의 free page 만 회수하고, `wal_checkpoint` 는 화면을 보는 중(reader 활성) 실행 시 busy 로 부분 실패할 수 있어(다음 정리에서 재시도) `freedBytes` 가 삭제량 대비 작게 나올 수 있습니다 (한도이지 결함 아님).

## [0.2.0] - 2026-06-11

> 성능 수습 + 설정 페이지 + trace 필터. agent 모듈은 변경 없음 (v0.1 agent 그대로 호환).

### BREAKING CHANGES

- **`GET /v1/services` 의 `traceCount` 의미 변경 — 누적 전수 → 최근 24시간** (`start_time` 기준, 경계값 포함). 누적 카운트가 대용량 DB 에서 매 호출 풀스캔을 유발해 윈도우 한정으로 변경했습니다. 필드명·응답 구조는 무변경이며 UI 라벨은 `Trace 수 (24h)` 로 표기합니다. 이 값을 누적 카운트로 소비하던 외부 스크립트가 있다면 의미를 재해석해야 합니다.
- **retention cleanup 이 실제로 동작 시작 — 기본 30일 초과 trace 자동 영구 삭제**. v0.1 의 retention 설정은 읽는 코드가 없는 dead key 여서 데이터가 무한 보관됐지만, v0.2.0 부터 매일 04:00 에 보관 기간(기본 30일)을 초과한 trace 가 영구 삭제됩니다 (복구 불가). 30일 넘은 trace 를 보존하려면 업그레이드 후 첫 04:00 전에 설정 페이지(`/settings`)에서 보관 기간을 늘려 두세요 (1~3650일).
- **SQLite `journal_mode` 가 `delete` → `WAL` 로 전환** — DB 파일 옆에 `-wal` / `-shm` 동반 파일이 생기며, 한 번 WAL 로 전환된 DB 는 v0.1 jar 로 롤백해도 WAL 이 유지됩니다 (v0.1 동작에 문제는 없음). 또한 `auto_vacuum=INCREMENTAL` 전환을 위해 v0.2.0 첫 기동 시 1회 `VACUUM` 이 수행됩니다 — 기존 DB 크기에 비례한 1회성 기동 지연이 있습니다 (멱등 — 2회차 기동부터 건너뜀).

### Added

- **설정 페이지 (`/settings`)** — 보관 기간(retention) 변경 + 마스킹 룰 관리 UI.
- **마스킹 룰 관리 API** (`/v1/masking-rules` — 목록/추가/삭제/토글 + 라이브 프리뷰). v0.1 의 빌트인 default 룰 4종(주민번호/카드번호/password/token)은 그대로이며, 관리 동작이 추가된 것입니다. default 룰은 **삭제 불가(409), 비활성 토글만 가능**. 프리뷰는 agent/server 와 같은 공유 마스킹 엔진으로 계산 — 화면 토글(저장 전) 상태를 그대로 반영합니다.
- **룰 핫 리로드** — 룰 추가/삭제/토글이 서버 재기동 없이 적용됩니다. **룰 변경은 이후 ingest 분에만 적용 — 기존 저장 payload 의 재마스킹은 없습니다** (재마스킹 경로 자체가 없음). 룰 로드 실패 시 동작: 기동 시점 실패는 기동을 차단하고 (마스킹 없는 침묵 기동 방지 — v0.1 의 fail-closed 와 동일한 의미), 운영 중 reload 실패는 기존 룰 세트를 그대로 유지합니다.
- **설정 API** (`GET/PUT /v1/settings`) — `retention.days` (정수 1~3650, 원자 적용). **설정 페이지 저장 값(DB)이 yml 값보다 우선**, 미설정 시 yml fallback (기본 30일).
- **retention cleanup 스케줄러** — 매일 04:00 (yml `apilens.retention.cleanup-cron`), payloads → spans → traces 순 행 단위 배치 삭제 (DB 파일 삭제/재생성 없음).
- **Dashboard trace 필터** — status + operation 검색 (`GET /v1/traces?q=` — root operation 풀 FQCN 부분 일치, `%`/`_`/`\` 리터럴 매칭).

### Changed

- **ingest 저장을 batchUpdate 로 전환** — span/payload 단건 INSERT 루프 제거 (수신 batch 당 왕복 감소). 저장 결과·REPLACE 동작은 동일.
- **SQLite 운영 설정 적용** — `journal_mode=WAL` (위 BREAKING CHANGES 참조) / `synchronous=NORMAL` / `busy_timeout=5000` 을 JDBC URL 로 모든 connection 에 적용. `auto_vacuum=INCREMENTAL` 전환은 첫 기동 시 1회 자동 수행 (기존 v0.1 DB 포함, 멱등 — sqlite-jdbc 가 auto_vacuum 을 URL 파라미터로 지원하지 않아 startup 전환 방식 채택). 조회 인덱스 1종 추가 (`traces(service_name, start_time DESC, trace_id DESC)`).

## [0.1.0] - 2026-06-03

> 첫 public release. 한국 SI 운영자가 NAS 같은 환경에 결재 없이 깔 수 있는 가벼운 호출 추적 도구.
>
> **사전 dogfooding 으로 `0.1.0-SNAPSHOT` 빌드를 배포한 환경**(예: NAS 원격)은 아래 **Changed** 의 wizard 옵션 키 변경과 **Security** 의 데이터 무결성 fix 때문에 **즉시 `0.1.0` 으로 재배포** 하기를 권장합니다.

### Added

- **Spring Boot agent** (ByteBuddy + premain, shadow jar relocate). 호스트 앱과 클래스 충돌 0 — 모든 외부 의존성을 `io.apilens.agent.shaded.*` 로 relocate.
- **HTTP server in/out 호출 instrument** (Spring MVC + WebClient / RestTemplate).
- **JDBC PreparedStatement instrument** (raw `Statement` 는 best-effort). 표준 setter + `addBatch()` 가 PAYLOAD IN 에 직렬화됩니다.
- **JDBC ResultSet capture** (opt-in `apilens.jdbc.capture-result-set=true`, 최대 100 row / 65536 bytes).
- **MyBatis Mapper instrument**.
- **Span collector** (HTTP POST → `/v1/spans`, daemon thread batch 송신).
- **W3C Trace Context (`traceparent`) 표준 전파** — OpenTelemetry 호환 span 모델.
- **Trace / Span / Payload 분리 저장** (SQLite + Flyway). 큰 payload 는 마스킹 적용 후 별도 테이블에 저장.
- **Server-side PII 마스킹** — 주민등록번호 / 카드번호 / `password`·`token`·`secret` 기본 룰을 ingest 시 서버에서 자동 적용.
- **노드 그래프 UI** (mind-map, 수평 시간 흐름) + 응답시간 대시보드 + payload inspector + 에러 시 stack trace 즉시 표시. React + Vite + TypeScript.
- **Setup wizard** — 4단계로 `-javaagent:` 옵션 한 줄 생성 (java / Maven / Gradle / Docker 환경별 안내, agent jar 자동 추출·다운로드).
- **단일 jar 배포** — server jar 안에 agent jar + UI 정적 파일 임베드.
- **Apache 2.0** 라이선스.

### Changed

> 0.1.0 이 첫 public release 이므로 공개 버전 간 호환성 영향은 없습니다. 아래는 사전 SNAPSHOT 빌드를 배포한 환경에만 해당합니다.

- **Setup wizard 가 출력하는 agent 옵션 키 교정** — `apilens.server.url` → `apilens.server`, `apilens.capture.params` → `apilens.jdbc.capture-params`, `apilens.capture.resultset` → `apilens.jdbc.capture-result-set`. 기존 키는 agent 가 읽지 못해 원격(app ≠ server) 환경에서 trace 0건이 되는 버그가 있었습니다. 옛 옵션을 복붙해 둔 환경은 새 키로 교정 후 재기동이 필요합니다.
- **`GET /v1/services` 응답 필드** — `lastSeen` 이 `lastSeenAt`(nullable) 으로 대체되고 `registeredAt` / `source` / `healthStatus` 가 추가되었습니다.

### Fixed

- **`apilens.jdbc.capture-result-set=true` 시 NULL 값 primitive getter 의 호스트 크래시 차단** — NULL 컬럼에 `getLong` 등을 호출할 때 타입 불일치로 호스트 앱이 기동 크래시하던 문제를, 반환 타입별 정합 wrapper 로 교정.
- **agent 가 server 에 연결하지 못할 때의 반복 에러 로그 억제** — 매 시도마다 출력하던 것을 끊김/복구 전환 시 1회씩만(edge-triggered) 출력하도록 변경.

### Security

- **[CRITICAL] agent 직렬화가 호스트 앱 파일을 0바이트로 truncate 하던 결함 차단** — `ResponseEntity<ResourceRegion>` 같은 반환값을 직렬화하면서 `Resource` 의 stream getter 를 호출해 호스트 파일이 잘리던 문제(실측: 비디오 스트리밍 endpoint 에서 mp4 약 649MB). 위험 타입 사전 차단 + body 타입 검사 + Jackson MixIn 의 3중 방어로 해결.
- **[CRITICAL] `apilens.jdbc.capture-result-set=true` 가 호스트 SELECT 결과를 잘라내고 커넥션 누수를 유발하던 결함 차단** — preread 후 원본을 닫던 설계를 pass-through tee 로 교체. 호스트는 전체 결과를 받고, 캡처 버퍼는 payload 샘플 용도로만 동작합니다 (`truncated=true` 는 payload 샘플 상한일 뿐 호스트 결과 손실이 아닙니다).
- **이전 `0.1.0-SNAPSHOT` 빌드 사용자는 즉시 `0.1.0` 으로 재배포 권장** — 위 두 결함은 agent 가 부착된 호스트 앱의 데이터를 파괴할 수 있었습니다.
- **`apilens.jdbc.capture-params` 기본값 `true`** (default ON) — PreparedStatement 파라미터가 PAYLOAD IN 에 직렬화됩니다. PII 가 우려되면 `apilens.jdbc.capture-params=false` 로 끄세요.

## Known limitations (v0.1)

- **단일 서비스 trace 만 지원** — 멀티 서비스 cross-service propagation 은 향후 버전.
- **동기 호출 기준** — `CompletableFuture` 등 비동기는 best-effort.
- **메서드 파라미터 이름이 `arg0`·`arg1`… 인덱스로 표시** — agent 는 모든 인자를 캡처하지만, 사용자 앱이 `-parameters` 컴파일 옵션 없이 빌드된 경우 바이트코드에 파라미터 이름이 없어 인덱스로 표시됩니다. 사용자 앱을 `-parameters` 로 빌드하면 실제 이름이 나타납니다. v0.2 에서 개선 예정.
- **JDBC PreparedStatement PAYLOAD IN 키 = parameterIndex** (`"1"`, `"2"`) — 이름 기반 마스킹 룰이 매칭되지 않을 수 있습니다. 민감 컬럼이 우려되면 `apilens.jdbc.capture-params=false`.
- **마스킹 룰 관리 UI 미제공** — v0.1 은 기본 룰을 서버에서 자동 적용만 합니다. 룰 추가/삭제·토글 UI 와 라이브 프리뷰는 향후 버전.
- **WebFlux 미지원** — agent 의 위험 타입 차단 목록에 WebFlux 전용 타입(`FilePart` 등)이 미포함이며, v0.1 은 WebFlux 자체를 instrument 하지 않습니다 (servlet stack — Spring MVC + Tomcat — 은 영향 없음).
- **인증 없음** — 운영자 단독 사용 전제. server (포트 8765) 를 신뢰할 수 없는 네트워크에 직접 노출하지 마세요.
- **Gantt chart / 수직 레이아웃 UI 미제공** — 노드 그래프 · 수평 흐름이 설계 방향입니다 (의도적 제외).
