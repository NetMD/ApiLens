Phase H 후속 #2 — apilens-agent 가 instrument 대상 앱의 file 을 0바이트로 truncate (Jackson serialize side effect, multi-layer defense + 회귀 테스트)

## 컨텍스트

CLAUDE.md 먼저 읽고 시작. R10 Phase H 후속 (Setup wizard + ActiveServices 6 카테고리 회수, 2026-05-26 01:35 종료) 직후 사용자가 release tag 직전 dogfooding 의 일환으로 VAMS (Spring Boot Maven) 에 ApiLens agent 를 부착해 검증하던 중 **P0 데이터 손실 버그** 를 발견.

본 R11 은 v0.1.0 release tag 진입을 가로막는 release blocker 단독 의제. R10 의 다음 권장 작업 (R11 = release-gate phase, V-USER-1 + W-01 CHANGELOG + NAS dogfooding 3 게이트) 은 본 fix 후 **R12 로 한 phase 밀림** (의제 재정렬, journal 2026-05-26T03-02-24 §4 명문).

## P0 BUG 한 줄 요약

`AdviceSupport.serializeReturn` 이 `ResponseEntity<ResourceRegion>` body 를 Jackson `ObjectMapper.writeValueAsString` 으로 직렬화하다가 `FileSystemResource.getOutputStream()` 을 호출 → `Files.newOutputStream(path)` (= `WRITE | CREATE | TRUNCATE_EXISTING`) 이 발동되어 **타깃 앱이 응답으로 내보내려던 mp4 원본 파일이 0바이트로 잘림**.

**CLAUDE.md 핵심 원칙 정면 위배**:
> "Agent 자체 장애가 호스트 앱에 영향 0 — 모든 agent 코드는 try-catch 로 감싸고 실패 시 silent drop. 절대 호스트 thread block 안 함"

이 버그는 단순 장애가 아니라 **호스트 앱의 데이터를 파괴**. v0.1.0 release tag 절대 금지 상태.

## 재현 (100% 재현 / agent 비활성 시 100% 해결)

### 타깃 앱
- VAMS (Spring Boot Maven, 비디오 스트리밍 컨트롤러 보유)
- 컨트롤러 시그니처:
  ```java
  @GetMapping("/{videoId}/stream")
  public ResponseEntity<ResourceRegion> streamVideo(...) {
      Resource resource = new FileSystemResource(video.getFilePath());
      long contentLength = resource.contentLength();
      ResourceRegion region = new ResourceRegion(resource, start, length);
      return ResponseEntity.status(httpStatus).body(region);
  }
  ```

### JVM 옵션
```
-javaagent:/Users/netmd/.apilens/apilens-agent.jar
-Dapilens.service.name=vams
-Dapilens.server.url=http://localhost:8765
-Dapilens.capture.params=true
-Dapilens.capture.resultset=true
```

### 재현 절차
1. VAMS 에 위 JVM 옵션으로 부착 후 기동
2. 클라이언트가 `/api/videos/{id}/stream` 을 GET
3. 약 1~2초 뒤 디스크의 원본 mp4 가 0바이트로 truncate
4. `sudo fs_usage -w -e -f filesys | grep mp4` 로 확인 시 동일 java 프로세스의 thread 가 `_WC_T` (`O_WRONLY | O_CREAT | O_TRUNC`) 플래그로 mp4 를 open 하는 것이 잡힘

### 검증 (release blocker 해제 조건)
```bash
SIZE_BEFORE=$(stat -f %z /path/to/video.mp4)
curl -s "http://localhost:8088/api/videos/628/stream" -H "Range: bytes=0-1023" >/dev/null
SIZE_AFTER=$(stat -f %z /path/to/video.mp4)
test "$SIZE_BEFORE" = "$SIZE_AFTER" && echo OK || echo FAIL
```

## 근본 원인 (사용자 분석 완성 — verbatim 인용)

### 코드 anchor 1 — `AdviceSupport.serializeReturn` (line 384-414)
`ApiLens/apilens-agent/src/main/java/io/apilens/agent/instrument/AdviceSupport.java`

- `unwrapResponseEntity(returnValue)` 로 `ResponseEntity.getBody()` 호출 → `ResourceRegion` 인스턴스 반환
- `InstrumentationInstaller.MAPPER.writeValueAsString(toSerialize)` 로 직렬화
- Jackson 은 기본 설정에서 public getter 를 모두 traverse → `ResourceRegion.getResource()` → `FileSystemResource` → **`getOutputStream()`** 호출
- `FileSystemResource.getOutputStream()` 내부 구현이 `Files.newOutputStream(path)` 이며, `OpenOption` 미지정 시 기본값 `CREATE + TRUNCATE_EXISTING + WRITE` 가 적용되어 **그 자리에서 파일이 0바이트로 잘림**
- 그 후 Jackson 은 OutputStream 을 직렬화 불가로 throw 하지만, 파일은 이미 truncate 된 상태로 close

### 코드 anchor 2 — `InstrumentationInstaller.MAPPER` (line 89-91)
`ApiLens/apilens-agent/src/main/java/io/apilens/agent/instrument/InstrumentationInstaller.java`

```java
MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
```
→ 위험 getter 에 대한 가드 없음.

## 영향 범위

같은 `AdviceSupport.serializeReturn` 을 호출하는 모든 advice:
- `instrument/advice/ControllerAdvice.java:87`
- `instrument/advice/ServiceAdvice.java:67`
- `instrument/advice/RepositoryAdvice.java:69`

위험 타깃:
- `org.springframework.core.io.Resource` 구현체 (특히 `WritableResource` = `FileSystemResource`, `PathResource`)
- `org.springframework.http.converter.ResourceRegion`
- `java.io.InputStream` / `OutputStream` / `Reader` / `Writer`
- `org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody`
- `org.springframework.web.multipart.MultipartFile`

추가로 `serializeArgs` (line 341-382) 도 같은 `MAPPER.writeValueAsString(shape)` 사용. 현재는 servlet 타입 (HttpServletRequest/Response) 만 skip → controller 메서드 인자에 Resource/MultipartFile/InputStream 이 들어오면 동일 위험.

## 사용자 결정 사항 (비협상, 변경 금지)

### 1. multi-layer defense 3 layer 모두 적용 (단일 layer 만 의존 금지)

agent 안전성은 단일 차단점 의존이 절대 금지. 3 layer 모두 적용으로 한 layer 실패 시도 호스트 앱 데이터 보호.

#### Layer 1 — `serializeReturn` / `serializeArgs` 에서 위험 타입 사전 skip (최우선)

`AdviceSupport.java` 에 helper 추가하고 두 serializer 에서 호출:

```java
/**
 * Jackson 에 넘기면 부수효과 (특히 file truncate) 를 일으킬 수 있는 타입인지 판별.
 * - WritableResource.getOutputStream() → Files.newOutputStream(path) → CREATE+TRUNCATE_EXISTING
 *   (FileSystemResource, PathResource 등이 해당)
 * - Stream/Reader/Writer 류는 직렬화도 불가능하고 consume 시 side effect 발생
 */
private static boolean isUnsafeToSerialize(Object v) {
    if (v == null) return false;
    Class<?> c = v.getClass();
    // class 이름 기반 매칭 (agent 는 Spring 의존성 없음)
    String n = c.getName();
    if (n.startsWith("org.springframework.core.io.")) return true;             // Resource 군 전체
    if (n.equals("org.springframework.http.converter.ResourceRegion")) return true;
    if (n.startsWith("org.springframework.web.multipart.")) return true;       // MultipartFile
    if (n.contains("StreamingResponseBody")) return true;
    if (v instanceof java.io.InputStream
            || v instanceof java.io.OutputStream
            || v instanceof java.io.Reader
            || v instanceof java.io.Writer
            || v instanceof java.nio.channels.Channel
            || v instanceof java.nio.file.Path
            || v instanceof java.io.File) {
        return true;
    }
    return false;
}
```

- `serializeReturn`: `unwrapResponseEntity` 직후 `isUnsafeToSerialize(toSerialize)` 면 placeholder 반환:
  ```java
  if (isUnsafeToSerialize(toSerialize)) {
      return "{\"_apilens\":\"streaming-body-skipped\",\"type\":\""
              + toSerialize.getClass().getName() + "\"}";
  }
  ```
- `serializeArgs`: 루프 안에서 `isUnsafeToSerialize(a)` 면 `shape.put("arg" + i, "[skipped:" + cn + "]")` 처리.

#### Layer 2 — `unwrapResponseEntity` 보강

ResponseEntity body 가 위험 타입이면 바로 placeholder string 으로 치환해서 Jackson 에 안 넘기게.

#### Layer 3 — Jackson MAPPER 자체에 가드 추가 (`InstrumentationInstaller.MAPPER`)

`org.springframework.core.io.Resource` 인터페이스에 대해 MixIn 으로 `getOutputStream`, `getInputStream`, `getFile`, `getURI`, `getURL` 을 `@JsonIgnore` 처리.

agent 가 Spring class 를 직접 import 할 수 없으므로 reflection 으로 클래스 로드 + 존재할 때만 MixIn 등록:

```java
try {
    Class<?> resourceCls = Class.forName(
            "org.springframework.core.io.Resource", false, getClass().getClassLoader());
    MAPPER.addMixIn(resourceCls, ResourceMixIn.class);
} catch (ClassNotFoundException ignore) { /* Spring 없는 앱 */ }
```

```java
abstract class ResourceMixIn {
    @com.fasterxml.jackson.annotation.JsonIgnore abstract Object getOutputStream();
    @com.fasterxml.jackson.annotation.JsonIgnore abstract Object getInputStream();
    @com.fasterxml.jackson.annotation.JsonIgnore abstract Object getFile();
    @com.fasterxml.jackson.annotation.JsonIgnore abstract Object getURI();
    @com.fasterxml.jackson.annotation.JsonIgnore abstract Object getURL();
}
```

추가로 `SerializationFeature.FAIL_ON_EMPTY_BEANS` 를 disable, `MapperFeature.USE_GETTERS_AS_SETTERS` 등도 안전 점검 권장.

### 2. 회귀 방지 단위 테스트 2건 신규 필수

CI 에 fs_usage 류 통합 테스트는 무리이므로 단위 테스트 2건으로 회귀 영구 봉인:

- **Test 1**: `AdviceSupportSerializeReturnSafetyTest`
  - `Resource` mock (반환 시 OutputStream getter 가 호출되면 `RuntimeException("must not be invoked")` throw 하도록) 을 만들고 `serializeReturn(new ResponseEntity<>(mockResource, OK))` 호출 → mock 의 OutputStream getter 가 호출되지 않아야 함
- **Test 2**: 실제 임시 파일을 만들고 `FileSystemResource` 로 감싸서 `serializeReturn` 호출 후 파일 크기가 변하지 않는지 assertion

### 3. cross-stack 영향 평가 — 위 3 advice 단위 lock-in 회귀 가드 동봉

dev-backend.ApiLens EXT-005 (R10 신규, S-24 첫 발동) 의 직접 적용 영역. 3 advice (Controller/Service/Repository) 동시 영향이므로 각 advice 변경마다 본 P0 BUG 같은 회귀 0 hit grep 패턴 박제 의무.

### 4. dogfooding 회귀 0 의무

- 본 fix 적용 후 VAMS dogfooding 재실행
- `/api/videos/{id}/stream` 호출 후 mp4 사이즈 보존 (위 검증 스크립트 PASS)
- 동일 시나리오 + 다른 ResourceRegion endpoint 추가 검증 (사용자가 별도 확인)

### 5. 본 라운드는 회수 라운드 — 신규 기능 0

R11 = P0 BUG fix 단독. 새 advice 추가 / 새 MAPPER 옵션 신설 / 새 instrumentation 도입 모두 금지. 기존 advice 안전성 회수만.

## 작업 범위 — Backend (agent) only

본 라운드는 UI / docs / DB 영향 0. `apilens-agent` 모듈 단독 변경.

### 변경 파일 추정 (architect 가 ground truth grep 확정)

- `apilens-agent/src/main/java/io/apilens/agent/instrument/AdviceSupport.java` (변경) — Layer 1 + Layer 2
- `apilens-agent/src/main/java/io/apilens/agent/instrument/InstrumentationInstaller.java` (변경) — Layer 3 (MAPPER MixIn 등록)
- `apilens-agent/src/main/java/io/apilens/agent/instrument/ResourceMixIn.java` (신규) — Layer 3 MixIn 정의
- `apilens-agent/src/test/java/io/apilens/agent/instrument/AdviceSupportSerializeReturnSafetyTest.java` (신규) — 회귀 봉인 단위 테스트 2건
- `apilens-agent/src/test/resources/` (필요 시) — Test 2 임시 mp4 fixture (또는 in-test 동적 생성)

3 advice 클래스 (ControllerAdvice / ServiceAdvice / RepositoryAdvice) 본문 변경 0 — 모두 `AdviceSupport.serializeReturn/Args` 호출만 하므로 helper 측 fix 로 자동 흡수.

### Ground truth 확정 의무 (architect §0.7)

본 라운드 시작 전 ground truth grep 으로 확정:

1. `AdviceSupport.serializeReturn` 실제 시그니처 + line 위치 (사용자 분석은 line 384-414)
2. `unwrapResponseEntity` 실제 시그니처 + body 추출 방식
3. `InstrumentationInstaller.MAPPER` 정확 위치 (line 89-91 추정)
4. 3 advice 정확 시그니처 + serializeReturn 호출 위치
5. Jackson 의존성 버전 + MixIn API 가용성 (shadow jar relocate 후 패키지 경로 정합)
6. CLAUDE.md "shadow jar relocate 함정" — MixIn 등록이 relocate 된 Jackson 패키지로 정합되어야 함

## QA / Security / review-arch / review-plan 의무

### QA
1. **자동 검증 게이트**:
   - `./gradlew :apilens-agent:test` (기존 145 + 신규 2 ≥ 147 PASS, 회귀 0)
   - `./gradlew :apilens-agent:shadowJar` (relocate + MixIn 패키지 정합 검증)
   - `./gradlew test` 전체 (242 + 신규 2 = 244+ PASS)
2. **3 layer 적용 정합 grep**:
   - Layer 1: `isUnsafeToSerialize` 정의 1건 + `serializeReturn` / `serializeArgs` 두 곳 호출 hit
   - Layer 2: `unwrapResponseEntity` 보강 hit
   - Layer 3: `addMixIn(resourceCls, ResourceMixIn.class)` reflection hit
3. **단위 lock-in 회귀 가드 grep** (S-24/EXT-005 적용):
   - 정방향: 신규 2 단위 테스트가 "OutputStream getter 호출 안 됨" / "파일 사이즈 보존" 정방향 동사로 명시
   - 반대 방향: `assertThrows(IOException)` 으로 "side effect 가 일어남" 을 lock-in 하는 패턴 0 hit
4. **본질 의도 보존 검증**:
   - 정상 케이스 (DTO record / Map / String 반환) 직렬화 회귀 0 — placeholder 안 박힘
   - 정상 케이스 trace payload 가 dashboard 에서 그대로 보임
5. **dogfooding 회귀 0**:
   - VAMS 부착 + `/stream` 호출 + mp4 사이즈 보존 (위 §검증 스크립트 PASS)

### Security
- 본 fix 가 새 attack surface 도입 0 (agent 내부 helper + Jackson MAPPER 옵션만)
- placeholder 응답 `{"_apilens":"streaming-body-skipped","type":"..."}` 가 ApiLens server payload 로 전송 — 운영자 본인 데이터, leak 없음
- ResourceMixIn 의 reflection `Class.forName` 은 Spring 클래스만 대상 (path traversal 없음)
- shadow jar relocate 후 MixIn 등록 정합 — relocate 안 된 Jackson 클래스로 등록되면 host 앱 Jackson 과 충돌 가능 (architect 가 정합 검증 의무)

### review-arch (자기증명 grep 9축, S-25/EXT-003 두 번째 발동)
- 9번째 축 (잘못된 lock-in 패턴 0 hit) 직접 grep:
  - `assertThrows(IOException` 0 hit (정상 case 가 IOException throw 하지 않아야 함)
  - 직접 `Files.newOutputStream(` 0 hit (Layer 1 이후 호출 안 일어남)
  - `getOutputStream()` 직접 호출 0 hit (advice 코드 영역)
- 8 축 (기존): anchor verbatim / Plan AC / 비협상 라벨 / 정방향 동사 / 반대방향 0 hit / cross-stack parity (해당 없음) / Ground truth 정정 / V-USER 없음 (본 라운드 시각 영역 아님) / R9·R10 본체 회귀 0

### review-plan (4 출처 release blocker 0 누적 표, G1-C 세 번째 발동)
- Planner self-증명: 본질 의도 단일 (P0 BUG fix) 5 단계 전파 100%
- QA: 자동 게이트 + 회귀 grep + 본질 의도 정상 케이스 보존 + dogfooding PASS
- Security: 새 attack surface 0 + relocate 정합
- Review-arch: 9축 grep 직접 hit 검증

## R11 라운드 정체성 선언 (PM §0 EXT-003 적용)

- 신규 기능 0 + 회수 라운드 + agent 안전성 영역 (UI / wizard 영역과 직교)
- R10 회수 6건 (D-H10-01~06) 보존 — 본 R11 은 agent advice 영역 단독, R10 변경 영역과 0 line 충돌
- R9 본체 28 결정 보존 — 회귀 0
- V-USER-R10-01~05 5건 sign-off 보존 (재검토 금지, EXT-005 절차 3)
- 본 P0 BUG fix 자체에 새로운 사용자 비협상 결정 추가 (D-P0-01~05 5건 verbatim 박제)

## docs 영향 (선택, architect 결정)

- `docs/troubleshooting.md` 신규 또는 추가: OS syscall trace 가이드 (`fs_usage -w -e -f filesys | grep mp4`) — 사용자 직접 진단 시 활용 가능 (저널 §사건 3 §"발견 경로" 직접 인용)
- `CHANGELOG.md` 본 R11 P0 BUG fix 항목 — release-plan agent (S-27 R10 신규) 가 R12 release-gate phase 에서 합산 (본 R11 단독 CHANGELOG 갱신 X)

## 검증 (사용자가 수행)

자동:
1. `./gradlew :apilens-agent:test` — 기존 145 + 신규 2 ≥ 147 모두 PASS
2. `./gradlew :apilens-agent:shadowJar` — relocate + MixIn 정합 PASS
3. `./gradlew test` 전체 회귀 0
4. (자동 가능 시) shadow jar 안에 relocate 된 `ResourceMixIn` 클래스 존재 확인

수동 dogfooding (사용자 본인 환경):
- [ ] VAMS 에 새 agent jar 부착 후 기동 — agent 정상 부팅 (`[ApiLens] ApiLens agent started: service=vams ...` 로그 확인)
- [ ] VAMS `/api/videos/{id}/stream` 호출
- [ ] mp4 사이즈 보존 검증:
  ```bash
  SIZE_BEFORE=$(stat -f %z /path/to/video.mp4)
  curl -s "http://localhost:8088/api/videos/628/stream" -H "Range: bytes=0-1023" >/dev/null
  SIZE_AFTER=$(stat -f %z /path/to/video.mp4)
  test "$SIZE_BEFORE" = "$SIZE_AFTER" && echo OK || echo FAIL
  ```
- [ ] (선택) `sudo fs_usage -w -e -f filesys | grep mp4` 로 `_WC_T` 호출 0 hit 확인
- [ ] ApiLens dashboard `/services` 에서 `vams` 정상 등록 + trace 정상 표시 (placeholder `{"_apilens":"streaming-body-skipped",...}` 가 payload 에 박혀 있어도 trace 자체는 정상 흐름)

## 주의

- 본 R11 사용자 비협상 6 결정 (위 §사용자 결정 사항 1~5 + §R11 라운드 정체성) verbatim 봉인 — architect 재해석 금지
- R10 회수 결정 (D-H10-01~06) + R9 본체 결정 (D-01~D-05 / W-01~W-03 / V-USER-H1·H2 / Q-01~Q-08 / SH-* 18) 모두 보존 변경 0 line
- 본 라운드 시각 phase 아님 — UX 단계 NO (EXT-005 V-USER 게이트 비활성)
- agent shadow jar 임베드는 R10 결과 server resources/main/agent/apilens-agent.jar 갱신 의무 — server 재기동 후 wizard Step 4 의 절대경로가 새 jar 가리키도록 (server build 재실행 필요)
- 본 R11 종료 후 R12 = release-gate phase (R10 핸드오프 §2 의제 1~10 한 phase 밀려서 진행, release-plan agent 첫 발동)
- TypeScript / FE 변경 0 (UI 영역 직교)
- "수동 dogfooding 통과 확인됨" 단정 보고 금지 — 사용자 검증 항목 명시

작업 시작 전 (Backend 작업 시):
- CLAUDE.md "Agent 자체 장애가 호스트 앱에 영향 0" + "Build 설정 lessons §1 shadow jar relocate 함정"
- apilens-agent/src/main/java/io/apilens/agent/instrument/AdviceSupport.java (변경 대상)
- apilens-agent/src/main/java/io/apilens/agent/instrument/InstrumentationInstaller.java (변경 대상)
- apilens-agent/src/main/java/io/apilens/agent/instrument/advice/*.java (3 advice — 호출자 영향 평가만)
- apilens-agent/build.gradle.kts (shadow jar relocate 규칙 확인)
- inter-pipeline 저널 (2026-05-26T03-02-24) §사건 3 — P0 BUG 진단 + 사용자 권장 multi-layer 3 layer + 회귀 테스트 2건
