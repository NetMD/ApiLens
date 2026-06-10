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

import org.junit.jupiter.api.Test;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.mvc.ParameterizableViewController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase R12] T-B4 — SPA forward enumerate 회귀 가드 (V-11, Design §7.2).
 *
 * <p>// [Phase R12] AC-B4-1 — BL-11: {@code /settings} forward 페어 누락 시 설정 페이지
 * // 새로고침/직접 진입에서 Whitelabel 404 (E-09). 기존 3건(/setup, /services, /traces/**)
 * // 보존 + /settings 추가의 4건 전체를 집합으로 봉인 — 차기 라우트 추가 시 본 테스트가
 * // enumerate 갱신을 강제한다.
 *
 * <p>검증 방식: {@link ViewControllerRegistry} 에 실제 등록된 (urlPath → viewName) 을
 * 리플렉션으로 추출 (Spring 6.x 내부 필드 — Boot 3.4 고정 전제. 필드명 변경 시 본 테스트가
 * 즉시 깨져 갱신을 알린다).
 */
class WebMvcConfigForwardTest {

    @Test
    void forwardsSettingsAndAllExistingSpaRoutesToIndexHtml() throws Exception {
        ViewControllerRegistry registry = new ViewControllerRegistry(new StaticWebApplicationContext());
        new WebMvcConfig().addViewControllers(registry);

        Map<String, String> routes = extractRegisteredForwards(registry);

        assertEquals(
                Set.of("/setup", "/services", "/traces/**", "/settings"),
                routes.keySet(),
                "SPA forward enumerate 4건 정확 일치 (V-11 — /settings 누락/기존 3건 회귀 모두 검출)");
        for (Map.Entry<String, String> route : routes.entrySet()) {
            assertEquals("forward:/index.html", route.getValue(),
                    route.getKey() + " 의 viewName 은 forward:/index.html 이어야 한다");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractRegisteredForwards(ViewControllerRegistry registry)
            throws Exception {
        Field registrationsField = ViewControllerRegistry.class.getDeclaredField("registrations");
        registrationsField.setAccessible(true);
        List<ViewControllerRegistration> registrations =
                (List<ViewControllerRegistration>) registrationsField.get(registry);

        Field urlPathField = ViewControllerRegistration.class.getDeclaredField("urlPath");
        urlPathField.setAccessible(true);
        Method getViewController = ViewControllerRegistration.class.getDeclaredMethod("getViewController");
        getViewController.setAccessible(true);

        Map<String, String> routes = new HashMap<>();
        for (ViewControllerRegistration registration : registrations) {
            String urlPath = (String) urlPathField.get(registration);
            ParameterizableViewController controller =
                    (ParameterizableViewController) getViewController.invoke(registration);
            routes.put(urlPath, controller.getViewName());
        }
        return routes;
    }
}
