// 독립 빌드: 루트의 settings.gradle.kts에서 include 하지 않음.
// 루트 wrapper로 빌드 가능: ./gradlew --project-dir examples/sample-app bootRun

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

rootProject.name = "sample-app"
