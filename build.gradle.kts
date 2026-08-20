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
    //   실행 deadline 승격. agent 첫 변경 라운드라 AgentMain.AGENT_VERSION 도 0.4.0 정렬).
    //   이 한 곳이 버전 리터럴의 유일 출처 — build-info 를 통해 OpenAPI info.version 까지 자동 전파(게이트 E).
    // [Phase R19] FR-01/FR-02 — 0.4.0 → 0.5.0 (minor: services.agent_version 컬럼 + 계측 분석 화면).
    //   ⚠️ agent 는 이번에 변경하지 않는다 — AgentMain.AGENT_VERSION 은 0.4.0 유지가 정답이고
    //   그 자리에 사유 주석을 다는 것조차 하지 않는다(agent 소스 diff 0 비협상). 제품 0.5.0 ≠ agent 0.4.0
    //   이 의도라는 사실은 CHANGELOG.md 가 적는다.
    // [Phase R20] R20/AC-13-1 — 0.5.0 → 0.6.0 (minor: agent 고아 trace 억제 옵션 + 원격 config 채널 +
    //   O-2 잔여. 두 번째 agent 변경 라운드라 AgentMain.AGENT_VERSION 상수도 0.6.0 정렬).
    //   [Phase R21] R21/AC-08-2 (G-08) — 위 두 줄의 낡은 줄번호 좌표를 값·심볼 표기로 정정(주석만).
    //   agent 쪽 반대 방향 stale 좌표는 agent 소스 diff 0 봉인이라 이번에 열지 않는다(다음 agent 변경 라운드 몫).
    // [Phase R22] R22/AC-06-1 — 0.6.0 → 0.6.1 (patch: server 전용 정비 — 디스크 회수 예산 루프 +
    //   절감 계산 기준 교체 + 고아 span 2밤차 스윕 + 정리 시각 upsert + 문서 오진단 정정).
    //   ⚠️ agent 는 이번에 변경하지 않는다 — AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정이 정답이고,
    //   그 자리에 사유 주석을 다는 것조차 하지 않는다(agent 소스 diff 0 비협상). 제품 0.6.1 ≠ agent 0.6.0
    //   이 의도라는 사실은 CHANGELOG.md 가 적는다.
    //   ⚠️ db/migration 안의 v0.6.0 문자열도 무접촉이다 — 한 글자만 고쳐도 Flyway 체크섬 불일치로
    //   운영 DB 기동이 실패한다. 버전 리터럴 일괄 치환을 이 저장소에서 하지 말 것.
    // [Phase R23] R23/AC-17-1 — 0.6.1 → 0.6.2 (patch: server 전용 정비 — 요약 저장 경합 해소 +
    //   진단 로그 + 고아 유예를 정리 주기에서 분리 + 관측 표면 상시화). 위 두 ⚠️ 는 이번에도 그대로다:
    //   AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정이고 그 자리에 사유 주석도 달지 않는다.
    //   db/migration 은 주석 한 글자도 손대지 않는다.
    version = "0.6.2"
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
