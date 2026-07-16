<!--
title: ApiLens Agent 옵션
owner: maintainer
last-reviewed: 2026-07-16
-->

# ApiLens Agent 옵션

운영자가 사용자 Spring Boot 앱에 ApiLens agent를 붙일 때 설정하는 JVM 시스템
프로퍼티 명세. agent args(`-javaagent:agent.jar=foo=bar`)는 사용하지 않는다 —
`-D` 시스템 프로퍼티로 통일 (디버깅 시 `jcmd` 등으로 확인 편함).

apilens.debug: 운영 환경에서는 비활성(false) 권장. true 시 PreparedStatement SQL 템플릿이 stderr에 누적되어 스키마 정보가 로그 파일에 남을 수 있음.

## 설치 / 실행 형태

```bash
java -javaagent:/path/to/apilens-agent.jar \
     -Dapilens.service.name=my-app \
     -Dapilens.server=http://localhost:8765 \
     -jar my-app.jar
```

## 옵션 표

실제 agent가 인식하는 시스템 프로퍼티는 다음 12개다.

| 옵션                                | 기본값                  | 타입    | 필수 | 설명                                                                |
| ----------------------------------- | ----------------------- | ------- | ---- | ------------------------------------------------------------------- |
| `apilens.service.name`              | — (없으면 agent 비활성) | String  | ✅   | 서비스 식별자 (한 호스트 = 한 service.name 권장). 누락/공백 시 agent disabled |
| `apilens.server`                    | `http://localhost:8765` | String(URL) |  | ApiLens server base URL. 끝 슬래시 무관. http/https만 허용          |
| `apilens.enabled`                   | `true`                  | boolean |      | `false` 시 agent 완전 비활성 (transport thread도 안 띄움)           |
| `apilens.sampling.rate`             | `1.0`                   | double  |      | head-based, [0.0, 1.0]. 범위 외/parse fail → 1.0으로 fallback       |
| `apilens.batch.max-size`            | `100`                   | int     |      | 한 HTTP POST 당 span 최대                                           |
| `apilens.batch.flush-interval-ms`   | `1000`                  | long    |      | 강제 flush 주기 (ms)                                                |
| `apilens.queue.capacity`            | `10000`                 | int     |      | 메모리 buffer 크기. 초과 시 silent drop (호스트 앱 보호 우선)       |
| `apilens.payload.max-bytes`         | `65536`                 | int     |      | payload 캡처 임계(64KB). 초과 시 truncate=true로 자름               |
| `apilens.debug`                     | `false`                 | boolean |      | `true` 시 stderr에 SQL 템플릿 등 상세 로그 누적 — 운영 비권장       |
| `apilens.jdbc.capture-result-set`   | `false`                 | boolean |      | **opt-in** — true 시 JDBC SELECT 결과(row)를 payload_out에 캡처. 위험 항목 참고 |
| `apilens.jdbc.capture-params`       | `true`                  | boolean |      | **default ON** — PreparedStatement 표준 12종 setter + addBatch 호출에서 파라미터 값을 PAYLOAD IN에 직렬화. 운영망 hot-path 오버헤드 회피용 escape hatch로 `false` 토글 가능. 비활성 시 advice 자체가 weaving 되지 않아 런타임 비용 0. 자세한 동작은 아래 항목 참고 |
| `apilens.instrument.exclude-packages` | `(없음)`               | String(콤마 목록) |  | **opt-in** — 계측에서 제외할 패키지 prefix 목록(콤마 구분). 미설정/빈 값이면 제외 없음(현 계측 그대로). 지정한 prefix 로 시작하는 클래스는 weaving 대상에서 빠져 span·payload 생성이 없다(weaving 시점 결정, 런타임 비용 0). 자세한 동작·가이드는 아래 항목 참고 |

## 검증 / 안전 동작 (모두 silent + agent disabled — 호스트 앱은 정상 시작)

| 입력                                       | 결과                                                                    |
| ------------------------------------------ | ----------------------------------------------------------------------- |
| `apilens.service.name` 누락 또는 공백      | agent disabled, stderr 한 줄 경고                                       |
| `apilens.enabled=false`                    | agent disabled (조용히)                                                 |
| `apilens.server` URL parse fail (`"abc"`)  | agent disabled, stderr 경고                                             |
| `apilens.server` 비-http/https (`ftp://…`) | agent disabled, stderr 경고                                             |
| `apilens.sampling.rate=1.5` 또는 `-0.1`    | 1.0으로 fallback, 경고. agent는 enabled 유지                            |
| `apilens.batch.max-size=abc` 또는 `0`      | 100으로 fallback, 경고. agent enabled 유지                              |
| 그 외 모든 예외 (premain 자체 fail 포함)   | stderr 한 줄, agent 비활성, 호스트 앱 정상 시작                         |

## 동작 메모

- agent는 daemon thread 1개(`apilens-sender`)만 띄움 — JVM 종료 막지 않음
- 종료 시 shutdown hook이 큐 잔여 span을 최대 2초 동안 flush 시도
- 모든 외부 의존성(ByteBuddy, Jackson)은 shadow jar에서 `io.apilens.agent.shaded.*`로 relocate되어 사용자 앱과 클래스 충돌 없음
- HTTP 전송은 JDK `java.net.http.HttpClient` 사용 — agent는 추가 외부 의존성 0

## 재시도 정책

- `2xx` → 성공
- `4xx` → drop (재시도 무의미; 잘못된 payload)
- `5xx` 또는 IO 실패 → 1초 대기 후 1회 재시도. 그래도 실패면 silent drop
- exponential backoff / 영속 큐는 향후 예정

## startup 검증

agent가 정상 시작하면 stderr에 한 줄 출력:

```
[ApiLens] ApiLens agent started: service=my-app, server=http://localhost:8765, samplingRate=1.0, batchMaxSize=100
```

이어서 hello span (`operationName=agent.startup`, `spanKind=INTERNAL`) 1건이
서버에 전송됨. 이걸 dashboard에서 확인하면 agent ↔ server 채널이 살아있다는
신호.

```bash
curl -s http://localhost:8765/v1/traces?service=my-app | jq
# → traces[0].rootOperation == "agent.startup"
```

> 서버에 API Key 인증을 켠 경우 위 `curl` 호출에는 `-H "Authorization: Bearer <키>"` 가
> 필요하다. 단 agent 적재 경로(`POST /v1/spans`)는 무인증 화이트리스트이므로 agent JVM
> 옵션에는 토큰이 필요 없다 (자세한 내용은 [setup.md](setup.md)의 "인증 (선택)" 절 참조).

## `apilens.jdbc.capture-result-set` (opt-in)

기본 비활성. 켜면 JDBC `executeQuery()`가 반환한 `ResultSet`을 agent가 가로채
모든 row를 미리 메모리에 읽고, caller에게는 같은 내용을 들고 있는 wrapper를
돌려준다. 결과는 trace 상세 화면의 PAYLOAD OUT 자리에 `{columns: [...], rows: [[...], ...]}`
JSON으로 표시된다.

### 켜기 전 알아야 할 위험 (운영자 책임)

- **wrapper는 표준 `ResultSet` API만 지원**: `next` / `getXxx` / `getMetaData`
  / `close` / `wasNull` / `getRow` / `findColumn` / `is{Before,After}First/First/Last`
  은 정상 동작. driver-specific `unwrap`(예: `OracleResultSet`), scrollable
  navigation(`absolute` / `previous`), row update API는 `SQLFeatureNotSupportedException`.
- **확인된 안전 조합**: MyBatis `TypeHandler`, Spring JDBC `RowMapper`, raw JDBC.
- **위험 가능 조합**: Hibernate 일부 path가 driver-specific unwrap을 호출.
  운영망 활성화 전 staging에서 본인 ORM과 검증 후 켤 것.
- **capture 자체 실패** 시 agent는 silent drop하지만 underlying ResultSet이
  이미 부분 진행된 상태로 caller에 노출됨 — caller가 row 일부를 못 받을 수
  있음. 이건 opt-in으로 명시 수용한 위험이다.

### 한계

- 한 SELECT 당 최대 100 row + `apilens.payload.max-bytes` 한도(기본 65,536 byte) 중 먼저 닿는 것까지만 capture. 초과 시 `db.rows_truncated=true` attribute.
- `wasNull()`은 마지막 `getXxx()` 호출 결과만 추적 (표준 ResultSet 동작).
- 변환(예: `getDate(int, Calendar)`)은 best-effort. 정확한 변환이 필요하면 옵션을 꺼서 raw driver에 위임할 것.

## `apilens.jdbc.capture-params` (default ON)

기본 활성. `PreparedStatement` 의 표준 12종 setter (`setString`, `setInt`,
`setLong`, `setDouble`, `setFloat`, `setBoolean`, `setBigDecimal`, `setDate`,
`setTime`, `setTimestamp`, `setBytes`, `setNull`) 와 `addBatch()` 호출이
agent 에 후킹되어 trace 상세 화면의 PAYLOAD IN 자리에 JSON 으로 떨어진다.
Spring Data JPA / Spring JDBC / MyBatis 모두 내부적으로 `PreparedStatement` 를
쓰기 때문에 사실상 모든 DB 호출의 파라미터가 보인다.

### 직렬화 정책

| JDBC 타입                              | PAYLOAD IN 형식                                            |
| -------------------------------------- | ---------------------------------------------------------- |
| `String` / `Number` / `Boolean`        | `toString()` 그대로                                        |
| `BigDecimal`                           | `toPlainString()` (지수 표현 회피)                         |
| `byte[]` (setBytes)                    | `[B@<lowercase-hex-prefix>]` — 최대 16 bytes(32 hex chars) |
| `null` (setNull 의 value 자체가 null)  | `NULL`                                                     |
| `java.sql.Date`                        | ISO-8601 (`YYYY-MM-DD`)                                    |
| `java.sql.Time`                        | ISO-8601 (`HH:MM:SS`)                                      |
| `java.sql.Timestamp`                   | ISO-8601 (`YYYY-MM-DDTHH:MM:SS[.fff]`)                     |
| `java.time.LocalDate/Time/DateTime`    | ISO-8601 (defensive — MyBatis TypeHandler 가 종종 사용)    |
| `java.time.Instant`                    | ISO-8601 (`...Z`)                                          |
| 그 외 타입                             | `<unknown:SimpleClassName>` (silent fall-through)          |

단일 호출의 결과 JSON 모양:
```json
{"1":"42","2":"John","3":"2026-05-14"}
```

`addBatch()` 가 끼어있으면 batch 묶음으로 직렬화 + `db.batch_size=N` attribute 부착:
```json
{"batch_size":3,"batch":[{"1":"a"},{"1":"b"},{"1":"c"}]}
```

### 끄는 법 (escape hatch)

```bash
-Dapilens.jdbc.capture-params=false
```

운영망에서 초당 수만 PreparedStatement 호출이 발생하는 hot-path 가 있고
오버헤드를 0 으로 만들어야 한다면 위 토글로 advice 자체를 끈다. `false` 로
시작한 JVM 에서는 advice 가 weaving 되지 않으므로 cache 도, setter 후킹도,
addBatch 후킹도 발생하지 않는다.

### 동작 보증

- 모든 advice 진입점에 `try-catch(Throwable)` + silent drop — 호스트 앱
  throw 0 단언 (`ClassCastException` / `NoSuchMethodError` / `VerifyError`
  포함).
- 캡처 캐시는 `WeakHashMap<PreparedStatement, ...>` — statement close 시
  자동 회수. execute exit 시점에 명시적 clear 도 호출하므로 정상 경로에서
  leak 0.
- 비표준 driver-specific setter (`setOracleObject`, `setPGobject`, etc.) 와
  12종 외 표준 setter (`setObject`, `setBlob`, etc.) 는 본 영역 밖 —
  매처 자체가 매치하지 않아 advice 진입 0건.

### ⚠️ PII 노출 경고 (현재 제약, 향후 개선 예정)

PAYLOAD IN 본문의 키는 **JDBC parameterIndex 의 decimal string** 입니다
(예: `{"1":"hong","2":"password123"}`). 이 구조는 server-side 기본 마스킹 룰
중 **이름 기반 룰 (`password|passwd|pwd|secret|token`) 이 매칭되지 않는다는**
의미입니다 — 룰이 키 이름을 fullmatch 로 검사하기 때문입니다.

영향:

| PII 유형 | 마스킹 결과 (현재) |
| -------- | ----------------- |
| 주민등록번호 (RRN), 카드번호 — REGEX 패턴 강한 룰 | ✓ 정상 마스킹 (값 자체가 정규식 매치) |
| password, token, secret — **이름 기반 룰** | ✗ **평문 노출** |

운영자 권장 조치:

1. 의심되는 운영망에서는 `apilens.jdbc.capture-params=false` 로 시작 (위 escape hatch 참조).
2. PreparedStatement 의 `?` 가 사용자 비밀번호 / API 토큰 / 세션 키 같은 컬럼에
   바인딩되는 경우, **server-side custom masking 룰을 REGEX 형태로 직접 추가**
   하시기 바랍니다 (예: `^[A-Za-z0-9_]{32,}$` 처럼 값의 형태로 매칭).
3. 향후 parameterIndex 키 구조에서도 작동하는 마스킹 룰 보강 예정
   (column 이름 추적 또는 value heuristic).

이 제약은 capture-params default ON 결정의 결과이며 구현 버그 아닙니다. 단
인지 없이 설치하는 운영자에게 위험할 수 있으므로 본 문단 명시.

## `apilens.instrument.exclude-packages` (opt-in)

기본 비어 있음(제외 없음). 운영자가 **계측 자체에서 빼고 싶은 패키지 prefix** 를 콤마로
나열하면, 그 prefix 로 시작하는 클래스는 advice weaving 대상에서 제외됩니다.

```bash
-Dapilens.instrument.exclude-packages=com.acme.noisy,com.acme.batch
```

위 예시는 `com.acme.noisy.*` / `com.acme.batch.*` 로 시작하는 클래스를 계측에서 뺍니다.

### 동작

- **weaving 시점에 결정 — 런타임 비용 0.** 제외된 클래스는 advice bytecode 가 아예
  합성되지 않으므로, 그 클래스의 메서드가 아무리 자주 호출돼도 span·payload 생성이
  일어나지 않고 마스킹·전송 경로도 타지 않습니다. 즉 "런타임에 필터링"이 아니라
  "처음부터 안 짜여" 있습니다.
- **prefix 시맨틱(경계 아님).** `com.acme` 는 `com.acme.Foo` 뿐 아니라 `com.acme2.Bar`
  도 매치합니다(순수 문자열 prefix). 좁게 지정하려면 `com.acme.` 처럼 끝에 점을 붙여
  경계를 명확히 하세요.
- **미설정/빈 값/공백/후행 콤마 → 제외 없음(현 계측 그대로).** 안전 폴백이므로 오타로
  빈 값이 들어가도 계측이 조용히 꺼지지 않습니다.

### exclude 대상 선정 가이드 (조상 패키지 제외 주의)

- **잎(leaf) 계층에만 쓰세요.** 잡음이 많은 특정 repository/batch 패키지처럼, 빠져도
  흐름 해석에 지장이 없는 말단 계층이 대상입니다.
- **하위 계층을 계속 보고 싶으면 그 조상 패키지를 exclude 하지 마세요.** 예를 들어
  Controller 를 exclude 하면서 Service/Repository 는 계속 계측하면, root Controller
  노드가 그래프에서 빠져 흐름이 끊겨 보입니다(고아 노드 자체로 무결성이 깨지진 않지만
  — 가장 가까운 계측된 조상이 부모가 됩니다 — UX 상 흐름 파악이 어려워집니다).

### 효과와 한계

- 이 옵션의 목적은 **불필요한 계측을 줄여** 대시보드 잡음과 저장 부담(span·payload
  생성, 적재 시 write lock 보유)을 낮추는 것입니다. 제외한 패키지만큼 그 물리적 부하가
  발생하지 않습니다.
- 실제로 얼마나 줄었는지(용량·유실률 변화)는 **본인 운영망에서 적용 전·후를 측정**해
  확인하세요. 트래픽·계측 대상 분포에 따라 달라지므로 문서가 정량 수치를 단정하지
  않습니다.
- setup wizard(설치 명령 생성기)에는 노출하지 않는 **고급 opt-in** 입니다. NAS 등
  운영망 JVM 의 `-D` 로 직접 지정하세요.

## Security 권고

본 절은 운영망에 ApiLens agent 를 적용할 때의 보안 권고를 단일 위치에서 안내합니다.

### apilens.debug 운영 비활성 권고

`apilens.debug=true` 는 stderr 에 PreparedStatement SQL 템플릿이 누적되어 스키마 정보가 로그 파일에 남을 수 있습니다. 운영 환경에서는 **비활성(`apilens.debug=false`, default) 권장**. 디버깅 필요 시 staging 에서만 활성 후 재현 끝나면 즉시 비활성.

### apilens.server URL 내부망 권장

`apilens.server` 는 NAS dogfooding 시 동일 호스트 (`http://localhost:8765`) 가정. 외부 공개 endpoint 사용 시 TLS 종단 (향후 `apilens.server.ca-cert` 옵션 추가 예정) 적용 후 사용 권장. 현재는 plain HTTP 만 지원합니다.

### agent 와 사용자 앱 같은 JVM 가정

ApiLens agent 는 사용자 앱과 **같은 JVM 프로세스** 안에서 동작합니다 (premain). 별도 sidecar 프로세스 / IPC 호출 없음. 본 가정의 결과로:

- agent 의 외부 의존성 (ByteBuddy, Jackson) 은 모두 `io.apilens.agent.shaded.*` 로 relocate 되어 사용자 앱 classpath 와 충돌 0
- agent 자체 메모리는 사용자 앱 heap 일부 사용 (default queue capacity 10000 span + payload max bytes 65536 기준 약 수십 MB)
- agent 가 OOM / GC 영향을 사용자 앱에 전가하지 않도록 queue 초과 시 silent drop (호스트 앱 보호 우선)

### 호스트 throw 0 절대 원칙

agent 코드 모든 진입점은 `try { ... } catch (Throwable t) { silent drop }` 패턴 의무. 본 원칙으로 agent 자체 장애가 호스트 앱에 영향 0 보장. 다음 영역 모두 호스트 throw 0 단언:

- 모든 advice 진입점 (`@Advice.OnMethodEnter` / `@Advice.OnMethodExit`)
- `capture-params` advice (PreparedStatement 표준 12종 setter + addBatch)
- `capture-result-set` wrapper (opt-in, ResultSet wrap)
- HTTP 전송 thread (`apilens-sender` daemon)
- 종료 hook (shutdown hook 큐 잔여 flush)

PII 마스킹 한계는 위 `## ⚠️ PII 노출 경고` 절을 참조하세요. 운영자 권장 의사결정:

1. 운영망에 PII 의심 컬럼 (password / token / secret / api-key 등) 이 PreparedStatement 의 `?` 에 바인딩되는지 사전 검토
2. 의심 시 즉시 `apilens.jdbc.capture-params=false` (escape hatch) 적용 — advice weaving 자체 비활성, 호스트 오버헤드 0
3. 또는 server-side custom masking 룰을 REGEX 형태 (`^[A-Za-z0-9_]{32,}$` 등) 로 추가

### Server-side 마스킹 엔진 우회 금지

agent 가 payload 를 server 로 송신할 때 server 의 마스킹 엔진 (`apilens-common` 공유 엔진) 을 반드시 통과합니다. agent 자체에서 DB 의 `payloads` 테이블에 직접 INSERT 하거나 마스킹을 우회하는 경로 0건. CI workflow 의 회귀 grep gate 가 본 단언을 자동 검증합니다.

## 운영망 deployment 권장 옵션 (5 환경)

본 표로 운영자가 자기 환경을 정확히 1개로 매핑할 수 있습니다. 매핑 불가능 시 (예: "운영망인데 PII 의심 + 고부하" 동시) → **보안 우선 row 채택** (운영망 PII 의심).

| 환경 | `apilens.jdbc.capture-params` | `apilens.jdbc.capture-result-set` | `apilens.sampling.rate` | `apilens.payload.max-bytes` | `apilens.instrument.exclude-packages` |
|---|---|---|---|---|---|
| 개발 (local) | `true` (default) | `true` (디버깅용 opt-in) | `1.0` | `65536` (default) | `(없음)` |
| 스테이징 | `true` (default) | `false` (default, 검증 후 활성 가능) | `1.0` | `65536` | `(없음)` |
| 운영망 일반 | `true` (default) | `false` | `1.0` (저트래픽 가정) | `65536` | `(없음)` |
| 운영망 PII 의심 | **`false` (escape hatch)** | `false` | `1.0` | `65536` | `(없음)` |
| 고부하 hot-path | **`false` (오버헤드 0)** | `false` | `0.1` 또는 그 이하 | `16384` (축소) | 잡음 leaf 패키지 예: `com.acme.batch,com.acme.noisy` |

> NAS 디스크 절약을 위한 데이터 보존 기간(retention)은 agent 옵션이 아니라 **서버측 설정**입니다.
> 서버 설정 페이지에서 보존 정책을 조정하세요.

## 운영 dogfooding 체크리스트 (NAS 등 운영망 첫 부착 시)

### 1. host throw 0 확인

- [ ] agent 기동 후 사용자 앱 stack trace 에 `io.apilens.*` 등장 0 hit 확인
- [ ] agent stderr 에서 `Throwable` / `Exception` 단어 등장 시 → 즉시 escape hatch (`apilens.enabled=false`) 적용 + GitHub Issue 등록 권장

### 2. 메모리 누수 0 확인

- [ ] agent 기동 후 24시간 dogfooding 동안 사용자 앱 heap 사용량 base line 대비 +수십 MB 이내 (queue capacity 10000 + payload max bytes 65536 기준)
- [ ] `jstat -gcutil <pid> 60000` 으로 old gen 누적 증가율 측정 — base line 대비 차이 없음 확인
- [ ] PreparedStatement 캡처 cache (`WeakHashMap<PreparedStatement, ...>`) 가 statement close 시 자동 회수 — leak 0 단언

### 3. latency 영향 < 5% 확인

- [ ] agent 미부착 base line 의 controller p95 latency 와 agent 부착 후 p95 latency 차이 < 5%
- [ ] 측정 방법: 사용자 앱의 healthcheck endpoint 에 50 req 부하 후 평균 응답시간 비교
- [ ] 5% 초과 시 → `apilens.sampling.rate=0.1` 또는 `apilens.jdbc.capture-params=false` 로 튜닝

### 4. PII 노출 확인

- [ ] PreparedStatement 의 `?` 가 사용자 비밀번호 / API 토큰 / 세션 키 같은 컬럼에 바인딩되는지 사전 검토
- [ ] dashboard 의 trace 상세에서 PAYLOAD IN 본문에 평문 PII 노출 0 hit 확인 — 발견 시 `apilens.jdbc.capture-params=false` 적용 후 server-side custom masking 룰 추가
- [ ] 자세한 한계는 위 `## ⚠️ PII 노출 경고` 절 참조

### 5. kill switch 검증

- [ ] `-Dapilens.enabled=false` 토글 시 agent 완전 비활성 (transport thread 도 안 띄움) — `jps` 또는 thread dump 로 `apilens-sender` daemon thread 부재 확인
- [ ] `-Dapilens.jdbc.capture-params=false` 토글 시 advice 자체 비활성 — weaving 0 + cache 0 + 호스트 오버헤드 0 (advice 진입 자체가 발생하지 않음)

> 위 5 항목 모두 PASS 후 dogfooding sign-off. 임의 1건 fail 시 → "실패한 dogfooding" 으로 GitHub Issue 등록 권장 (NAS OS / Java 버전 / Spring Boot 버전 / agent stderr 마지막 30 line / `apilens.debug=true` 토글 후 재실행 결과 포함).

## 향후 확장 후보 (현재 미구현)

- agent args 기반 옵션 파싱 (시스템 프로퍼티와 병행)
- 마스킹 룰 client-side 적용 toggle
- exponential backoff + persistent retry queue
- TLS 인증서 옵션 (`apilens.server.ca-cert`, …)
- agent 자체 health endpoint
- 계측 include 필터(`exclude-packages` 의 대응 — 지정 패키지만 계측). 현재는 축소 레버로 exclude 만 제공
- 계측 2차 레버: 최소 duration 필터(짧은 span drop) · INTERNAL/payload-off 토글
- 계측 옵션 UI 설정 페이지 노출(현재는 JVM `-D` 전용 고급 opt-in)
