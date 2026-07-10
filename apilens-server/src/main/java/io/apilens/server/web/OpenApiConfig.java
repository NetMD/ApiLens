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
package io.apilens.server.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi customization. Registers a single {@link OpenAPI} bean so that
 * {@code /v3/api-docs} and {@code /swagger-ui} expose the API contract with a title,
 * description and a version that tracks the Gradle build version (no hard-coded literal).
 *
 * <p>Sits in the {@code io.apilens.server.web} package so the component scan root
 * ({@code io.apilens.server}) picks it up automatically.
 */
@Configuration
public class OpenApiConfig {

    /**
     * // [Phase R16] FR-06/게이트 E — info.version 은 손코딩 리터럴이 아니라 BuildProperties(build-info)
     * // 주입값이다. 사용자 명시 비협상 결정(stale 재발 차단이 이 라운드의 존재 이유 — BL-04).
     * // CLAUDE.md '릴리스·공개 문서 규약'(버전 SSOT 유지) 인용. 버전 리터럴을 이 파일에 절대 넣지 않는다(주입값만).
     *
     * <p>ObjectProvider 로 빈 부재를 허용하고, 부재 시 fallback 은 버전 문자열이 아니라 "unknown"
     * (비버전 placeholder) — fallback 경로에도 stale 버전 리터럴 유입 0. 배포 jar 는 bootJar 가
     * build-info 산출물을 포함하므로 항상 실버전을 노출한다("unknown" 은 build-info 미생성 엣지에서만).
     */
    @Bean
    OpenAPI apiLensOpenAPI(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties bp = buildProperties.getIfAvailable();
        String version = (bp != null) ? bp.getVersion() : "unknown";
        return new OpenAPI().info(new Info()
                .title("ApiLens API")
                .version(version)
                .description("ApiLens 호출 추적 서버의 REST API. 운영 서사(유지보수 503·마스킹·인증 전제)는 docs/api.md 병행."));
    }

    /**
     * // [Phase R17] FR-04 — 공통 오류 응답 표준을 재사용 component 1개로 명문화(EXT-010 단일 출처).
     * //   flat 표준 { "error": "<message>" }(docs/api.md '공통 오류 응답 표준') 그대로 노출. 중첩 { error:{code,message} } 안 씀.
     * //   endpoint 별 인라인 @Schema 를 전수 손으로 교체하지 않고 재사용 component 1개만 등록한다.
     *
     * <p>OpenApiCustomizer 로 등록하는 이유(설계 §3.4 대비 정정): springdoc 2.7.0 은 어떤 operation 도
     * {@code $ref} 로 참조하지 않는 커스텀 스키마를 OpenAPI 빈의 components 에 넣어도 최종 스펙에서
     * pruning 한다(실측 — /v3/api-docs 에 미노출). OpenApiCustomizer 는 스캔·조립이 끝난 최종 OpenAPI
     * 에 적용되므로 참조 여부와 무관하게 component 가 보존된다. 단일 출처(이 클래스) 원칙은 그대로 유지.
     */
    @Bean
    OpenApiCustomizer errorResponseComponentCustomizer() {
        return openApi -> {
            StringSchema errorMessage = new StringSchema();
            errorMessage.setExample("요청을 처리할 수 없습니다.");
            ObjectSchema errorResponse = new ObjectSchema();
            errorResponse.addProperty("error", errorMessage);
            errorResponse.setDescription("ApiLens 공통 오류 응답 — flat 단일 표준(400/404/409/503). "
                    + "컨텍스트 필드(traceId 등)가 추가로 붙을 수 있음.");
            errorResponse.setRequired(List.of("error"));

            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSchemas("ErrorResponse", errorResponse);
        };
    }
}
