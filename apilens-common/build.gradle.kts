// apilens-common: agent와 server가 공유하는 모델 + 마스킹 엔진

plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)  // implementation → api로 변경

    testImplementation(libs.jupiter.api)
    testRuntimeOnly(libs.jupiter.engine)
}