// apilens-ui는 Gradle 빌드 대상 아님 (Vite로 별도 빌드 후 server resources로 복사)
// pluginManagement는 plugins block을 위해 필요
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// foojay resolver: Java toolchain 21을 시스템에 없으면 자동 다운로드
// OSS clone 한 contributor가 JDK 21 미설치여도 빌드 가능하게
// [Phase R30] R30/AC-R30-49 — 0.8.0 → 1.0.0(설계 DD-R30-02 · Gradle 8.14 · 9 양쪽에서 선다 — 정정 DV-B-R30-02).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ApiLens"

include(
    "apilens-common",
    "apilens-agent",
    "apilens-server"
)