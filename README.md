<!--
Phase: G1 (Release polish 1/2)
AC: AC-01-1 ~ AC-01-6
비협상: PM §0.1-(1) v0.1 기능 11항목 + PM §0.1-(2) 제외 8종 + PM §0.1-(3) NAS dogfooding 6항목 원문 인용
CLAUDE.md 룰: "프로젝트 정체성" + "절대 변경하지 말아야 할 결정 사항" + "UI 디자인 철학" 인용
-->

# ApiLens

> Spring Boot 운영자를 위한 가벼운 호출 추적 도구 — 단일 jar, PII 마스킹 내장, Apache 2.0

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](./LICENSE)
[![Build](https://github.com/OWNER/ApiLens/actions/workflows/ci.yml/badge.svg)](./.github/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/temurin/releases/?version=21)

![Trace Detail](docs/images/trace-detail-hero.png)

ApiLens는 한국 SI 운영 환경을 염두에 두고 만든 백엔드 trace 도구입니다.
controller → service → repository → SQL 흐름과 각 단계의 payload를
시각적으로 보여줍니다. 한국 PII(주민번호, 카드번호 등) 자동 마스킹이 기본 내장입니다.

**현재 단계: v0.1 개발 중 (사용 가능 아님)**

## 왜 ApiLens인가

- **운영자 친화** — 코드 수정 0줄. `-javaagent:` 옵션 한 줄로 동작
- **단일 jar 배포** — agent + collector + storage + UI 모두 하나
- **PII 마스킹 내장** — 주민번호/카드번호 등 한국 환경 default 룰
- **시각적 trace** — 노드 그래프 형태로 흐름이 한눈에
- **Apache 2.0 오픈소스** — 회사 보안팀 결재 친화

### UI 디자인 철학 (기존 APM 도구와 차별화)

- 노드 그래프 (mind-map 스타일 — gantt chart 아님)
- 수평 시간 흐름 (왼쪽 = 브라우저, 오른쪽 = DB / 외부 API)
- 노드 duration 시각적 강조 안 함 (단일 서비스 trace 기준)
- 에러 시 stack trace 즉시 표시 (노드가 빨갛게 멈추고 옆에 에러 박스)

기존 APM 도구(Pinpoint, Datadog, Jaeger)와 경쟁하지 않습니다.
"운영자가 필요해서 직접 깔고 싶은 작은 도구"가 목표입니다.

## 빠른 시작 (예정)

```bash
# 1. ApiLens server 실행
java -jar apilens-server.jar

# 2. 자기 Spring Boot 앱에 agent 부착 (포트 8765 사용 — NAS 8080 충돌 회피)
java -javaagent:~/.apilens/apilens-agent.jar \
     -Dapilens.server=http://localhost:8765 \
     -Dapilens.service.name=my-app \
     -jar my-app.jar

# 3. 브라우저로 ApiLens 열기
open http://localhost:8765
```

## v0.1 범위 (11항목)

> PM §0.1-(1) 인용 — 재서술 금지.

- ✅ Spring Boot agent (ByteBuddy + premain, shadow jar relocate)
- ✅ HTTP server in/out 호출 instrument (Spring MVC + Spring WebClient/RestTemplate)
- ✅ JDBC PreparedStatement instrument (raw Statement는 best-effort)
- ✅ W3C Trace Context (`traceparent`) 헤더 표준 전파
- ✅ Trace / Span / Payload 분리 저장 (SQLite + Flyway)
- ✅ Server-side 마스킹 (공유 엔진 `apilens-common`)
- ✅ Default 마스킹 룰 (비활성만 가능, 삭제 불가)
- ✅ Custom 마스킹 룰 (활성/비활성/삭제 가능)
- ✅ 노드 그래프 UI (mind-map 스타일, 수평 시간 흐름)
- ✅ 마스킹 라이브 프리뷰 (룰 토글 시 샘플 페이로드 즉시 반영)
- ✅ 단일 jar 배포 (server jar + agent jar + UI dist 임베드)

## v0.2+ 로 이연 (8종)

> PM §0.1-(2) 인용 — 재서술 금지.

- ⏳ 멀티 서비스 cross-service trace propagation (→ v0.3)
- ⏳ Agent-side 마스킹 toggle (→ v0.2+)
- ⏳ raw `Statement` 완전 instrument (best-effort 만)
- ⏳ 로그 통합 (LogLens 의 영역)
- ⏳ Alert / 알람 시스템
- ⏳ 사용자 인증 / 권한 (운영자 단독 사용 전제)
- ❌ Gantt chart UI (사용자 명시 거부 — v0.x 영구 제외)
- ❌ 수직 레이아웃 UI (사용자 명시 거부 — v0.x 영구 제외)

## 화면 소개

| 화면 | 설명 |
|---|---|
| ![Dashboard](docs/images/dashboard.png) | 시간축 응답시간 산점도 + 서비스별 필터 |
| ![Trace Detail](docs/images/trace-detail.png) | 노드 그래프 (mind-map) + payload inspector + 마스킹 라이브 프리뷰 |
| ![Masking Rules](docs/images/masking-rules.png) | Default/Custom 룰 토글 + 샘플 페이로드 즉시 반영 |

> 스크린샷은 v0.1 release 직전 캡처됩니다. 현재는 placeholder (디렉터리: `docs/images/`).

## 빌드 절차

```bash
# Java 21 + Gradle 8.11+ + Node 20+ 필요
./gradlew build
```

산출물: `apilens-server/build/libs/apilens-server-0.1.0-SNAPSHOT.jar`

UI 별도 빌드 (선택):

```bash
cd apilens-ui
npm ci
npm run build
# dist/ 가 server processResources 시 자동 임베드. 미빌드 시 server 는 경고만 띄우고 정상 기동.
```

## 마스킹 룰 default

server-side 마스킹 엔진(`apilens-common`)이 모든 PAYLOAD 송신 전에 적용하는 default 룰:

| 룰 | 패턴 | 비고 |
|---|---|---|
| 주민등록번호 (RRN) | `\d{6}-\d{7}` REGEX | 한국 PII 핵심 |
| 신용카드번호 | 13~19자리 숫자 + Luhn check | REGEX 패턴 강함 |
| password / passwd / pwd / secret / token | 이름 기반 fullmatch | 키 이름이 정확 일치 시 적용 |

> Default 룰은 **비활성만 가능, 삭제 불가**. Custom 룰은 활성/비활성/삭제 가능 (PM §0.1-(1)-7 / §0.1-(1)-8 인용).

> ⚠️ **PII 노출 한계** (v0.1): JDBC PreparedStatement 의 PAYLOAD IN 본문 키가 parameterIndex 의 decimal string (`"1"`, `"2"`) 이므로 이름 기반 룰이 매칭되지 않습니다. 자세한 위험 / 권장 조치는 [docs/agent-options.md § ⚠️ PII 노출 경고](./docs/agent-options.md) 참조.

## NAS dogfooding 가이드 (5분 시한)

> PM §0.1-(3) NAS dogfooding 6항목 — 재서술 금지. 사용자 본인 NAS (Synology, Spring Boot 운영 환경) 가 1순위 dogfooding 대상.

### 9-1 서버 배치

- NAS (Synology) 위에 단일 jar 다운로드 + 실행 디렉터리에서 `./apilens.db` 자동 생성 (별도 mount 불필요)
- UI 접근: `http://NAS-IP:8765/` (별도 reverse proxy 없이 직접 접근)

### 9-2 agent 부착

한 줄 실행으로 server + agent 동시 기동:

```bash
java -javaagent:apilens-agent.jar -jar apilens-server.jar
```

> 운영자 본인의 Spring Boot 앱에 agent 를 붙일 때도 `java -javaagent:apilens-agent.jar -jar my-app.jar` 형태로 한 줄.

### 9-3 권장 옵션

- 포트 `8765` 사용 (NAS 의 8080 충돌 회피)
- 운영망 환경별 권장 옵션 조합은 [docs/agent-options.md § 운영망 deployment 권장 옵션](./docs/agent-options.md) 참조 (개발 / 스테이징 / 운영망 일반 / 운영망 PII 의심 / 고부하 hot-path 5 환경)

### 9-4 PII 위험 인지 (운영자 책임)

- `apilens.jdbc.capture-params` 기본값은 `true` (default ON)
- PreparedStatement 의 `?` 가 사용자 비밀번호 / API 토큰 / 세션 키 같은 컬럼에 바인딩되는 경우, v0.1 의 이름 기반 마스킹 룰이 매칭되지 않을 수 있습니다 (PAYLOAD IN 키가 parameterIndex 의 decimal string 이기 때문)
- 자세한 위험 / 권장 조치는 [docs/agent-options.md § ⚠️ PII 노출 경고](./docs/agent-options.md) 참조

### 9-5 escape hatch

- `-Dapilens.jdbc.capture-params=false` 로 advice 자체 비활성 — weaving 0 + cache 0 + 호스트 오버헤드 0
- `-Dapilens.enabled=false` 로 agent 완전 비활성 (transport thread 도 안 띄움)

### 9-6 체크리스트 (5분 시한)

첫 trace 가 5분 내 dashboard 에서 보이지 않으면 **"실패한 dogfooding" 으로 간주**합니다.

확인 명령:

```bash
# (1) agent stderr 한 줄 출력 확인
# [ApiLens] ApiLens agent started: service=my-app, server=http://localhost:8765, samplingRate=1.0, batchMaxSize=100

# (2) curl 로 첫 trace 확인 — traces[0].rootOperation 이 "agent.startup" 이어야 정상
curl -s http://NAS-IP:8765/v1/traces?service=my-app | jq
```

모두 fail 시 → GitHub Issue 등록 권장 (NAS OS / Java 버전 / agent stderr 마지막 30 line / curl 응답 포함).

## 절대 변경하지 말 결정

> CLAUDE.md "절대 변경하지 말아야 할 결정 사항" 인용 — 본 결정들은 길게 논의해서 확정한 사항입니다.

1. **백엔드**: Java 21 + Spring Boot 3.4 + Gradle (Kotlin DSL)
2. **DB**: SQLite + Flyway 마이그레이션 (스키마 변경은 항상 새 마이그레이션 파일 `V2__*.sql` 추가)
3. **Agent**: ByteBuddy + premain + shadow jar (의존성 relocate 필수 — 사용자 앱과 클래스 충돌 방지)
4. **UI**: React + Vite + TypeScript (npm 빌드 후 server resources/static 에 복사)
5. **모노레포 구조**: `/apilens-common`, `/apilens-agent`, `/apilens-server`, `/apilens-ui`
6. **단일 jar 배포**: server jar 안에 agent jar + UI 정적 파일 packaging
7. **포트 8765** (사용자 앱이 보통 8080 을 쓰므로 회피)
8. **패키지: `io.apilens.*`**
9. **버전: `0.1.0-SNAPSHOT`** (첫 release 시점에 `0.1.0` 으로 unmark)
10. **R6 호스트 throw 0 절대 원칙** — agent 코드 모든 진입점은 `try { ... } catch (Throwable t) { silent drop }` 패턴 의무. 호스트 앱에 ApiLens 자체 장애 영향 0.

자세한 결정 배경은 [CLAUDE.md](./CLAUDE.md) "절대 변경하지 말아야 할 결정 사항" 절 참조.

## 라이선스

[Apache License 2.0](./LICENSE). 무료 오픈소스 (donation only).

### Donation

GitHub Sponsors 를 통해 지원 가능 (link TBD — v0.1.0 release 시점에 활성화).

## 관련 문서

- [v0.1 스코프 SSOT (재해석 차단)](./docs/v01-scope.md)
- [Agent 옵션 명세 + 운영망 5 환경 표 + NAS 체크리스트](./docs/agent-options.md)
- [Span attribute 키 명세 (OTel)](./docs/otel-attributes.md)
- [API 계약 5개 endpoint](./docs/api.md)
- [CHANGELOG (v0.1.0)](./CHANGELOG.md)
- [CLAUDE.md (프로젝트 정체성 + 절대 변경 금지 결정)](./CLAUDE.md)

---

## English

ApiLens is a lightweight call-tracing tool for Spring Boot, designed for
operators in Korean SI environments. It captures controller → service →
repository → SQL flow with payloads, masks Korean PII by default, and
ships as a single jar. It is not an APM replacement — it is the small
debug tool an operator can install on their own.

**Status: v0.1 in development.**

### Why ApiLens

- **Operator-friendly** — zero code change, single `-javaagent:` flag
- **Single jar** — agent + collector + storage + UI bundled together
- **Built-in PII masking** — Korean PII (RRN, card number) default rules
- **Visual trace** — node-graph (mind-map) layout
- **Apache 2.0** — friendly to corporate security review

### v0.1 scope (11 items)

> Quoted from PM §0.1-(1). Spring Boot agent / HTTP in-out instrument / JDBC
> PreparedStatement instrument / W3C Trace Context propagation / Trace · Span ·
> Payload separated storage (SQLite + Flyway) / Server-side masking / Default
> masking rules (deactivate-only) / Custom masking rules / Node-graph UI /
> Live masking preview / Single-jar deployment.

### Excluded from v0.1 (8 items)

> Quoted from PM §0.1-(2). Multi-service cross-service trace propagation (→ v0.3) /
> Agent-side masking toggle (→ v0.2+) / raw `Statement` full instrument (best-effort
> only) / Log integration (LogLens scope) / Alerts / Authentication & authorization
> (operator-only assumption) / Gantt chart UI (explicit reject) / Vertical layout UI
> (explicit reject).

### NAS dogfooding guide

See the Korean section above (`## NAS dogfooding 가이드`) for the full 9-1 ~ 9-6
checklist. Summary: deploy single jar on NAS (Synology), attach agent in one
line, observe first trace within 5 minutes — otherwise file a GitHub Issue.

### Build

```bash
./gradlew build
```

Output: `apilens-server/build/libs/apilens-server-0.1.0-SNAPSHOT.jar`.

### License

[Apache License 2.0](./LICENSE). Free and open source (donation only).
