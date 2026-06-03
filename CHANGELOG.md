# Changelog

All notable changes to ApiLens will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

다음 release 후보가 누적되는 영역입니다.

### Added

- (candidate) `JdbcParamSerializer` 의 `java.time` 타입 확장 (LocalDate / LocalDateTime / LocalTime / ZonedDateTime).

### Security

- (candidate) JDBC PreparedStatement 파라미터의 이름 기반 마스킹 보강 — 현재 PAYLOAD IN 키가 parameterIndex 라 이름 기반 룰이 매칭되지 않는 한계 개선.

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
