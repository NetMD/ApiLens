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
package io.apilens.agent.config;

import io.apilens.agent.util.AgentLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent runtime configuration parsed from JVM system properties (-D...).
 *
 * <p>Parsing never throws — any malformed input falls back to a default value
 * (with a warning) or, when no safe default exists (e.g. missing serviceName,
 * unparseable serverUrl), produces a {@code disabled} config so {@link AgentConfig#enabled}
 * is {@code false} and the agent silently no-ops while the host app keeps running.
 *
 * @param enabled              false when agent should not start (missing required option, parse error)
 * @param disabledReason       human-readable reason; null when {@link #enabled} is true
 * @param serverUrl            ApiLens server base URL, e.g. {@code http://localhost:8765}
 * @param serviceName          required service identifier
 * @param samplingRate         head-based sampling, [0.0, 1.0]
 * @param batchMaxSize         max spans per HTTP POST
 * @param batchFlushIntervalMs forced flush interval even when batch is partial
 * @param queueCapacity        in-memory buffer; over-capacity offers are dropped
 * @param payloadMaxBytes      payload truncation threshold
 * @param debug                when true, agent emits verbose stderr logs
 * @param captureResultSet     when true, wrap JDBC ResultSet for payload_out capture (opt-in, default false)
 * @param captureParams        when true, capture PreparedStatement setter parameters into PAYLOAD IN.
 *                             Phase E3 (사용자 비협상 D-03): default {@code true} — kill switch for
 *                             operators worried about hot-path overhead is {@code -Dapilens.jdbc.capture-params=false}.
 * @param excludePackages      weaving-time instrumentation exclude list — comma-separated package
 *                             prefixes. Phase R18 (사용자 비협상 NFR-05): default {@code List.of()}
 *                             (= 제외 없음 = 현 계측 그대로, opt-in). Types whose name starts with any
 *                             listed prefix are never advice-woven (runtime cost zero — the advice
 *                             bytecode is simply not synthesised).
 */
public record AgentConfig(
        boolean enabled,
        String disabledReason,
        String serverUrl,
        String serviceName,
        double samplingRate,
        int batchMaxSize,
        long batchFlushIntervalMs,
        int queueCapacity,
        int payloadMaxBytes,
        boolean debug,
        boolean captureResultSet,
        boolean captureParams,
        List<String> excludePackages
) {

    public static final String PROP_SERVER = "apilens.server";
    public static final String PROP_SERVICE_NAME = "apilens.service.name";
    public static final String PROP_ENABLED = "apilens.enabled";
    public static final String PROP_SAMPLING_RATE = "apilens.sampling.rate";
    public static final String PROP_BATCH_MAX_SIZE = "apilens.batch.max-size";
    public static final String PROP_BATCH_FLUSH_INTERVAL_MS = "apilens.batch.flush-interval-ms";
    public static final String PROP_QUEUE_CAPACITY = "apilens.queue.capacity";
    public static final String PROP_PAYLOAD_MAX_BYTES = "apilens.payload.max-bytes";
    public static final String PROP_DEBUG = "apilens.debug";
    public static final String PROP_CAPTURE_RESULT_SET = "apilens.jdbc.capture-result-set";
    /**
     * Phase E3 — JDBC parameter capture kill switch
     * (사용자 비협상 D-03 직접 인용: "{@code -Dapilens.jdbc.capture-params=true|false},
     * default=true, 표준 API 호환성은 default 충족"). default change forbidden —
     * review-arch FAIL condition.
     */
    public static final String PROP_CAPTURE_PARAMS = "apilens.jdbc.capture-params";
    /**
     * [Phase R18] AC-01-1 — 계측량 제어 opt-in 패키지 exclude 필터. 사용자 명시 비협상 결정
     * (NFR-05: default = 현 계측 유지). 값 = 콤마 구분 패키지 prefix 목록. 미설정/빈 값이면
     * 빈 목록(= 제외 없음 = weaving byte-identical). weaving 시점에만 쓰여 런타임 비용 0 —
     * {@link io.apilens.agent.instrument.matcher.SpringMatchers#userExcludedTypes(List)} 의
     * {@code .ignore(...)} 합성으로만 소비된다(static 필드 불요, advice 런타임 미참조).
     * CLAUDE.md 'v0.1 범위'(계측 레버) · '아키텍처 핵심 원칙'(Agent 는 가볍게) 인용.
     */
    public static final String PROP_EXCLUDE_PACKAGES = "apilens.instrument.exclude-packages";

    public static final String DEFAULT_SERVER = "http://localhost:8765";
    public static final double DEFAULT_SAMPLING_RATE = 1.0;
    public static final int DEFAULT_BATCH_MAX_SIZE = 100;
    public static final long DEFAULT_BATCH_FLUSH_INTERVAL_MS = 1000L;
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    public static final int DEFAULT_PAYLOAD_MAX_BYTES = 65_536;

    public static AgentConfig disabled(String reason, boolean debug) {
        // captureParams=false in disabled state — advice classes never get to weave,
        // so the runtime value is irrelevant; we pick false to stay consistent with
        // captureResultSet (also opt-in / off when the agent itself is off).
        // excludePackages=List.of() — [Phase R18] disabled 상태에선 어차피 weaving 0 이므로
        // 빈 목록이 일관적(제외 대상 없음).
        return new AgentConfig(false, reason, null, null,
                0.0, 0, 0, 0, 0, debug, false, false, List.of());
    }

    /**
     * Parses configuration from {@link System#getProperty(String)}. All errors are
     * captured to the warn channel of {@code logger} — this method never throws.
     */
    public static AgentConfig fromSystemProperties(AgentLogger logger) {
        boolean debug = parseBoolean(PROP_DEBUG, false, logger);

        if (!parseBoolean(PROP_ENABLED, true, logger)) {
            return disabled("disabled via " + PROP_ENABLED + "=false", debug);
        }

        String serviceName = trimToNull(System.getProperty(PROP_SERVICE_NAME));
        if (serviceName == null) {
            return disabled("required option missing: " + PROP_SERVICE_NAME, debug);
        }

        String serverRaw = trimToNull(System.getProperty(PROP_SERVER));
        if (serverRaw == null) {
            serverRaw = DEFAULT_SERVER;
        }
        String serverUrl = validateUrl(serverRaw, logger);
        if (serverUrl == null) {
            return disabled("invalid server URL: " + serverRaw, debug);
        }

        double sampling = parseDouble(PROP_SAMPLING_RATE, DEFAULT_SAMPLING_RATE, logger);
        if (sampling < 0.0 || sampling > 1.0) {
            logger.warn(PROP_SAMPLING_RATE + "=" + sampling + " out of [0,1], using "
                    + DEFAULT_SAMPLING_RATE);
            sampling = DEFAULT_SAMPLING_RATE;
        }

        int batchMax = parsePositiveInt(PROP_BATCH_MAX_SIZE, DEFAULT_BATCH_MAX_SIZE, logger);
        long flushInterval = parsePositiveLong(PROP_BATCH_FLUSH_INTERVAL_MS,
                DEFAULT_BATCH_FLUSH_INTERVAL_MS, logger);
        int queueCap = parsePositiveInt(PROP_QUEUE_CAPACITY, DEFAULT_QUEUE_CAPACITY, logger);
        int payloadMax = parsePositiveInt(PROP_PAYLOAD_MAX_BYTES, DEFAULT_PAYLOAD_MAX_BYTES, logger);
        boolean captureResultSet = parseBoolean(PROP_CAPTURE_RESULT_SET, false, logger);
        // 사용자 비협상 D-03: default=true. 두 번째 인자 변경 시 review-arch FAIL.
        boolean captureParams = parseBoolean(PROP_CAPTURE_PARAMS, true, logger);
        // [Phase R18] AC-01-1/AC-01-2 — 사용자 명시 비협상(NFR-05): 미설정/빈 값 → List.of()
        //   (= 제외 없음 = 현 계측 그대로). 침묵 회귀 0.
        List<String> excludePackages = parseCommaList(System.getProperty(PROP_EXCLUDE_PACKAGES));

        return new AgentConfig(true, null, serverUrl, serviceName,
                sampling, batchMax, flushInterval, queueCap, payloadMax, debug, captureResultSet,
                captureParams, excludePackages);
    }

    /**
     * [Phase R18] AC-01-1 — 콤마 구분 목록을 trim + 빈 항목 제거 후 immutable 리스트로 파싱한다.
     * null / 빈 문자열 / 공백만 / 후행 콤마 / 전부 빈 항목은 모두 {@code List.of()} 로 귀결(안전 폴백).
     */
    private static List<String> parseCommaList(String raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean parseBoolean(String key, boolean defaultValue, AgentLogger logger) {
        String value = trimToNull(System.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        logger.warn(key + "=" + value + " not a boolean, using " + defaultValue);
        return defaultValue;
    }

    private static double parseDouble(String key, double defaultValue, AgentLogger logger) {
        String value = trimToNull(System.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warn(key + "=" + value + " not a number, using " + defaultValue);
            return defaultValue;
        }
    }

    private static int parsePositiveInt(String key, int defaultValue, AgentLogger logger) {
        String value = trimToNull(System.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                logger.warn(key + "=" + parsed + " must be > 0, using " + defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn(key + "=" + value + " not an integer, using " + defaultValue);
            return defaultValue;
        }
    }

    private static long parsePositiveLong(String key, long defaultValue, AgentLogger logger) {
        String value = trimToNull(System.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                logger.warn(key + "=" + parsed + " must be > 0, using " + defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn(key + "=" + value + " not a long, using " + defaultValue);
            return defaultValue;
        }
    }

    private static String validateUrl(String raw, AgentLogger logger) {
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() == null
                    || (!uri.getScheme().equalsIgnoreCase("http")
                    && !uri.getScheme().equalsIgnoreCase("https"))) {
                logger.warn("server URL must use http or https: " + raw);
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                logger.warn("server URL missing host: " + raw);
                return null;
            }
            // strip trailing slash so callers can append "/v1/spans" safely
            String normalised = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
            return normalised;
        } catch (URISyntaxException e) {
            logger.warn("server URL not a valid URI: " + raw);
            return null;
        }
    }
}
