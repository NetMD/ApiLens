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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
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