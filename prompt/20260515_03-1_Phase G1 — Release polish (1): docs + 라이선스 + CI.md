Phase G1 — Release polish (1/2): docs + 라이선스 + CI

## 컨텍스트
CLAUDE.md 를 먼저 읽고 시작. ApiLens v0.1 의 핵심 기능 완성:
- Backend (Phase A~E3 + dogfooding 후속): agent + collector + storage, 145 agent tests + 27 server tests, JDBC parameter capture (default ON + kill switch) / MyBatis Mapper instrumentation / ResultSet capture (opt-in)
- Frontend (Phase F1~F2 fix³): Dashboard + Trace 상세 노드 그래프 + 수직 4단 레이아웃 비협상 봉인
- Sample-app: H2 + 검증용 분기 trace 시나리오 (시나리오 1~5)
- 실 운영 dogfooding 검증: VAMS (Java 25 + MyBatis + HikariCP + MariaDB) — 12라운드 + Phase E3 fix¹/²/³ 3라운드 회귀 봉인 (저널 line 132 + inter-pipeline 2026-05-14T23-54-13 참조)

v0.1.0 release blocker 모두 해소 완료. 본 phase 는 G 의 1/2 — docs/라이선스/CI + NAS dogfooding 가이드.
G2 (자동화 통합 테스트) 는 별도 phase. 첫 release tag 는 G1 + G2 + 사용자 NAS dogfooding 안정 기간 후.

## 작업 1: README 보강 (루트 README.md)

현재 README는 v0.1 개발 시작 시점 기준. 백엔드 + UI 다 됐으니 업데이트.

목표 독자: 한국 SI 운영자 (1차) + 글로벌 OSS 커뮤니티 (2차)

구조:
1. 한 줄 설명 + 배지 (License Apache 2.0, Build status, Java 21)
2. Hero 스크린샷 (docs/images/trace-detail-hero.png) — F2 결과 화면.
   사용자가 별도로 캡처해서 docs/images/에 둘 것. 이번 작업에선 이미지 placeholder만.
3. 왜 ApiLens인가 (운영자 친화 / 단일 jar / PII 마스킹 내장 / Apache 2.0)
4. 빠른 시작 (3 단계)
   - server 실행 (`java -jar apilens-server-0.1.0.jar` 또는 `./gradlew :apilens-server:bootRun`)
   - agent 부착 (`-javaagent:apilens-agent-0.1.0.jar -Dapilens.service.name=<service> -Dapilens.server=http://<server-host>:8765`)
   - 브라우저 접속 (`http://localhost:8765`)
5. v0.1 기능 목록 (사용자 명시 — 아래 11개 항목 모두 포함):
   - Java agent (ByteBuddy 1.17.8, Java 21~25 호환)
   - Controller / Service / Repository / JDBC 자동 인스트루먼트
   - **MyBatis Mapper instrumentation** — `MapperProxy.invoke` 한 점으로 모든 `@Mapper interface` cover (E3 후속 R8)
   - **JDBC parameter capture (default ON + kill switch)** — `?` 자리에 실제 바인딩된 값 표시. `apilens.jdbc.capture-params=false` 로 비활성. blacklist 매처 (모든 표준 setter cover)
   - **JDBC ResultSet capture (opt-in)** — `apilens.jdbc.capture-result-set=true` 시 SELECT 결과 row 본문 표시 (최대 100 rows / 65536 bytes)
   - W3C Trace Context (`traceparent` 헤더, outbound only — v0.1)
   - Spring `ResponseEntity` body 자동 unwrap (DevTools Network 응답과 일치)
   - PII 마스킹 (server-side, default 룰: 주민번호 / 카드번호 / password / token)
   - React UI: Dashboard (latency scatter + recent traces + Live 모드 sliding window)
   - React UI: Trace detail (수직 4단 레이아웃 — 노드 그래프 + 범례 + selected 카드)
   - 단일 jar 배포 (server + agent + UI 임베드)
6. 화면 소개 (Dashboard + Trace 상세 스크린샷 2~3장)
7. 빌드 절차 (Gradle 8.x + Node 20+)
8. 마스킹 룰 default (주민번호, 카드번호, password, token)
9. **NAS / 운영망 dogfooding 가이드** (신규) — 사용자 명시 요청
   - 9-1. ApiLens 서버 배치 위치 결정 (사용자 앱과 같은 박스 / 별도 박스 / NAS docker)
   - 9-2. agent 부착 — 운영 앱의 JVM 옵션에 4 줄 추가 (`-javaagent` / `-Dapilens.service.name` / `-Dapilens.server` / 선택 옵션)
   - 9-3. 권장 운영 옵션 — `-Dapilens.jdbc.capture-params=true` (default, SQL `?` 값 표시) + `-Dapilens.jdbc.capture-result-set=true` (운영자 가치 vs 메모리/성능 trade-off 명시)
   - 9-4. PII 노출 위험 + 운영자 권장 조치 (docs/agent-options.md PII 경고 박스 인용)
   - 9-5. agent 의심 상황 escape hatch — `-Dapilens.jdbc.capture-params=false` 즉시 토글로 advice weaving 자체 skip
   - 9-6. 운영 dogfooding 체크리스트 — host throw 0 / 메모리 누수 0 / latency 영향 < 5% (실측 권고)
10. 라이선스 + Donation 안내 (GitHub Sponsors)
11. English 섹션 (위 1-10 핵심 요약 영어로)

스크린샷 자리는 placeholder `![Trace Detail](docs/images/trace-detail-hero.png)` 같은 식.
실제 이미지 파일은 사용자가 별도 캡처. docs/images/.gitkeep 추가.

## 작업 2: CHANGELOG.md 신규 작성

루트에 CHANGELOG.md. Keep a Changelog 형식.

[Unreleased]
### Added
- ... (v0.1.1 backlog)

## [0.1.0] - 2026-XX-XX (실제 release 시 채움)
### Added
- Java agent with ByteBuddy 1.17.8 instrumentation (Java 21~25 호환, Controller/Service/Repository/JDBC auto-capture)
- MyBatis Mapper instrumentation — `MapperProxy.invoke` 한 점으로 모든 `@Mapper interface` cover
- **JDBC parameter capture (default ON + kill switch)** — PreparedStatement 의 모든 표준 setter 인터셉트, payload IN 에 `{"1":"value1","2":"value2"}` 형식 직렬화. addBatch 시 `{"batch_size":N,"batch":[...]}`. `apilens.jdbc.capture-params=false` 로 비활성 (advice weaving 자체 skip)
- **JDBC ResultSet capture (opt-in)** — `apilens.jdbc.capture-result-set=true` 시 SELECT 결과 row 본문 capture (max 100 rows / 65536 bytes, Dynamic Proxy wrapper)
- Span collector with W3C Trace Context propagation (outbound only)
- SQLite storage with Flyway migration
- Default masking rules (RRN, card number, password, token)
- Spring `ResponseEntity` body auto unwrap (DevTools Network 응답과 일치)
- jackson-datatype-jsr310 (Java 8+ 시간 타입 직렬화)
- React UI: Dashboard (latency scatter + recent traces + Live 모드 sliding window)
- React UI: Trace detail (수직 4단 레이아웃 — 노드 그래프 + 범례 + selected 카드, dagre `NODESEP 70 / RANKSEP 150`)
- Single jar distribution (server + agent + UI bundled, 단일 jar 2개)
- Sample app for verification (H2 + 시나리오 1~5)
- Documentation: agent options (PII 노출 경고 박스 포함), API contract, OTel attributes, NAS dogfooding 가이드

### Known limitations
- Single service only (MSA support in v0.3)
- Synchronous calls only (@Async / WebFlux support in v0.2)
- Method args captured as `arg0` (compile with -parameters for real names, v0.2 will improve)
- **JDBC param payload key 는 parameterIndex decimal string** (`{"1":...}`) — server-side FIELD_NAME 마스킹 룰 미작동 (password/token 평문 노출 가능). REGEX 강한 룰 (RRN/CARD) 은 정상 마스킹. v0.2 에서 column-name 추적 또는 value-based heuristic 보강 예정

## 작업 3: Apache 2.0 라이선스 헤더

모든 .java 파일 상단에 표준 헤더:
```
/*

Copyright 2026 ApiLens contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing
permissions and limitations under the License.
*/
```
자동 스크립트로 .java 파일 일괄 적용:
- 기존 헤더 있는 파일은 skip
- 없는 파일만 추가
- 모든 모듈 (apilens-common, apilens-agent, apilens-server, examples/sample-app)

.ts/.tsx 파일은 헤더 안 추가 (관례적으로 안 함, package.json의 license 필드로 충분).

## 작업 4: GitHub Actions CI

.github/workflows/ci.yml 신규 작성.

트리거: push to main, pull_request

작업:
1. backend-test
   - ubuntu-latest
   - JDK 21 setup
   - ./gradlew clean test
   - 결과 캐싱
2. frontend-build
   - ubuntu-latest
   - Node 20
   - cd apilens-ui && npm ci && npm run lint && npm run test && npm run build
3. integration-build (optional, 1번 + 2번 후)
   - npm run build → dist 산출
   - ./gradlew :apilens-server:bootJar
   - jar 안에 static/ 임베드 검증 (간단한 shell 스크립트)

배지:
- README 상단에 ![Build](https://github.com/{user}/ApiLens/actions/workflows/ci.yml/badge.svg)
- 사용자 GitHub username은 모름. README의 배지 URL은 placeholder {OWNER}/ApiLens로
  두고 release 시 실제 owner로 교체.

## 작업 5: docs/agent-options.md 보강

기존 문서 (Phase E3 에서 이미 `apilens.jdbc.capture-params` 항목 + PII 노출 경고 박스 추가됨) 에
다음 영역 추가:

### Security 권고
- `apilens.debug=true` 는 stderr 에 transform / advice 로그 출력. 운영 환경에서는
  false (기본값) 유지 권고. `[ApiLens][JDBC-PARAM]` 로그가 다수 출력될 수 있음.
- `apilens.server` URL 은 운영망 내부 주소 사용. 외부 노출 금지.
- agent 는 사용자 앱과 같은 JVM 에서 동작. PII 마스킹은 server-side 에서 적용되어
  default 마스킹 룰 (주민번호 / 카드번호 / password / token) 이 trace 본문에 적용됨.
  단 JDBC param payload 의 key 가 parameterIndex decimal string (`{"1":...}`) 이라
  이름 기반 룰은 미작동 — REGEX 강한 룰 (RRN/CARD) 만 정상 마스킹. 운영자가 의심
  컬럼이 있으면 `apilens.jdbc.capture-params=false` escape hatch 사용 또는
  custom REGEX 룰 추가 권장 (PII 노출 경고 박스 참조, v0.2 에서 보강 예정).

### 운영망 deployment 권장 옵션 표

| 환경 | capture-params | capture-result-set | debug | 비고 |
|---|---|---|---|---|
| 개발 / sample-app | true (default) | true | true | 시각 검증 + 로그 추적 |
| 스테이징 / QA | true | true | false | 운영자 가치 최대화 |
| 운영망 일반 | true (default) | false (default) | false | 메모리 / latency 안전 |
| 운영망 PII 의심 | **false** | false | false | escape hatch 적용 후 운영자 결정 |
| 고부하 hot-path | false | false | false | 런타임 비용 0 (advice weaving skip) |

### 운영 dogfooding 체크리스트 (NAS 등 운영망 첫 부착 시)

운영자가 NAS VAMS 같은 운영 시스템에 처음 agent 부착 시 다음을 확인:
1. **호스트 throw 0** — agent advice 의 try-catch silent drop 으로 host 앱 영향 0 보장.
   부착 직후 운영 앱 로그에 `ClassCastException` / `NoSuchMethodError` / `VerifyError`
   발생 시 즉시 `apilens.jdbc.capture-params=false` + `apilens.jdbc.capture-result-set=false`
   토글 또는 agent 전체 제거.
2. **메모리 누수 0** — WeakHashMap 기반 cache 라 정상 경로 leak 0. 운영 1시간 후
   heap usage 안정 확인.
3. **latency 영향 < 5%** — agent attached/detached 비교 (운영자 실측 권고).
4. **PII 노출 확인** — 첫 trace 5건의 PAYLOAD IN 본문에 평문 PII 있는지 시각 확인.
   있으면 server-side custom 룰 추가 또는 capture-params=false 적용.
5. **kill switch 검증** — `apilens.jdbc.capture-params=false` 재기동 시 PAYLOAD IN
   비어있는지 + advice weaving skip 확인.

## 작업 외 (G2 또는 v0.1.1 backlog)
- 자동화 통합 테스트 (Phase G2)
- npm audit fix dev deps 별도 PR
- v0.1.1 작은 fix: arg0 → 실제 인자, agent.startup noise, payload_in trace 컨텍스트
- CSP 헤더 (Security backlog)

## 검증 (사용자가 수행)

자동:
1. ./gradlew clean build  -- 라이선스 헤더 추가 후에도 빌드 정상
2. cd apilens-ui && npm run build  -- 변경 없음 확인
3. find . -name "*.java" -not -path "*/build/*" | xargs grep -L "Licensed under the Apache License"
   -- 출력 0 (모든 .java 파일에 헤더 있음)

수동:
[ ] README.md 한국어 섹션 운영자 관점 명확한지
[ ] README.md English 섹션 글로벌 OSS 표준 톤인지
[ ] CHANGELOG.md 첫 release 항목 가독성
[ ] CI workflow YAML 문법 (GitHub Actions 검증기로 확인 가능)
[ ] docs/agent-options.md Security 항목 운영자가 이해 가능

## 주의

- 라이선스 헤더 추가는 자동 스크립트로. 사용자가 직접 200+ 파일 손대지 말 것.
- README의 hero 이미지 파일 자체는 사용자가 별도 캡처. 작업에선 placeholder만.
- GitHub username 모름. {OWNER} placeholder 사용. 실제 release 시 일괄 교체.
- 본 phase는 docs + meta 위주. 코드 동작은 변경 없음.
- "테스트 통과" 단정 보고 금지.

작업 시작 전:
- 현재 README.md 구조
- 현재 docs/ 안 파일들
- 라이선스 헤더 자동 추가 스크립트 작성 (find + sed 또는 별도 .sh)