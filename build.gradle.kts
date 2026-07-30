plugins {
    java
}

allprojects {
    group = "io.apilens"
    // 버전 연쇄 표면(README/examples/.github/UI 라벨)은 버전 무관 글롭/플레이스홀더로 정비됨 (Design §9.3).
    // [Phase K] NFR-06 — 0.2.1 → 0.3.0 (R14 v0.3 첫 라운드: API Key 인증 + ReDoS 가드 + 온라인 VACUUM).
    // [Phase R15] FR-D4 — 0.3.0 → 0.3.1 (유지보수 모드: 수신 일시정지). 사용자 명시 비협상 결정(D01).
    // [Phase R16] FR-07 — 0.3.1 → 0.3.2 (API 문서 자동화: springdoc/OpenAPI + Swagger UI, server 전용).
    // [Phase R17] FR-01 — 0.3.2 → 0.3.3 (거대 trace 적재 청크 커밋 즉시완화 + OpenAPI polish, server 전용).
    // [Phase R18] FR-03/게이트 2 — 0.3.3 → 0.4.0 (minor: agent 계측 exclude 필터 opt-in + 공유 ReDoS
    //   실행 deadline 승격. agent 첫 변경 라운드라 AGENT_VERSION 도 0.4.0 정렬 — AgentMain.java:52).
    //   이 한 곳이 버전 리터럴의 유일 출처 — build-info 를 통해 OpenAPI info.version 까지 자동 전파(게이트 E).
    // [Phase R19] FR-01/FR-02 — 0.4.0 → 0.5.0 (minor: services.agent_version 컬럼 + 계측 분석 화면).
    //   ⚠️ agent 는 이번에 변경하지 않는다 — AgentMain.AGENT_VERSION 은 0.4.0 유지가 정답이고
    //   그 자리에 사유 주석을 다는 것조차 하지 않는다(agent 소스 diff 0 비협상). 제품 0.5.0 ≠ agent 0.4.0
    //   이 의도라는 사실은 CHANGELOG.md 가 적는다.
    version = "0.5.0"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }
}
