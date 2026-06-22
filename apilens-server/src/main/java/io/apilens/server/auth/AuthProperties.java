/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apilens.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API key configuration from the server startup options ({@code apilens.auth.*}).
 *
 * <p>// [Phase K] AC-01-1/AC-02-1/AC-02-3 — R14-D02/D03/D08 사용자 명시 비협상 결정:
 * // 인증 = 단일 API Key 헤더 토큰. 토큰 SSOT = server 起動 옵션(env APILENS_AUTH_API_KEY /
 * // -Dapilens.auth.api-key), DB 저장 안 함. 키 미설정 = 무인증 폴백.
 * // CLAUDE.md '절대 변경하지 말아야 할 결정 사항' (단일 jar 배포·신뢰망 전제) 인용.
 *
 * <p>// @ConfigurationPropertiesScan(ApiLensApplication.java:27)이 이미 적용되어 record 만
 * // 추가하면 bean 자동 등록 — 별도 @EnableConfigurationProperties 코드 0 (IngestProperties 동형, GT-1).
 *
 * <p>// env APILENS_AUTH_API_KEY 는 Spring relaxed binding 으로 apilens.auth.api-key 에 매핑된다.
 * // PropertySource 우선순위(시스템 프로퍼티 > env > yml)는 Spring 이 보장 — 별도 우선순위 코드 불요.
 *
 * @param apiKey 起動 옵션으로 주입되는 API Key. null/blank 면 인증 비활성(무인증 폴백, R14-D08).
 */
@ConfigurationProperties(prefix = "apilens.auth")
public record AuthProperties(String apiKey) {

    /**
     * 인증 활성 조건 — "키가 설정됨" 단 하나(BL-02). blank 도 미설정 취급.
     *
     * <p>// [Phase K] AC-02-3 — R14-D08 비협상: 키가 비어 있으면 필터가 검증을 건너뛴다.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
