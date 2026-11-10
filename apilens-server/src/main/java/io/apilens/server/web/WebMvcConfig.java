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

import org.springframework.context.annotation.Configuration;
// [Phase R30] R30/AC-R30-04 — Spring Framework 7 이 org.springframework.lang 의 널 표기를 폐기 예고 → JSpecify 표기로(동작 변경 0).
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        // SPA client-side routes (React Router) → index.html 로 forward.
        // 새로고침 / 북마크 / 직접 URL 진입 시 Whitelabel 404 회피.
        // /v1/**, /actuator/** 등 BE 경로는 컨트롤러 매핑이 먼저라 영향 0.
        registry.addViewController("/setup").setViewName("forward:/index.html");
        registry.addViewController("/services").setViewName("forward:/index.html");
        registry.addViewController("/traces/**").setViewName("forward:/index.html");
        // [Phase R12] AC-B4-1 — BL-11: /settings forward 페어 (명시 enumerate).
        // 누락 시 설정 페이지 새로고침/직접 진입에서 Whitelabel 404 (E-09).
        registry.addViewController("/settings").setViewName("forward:/index.html");
    }

    // [Phase R32] R32/AC-R32-03-1/R32/AC-R32-03-3/R32/AC-R32-03-6/R32/AC-R32-03-7 — YAML 입구를 닫는다. 사용자 명시 결정
    //   (YAML 본문 POST 202 → 415 · YAML 만 받는 조회 200 → 406 · API 문서 YAML 판 200 그대로).
    //   빌더의 최종 목록 갈고리에서 YAML 을 다루는 변환기를 뺀다 — 처리기 어댑터와 예외 처리기가 이 목록 하나에서 나온다.
    //   변환기 구성은 이 한 자리뿐이다. 목록을 직접 받는 옛 갈고리 둘은 폐기 예고(지워질 예정)라 쓰지 않는다.
    //   ★한계 그 자리에: 폼 변환기 안의 부분 변환기 목록에는 YAML 변환기가 남는다(멀티파트 부분 본문용 · 입구 아님 · spring-web 7.0.10 소스 확인).
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters -> converters.removeIf(WebMvcConfig::handlesYaml));
    }

    /**
     * Returns whether the converter supports a YAML media type ({@code application/yaml} or a {@code +yaml} suffix).
     * Wildcard media types are deliberately not counted, so byte, string and resource converters stay.
     */
    // 매체 형식으로만 고른다(클래스 이름을 안 본다) — Jackson 2 · 3 어느 YAML 변환기든 같은 기준으로 빠진다.
    //   와일드카드까지 YAML 로 치면 바이트 · 문자열 · 자원 변환기가 빠져 API 문서(바이트 응답)가 깨진다 — 틀리는 방향이 위험한 쪽이다.
    static boolean handlesYaml(HttpMessageConverter<?> converter) {
        for (MediaType type : converter.getSupportedMediaTypes()) {
            String subtype = type.getSubtype();
            if ("application".equals(type.getType()) && ("yaml".equals(subtype) || subtype.endsWith("+yaml"))) {
                return true;
            }
        }
        return false;
    }
}
