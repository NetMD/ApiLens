// apilens-agent: Java Agent
// premain 진입점, ByteBuddy로 controller/service/repo/JDBC 자동 instrument
// 사용자 앱 클래스로더와 충돌 방지를 위해 shadow jar로 의존성 relocate

// [Phase R30] R30/AC-R30-49 — shadow 는 8.3.5 그대로 둔다(설계 정정 DV-B-R30-02 둘째 출구 · Gradle 8.14 경유).
//   9.6.1 로 올리면 같은 소스에서 jar 가 달라진다 — 판별 다중 판(META-INF/versions/*) 엔트리까지 relocate 되고
//   manifest 에 Multi-Release: true 가 붙어, 호스트 JVM 이 그 판별 클래스를 실제로 싣게 된다(8.3.5 산출에선 죽은 엔트리).
//   이 변화를 받을지는 Gradle 9 덩이(CM-R30-3b)에서 정한다. CLAUDE.md '아키텍처 핵심 원칙'(Agent 자체 장애가 호스트 앱에
//   영향 0) · 'Build 설정 lessons §1'(shadow jar relocate 함정) 인용.
plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":apilens-common"))
    implementation(libs.bytebuddy)
    implementation(libs.bytebuddy.agent)
    implementation(libs.jackson.databind)  // ← 추가 (명시적)
    implementation(libs.jackson.jsr310)    // 사용자 앱 DTO의 LocalDateTime 등 java.time 직렬화 지원

    testImplementation(libs.jupiter.api)
    testRuntimeOnly(libs.jupiter.engine)
    testImplementation(libs.mockito.core)

    // agent ↔ server end-to-end 통합 테스트가 이 모듈에 있어서 server를 test 시점에 로드.
    // 반대 방향(server → agent)은 shadow jar의 의존성 흡수 + relocate로 인해 깨지므로
    // 통합 테스트의 거주지로 agent 쪽이 안전하다 (server는 raw 모듈을 그대로 노출).
    // [Phase R30] R30/AC-R30-07 — 서버는 dialects 를 판 없는 별칭으로 선언한다(서버 BOM 이 판을 준다). agent 는 BOM 밖이라
    //   전이로 받으면 판이 비어 해석이 깨진다 → 여기서 뺀다(설계 정정 DV-S-R30-1 갈래 ⓒ · agent 시험은 JPA 방언을 안 쓴다).
    testImplementation(project(":apilens-server")) {
        exclude(group = "org.hibernate.orm", module = "hibernate-community-dialects")
    }
    testImplementation(libs.spring.boot.starter.test)
    // [R11] R11 P0 회수: AdviceSupportSerializeReturnSafetyTest 가 org.springframework.http.ResponseEntity
    // 를 직접 import (Layer 2 unwrapResponseEntity 검증). apilens-server 의 implementation(spring-web)
    // 은 testCompileClasspath 에 transitive 노출되지 않음 (CLAUDE.md Build lessons §3).
    // [Phase R12] #20 — 영구 의존성으로 확정 (사전 체크리스트 #11, 사용자 명시 결정. Design §2-#20):
    // "회수 라운드 한정" → 영구화. AdviceSupportSerializeReturnSafetyTest 는 영구 회귀 가드
    // 테스트이며, Build lessons §3 (implementation 은 transitive 비노출)상 직접 추가가 유일 해법.
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.sqlite.jdbc)
    // server는 Flyway + JdbcTemplate을 implementation()으로 쓰므로 transitive 노출 안 됨.
    // 통합 테스트가 직접 사용하기 위해 명시적으로 추가.
    testImplementation(libs.flyway.core)
    testImplementation(libs.spring.boot.starter.jdbc)
}

// [Phase R30] R30/AC-R30-23 — 시험 클래스패스의 계측 엔진(byte-buddy)을 배포물(shadow jar)과 같은 판에 묶는다(설계 DD-R30-19).
//   Boot 시험 스타터가 끌어오는 assertj 가 더 높은 byte-buddy 를 요청해 시험만 다른 엔진으로 돌게 되면, agent 시험 212 가
//   배포물을 증명하지 못한다. 시험 구성 두 개에만 건다 — runtimeClasspath(배포물)는 원래 카탈로그 판 그대로다.
//   CLAUDE.md '아키텍처 핵심 원칙'(Agent 는 가볍게 · 호스트 앱 영향 0) 인용.
val agentTestByteBuddy = libs.versions.byteBuddy.get()
configurations.named("testCompileClasspath") {
    resolutionStrategy.force("net.bytebuddy:byte-buddy:$agentTestByteBuddy", "net.bytebuddy:byte-buddy-agent:$agentTestByteBuddy")
}
configurations.named("testRuntimeClasspath") {
    resolutionStrategy.force("net.bytebuddy:byte-buddy:$agentTestByteBuddy", "net.bytebuddy:byte-buddy-agent:$agentTestByteBuddy")
}

// [v0.8.0] agent 가 보고하는 버전을 제품 버전에 묶는다 — 빌드가 리소스에 버전을 심고 AgentMain 이 읽는다.
//   손으로 박은 버전 문자열이 제품 버전과 갈려 화면(0.6.0)과 jar 이름(0.8.0)이 달랐던 혼동을 없앤다.
// 버전 문자열은 각 블록 안 지역 변수로 잡는다 — 스크립트 최상단 변수를 람다가 잡으면 설정 캐시가 거부한다.
tasks.processResources {
    val agentVersionText = project.version.toString()
    inputs.property("agentVersion", agentVersionText)
    filesMatching("io/apilens/agent/agent-version.properties") {
        expand("version" to agentVersionText)
    }
}

tasks.test {
    // 시험이 「빌드가 심은 값 = 제품 버전」을 대조할 기준값.
    systemProperty("apilens.expectedProductVersion", project.version.toString())
}

tasks.jar {
    enabled = false
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("apilens-agent")

    // 사용자 앱과 클래스 충돌 방지: ByteBuddy/Jackson을 우리 패키지 아래로 relocate
    relocate("net.bytebuddy", "io.apilens.agent.shaded.bytebuddy")
    relocate("com.fasterxml.jackson", "io.apilens.agent.shaded.jackson")

    manifest {
        attributes(
            "Premain-Class" to "io.apilens.agent.AgentMain",
            "Agent-Class" to "io.apilens.agent.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Implementation-Version" to project.version
        )
    }

    mergeServiceFiles()
}

// build 태스크가 shadowJar를 산출물로 인식하게
// [Phase R30] R30/AC-R30-49 — Gradle 9 는 archives 구성에 산출물을 적는 꼴을 폐기 예고했다(Gradle 10 에서 오류).
//   안내대로 assemble 이 shadowJar 를 직접 의존하게 바꾼다 — build/assemble 이 shadow jar 를 만든다는 뜻은 같다.
tasks.assemble {
    dependsOn(tasks.shadowJar)
}
