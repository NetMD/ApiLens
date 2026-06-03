# ApiLens — Claude Code 컨텍스트

이 파일은 Claude Code가 프로젝트 컨텍스트를 빠르게 파악하도록 돕습니다.
일반 README는 [README.md](./README.md) 참고.

## 프로젝트 정체성

**ApiLens**는 한국 SI 운영자를 위한 가벼운 Spring Boot 호출 추적 도구입니다.

- **타깃 사용자**: 한국 SI 운영자 (개발자가 아니라 운영자가 직접 깔아서 씀)
- **핵심 가치**: 운영망에서 "어디서 끊겼나 / 뭐가 흘렀나" 한 화면에. Datadog/Pinpoint 같은 본격 APM의 대체재 아님 — "운영자가 결재 없이 직접 깔고 싶은 작은 도구"
- **라이선스**: Apache 2.0, 무료 오픈소스 (donation only)
- **배포 모양**: 단일 jar (agent + collector + storage + UI 통합)

## 절대 변경하지 말아야 할 결정 사항

이 결정들은 길게 논의해서 확정한 사항. 사용자가 명시적으로 바꾸자고 하기 전엔 변경 금지:

1. **백엔드: Java 21 + Spring Boot 3.4 + Gradle (Kotlin DSL)**
2. **DB: SQLite + Flyway 마이그레이션**
3. **Agent: ByteBuddy + premain + shadow jar (의존성 relocate 필수 — 사용자 앱과 클래스 충돌 방지)**
4. **UI: React + Vite + TypeScript** (별도 Gradle 빌드 아님, npm으로 빌드 후 server resources/static에 복사)
5. **모노레포 구조**: `/apilens-common`, `/apilens-agent`, `/apilens-server`, `/apilens-ui`
   - 현재 `apilens-ui`는 아직 생성 전. 추가 시 Vite + React + TS 프로젝트로 만들고 Gradle 빌드 대상에서는 제외 ([settings.gradle.kts:9](settings.gradle.kts#L9))
6. **단일 jar 배포**: server jar 안에 agent jar + UI 정적 파일 packaging
7. **포트 8765** (사용자 앱이 보통 8080을 쓰므로 회피)
8. **패키지: `io.apilens.*`**
9. **버전: `0.1.0-SNAPSHOT`**

## 데이터 모델 (5개 테이블, 변경 신중히)

`apilens-server/src/main/resources/db/migration/V1__initial_schema.sql` 참고.

- `traces` — 리스트 화면 빠른 조회용 요약 (root_operation, duration, status 등)
- `spans` — OpenTelemetry 호환 span 트리 (parent_span_id로 부모-자식)
- `payloads` — 별도 분리 (큰 사이즈, 마스킹 적용 후 저장)
- `masking_rules` — default(is_default=1)와 custom 모두. default는 비활성만 가능, 삭제 불가
- `retention_meta` — nightly cleanup 메타

스키마 변경은 항상 새 마이그레이션 파일 추가 (`V2__*.sql`). 기존 V1 수정 금지.

## v0.1 범위

스코프 요약(✅/❌)은 [README.md](./README.md) 참고. 핵심 차이점:
- **마스킹은 server-side 적용** (agent에서 raw payload 보내고 server에서 정규화). agent 옵션으로 client-side toggle은 v0.2+
- **단일 서비스 trace만**. MSA cross-service propagation은 v0.3
- **JDBC instrument는 PreparedStatement 우선**. raw Statement는 best-effort

## 아키텍처 핵심 원칙

- **마스킹은 `apilens-common`의 공유 엔진** — agent와 server가 같은 엔진 사용. 결과 일관성 필수
- **Agent는 가볍게** — 사용자 앱과 클래스 충돌 절대 금지. 모든 외부 의존성 (ByteBuddy, Jackson)은 shadow jar에서 relocate ([apilens-agent/build.gradle.kts:29-30](apilens-agent/build.gradle.kts#L29-L30))
- **Agent 자체 장애가 호스트 앱에 영향 0** — 모든 agent 코드는 try-catch로 감싸고 실패 시 silent drop. 절대 호스트 thread block 안 함
- **W3C Trace Context (`traceparent` 헤더) 표준** — 자체 포맷 만들지 말 것
- **Trace 모델은 OpenTelemetry 호환** — 나중에 Jaeger export 가능하도록
- **server jar = 단일 배포 산출물** — build 시 `:apilens-agent:shadowJar` 결과를 `resources/main/agent/apilens-agent.jar`로, `apilens-ui/dist`를 `resources/main/static`으로 임베드 ([apilens-server/build.gradle.kts:29-55](apilens-server/build.gradle.kts#L29-L55)). UI 미빌드 시 server는 경고만 띄우고 정상 기동

## UI 디자인 철학

기존 APM 도구와 차별화 포인트:

- **노드 그래프 (mind-map 스타일)** — gantt chart 안 씀. 운영자는 시간 분석보다 "흐름과 끊긴 지점"이 궁금
- **수평 시간 흐름** — 왼쪽=시작점(브라우저), 오른쪽=DB/외부 API. 수직 레이아웃 절대 안 씀 (사용자 명시적 결정)
- **node duration은 시각적으로 강조 안 함** (단일 서비스 trace에서). MSA에선 서비스별 시간만 표시
- **에러 시 stack trace 즉시 표시** — 노드가 빨갛게 멈추고 옆에 에러 박스
- **마스킹 라이브 프리뷰** — 룰 토글 시 샘플 페이로드 즉시 반영. 결재용 신뢰 도구

## 코드 컨벤션

- Java 21 features 적극 활용 (records, pattern matching, virtual threads — agent에선 신중히)
- DTO는 record 우선
- null-safety: 가능한 곳에서 `Optional` 또는 명시적 null 체크
- 주석은 한국어 (이 프로젝트 타깃이 한국 SI라 한국어 메인). 단 클래스/메서드 javadoc은 영어 (글로벌 contributor 가능성)
- 로그 메시지는 영어 (운영 환경 표준)
- 테스트는 JUnit 5, Mockito (필요 시)

## 빌드 명령

> **⚠️ Gradle wrapper 미생성 상태.** 최초 1회 `gradle wrapper --gradle-version 8.11.1` 실행 필요 (시스템 gradle 8.11+ 설치 전제).
> 이후 명령은 모두 `./gradlew` 사용.

```bash
./gradlew build                                       # 전체 빌드 + 테스트
./gradlew :apilens-server:bootRun                     # server 실행 (포트 8765, ./apilens.db 자동 생성)
./gradlew :apilens-agent:shadowJar                    # agent jar만 빌드 (relocate 적용)
./gradlew :apilens-server:test                        # 특정 모듈 테스트
./gradlew :apilens-server:test --tests "FQCN.method"  # 단일 테스트
./gradlew clean                                       # 산출물 정리
```

UI 빌드는 `apilens-ui/`에서 `npm run build` 별도 실행 → `dist/`가 server `processResources` 시 자동 임베드.

## 관련 문서

- API 계약 (5개 endpoint, 요청/응답 예시): [docs/api.md](docs/api.md)
- Span attribute 키 명세 (OTel semantic conventions): [docs/otel-attributes.md](docs/otel-attributes.md)
- Agent 옵션 명세 (JVM 시스템 프로퍼티): [docs/agent-options.md](docs/agent-options.md)
- 아키텍처 결정: `docs/architecture.md` (TBD)
- 마스킹 룰 명세: `docs/masking-rules.md` (TBD)

## 사용자 컨텍스트 (Claude Code가 알면 좋은 것)

- 사용자는 한국인 솔로 개발자, Spring Boot 7~15년 경험
- 직설적인 커뮤니케이션 선호. 환상이나 거품 빼고 정직한 평가 원함
- 재정 안정 우선 — donation 모델로 수익 기대 거의 없음을 인지하고 시작. ApiLens는 포지션 빌딩과 다음 기회 위한 작업
- LogLens (Spring Boot 로그 분석 데스크톱 앱, Tauri+React+TS)를 먼저 출시했고 ApiLens가 두 번째 OSS 프로젝트

## Build 설정 lessons (Phase D 후속에서 학습)

다음 함정들은 한 번 데였고 다시 데이지 말 것:

### 1. Shadow jar 모듈은 project() 의존성으로 쓰지 말 것
Shadow jar는 의존 모듈의 클래스까지 흡수하면서 relocate 규칙을 함께 적용한다.
다른 모듈이 그걸 testImplementation(project(":shaded-module"))로 의존하면,
testRuntimeClasspath에 raw 클래스 + relocated 클래스가 둘 다 들어가 충돌
(NoSuchMethodError, 시그니처는 같지만 파라미터 타입 패키지가 다름).

→ 규칙: shadow jar 빌드되는 모듈(apilens-agent)을 다른 모듈의 project() 의존성으로
   쓰지 말 것. 통합 테스트가 필요하면 shadow 모듈 안에 두고, 반대 방향
   (agent → testImplementation(project(":server"))) 으로 의존받기.

### 2. Spring Boot 플러그인은 plain jar 기본 비활성
spring-boot 플러그인이 적용된 모듈은 bootJar(fat) 만 산출하고 일반 jar는 disabled.
다른 모듈이 그걸 project() 로 의존하면 클래스를 못 받음.

→ 그 모듈을 의존성으로 노출하려면:
   tasks.jar { enabled = true; archiveClassifier.set("plain") }
   bootJar와 파일명 충돌 회피 위해 classifier 필수.

### 3. implementation()은 consumer에 transitive 노출 안 됨
A 모듈이 implementation(libs.flyway.core) 로 선언하면, B 모듈이
project(":A") 로 의존해도 B에서 Flyway 클래스 직접 참조 불가.

→ B에서 직접 참조하는 클래스의 의존성은 B의 build.gradle.kts에 명시 추가.
   또는 A에서 api() 로 선언하면 transitive 노출됨 (단, 캡슐화 손해).