// apilens-server: 메인 실행 jar
// REST endpoint(span 수신) + storage(SQLite) + UI 호스팅
// 빌드 시 agent jar와 UI 빌드 산출물을 함께 packaging

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":apilens-common"))

    // [Phase R30] R30/AC-R30-10/R30/AC-R30-19/R30/AC-R30-20 — Boot 4.1 스타터 이름(web → webmvc · Flyway 스타터 ·
    //   시험 스타터 webmvc-test + restclient). JSON 은 Jackson 3 자동 구성(JsonMapper)이 맡는다 — Jackson 2 자동 구성 모듈은
    //   선언하지 않는다(길 ⓒ 기각 · 사용자 명시 비협상 결정 「길 ⓐ 전면 이행」). data-jpa 선언은 그대로(OQ-R30-9 · 이번 밖).
    //   CLAUDE.md '절대 변경하지 말아야 할 결정 사항' 1번(Boot 판 — 사용자가 이번 라운드에 바꾸기로 함) 인용.
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // [Phase R16] FR-01 — API 문서 자동화(springdoc-openapi + Swagger UI). server 전용 implementation scope.
    //   CLAUDE.md 'Build 설정 lessons §1' 준수: agent 를 testImplementation 으로 끌어오지 않으므로
    //   relocate 충돌 표면 0. springdoc 는 server classpath 에만 얹혀 agent shadowJar 로 유입되지 않는다.
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.hibernate.community.dialects)
    // [Phase R30] R30/AC-R30-11 — 마스킹 전용 Jackson 2 mapper 의 java.time 모듈을 직접 선언(설계 DD-R30-05).
    implementation(libs.jackson.jsr310.server)

    // [Phase R30] R30/AC-R30-20 — 시험 스타터가 spring-boot-starter-test 를 전이로 준다(따로 선언하지 않는다).
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.sqlite.jdbc)
    // NOTE: project(":apilens-agent")를 testImplementation으로 절대 추가하지 말 것.
    // agent는 shadow jar로 빌드되며 그 과정에서 의존 클래스(common 포함)를 흡수 +
    // jackson을 io.apilens.agent.shaded.jackson 으로 relocate함. 이 변형 클래스가
    // testRuntimeClasspath의 raw common 클래스보다 먼저 로드되면 NoSuchMethodError.
    // agent ↔ server end-to-end 통합 테스트는 apilens-agent 모듈에 둔다.
}

// [Phase R30] R30/AC-R30-02/R30/AC-R30-13 — 응답 스냅샷 시험(ResponseSnapshotIntegrationTest)의 기록 모드 스위치.
//   Gradle 속성 apilensSnapshotRecord(값 = 출력 절대경로)를 시험 JVM 시스템 속성으로 넘긴다 · 없으면 빈 값 = 비교 모드.
//   평소 ./gw test 는 늘 비교 모드다 — 기록은 판을 올리기 전 기준을 뜰 때만 손으로 켠다. 사용자 명시 비협상 결정
//   (AC-R30-02 「3.4.1 응답 모양 스냅샷」 · 3.5 첫 커밋보다 먼저). CLAUDE.md '아키텍처 핵심 원칙'(단일 jar · 신뢰망) 인용 —
//   시험 classpath 밖(배포 jar · bootRun)에는 닿지 않는다.
tasks.test {
    systemProperty("apilens.snapshot.record", providers.gradleProperty("apilensSnapshotRecord").getOrElse(""))
}

// ─── build-info 생성 (게이트 E — info.version 단일 출처) ────────────────────
// [Phase R16] FR-06/게이트 E — META-INF/build-info.properties(build.version = Gradle version)를 생성.
//   spring-boot-starter-actuator 가 이 파일에서 BuildProperties 빈을 자동 등록하고,
//   OpenApiConfig 가 이를 주입받아 OpenAPI info.version 을 채운다(손코딩 "0.3.2" 금지 — stale 원천 제거).
//   버전 리터럴의 유일 출처 = 루트 build.gradle.kts 의 version. bump 시 info.version 자동 추종.
springBoot {
    buildInfo()
}

// ─── agent jar 임베드 ──────────────────────────────────────────────────────
// server jar 안에 agent jar를 리소스로 넣어두고, 첫 실행 시 ~/.apilens/agent.jar로 풀어줌
// (운영자는 별도 다운로드 없이 server 한 번 띄우면 agent.jar 위치 안내받음)

// [Phase R13] E3: embedAgent 는 Copy 유지 — rename 으로 고정 파일명(apilens-agent.jar)을 쓰므로
// 버전 bump 시에도 이름이 바뀌지 않아 옛 산출물 잔존이 없다 (embedUi 의 Sync 전환과 무관).
// [Phase R30] R30/AC-R30-49 — Gradle 9.6 이 `val x by tasks.registering(Type::class)` 위임 꼴을 폐기 예고 →
//   안내된 `tasks.register<Type>(이름)` 꼴로(태스크 이름 · 동작 무변경). 아래 embedUi 도 같다.
val embedAgent = tasks.register<Copy>("embedAgent") {
    dependsOn(":apilens-agent:shadowJar")
    from(project(":apilens-agent").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("resources/main/agent"))
    rename { "apilens-agent.jar" }
}

// ─── UI 빌드 산출물 임베드 ────────────────────────────────────────────────
// apilens-ui (Vite)의 dist를 server resources/static으로 복사
// UI 빌드는 별도 스크립트(npm run build)에서 수행 — Gradle은 산출물만 가져다 씀

// [Phase R13] E3: Copy → Sync 전환 — UI 빌드 산출물은 hash 가 박힌 파일명(index-<hash>.js 등)이라
// Copy 면 옛 assets 가 static 에 잔존해 stale 번들이 임베드될 수 있다. Sync 는 source 에 없는
// 대상 파일을 자동 제거해 dist 와 1:1 동기화한다 (embedAgent 는 고정명이라 Copy 유지 — 위 주석 참조).
val embedUi = tasks.register<Sync>("embedUi") {
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
