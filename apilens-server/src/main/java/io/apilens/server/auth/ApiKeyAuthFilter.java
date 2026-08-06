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
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Lightweight API key filter (R14-D07 — 경량 OncePerRequestFilter, spring-security 미추가).
 *
 * <p>// [Phase K] AC-01-1/AC-01-4/AC-01-5/AC-02-2 — R14-D02/D07/D08 사용자 명시 비협상 결정:
 * // (a) 화이트리스트 판정 (b) Authorization: Bearer 토큰 추출 (c) 起動 옵션 토큰과 상수시간 비교
 * // (d) 불일치/누락 시 401 + {"error":...} 직접 쓰기 — 인증 단일 책임만 (인가/RBAC/감사 0건, planner §8-Filter).
 * // 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' (신뢰망·단일 jar) 인용.
 *
 * <p>GT-6: 순수 서블릿 필터 — Spring SecurityFilterChain 아님. {@code @Component} 등록 시 Spring Boot 가
 * FilterRegistrationBean 을 자동 생성한다. spring-security 의존 0(libs.versions.toml 0 hit).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** [Phase K] AC-01-2 — A2 표준 토큰 헤더 prefix (양측 동일 리터럴, Design §5). */
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthProperties props;
    private final ObjectMapper mapper;

    public ApiKeyAuthFilter(AuthProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * 키 미설정 시 기동 WARN 정확히 1회 (BL-03 — R14-D08). 매 요청 출력 금지라 @PostConstruct 1회.
     *
     * <p>// [Phase K] AC-02-2 — 키 미설정(env·시스템 프로퍼티 모두 비어 있음) 시 기동 로그 WARN 1회.
     */
    @PostConstruct
    void warnIfAuthDisabled() {
        if (!props.isConfigured()) {
            log.warn("API key not configured (apilens.auth.api-key empty) — authentication disabled, "
                    + "all endpoints open. Set APILENS_AUTH_API_KEY to enable.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // 1. 키 미설정 = 인증 비활성 → 항상 통과 (BL-02, R14-D08, AC-02-1/02-3).
        if (!props.isConfigured()) {
            chain.doFilter(req, res);
            return;
        }
        // 2. 화이트리스트 path → 토큰 검증 skip (BL-01, AC-01-3/03-1).
        if (AuthWhitelist.isWhitelisted(req)) {
            chain.doFilter(req, res);
            return;
        }
        // 3. 토큰 추출 + 상수시간 비교 (BL-04, AC-01-2/01-5).
        if (compareToken(extractBearer(req))) {
            chain.doFilter(req, res);
            return;
        }
        // 4. 불일치/누락 → 401 + {"error":...} 직접 쓰기 (BL-05, AC-01-1/01-4) — 체인 중단.
        // [Phase R21] R21/AC-08-1 (L-5) — 401 거절 관측성. 메서드·경로만 기록한다(인증 판정 로직 diff 0).
        //   토큰 값·Authorization 헤더 내용 로깅 금지(자격 증명 유출 방지 — 오타 토큰도 준자격 정보).
        log.debug("auth rejected (401): {} {}", req.getMethod(), req.getRequestURI());
        writeUnauthorized(res);
    }

    /**
     * Authorization: Bearer &lt;token&gt; 에서 토큰부 추출. 없거나 prefix 불일치/빈 토큰이면 null.
     */
    private String extractBearer(HttpServletRequest req) {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length());
        return token.isEmpty() ? null : token;
    }

    /**
     * 상수시간 토큰 비교 — 단일 진입점(EXT-008). MessageDigest.isEqual 직접 호출은 이 메서드 안에서만(1/1).
     *
     * <p>// [Phase K] AC-01-5 — R14-D02 비협상: 토큰 비교는 상수시간(MessageDigest.isEqual 류)으로 수행.
     * // String.equals/== 단축 평가 금지(타이밍 누출). 사용자 명시 비협상 결정.
     */
    private boolean compareToken(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] expected = props.apiKey().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual); // allow: 상수시간 비교 단일 진입점 (EXT-008 1/1)
    }

    /**
     * 401 본문을 필터가 직접 완성한다 (Design §2.7 위임 #3 — ★비협상★).
     *
     * <p>// [Phase K] AC-01-1/AC-01-4 — res.sendError() 금지: sendError 는 컨테이너 ERROR 디스패치를
     * // 트리거해 BasicErrorController(/error)가 HTML whitelabel / 다른 JSON 형식으로 응답할 수 있어
     * // {"error":...} 표준이 깨진다. setStatus + setContentType(json) + writer 직접 write + return
     * // (체인 미호출) 으로만 응답한다. 본문에 토큰/내부정보 0. 사용자 명시 비협상 결정(Design §2.7).
     */
    private void writeUnauthorized(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        res.getWriter().write(mapper.writeValueAsString(Map.of("error", "unauthorized")));
    }
}
