<!--
Phase: G1 (Release polish 1/2)
AC: AC-02-1 ~ AC-02-4
비협상: PM §0.1-(1) v0.1 기능 11항목 + PM §0.1-(2) 제외 8종 + PM §0.1-(5) grep 단언 + PM §0.1-(6) R15/R6/R14 인용
CLAUDE.md 룰: "절대 변경하지 말아야 할 결정 사항 §9" (0.1.0-SNAPSHOT → 0.1.0 unmark) 인용
-->

# Changelog

All notable changes to ApiLens will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> v0.1.1 backlog 후보가 누적되는 영역입니다. v0.1.0 release 직후 본 절이 다음 release 의 cumulative log 가 됩니다.

### Added

- (v0.1.1 candidate) JdbcParamSerializer 의 `java.time` 타입 4종 확장 (LocalDate / LocalDateTime / LocalTime / ZonedDateTime) — backlog.md "Phase G1 시작 시 보류된 항목" §15 참조
- (v0.1.1 candidate) Playwright 트리거 진행률 갱신 자동화 — backlog.md §6 참조

### Changed

N/A.

### Deprecated

N/A.

### Removed

N/A.

### Fixed

N/A.

### Security

- (v0.2 candidate) SEC-01 PII 노출 처리 방향 3안 검토 — column 이름 추적 / value heuristic / capture-params 키 구조 재설계. backlog.md §14 참조.

## [0.1.0] - 2026-06-03

> 첫 public release. 한국 SI 운영자가 NAS 같은 환경에 결재 없이 깔 수 있는 가벼운 호출 추적 도구.
> 본 release 시점에 `0.1.0-SNAPSHOT` 라벨이 `0.1.0` 으로 unmark 됩니다 (CLAUDE.md "절대 변경하지 말아야 할 결정 사항 §9" 인용).

### Added

> PM §0.1-(1) v0.1 기능 11항목 인용 — 재서술 금지.

- **Spring Boot agent** (ByteBuddy 1.17.8 + premain, shadow jar relocate). 호스트 앱과 클래스 충돌 0 — 모든 외부 의존성 (ByteBuddy, Jackson) 을 `io.apilens.agent.shaded.*` 로 relocate.
- **HTTP server in/out 호출 instrument** (Spring MVC + Spring WebClient/RestTemplate). controller / RestController / WebClient 호출이 자동으로 span 으로 캡처됩니다.
- **JDBC PreparedStatement instrument** (raw `Statement` 는 best-effort 만). PreparedStatement 표준 12종 setter (`setString`, `setInt`, `setLong`, `setDouble`, `setFloat`, `setBoolean`, `setBigDecimal`, `setDate`, `setTime`, `setTimestamp`, `setBytes`, `setNull`) + `addBatch()` 가 PAYLOAD IN 에 직렬화됩니다.
- **JDBC ResultSet capture** (opt-in `apilens.jdbc.capture-result-set=true`). SELECT 결과 row 가 PAYLOAD OUT 에 캡처됩니다 (최대 100 row + 65536 bytes 한도).
- **MyBatis Mapper instrument**. Mapper 인터페이스 메서드가 자동 span 캡처 대상에 포함됩니다.
- **Span collector** (HTTP POST → `/v1/spans`). agent 가 daemon thread (`apilens-sender`) 1개로 batch 송신 (default 100 span / 1초 flush).
- **W3C Trace Context (`traceparent`) 헤더 표준 전파**. 자체 포맷 도입 0건 — OpenTelemetry 호환.
- **Trace / Span / Payload 분리 저장** (SQLite + Flyway). 큰 payload 는 별도 테이블 (`payloads`) 로 분리되어 마스킹 적용 후 저장.
- **ResponseEntity unwrap**. Spring MVC controller 의 `ResponseEntity<T>` 반환값이 body 부분만 PAYLOAD OUT 으로 직렬화됩니다.
- **jsr310 직렬화** (java.time API 호환). `LocalDateTime` / `Instant` 등이 ISO-8601 형태로 정상 직렬화됩니다.
- **Server-side 마스킹** (공유 엔진 `apilens-common`). agent 와 server 가 같은 엔진을 사용해 결과 일관성 보장.
- **Default 마스킹 룰** (비활성만 가능, 삭제 불가). 주민등록번호 / 카드번호 / password · token · secret 이름 기반 룰.
- **Custom 마스킹 룰** (활성 / 비활성 / 삭제 가능). REGEX 형태로 운영자가 자유롭게 추가.
- **노드 그래프 UI** (mind-map 스타일, 수평 시간 흐름). React + Vite + TypeScript. gantt chart / 수직 레이아웃은 사용자 명시 거부.
- **React UI Dashboard** (시간축 응답시간 산점도 + 서비스별 필터).
- **React UI Trace detail** (노드 그래프 + payload inspector + 에러 시 stack trace 즉시 표시).
- **마스킹 라이브 프리뷰** (룰 토글 시 샘플 페이로드 즉시 반영 — 결재용 신뢰 도구).
- **단일 jar 배포** (server jar + agent jar + UI dist 임베드). server bootJar 안에 `BOOT-INF/classes/agent/apilens-agent.jar` + `BOOT-INF/classes/static/**` 동시 임베드.
- **Sample app** (`examples/sample-app`). User CRUD + AuditLog 도메인으로 controller / service / repository / SQL 흐름 시연.
- **Documentation** — README NAS dogfooding 가이드 9-1~9-6 / docs/agent-options.md 운영망 5 환경 표 / docs/v01-scope.md SSOT / docs/api.md 5 endpoint 명세 / docs/otel-attributes.md.

### Changed

> 0.1.0 이 첫 public release 이므로 공개 버전 간 호환성 영향은 없습니다. 아래는 **사전 dogfooding 으로 `0.1.0-SNAPSHOT` 빌드를 배포한 환경** (예: NAS 원격) 에만 해당하는 변경입니다 (deprecation period 0).

- **[BREAKING] Setup wizard 가 출력하는 agent `-D` 옵션 키 3건 교정** — `apilens.server.url` → `apilens.server`, `apilens.capture.params` → `apilens.jdbc.capture-params`, `apilens.capture.resultset` → `apilens.jdbc.capture-result-set`. 기존 wizard 출력은 agent 실제 키 (`AgentConfig` 의 `PROP_*`) 와 달라, 원격 (app ≠ server) 환경에서 agent 가 server URL 을 못 읽고 `localhost:8765` 로 fallback → trace 0건이 되는 버그가 있었습니다. 옛 wizard 출력을 JVM 옵션에 복붙해 둔 환경은 새 키로 교정 후 재기동해야 합니다 (자동 이관 없음). SSOT: [docs/agent-options.md](./docs/agent-options.md).
- **[BREAKING] `GET /v1/services` 응답 필드 변경 (W-01)** — `lastSeen` (항상 값 존재) 이 `lastSeenAt` (nullable — 등록만 되고 trace 0건이면 `null`) 으로 대체되고 `registeredAt` / `source` / `healthStatus` 3 필드가 추가되었습니다. client 측 `lastSeen` 참조는 `lastSeenAt` 으로 업데이트가 필요합니다.

### Deprecated

N/A (first release).

### Removed

N/A (first release).

### Fixed

- **[P0] `apilens.jdbc.capture-result-set=true` 시 NULL 값 primitive getter 의 호스트 크래시 차단** — `CapturedResultSet` 의 NULL 기본값이 반환 타입과 무관한 wrapper (`Integer 0`) 로 반환돼, NULL `BIGINT` 컬럼에 `getLong` 호출 시 JDK Proxy 언박싱이 `Integer → Long` ClassCast 로 터지면서 호스트 앱이 기동 크래시 · 재시작 루프에 빠졌습니다. NULL 분기를 반환 primitive 타입별 정합 wrapper (`int→0` / `long→0L` / `short` / `byte` / `double` / `float` / `boolean`) 로 교정하고 wrapper · object 타입은 `null` 을 유지합니다 (JDBC `getObject(col, Class)` 정합). (NAS 원격 dogfooding 발견)
- **agent 가 server 에 연결 못 할 때 호스트 앱 로그를 도배하던 반복 에러 로깅 억제** — `HttpTransport` 가 매 flush 마다 `ConnectException` 을 error 로 출력하던 것을 `serverReachable` 플래그 기반 edge-triggered 로 전환 (끊김 시 1회 error / down 중 debug / 복구 시 1회 info).

### Security

- **[CRITICAL] agent serialize 가 호스트 앱 파일을 0바이트로 truncate 하던 P0 데이터 무결성 결함 차단** (CWE-440 / CWE-664 / OWASP A08). `AdviceSupport.serializeReturn` 이 `ResponseEntity<ResourceRegion>` 같은 반환값을 Jackson 으로 직렬화하면서 `FileSystemResource.getOutputStream()` 을 호출 → `Files.newOutputStream(... TRUNCATE_EXISTING)` 으로 호스트 앱 파일이 잘렸습니다 (실측: 비디오 스트리밍 endpoint 호출 시 mp4 파일 합계 약 649MB 가 0바이트로 truncate). **multi-layer 3 layer defense** 적용 — Layer 1 `isUnsafeToSerialize` (위험 타입 11종 사전 차단) + Layer 2 `unwrapResponseEntity` body 타입 검사 + Layer 3 Jackson `ResourceMixIn` (`@JsonIgnore` destructive getter 5종: `getOutputStream` / `getInputStream` / `getFile` / `getURI` / `getURL`). shadow jar relocate 정합은 `javap -v` bytecode 직접 검증.
- **[CRITICAL] `apilens.jdbc.capture-result-set=true` 가 호스트 SELECT 결과를 truncate + 커넥션 누수를 유발하던 P0 결함 차단**. 기존 설계가 호스트 ResultSet 을 최대 100행만 미리 읽고 원본을 닫은 뒤 버퍼-backed proxy 를 호스트에 돌려줘, 행이 많은 SELECT 가 호스트 측에서도 잘리고 (조용한 데이터 손상) ORM lifecycle 이 깨져 HikariCP 커넥션 누수 · 롤백이 발생했습니다. **pass-through tee** 로 재설계 — agent 는 앞 N행만 payload 샘플 버퍼로 읽되 원본을 닫지 않고 `next` / getter / `close` / metadata 를 모두 underlying 에 위임합니다. `truncated=true` 는 **payload 샘플 상한** 일 뿐 호스트 결과 손실이 아닙니다.
- **이전 `0.1.0-SNAPSHOT` (사전 dogfooding 빌드) 사용자는 즉시 `0.1.0` 으로 재배포 권장** — 위 두 P0 는 agent 가 부착된 호스트 앱의 데이터를 파괴할 수 있던 결함입니다.
- **Apache 2.0 라이선스 헤더 일괄 적용** — `apilens-common`, `apilens-agent`, `apilens-server`, `examples/sample-app` 의 모든 `.java` 파일에 17 line 표준 헤더 prepend (`scripts/apply-license-header.sh` 자동 스크립트, idempotent).
- **`apilens.jdbc.capture-params` 기본값 `true`** (default ON) — PreparedStatement 의 표준 12종 setter 가 hook 되어 PAYLOAD IN 에 직렬화됩니다. 운영자는 PII 위험 인지 후 escape hatch (`apilens.jdbc.capture-params=false`) 사용 권장 ([docs/agent-options.md § ⚠️ PII 노출 경고](./docs/agent-options.md) 참조).

## Known limitations (v0.1)

> PM §0.1-(2) 제외 8종 + PM §0.1-(5) grep 단언 + PM §0.1-(6) R15/R6/R14 + SEC-01 + backlog cross-link 통합 절.

### 스코프 밖 (PM §0.1-(2) 인용)

- 멀티 서비스 cross-service trace propagation (→ v0.3)
- Agent-side 마스킹 toggle (→ v0.2+)
- raw `Statement` 완전 instrument (best-effort 만)
- 로그 통합 (LogLens 의 영역)
- Alert / 알람 시스템
- 사용자 인증 / 권한 (운영자 단독 사용 전제)
- Gantt chart UI (사용자 명시 거부 — v0.x 영구 제외)
- 수직 레이아웃 UI (사용자 명시 거부 — v0.x 영구 제외)

### 회귀 가드 (PM §0.1-(5) / §0.1-(6) 인용)

- **R15 capture 분기 충돌 봉인**: agent 의 HTTP / JDBC capture 분기가 단일 분기로 봉인된 상태. v0.1 release 후 다중 분기 부활 금지.
- **R6 호스트 throw 0 절대 원칙**: agent 코드 모든 진입점은 `try { ... } catch (Throwable t) { silent drop }` 패턴 의무. release 후 본 패턴 위반 시 release blocker.
- **R14 db.params.diag 정리 완료**: 임시 진단 attribute 제거 완료. OTel semantic conventions 의 정식 `db.statement` / `db.system` 만 사용. 임시 attribute 재도입 금지.
- **회귀 가드 grep `payloads = ` 0 hit 단언**: agent 코드 어디에서도 `payloads = ` 직접 SQL 작성 금지. 모든 payload 쓰기는 server-side 마스킹 엔진을 거칩니다. v0.1 release 후 `grep -rn 'payloads = ' apilens-agent/src/main/java/` 결과 0 hit 유지 의무.

### 알려진 제약 (SEC-01)

- **WebFlux 기반 앱은 별도 가드 필요** — agent 의 위험 타입 차단 목록 (`isUnsafeToSerialize` 11종) 에 WebFlux 전용 타입 (`org.springframework.http.codec.multipart.FilePart` 등) 은 미포함입니다. v0.1 은 ThreadLocal 기반 trace context 한계로 WebFlux 자체를 instrument 하지 않으므로 직접 영향은 0 으로 추정되며, v0.2+ WebFlux 지원 시 위험 타입 목록을 동시 보강할 예정입니다. (servlet stack — Spring MVC + Tomcat — 은 영향 없음)
- **Single service trace 만 지원** (v0.3 에서 cross-service propagation 보강 예정).
- **Synchronous calls 기준**. CompletableFuture / 비동기 호출은 best-effort 수준 (v0.2 이후 평가).
- **`arg0` 추출 한계**: ByteBuddy advice 가 `@Argument(0)` 로 첫 인자만 캡처 — 다중 인자 method 의 다른 인자는 PAYLOAD IN 에 미포함 (v0.2 이후 평가).
- **JDBC PreparedStatement PAYLOAD IN 키 = `parameterIndex`** (decimal string). 예: `{"1":"hong","2":"password123"}`. 서버측 기본 마스킹 룰 중 **이름 기반 룰** (`password|passwd|pwd|secret|token`) 이 매칭되지 않을 수 있습니다. v0.2 에서 column 이름 추적 또는 value heuristic 보강 예정 ([docs/agent-options.md § ⚠️ PII 노출 경고](./docs/agent-options.md) 참조).

### 보류 항목 (cross-link)

본 phase G1 시작 시 후속 phase 로 이관된 항목 5건은 PMO 메타 디렉터리 `backlog.md` "Phase G1 시작 시 보류된 항목" 섹션을 참조하세요 (Playwright 트리거 진행률 / inter-pipeline-handoff 노트 / SEC-01 v0.2 처리 방향 3안 / JdbcParamSerializer java.time 4종 확장 / 회고 모범 패턴 한계 라벨).
