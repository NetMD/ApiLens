Phase D 후속 — shadow jar 의존성 흡수 충돌 해결

## 문제
./gradlew clean test 실행 시 server의 25개 테스트가 NoSuchMethodError로 실패.
원인: apilens-server가 testImplementation(project(":apilens-agent"))로 의존하는데,
agent는 shadow jar로 빌드되면서 apilens-common의 클래스를 통째로 흡수하고,
그 과정에서 com.fasterxml.jackson 패키지가 io.apilens.agent.shaded.jackson으로
relocate됨. 결과적으로 agent jar 안에는 MaskingEngine(List, shaded.ObjectMapper)
시그니처를 가진 stale MaskingEngine.class가 들어있고, 이게 testRuntimeClasspath
순서에 따라 원본 common 클래스보다 먼저 로드되어 NoSuchMethodError 발생.

증거:
- javap로 apilens-common/build/classes/.../MaskingEngine.class 확인 → 시그니처 정상
- 그러나 NoSuchMethodError 25개 일관 발생
- testRuntimeClasspath에 apilens-agent + apilens-common 둘 다 포함

## 해야 할 일

1. apilens-server/build.gradle.kts에서 testImplementation(project(":apilens-agent"))
   제거.

2. apilens-server/src/test/java/io/apilens/server/integration/
   AgentToServerIntegrationTest.java 파일을 apilens-agent 모듈로 이동:
   - 새 위치: apilens-agent/src/test/java/io/apilens/agent/integration/
     AgentToServerIntegrationTest.java
   - 패키지 선언도 io.apilens.agent.integration로 변경

3. apilens-agent/build.gradle.kts에 server의 일부를 테스트 의존성으로 추가:
   testImplementation(project(":apilens-server"))
   testImplementation(libs.spring.boot.starter.test)
   testImplementation(libs.sqlite.jdbc)
   (기존 테스트 의존성은 그대로 유지)

4. AgentToServerIntegrationTest 안에서 server를 띄우는 코드는 변경 없을 가능성 높음
   (Spring Boot test 어노테이션이 그대로 동작). 만약 import가 어긋나면 정리.

## 검증

다음 순서로 한 단계씩 확인. 단계마다 통과 확인 후 다음으로.

1. ./gradlew :apilens-common:test
   - common 14개 통과

2. ./gradlew :apilens-server:test
   - server 23개 통과 (ingest 9 + query 14, integration은 이제 server가 아님)

3. ./gradlew :apilens-agent:test
   - agent 27개 통과 (단위 25 + integration 2)
   - integration 2개가 새 위치에서 정상 동작하는지가 핵심

4. ./gradlew clean test
   - 전체 64개 통과

5. ./gradlew :apilens-agent:shadowJar
   - 빌드 성공
   - unzip -p apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
     → Premain-Class: io.apilens.agent.AgentMain 보여야 함
   - unzip -l apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar | grep -E "(bytebuddy|jackson)" | head -10
     → io/apilens/agent/shaded/bytebuddy/..., io/apilens/agent/shaded/jackson/... 만 보여야 함
     → net/bytebuddy/... 또는 com/fasterxml/jackson/...이 보이면 relocate 실패

## 짚어둘 것

- 이번이 두 번째 부작용. 첫 번째는 jackson 캡슐화 (서명 변경 사용자 앱 노출),
  이번은 shadow jar의 의존성 흡수가 의존 모듈 클래스도 통째로 변형시키는 함정.
- 이 두 사례의 공통 교훈: shadow jar로 빌드되는 모듈을 다른 모듈의 의존성으로
  쓸 때, 흡수된 클래스의 변형까지 고려해야 함. 안전한 패턴은 "shadow jar 모듈은
  최종 산출물 전용, 다른 모듈은 raw 모듈 의존".
- 작업은 server 모듈에서 testImplementation 제거 + 통합 테스트 파일 이동만.
  Phase A/B/C/D 코드 자체는 수정 금지.
- 본인이 standalone javac로 검증할 수 있는 범위는 컴파일 통과까지. "테스트 통과"
  단정 보고 금지. 보고 형식: "변경 파일 목록 + 사용자가 ./gradlew clean test로
  검증 필요" 명시.