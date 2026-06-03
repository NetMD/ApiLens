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
        boolean captureParams
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
        return new AgentConfig(false, reason, null, null,
                0.0, 0, 0, 0, 0, debug, false, false);
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

        return new AgentConfig(true, null, serverUrl, serviceName,
                sampling, batchMax, flushInterval, queueCap, payloadMax, debug, captureResultSet, captureParams);
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
