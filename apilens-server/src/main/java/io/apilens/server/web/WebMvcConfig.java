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
import org.springframework.lang.NonNull;
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
}
