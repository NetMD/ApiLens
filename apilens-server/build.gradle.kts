// apilens-server: 메인 실행 jar
// REST endpoint(span 수신) + storage(SQLite) + UI 호스팅
// 빌드 시 agent jar와 UI 빌드 산출물을 함께 packaging

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":apilens-common"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.flyway.core)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.hibernate.community.dialects)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.sqlite.jdbc)
    // NOTE: project(":apilens-agent")를 testImplementation으로 절대 추가하지 말 것.
    // agent는 shadow jar로 빌드되며 그 과정에서 의존 클래스(common 포함)를 흡수 +
    // jackson을 io.apilens.agent.shaded.jackson 으로 relocate함. 이 변형 클래스가
    // testRuntimeClasspath의 raw common 클래스보다 먼저 로드되면 NoSuchMethodError.
    // agent ↔ server end-to-end 통합 테스트는 apilens-agent 모듈에 둔다.
}

// ─── agent jar 임베드 ──────────────────────────────────────────────────────
// server jar 안에 agent jar를 리소스로 넣어두고, 첫 실행 시 ~/.apilens/agent.jar로 풀어줌
// (운영자는 별도 다운로드 없이 server 한 번 띄우면 agent.jar 위치 안내받음)

val embedAgent by tasks.registering(Copy::class) {
    dependsOn(":apilens-agent:shadowJar")
    from(project(":apilens-agent").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("resources/main/agent"))
    rename { "apilens-agent.jar" }
}

// ─── UI 빌드 산출물 임베드 ────────────────────────────────────────────────
// apilens-ui (Vite)의 dist를 server resources/static으로 복사
// UI 빌드는 별도 스크립트(npm run build)에서 수행 — Gradle은 산출물만 가져다 씀

val embedUi by tasks.registering(Copy::class) {
    val uiDist = rootProject.file("apilens-ui/dist")
    from(uiDist)
    into(layout.buildDirectory.dir("resources/main/static"))
    // dist 없으면 경고만 (UI 미빌드 상태로도 server는 띄울 수 있게)
    onlyIf {
        if (!uiDist.exists()) {
            logger.warn("apilens-ui/dist not found — run 'npm run build' in apilens-ui/")
            false
        } else true
    }
}

tasks.processResources {
    dependsOn(embedAgent, embedUi)
}

tasks.bootJar {
    archiveBaseName.set("apilens-server")
    archiveClassifier.set("")
}

// 일반(plain) jar 활성: 다른 모듈이 testImplementation(project(":apilens-server"))로
// 클래스를 가져갈 수 있어야 함 (Spring Boot 플러그인 기본은 disable이지만 우리는 켜둠).
// classifier "plain"으로 bootJar(fat)와 파일명 충돌 회피.
tasks.jar {
    enabled = true
    archiveClassifier.set("plain")
}
