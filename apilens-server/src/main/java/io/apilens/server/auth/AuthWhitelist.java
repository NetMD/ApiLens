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

import jakarta.servlet.http.HttpServletRequest;

/**
 * Path-based whitelist decision for {@link ApiKeyAuthFilter} (BL-01).
 *
 * <p>// [Phase K] AC-01-3/AC-03-1 — R14-D04 비협상: ingest(POST /v1/spans) 무인증 화이트리스트
 * // (agent HttpTransport 무변경). 사용자 명시 비협상 결정.
 * // CLAUDE.md '절대 변경하지 말아야 할 결정 사항' (포트 8765·신뢰망) 인용.
 *
 * <p>★설계 핵심 (default-deny for /v1/**, Design §2.2)★: 화이트리스트를 "면제 목록 enumerate"
 * 가 아니라 <b>"/v1/** 와 /actuator/** 가 아니면 정적 자산(면제)"</b> 의 역방향 기본값으로 둔다.
 * 따라서 신규 보호 API(/v1/foo) 는 자동으로 보호되어 "신규 endpoint 검증 누락" 위험을 구조로 차단한다.
 *
 * <p>면제(인증 skip) 경로:
 * <ul>
 *   <li>setup — {@code /v1/setup/**} (state/complete/agent-jar-path/agent-jar)</li>
 *   <li>ingest — {@code POST /v1/spans} (R14-D04, agent 무변경)</li>
 *   <li>health — {@code /actuator/health[/**]} (probe)</li>
 *   <li>정적 자산·SPA forward — /v1/** 도 /actuator/** 도 아닌 모든 경로 (index.html, /assets/**,
 *       /setup, /services, /traces/**, /settings 등 — WebMvcConfig forward 포함)</li>
 * </ul>
 *
 * <p>그 외 {@code /v1/**} 와 {@code /actuator/**} (health 제외) 는 전부 보호(토큰 필수).
 */
final class AuthWhitelist {

    private static final String API_PREFIX = "/v1/";
    private static final String ACTUATOR_PREFIX = "/actuator/";
    private static final String SETUP_PREFIX = "/v1/setup/";
    private static final String INGEST_PATH = "/v1/spans";
    private static final String HEALTH_PREFIX = "/actuator/health";

    private AuthWhitelist() {
    }

    /**
     * Returns whether the request path is exempt from token verification (인증 면제).
     *
     * <p>// [Phase K] AC-01-3 — 화이트리스트 판정. method + path prefix 매칭.
     */
    static boolean isWhitelisted(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) {
            return false;
        }
        // 1. setup 묶음 — /v1/setup/** 전체 면제 (정확 4 endpoint, prefix 로 일괄).
        if (path.startsWith(SETUP_PREFIX)) {
            return true;
        }
        // 2. ingest — POST /v1/spans 정확 매칭 (R14-D04). 다른 메서드는 보호.
        if ("POST".equalsIgnoreCase(req.getMethod()) && INGEST_PATH.equals(path)) {
            return true;
        }
        // 3. health probe — /actuator/health 및 그 하위.
        if (path.equals(HEALTH_PREFIX) || path.startsWith(HEALTH_PREFIX + "/")) {
            return true;
        }
        // 4. 정적 자산·SPA forward — /v1/** 도 /actuator/** 도 아니면 면제(역방향 기본값).
        //    신규 /v1/** API 는 여기서 false → 자동 보호(default-deny for /v1/**).
        return !path.startsWith(API_PREFIX) && !path.startsWith(ACTUATOR_PREFIX);
    }
}
