plugins {
    java
}

allprojects {
    group = "io.apilens"
    // 버전 연쇄 표면(README/examples/.github/UI 라벨)은 버전 무관 글롭/플레이스홀더로 정비됨 (Design §9.3).
    version = "0.2.0"
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
