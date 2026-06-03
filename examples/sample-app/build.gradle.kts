// ApiLens sample app — 수동 smoke test용 미니 Spring Boot 앱.
// ApiLens 본체와 분리된 독립 빌드. 부모 wrapper 사용:
//   ./gradlew --project-dir examples/sample-app bootRun
//
// ⚠️ JDK 21 필요. Gradle 8.11.1 데몬이 JDK 25로 뜨면 build.gradle.kts 컴파일
// 단계에서 "IllegalArgumentException: 25.0.2" 발생 (Kotlin JavaVersion.parse).
// 해결: examples/sample-app/gradle.properties 에 org.gradle.java.home 설정,
// 또는 JAVA_HOME 환경변수로 JDK 21을 지정한 뒤 실행. README 참조.

plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
}

tasks.test {
    useJUnitPlatform()
}

// ─── ApiLens agent 자동 attach ────────────────────────────────────────────────
// 이 build.gradle.kts 파일 위치 기준 ../../apilens-agent/build/libs/...
// (즉 ApiLens 루트의 apilens-agent 모듈 산출물 — 사전에 :apilens-agent:shadowJar 필요).
//
// 사용 패턴:
//   ./gradlew bootRun                                  → agent 자동 attach
//   ./gradlew bootRun -Pno-agent                       → agent 없이 실행 (baseline)
//   ./gradlew bootRun -Papilens.debug=true             → debug 로그 켬
//   ./gradlew bootRun -Papilens.server=http://x:9999   → 다른 server URL

tasks.bootRun {
    val agentJar = rootProject.layout.projectDirectory
            .file("../../apilens-agent/build/libs/apilens-agent-0.1.0-SNAPSHOT.jar")
            .asFile

    doFirst {
        if (project.hasProperty("no-agent")) {
            logger.lifecycle("[sample-app] running WITHOUT ApiLens agent (-Pno-agent)")
        } else if (!agentJar.exists()) {
            logger.warn("[sample-app] ApiLens agent jar not found at ${agentJar.absolutePath}")
            logger.warn("[sample-app] run from ApiLens root: ./gradlew :apilens-agent:shadowJar")
        } else {
            logger.lifecycle("[sample-app] attaching ApiLens agent: ${agentJar.absolutePath}")
        }
    }

    if (!project.hasProperty("no-agent") && agentJar.exists()) {
        jvmArgs("-javaagent:${agentJar.absolutePath}")
        // 기본 옵션 — 사용자가 -Papilens.service.name=other 로 override 가능
        if (!project.hasProperty("apilens.service.name")) {
            systemProperty("apilens.service.name", "sample-app")
        }
    }

    // -P로 전달된 모든 apilens.* 프로퍼티를 systemProperty로 변환
    project.properties
            .filterKeys { it.startsWith("apilens.") }
            .forEach { (k, v) -> systemProperty(k, v.toString()) }
}
