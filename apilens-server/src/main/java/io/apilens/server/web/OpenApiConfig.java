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
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
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

    /** [Phase R26] R26/AC-R26-58 — 공용 오류 조각의 참조 경로. 조각 이름과 이 문자열은 한 쌍이다. */
    private static final String ERROR_RESPONSE_SCHEMA_REF = "#/components/schemas/ErrorResponse";

    /**
     * [Phase R26] R26/AC-R26-58 — 공용 조각으로 묶는 오류 상태. {@code docs/api.md} 의
     * 「공통 오류 응답 표준」이 적은 넷과 같다. 2xx 는 여기 없다 — 성공 응답은 endpoint 마다 모양이 다르다.
     */
    private static final List<String> ERROR_STATUS_CODES = List.of("400", "404", "409", "503");

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

    /**
     * // [Phase R26] R26/AC-R26-58 — 공통 오류 응답을 <b>명세 화면에 실제로 보이게</b> 한다.
     * //   R17 이 공용 조각({@code ErrorResponse})을 등록했지만 <b>어떤 operation 도 그것을 가리키지 않아</b>
     * //   화면에는 endpoint 마다 손으로 적은 예시만 보였다 — 조각이 있는데 아무도 안 쓰는 상태였다.
     * //   그래서 조립이 끝난 최종 명세를 한 번 훑어 오류 상태의 응답 스키마를 그 조각 참조로 바꾼다.
     *
     * <p>★<b>동작 변경 0</b>: 이 customizer 는 {@code /v3/api-docs} 문서만 손댄다. 실제 응답 본문을
     * 만드는 코드는 한 줄도 안 거친다 — 서버가 돌려주는 JSON 은 오늘과 같다.
     *
     * <p>★<b>덮어쓰지 않는 것</b>: 이미 {@code $ref} 인 스키마와 속성이 정의된 스키마는 <b>그대로 둔다</b>
     * (다른 모양의 오류 본문을 쓰는 자리가 나중에 생겨도 이 훑기가 그것을 뭉개지 않는다).
     * 손으로 적어 둔 예시는 <b>미디어 타입 쪽으로 옮겨 보존</b>한다 — 참조 스키마 옆의 예시는 화면에서 무시되기 때문이다.
     */
    @Bean
    OpenApiCustomizer errorResponseRefCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(OpenApiConfig::pointErrorResponsesAtTheSharedSchema));
        };
    }

    /** 한 operation 의 오류 상태 응답들을 공용 조각 참조로 바꾼다. 성공 상태(2xx)는 안 건드린다. */
    private static void pointErrorResponsesAtTheSharedSchema(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }
        for (String status : ERROR_STATUS_CODES) {
            ApiResponse response = responses.get(status);
            if (response == null || response.getContent() == null) {
                continue;
            }
            MediaType json = response.getContent().get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
            if (json == null) {
                continue;
            }
            Schema<?> schema = json.getSchema();
            boolean alreadyShaped = schema != null
                    && (schema.get$ref() != null || (schema.getProperties() != null && !schema.getProperties().isEmpty()));
            if (alreadyShaped) {
                continue;
            }
            if (schema != null && schema.getExample() != null && json.getExample() == null) {
                json.setExample(schema.getExample());   // 참조 옆의 예시는 무시되므로 미디어 타입으로 옮긴다.
            }
            json.setSchema(new Schema<>().$ref(ERROR_RESPONSE_SCHEMA_REF));
        }
    }
}
