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
    // [Phase R24] R24/AC-07-6 — 0.6.2 → 0.6.3 (patch: server 전용 정비 — 끊겼다 이어진 구간을
    //   수집기 로그 한 줄로 남기기 + 평소 검사망을 출고 경로와 같은 명령으로 맞추기 + 빈 그물 두 곳 조이기).
    //   위 두 ⚠️ 는 이번에도 그대로다: AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정이고 그 자리에
    //   사유 주석도 달지 않는다. db/migration 은 주석 한 글자도 손대지 않는다(스키마 변경 0).
    // [Phase R25] AC-25-08-2 — 0.6.3 → 0.7.0 (minor: payload 본문을 저장소 전체에서 한 번만 저장 +
    //   SQL 원문 인터닝 + 옛 형태 읽기 폴백 + 로그 위생. **스키마 마이그레이션 V6·V7 이 들어간다** —
    //   되돌릴 수 없고 백업 없이는 앞 버전으로 못 내려간다. 그래서 patch 가 아니라 minor 다).
    //   ⚠️ agent 는 이번에도 변경하지 않는다 — AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정이 정답이고,
    //   그 자리에 사유 주석을 다는 것조차 하지 않는다(agent 소스 diff 0 비협상). 제품 0.7.0 ≠ agent 0.6.0
    //   이 의도라는 사실은 CHANGELOG.md 가 적는다. **계측기를 다시 배포할 필요가 없다.**
    //   ⚠️ db/migration 의 V1~V5 는 주석 한 글자도 손대지 않는다 — Flyway 는 파일 전체 내용의 체크섬을
    //   쓰므로 주석만 바꿔도 기존 운영 DB 가 부팅에 실패한다. 이번 변경은 새 파일 V6·V7 에만 있다.
    // [Phase R26] AC-R26-48 — 0.7.0 → 0.7.1 (patch: server·검사망·화면·문서 정비 — 참조 없는 SQL 원문
    //   야간 회수 + 본문 정리 상한을 시간 예산으로 + 적재 입구 형식 검사 + 화면 손질. **스키마 변경 0**
    //   이라 마이그레이션 파일이 늘지 않는다 — 되돌리는 길은 이전 jar 로 바꾸는 것뿐이고 백업 복원이
    //   필요 없다. 그래서 minor 가 아니라 patch 다).
    //   ⚠️ agent 는 이번에도 변경하지 않는다 — AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정이 정답이고,
    //   그 자리에 사유 주석을 다는 것조차 하지 않는다(agent 소스 diff 0 비협상). 제품 0.7.1 ≠ agent 0.6.0
    //   이 의도라는 사실은 CHANGELOG.md 가 적는다. **계측기를 다시 배포할 필요가 없다.**
    //   ⚠️ db/migration 의 V1~V7 은 주석 한 글자도 손대지 않는다 — 이번 라운드는 새 마이그레이션도 없다.
    //   ★이 줄을 올리면 OpenApiDocsIntegrationTest 의 assertEquals("0.7.1", …) 도 **같은 접촉에서**
    //   올려야 한다(안 올리면 RED — 게이트가 살아 있다는 신호다).
    // [Phase R27] R27/AC-27-05-1 — 0.7.1 → 0.8.0 (minor: 사용자가 알아볼 기능이 늘었다 — 대시보드 차트가 시계에
    //   맞춰 흐른다 · 설치 마법사가 서버가 거절한 이유를 보인다. 사용자 명시 결정(UD-2). **스키마 변경 0 ·
    //   agent 변경 0** — 마이그레이션이 없어 되돌리는 길은 이전 jar 로 바꾸는 것뿐이고, 계측기를 다시
    //   배포할 필요가 없다. AgentMain 의 AGENT_VERSION 은 "0.6.0" 고정 그대로다).
    //   ★OpenApiDocsIntegrationTest 의 info.version 단언도 이번 접촉에서 함께 올렸다.
    // [Phase R28] AC-28-06-1 — 0.8.0 → 0.9.0 (minor: 사용자가 알아볼 기능이 늘었다 — 대시보드 차트가 고른 시간 범위
    //   전체를 서버가 고른 대표 점으로 보인다 · 밤 정리가 [최적화]를 권한다. **스키마 변경 0 · agent 소스 변경 0** —
    //   되돌리는 길은 이전 jar 로 바꾸는 것뿐이다. 새로 빌드한 agent jar 는 제품 버전(0.9.0)을 보고하고, 다시
    //   배포하지 않은 agent 는 계속 0.8.0 으로 보인다 — 둘 다 정상이다).
    //   ★OpenApiDocsIntegrationTest 의 info.version 단언도 이번 접촉에서 함께 올렸다.
    // [Phase R29] R29/AC-R29-40 — 0.9.0 → 0.9.1 (patch: 정비 라운드 — 밤 회수 묶음 · 화면 정비 · 사용자 명시 결정
    //   UD-R29-1). **스키마 변경 0 · agent 소스 변경 0** — 되돌리는 길은 이전 jar 로 바꾸는 것뿐이다. 새로 빌드한
    //   agent jar 는 제품 버전(0.9.1)을 보고하고, 다시 배포하지 않은 agent 는 이전 라벨 그대로 보인다 — 둘 다 정상이다.
    //   ★OpenApiDocsIntegrationTest 의 info.version 단언도 이번 접촉에서 함께 올렸다.
    version = "0.9.1"
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

    // [Phase R30] R30/AC-R30-01 — 시험 실행기(junit-platform-launcher)를 시험 런타임에 직접 선언한다.
    //   Gradle 이 시험 실행기를 스스로 붙이던 옛 동작은 Gradle 9 에서 없어진다(이 라운드 Gradle 9 판올림의 선행 덩이).
    //   판은 junit-bom(카탈로그 jupiter 키)이 준다 — Spring BOM 밖인 agent · common 도 같은 길로 판을 얻는다.
    //   이 블록이 없으면 Gradle 9 에서 시험이 0 건 돌고 초록처럼 보인다 — 순서(이 덩이 → Gradle 판올림)를 바꾸지 않는다.
    dependencies {
        "testRuntimeOnly"(platform(rootProject.libs.junit.bom))
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    // [Phase R30] R30/AC-R30-31 — 시험 JVM 판을 Gradle 속성 apilensTestJdk 로 고른다(CI smoke 다리 = 27 · 사용자 명시 결정
    //   「27 로 바꿔 유지」). 대상은 server · common 뿐이다 — agent 시험은 계측 엔진(Byte Buddy 1.17.8) 지원 범위 때문에 21 그대로.
    //   속성이 없거나 비어 있으면 21(컴파일 toolchain 과 같음 · 로컬 ./gw test 무변경). 숫자가 아니면 설정 단계에서 멈춘다.
    //   고른 launcher 의 판을 build/apilens-test-jvm.txt 에 늘 한 줄 쓴다(시험 JVM 안 값이 아니라 Gradle 이 고른 launcher 의 판).
    //   컴파일 toolchain(위 languageVersion 21)은 건드리지 않는다. CLAUDE.md '절대 변경하지 말아야 할 결정 사항' 1번(Java 21) 인용.
    if (name == "apilens-server" || name == "apilens-common") {
        val requestedTestJdk = providers.gradleProperty("apilensTestJdk").getOrElse("").trim()
        val testJdk = if (requestedTestJdk.isEmpty()) 21 else requestedTestJdk.toIntOrNull()
            ?: throw GradleException("apilensTestJdk must be a whole number (e.g. 27)")
        val testLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk)
        }
        val testJvmFile = layout.buildDirectory.file("apilens-test-jvm.txt")
        tasks.withType<Test>().configureEach {
            javaLauncher.set(testLauncher)
            val launcherForRecord = testLauncher
            val recordFile = testJvmFile
            doFirst {
                val out = recordFile.get().asFile
                out.parentFile.mkdirs()
                out.writeText(launcherForRecord.get().metadata.javaRuntimeVersion + "\n")
            }
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }
}
