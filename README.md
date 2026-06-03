# ApiLens

**Spring Boot 운영자를 위한 가벼운 호출 추적 도구**
*A lightweight call-tracing tool for Spring Boot operators*

<!-- 스크린샷은 v0.1 릴리스 시 추가 | hero screenshot to be added with the v0.1 release -->

---

## 소개 | About

ApiLens는 운영 중인 Spring Boot 애플리케이션의 호출 흐름(controller → service → repository → SQL)과
각 단계의 payload를 한 화면에서 보여주는 가벼운 추적 도구입니다. 코드 수정 없이 `-javaagent:` 옵션
한 줄로 동작하며, 한국 PII(주민등록번호·카드번호 등)를 기본으로 마스킹합니다.

Datadog·Pinpoint 같은 본격 APM의 대체재가 아닙니다 —
**운영자가 결재 없이 직접 깔고 싶은 작은 도구**가 목표입니다.

ApiLens shows the call flow (controller → service → repository → SQL) and the payload at each step
of a running Spring Boot application on a single screen. It attaches with one `-javaagent:` flag — no
code change — and masks Korean PII by default. It is not a full APM replacement like Datadog or
Pinpoint; it is **the small tool an operator wants to install themselves, without sign-off.**

---

## 주요 기능 | Features

| 기능 | 설명 |
|------|------|
| 🔌 무중단 부착 | 코드 수정 0줄. `-javaagent:` 한 줄로 부착 (ByteBuddy premain) |
| 🧭 노드 그래프 추적 | controller → service → repository → SQL 흐름을 mind-map 형태로 시각화 (gantt chart 아님) |
| 📦 payload 캡처 | 각 노드의 요청/응답 본문 + JDBC 파라미터를 단계별로 확인 |
| 🛡️ PII 마스킹 내장 | 주민번호·카드번호·password/token 등 server-side 자동 마스킹 + 라이브 프리뷰 |
| 🔴 에러 즉시 표시 | 끊긴 노드가 빨갛게 멈추고 옆에 stack trace 박스 |
| 📊 응답시간 대시보드 | 시간축 산점도 + 서비스별 필터 |
| 📕 단일 jar 배포 | agent + collector + storage + UI 가 하나의 jar |
| 🧩 표준 호환 | W3C Trace Context (`traceparent`) + OpenTelemetry 호환 span 모델 |

- **백엔드 | Backend**: Java 21 · Spring Boot 3.4 · SQLite + Flyway
- **에이전트 | Agent**: ByteBuddy + premain (모든 의존성 relocate — 호스트 앱과 클래스 충돌 0, agent 장애가 호스트에 미치는 영향 0)
- **UI**: React + Vite + TypeScript (단일 jar 안에 임베드)

---

## 스크린샷 | Screenshots

> v0.1 화면 캡처 추가 예정 | Screenshots coming with the v0.1 release.

- **Dashboard** — 시간축 응답시간 산점도 + 서비스별 필터
- **Trace Detail** — 노드 그래프(mind-map) + payload inspector + 마스킹 라이브 프리뷰
- **Masking Rules** — Default / Custom 룰 토글 + 샘플 페이로드 즉시 반영

---

## 빠른 시작 | Quick Start

```bash
# 1. ApiLens server 실행 (포트 8765 — 앱이 보통 쓰는 8080 회피, ./apilens.db 자동 생성)
java -jar apilens-server-0.1.0.jar

# 2. 브라우저로 열고 Setup wizard 따라가기 (JVM 옵션 한 줄 생성 + agent jar 경로/다운로드 안내)
open http://localhost:8765

# 3. 자기 Spring Boot 앱에 agent 부착 후 재기동
java -javaagent:/path/to/apilens-agent.jar \
     -Dapilens.server=http://localhost:8765 \
     -Dapilens.service.name=my-app \
     -jar my-app.jar
```

첫 trace가 5분 안에 대시보드에 보이면 정상입니다. agent jar는 server 첫 실행 시 `~/.apilens/` 에
자동으로 풀립니다(Setup wizard가 절대경로 또는 다운로드를 안내).

Run the server, open the wizard in your browser, paste the generated `-javaagent:` line into your app, and restart — the first trace should appear within five minutes.

---

## 직접 빌드 | Build from Source

```bash
# 요구사항 | Requirements: JDK 21, Node 20+ (Gradle wrapper 포함)

git clone ssh://git@git-yirgacheffe.duckdns.org:3022/netmd/ApiLens.git
cd ApiLens

# UI 빌드 (선택 — 미빌드 시 server는 경고만 띄우고 정상 기동)
cd apilens-ui && npm ci && npm run build && cd ..

# 전체 빌드 (agent shadow jar + server bootJar + UI 임베드)
./gradlew clean build
```

산출물 | Output: `apilens-server/build/libs/apilens-server-0.1.0.jar` (단일 배포 jar)

---

## PII 마스킹 | PII Masking

server-side 마스킹 엔진이 모든 payload 를 저장하기 전에 적용하는 기본 룰:

| 룰 | 패턴 |
|---|---|
| 주민등록번호 (RRN) | `\d{6}-\d{7}` |
| 신용카드번호 | 13~19자리 + Luhn check |
| password / token / secret … | 키 이름 기반 |

기본 룰은 **비활성만 가능, 삭제 불가**입니다. Custom 룰은 자유롭게 추가/삭제할 수 있고,
룰을 토글하면 샘플 페이로드에 즉시 반영됩니다(라이브 프리뷰).

> ⚠️ **v0.1 한계** — JDBC PreparedStatement 파라미터(`apilens.jdbc.capture-params`, 기본 ON)는 키가
> parameterIndex(`"1"`, `"2"`)라 이름 기반 룰이 매칭되지 않을 수 있습니다. 민감 컬럼이 우려되면
> `-Dapilens.jdbc.capture-params=false` 로 끄세요.

---

## 라이선스 | License

[Apache License 2.0](./LICENSE). 무료 오픈소스입니다 (donation only).
Free and open source under the Apache 2.0 License.

---

## 기여 | Contributing

버그 리포트·기능 제안·PR 모두 환영합니다.
Bug reports, feature requests, and PRs are all welcome.

---

## 개발자 | Developer

1인 개발자로 개발자와 운영자를 위한 작고 유용한 도구들을 만들고 있습니다.
Solo developer building small, useful tools for developers and operators.

- 이전 프로젝트 | Previous project: **LogLens** — Spring Boot 로그 분석 데스크탑 앱
