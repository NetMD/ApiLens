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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase K] AuthWhitelist prefix 판정 단위 테스트 (AC-01-3, default-deny for /v1/**).
 *
 * <p>// [Phase K] AC-01-3 — R14-D04 비협상: ingest 무인증 화이트리스트 + 신규 /v1/** 자동 보호
 * // (default-deny 역방향 기본값, Design §2.2). 사용자 명시 비협상 결정.
 * // CLAUDE.md '아키텍처 핵심 원칙' (신뢰망) 인용.
 *
 * <p>★EXT-005 정방향 동사 lock-in 가드★: returns true(면제)/returns false(보호) 는 판정 결과의
 * 정방향 단언이다. 메서드명 {@code whitelists*}/{@code protects*} 는 AC 의도 자체(거부 lock-in 아님).
 */
class AuthWhitelistTest {

    /** [Phase K] AC-01-3 — setup 묶음(/v1/setup/**)은 면제(true). */
    @Test
    void whitelistsSetupPrefix() {
        assertTrue(AuthWhitelist.isWhitelisted(get("/v1/setup/state")));
        assertTrue(AuthWhitelist.isWhitelisted(post("/v1/setup/complete")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/v1/setup/agent-jar")));
    }

    /** [Phase K] AC-03-1 — ingest POST /v1/spans 면제(true). 다른 메서드는 보호(false). */
    @Test
    void whitelistsIngestPostOnly() {
        assertTrue(AuthWhitelist.isWhitelisted(post("/v1/spans")));
        // GET /v1/spans 는 ingest 가 아님 → /v1/** 보호 (default-deny).
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/spans")));
    }

    /** [Phase K] AC-01-3 — actuator/health probe 면제(true). */
    @Test
    void whitelistsActuatorHealth() {
        assertTrue(AuthWhitelist.isWhitelisted(get("/actuator/health")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/actuator/health/liveness")));
    }

    /** [Phase K] AC-01-3 — 정적 자산/SPA forward(/v1/ 도 /actuator/ 도 아님) 면제(true). */
    @Test
    void whitelistsStaticAndSpaForward() {
        assertTrue(AuthWhitelist.isWhitelisted(get("/")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/index.html")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/assets/index-abc.js")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/vite.svg")));
        assertTrue(AuthWhitelist.isWhitelisted(get("/settings")));   // SPA forward
        assertTrue(AuthWhitelist.isWhitelisted(get("/traces/abc")));  // SPA forward
    }

    /** [Phase K] AC-01-3 — 보호 묶음(/v1/** 비-화이트리스트)은 전부 false. */
    @Test
    void protectsManagementAndQueryApis() {
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/settings")));
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/masking-rules")));
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/traces")));
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/services")));
        assertFalse(AuthWhitelist.isWhitelisted(post("/v1/maintenance/cleanup")));
        assertFalse(AuthWhitelist.isWhitelisted(post("/v1/maintenance/purge")));
    }

    /**
     * [Phase K] AC-01-3 — ★신규 endpoint 자동 보호★: 화이트리스트에 명시 enumerate 하지 않은
     * 신규 /v1/** 는 전부 보호(false). default-deny 역방향 기본값(Design §2.2 핵심).
     */
    @Test
    void protectsNewlyAddedV1EndpointsByDefault() {
        // 본 라운드 신규 optimize.
        assertFalse(AuthWhitelist.isWhitelisted(post("/v1/maintenance/optimize")));
        // 가상의 미래 신규 endpoint — 화이트리스트 코드 변경 0 으로도 자동 보호.
        assertFalse(AuthWhitelist.isWhitelisted(get("/v1/future-new-api")));
        assertFalse(AuthWhitelist.isWhitelisted(post("/v1/some/nested/endpoint")));
    }

    /** [Phase K] AC-01-3 — /actuator/** (health 제외)은 보호(false). */
    @Test
    void protectsNonHealthActuatorEndpoints() {
        assertFalse(AuthWhitelist.isWhitelisted(get("/actuator/info")));
        assertFalse(AuthWhitelist.isWhitelisted(get("/actuator/metrics")));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static MockHttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
