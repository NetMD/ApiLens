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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [Phase K] ApiKeyAuthFilter 단위 테스트 — 화이트리스트/보호/401/폴백 (V-A01~A06, AC-01/AC-02).
 *
 * <p>// [Phase K] AC-01-1 verbatim: "키 설정 시, 보호 API(/v1/** 관리/조회)를 토큰 없이 호출하면
 * // 401 {"error":"..."} 가 반환된다" (R14-D02/D07 비협상). 사용자 명시 비협상 결정.
 * // CLAUDE.md '아키텍처 핵심 원칙' (신뢰망·단일 jar) 인용.
 *
 * <p>★EXT-005 정방향 동사 lock-in 가드★: 토큰 OK 케이스는 정방향(passesThrough*).
 * 보호경로 무토큰 401·오토큰 401 은 <b>AC-01-1/01-4 의 의도된 거부가 정상 동작</b>이므로
 * {@code returns401Without*} 류 메서드명은 정방향(Design EXT-005 anchor verbatim 명시).
 * dev 자기증명 grep: 화이트리스트/정토큰을 거부하는 반대방향 테스트(rejectsWhitelisted / throwsOnValidToken 류) 0 hit.
 */
class ApiKeyAuthFilterTest {

    private static final String TOKEN = "s3cr3t-key";
    private final ObjectMapper mapper = new ObjectMapper();

    private ApiKeyAuthFilter filterWithKey(String key) {
        return new ApiKeyAuthFilter(new AuthProperties(key), mapper);
    }

    // ── 키 미설정 폴백 (BL-02, AC-02-1/02-3) ──────────────────────────────

    /** [Phase K] AC-02-1/02-3 — 키 미설정(null) 시 무인증 폴백: 보호경로도 무토큰 통과(정방향). */
    @Test
    void passesThroughProtectedPathWhenKeyUnconfiguredNull() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/traces");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(null).doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res)); // 체인 통과
        assertEquals(200, res.getStatus());
    }

    /** [Phase K] AC-02-1 — 키 blank("  ") 도 미설정 취급 → 무인증 폴백(정방향). */
    @Test
    void passesThroughProtectedPathWhenKeyBlank() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/traces");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey("   ").doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res));
    }

    // ── 화이트리스트 무토큰 통과 (BL-01, AC-01-3/03-1) ────────────────────

    /** [Phase K] AC-03-1 verbatim: "POST /v1/spans 는 토큰 없이 200 으로 적재된다"(ingest 화이트리스트, 정방향). */
    @Test
    void passesThroughIngestWithoutToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/spans");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res));
        assertEquals(200, res.getStatus());
    }

    /** [Phase K] AC-01-3 — setup 경로(/v1/setup/**)는 무토큰 통과(정방향). */
    @Test
    void passesThroughSetupWithoutToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/setup/state");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res));
    }

    /** [Phase K] AC-01-3 — actuator/health probe 무토큰 통과(정방향). */
    @Test
    void passesThroughActuatorHealthWithoutToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res));
    }

    /** [Phase K] AC-01-3 — 정적 자산/SPA forward(/v1/ 도 /actuator/ 도 아님) 무토큰 통과(정방향). */
    @Test
    void passesThroughStaticAssetWithoutToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest index = get("/index.html");
        MockHttpServletRequest asset = get("/assets/index-abc.js");
        MockHttpServletRequest spa = get("/settings"); // SPA forward 경로

        filterWithKey(TOKEN).doFilter(index, new MockHttpServletResponse(), chain);
        filterWithKey(TOKEN).doFilter(asset, new MockHttpServletResponse(), chain);
        filterWithKey(TOKEN).doFilter(spa, new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(eq(index), any());
        verify(chain, times(1)).doFilter(eq(asset), any());
        verify(chain, times(1)).doFilter(eq(spa), any());
    }

    // ── 보호경로 정토큰 통과 (AC-01-2) ───────────────────────────────────

    /** [Phase K] AC-01-2 verbatim: "Authorization: Bearer <정상토큰> 헤더로 호출하면 200 정상 응답"(정방향). */
    @Test
    void passesThroughProtectedPathWithValidToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/traces");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(eq(req), eq(res));
        assertEquals(200, res.getStatus());
    }

    // ── 보호경로 무토큰/오토큰 401 (AC-01-1/01-4 — 의도된 거부 = 정방향) ──

    /** [Phase K] AC-01-1 — 보호경로 무토큰 → 401 + {"error":"unauthorized"}(HTML 아님). 의도된 거부 = 정방향. */
    @Test
    void returns401WithoutTokenOnProtectedPath() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/traces");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any()); // 체인 미호출 (차단)
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentType().contains("application/json"));
        // 본문은 {"error":"unauthorized"} — 토큰/내부정보 비노출 (AC-01-4).
        assertEquals("{\"error\":\"unauthorized\"}", res.getContentAsString());
        assertFalse(res.getContentAsString().contains(TOKEN), "응답 본문에 토큰 비노출");
    }

    /** [Phase K] AC-01-4 — 1글자 다른 오토큰 → 401(상수시간 비교). 의도된 거부 = 정방향. */
    @Test
    void returns401OnWrongTokenOnProtectedPath() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/traces");
        req.addHeader("Authorization", "Bearer " + TOKEN + "X"); // 1글자 추가
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, res.getStatus());
    }

    /** [Phase K] AC-01-1 — "Bearer " 만(토큰부 빈값) → 401(extractBearer null). 의도된 거부 = 정방향. */
    @Test
    void returns401OnEmptyBearerTokenOnProtectedPath() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = get("/v1/masking-rules");
        req.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, res.getStatus());
    }

    /** [Phase K] AC-01-1 — 신규 optimize endpoint(/v1/maintenance/optimize) 도 default-deny 보호. 의도된 거부 = 정방향. */
    @Test
    void returns401WithoutTokenOnNewOptimizeEndpoint() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/maintenance/optimize");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filterWithKey(TOKEN).doFilter(req, res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(401, res.getStatus());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
