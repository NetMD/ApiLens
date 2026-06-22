plugins {
    java
}

allprojects {
    group = "io.apilens"
    // 버전 연쇄 표면(README/examples/.github/UI 라벨)은 버전 무관 글롭/플레이스홀더로 정비됨 (Design §9.3).
    // [Phase K] NFR-06 — 0.2.1 → 0.3.0 (R14 v0.3 첫 라운드: API Key 인증 + ReDoS 가드 + 온라인 VACUUM).
    version = "0.3.0"
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
