// apilens-agent: Java Agent
// premain 진입점, ByteBuddy로 controller/service/repo/JDBC 자동 instrument
// 사용자 앱 클래스로더와 충돌 방지를 위해 shadow jar로 의존성 relocate

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
    testImplementation(project(":apilens-server"))
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
artifacts {
    archives(tasks.shadowJar)
}
