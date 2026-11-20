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

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link JsonAcceptGate} for the REST API paths ({@code /v1/**}).
 *
 * <p>Kept apart from the existing MVC configuration on purpose: that class owns view forwarding and the message
 * converter list and is not touched by this change. Static resources, the API docs endpoints, view forwards and
 * the error path are outside {@code /v1/**} and are not judged.
 */
@Configuration
public class JsonAcceptGateConfig implements WebMvcConfigurer {

    // [Phase R33] R33/AC-R33-06-2 재작업 1 — 처리 전 406 을 한 자리에서 마저 판정한다(q 값 · 문자셋). 사용자 명시 비협상 결정
    //   (UQ-R33-3 처리 전 406). 차례 = 인증 필터(401) → 매핑(produces · consumes) → 이 판정 → 처리기. 기존 MVC 설정 파일은
    //   그대로 둔다(설계 G-R33-05 · 변환기 목록 · 뷰 전달 무접촉).
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new JsonAcceptGate()).addPathPatterns("/v1/**");
    }
}
