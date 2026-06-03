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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    private static final String[] ALL_KEYS = {
            AgentConfig.PROP_SERVER, AgentConfig.PROP_SERVICE_NAME,
            AgentConfig.PROP_ENABLED, AgentConfig.PROP_SAMPLING_RATE,
            AgentConfig.PROP_BATCH_MAX_SIZE, AgentConfig.PROP_BATCH_FLUSH_INTERVAL_MS,
            AgentConfig.PROP_QUEUE_CAPACITY, AgentConfig.PROP_PAYLOAD_MAX_BYTES,
            AgentConfig.PROP_DEBUG,
            AgentConfig.PROP_CAPTURE_RESULT_SET,
            AgentConfig.PROP_CAPTURE_PARAMS
    };

    private final AgentLogger silent = new AgentLogger(false);
    private final Map<String, String> snapshot = new HashMap<>();

    @BeforeEach
    void snapshotProps() {
        for (String k : ALL_KEYS) {
            snapshot.put(k, System.getProperty(k));
            System.clearProperty(k);
        }
    }

    @AfterEach
    void restoreProps() {
        for (String k : ALL_KEYS) {
            String previous = snapshot.get(k);
            if (previous == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, previous);
            }
        }
        snapshot.clear();
    }

    @Test
    void missingServiceNameProducesDisabledConfig() {
        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertFalse(config.enabled());
        assertNotNull(config.disabledReason());
        assertTrue(config.disabledReason().contains(AgentConfig.PROP_SERVICE_NAME));
    }

    @Test
    void enabledFalseProducesDisabledConfig() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "ignored");
        System.setProperty(AgentConfig.PROP_ENABLED, "false");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertFalse(config.enabled());
        assertTrue(config.disabledReason().contains("apilens.enabled"));
    }

    @Test
    void defaultsApplyWhenOnlyServiceNameProvided() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "checkout");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertNull(config.disabledReason());
        assertEquals("checkout", config.serviceName());
        assertEquals(AgentConfig.DEFAULT_SERVER, config.serverUrl());
        assertEquals(AgentConfig.DEFAULT_SAMPLING_RATE, config.samplingRate());
        assertEquals(AgentConfig.DEFAULT_BATCH_MAX_SIZE, config.batchMaxSize());
        assertEquals(AgentConfig.DEFAULT_BATCH_FLUSH_INTERVAL_MS, config.batchFlushIntervalMs());
        assertEquals(AgentConfig.DEFAULT_QUEUE_CAPACITY, config.queueCapacity());
        assertEquals(AgentConfig.DEFAULT_PAYLOAD_MAX_BYTES, config.payloadMaxBytes());
        assertFalse(config.debug());
    }

    @Test
    void samplingRateOutOfRangeFallsBackToDefault() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SAMPLING_RATE, "1.5");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled(), "out-of-range sampling must NOT disable the agent");
        assertEquals(AgentConfig.DEFAULT_SAMPLING_RATE, config.samplingRate());
    }

    @Test
    void samplingRateNegativeFallsBackToDefault() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SAMPLING_RATE, "-0.1");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertEquals(AgentConfig.DEFAULT_SAMPLING_RATE, config.samplingRate());
    }

    @Test
    void garbageNumericFallsBackToDefaultsWithoutDisabling() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_BATCH_MAX_SIZE, "not-a-number");
        System.setProperty(AgentConfig.PROP_QUEUE_CAPACITY, "-50");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertEquals(AgentConfig.DEFAULT_BATCH_MAX_SIZE, config.batchMaxSize());
        assertEquals(AgentConfig.DEFAULT_QUEUE_CAPACITY, config.queueCapacity());
    }

    @Test
    void invalidServerUrlDisablesAgent() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SERVER, "not a url");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertFalse(config.enabled());
        assertTrue(config.disabledReason().contains("server URL"));
    }

    @Test
    void nonHttpSchemeRejected() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SERVER, "ftp://example.com");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertFalse(config.enabled());
    }

    @Test
    void httpsSchemeAccepted() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SERVER, "https://apilens.internal:9443");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertEquals("https://apilens.internal:9443", config.serverUrl());
    }

    @Test
    void trailingSlashStrippedFromServerUrl() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_SERVER, "http://localhost:8765/");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertEquals("http://localhost:8765", config.serverUrl());
    }

    @Test
    void debugFlagPropagates() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_DEBUG, "true");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertTrue(config.debug());
    }

    @Test
    void blankServiceNameTreatedAsMissing() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "   ");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertFalse(config.enabled());
        assertTrue(config.disabledReason().contains(AgentConfig.PROP_SERVICE_NAME));
    }

    // ─── Phase E3 — captureParams default ON + kill switch (사용자 비협상 D-03) ────

    /**
     * UT-CFG-CAP-01: PROP_CAPTURE_PARAMS unset → captureParams = true
     * (사용자 비협상 D-03 직접 인용 — default ON. parseBoolean 두 번째 인자가
     * {@code true} 임을 단언한다 — 두 번째 인자 변경 시 review-arch FAIL 조건).
     */
    @Test
    void captureParamsDefaultsToTrueWhenUnset() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertTrue(config.captureParams(),
                "default 변경 권한 0 — D-03 비협상: captureParams 디폴트는 반드시 true");
    }

    /** UT-CFG-CAP-02: 명시 false → kill switch 작동, captureParams = false. */
    @Test
    void captureParamsExplicitFalseTurnsOff() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_CAPTURE_PARAMS, "false");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertFalse(config.captureParams(),
                "kill switch (-Dapilens.jdbc.capture-params=false) 가 captureParams 를 false 로 설정해야 함");
    }

    /** UT-CFG-CAP-03: 명시 true 도 정상 — 운영자 명시 opt-in 경로. */
    @Test
    void captureParamsExplicitTrueAccepted() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_CAPTURE_PARAMS, "true");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.captureParams());
    }

    /**
     * UT-CFG-CAP-04: 잘못된 값 ("garbage") 은 parseBoolean fallback 으로
     * default(true) 유지 — 운영망 오타 시에도 agent 가 사용자 가치 (D-03 default ON)
     * 를 유지한다.
     */
    @Test
    void captureParamsGarbageValueFallsBackToDefaultTrue() {
        System.setProperty(AgentConfig.PROP_SERVICE_NAME, "svc");
        System.setProperty(AgentConfig.PROP_CAPTURE_PARAMS, "yes-please");

        AgentConfig config = AgentConfig.fromSystemProperties(silent);

        assertTrue(config.enabled());
        assertTrue(config.captureParams(),
                "잘못된 값은 default(true) 로 fallback — 운영망 오타 시에도 가치 보존");
    }

    /**
     * UT-CFG-CAP-05: PROP_CAPTURE_PARAMS 문자열 상수가 사용자 비협상 D-03 의
     * 정확값 "apilens.jdbc.capture-params" 인지 자기증명 (G-12 회귀 가드).
     */
    @Test
    void capturePropertyNameMatchesDocumentedValue() {
        assertEquals("apilens.jdbc.capture-params", AgentConfig.PROP_CAPTURE_PARAMS,
                "사용자 비협상 D-03 시스템 프로퍼티 키는 정확 'apilens.jdbc.capture-params' 여야 함 (docs/agent-options.md cross-link)");
    }

    /** UT-CFG-CAP-06: disabled() factory 도 captureParams=false 로 안전한 디폴트를 갖는다. */
    @Test
    void disabledFactoryHasCaptureParamsFalse() {
        AgentConfig disabled = AgentConfig.disabled("test reason", false);

        assertFalse(disabled.enabled());
        assertFalse(disabled.captureParams(),
                "disabled 상태에서는 advice 자체가 weaving 되지 않으므로 false 가 일관적");
    }
}
