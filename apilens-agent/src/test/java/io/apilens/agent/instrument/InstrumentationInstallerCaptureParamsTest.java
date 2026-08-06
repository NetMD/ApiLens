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
package io.apilens.agent.instrument;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.agent.config.AgentConfig;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.MaskingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.instrument.Instrumentation;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase E3 — kill-switch behaviour test for {@link InstrumentationInstaller}.
 *
 * <p>The matchers themselves are exercised by {@code SpringMatchersTest}. Here we
 * pin the contract that the {@code captureParams} flag flows from the parsed
 * {@link AgentConfig} into the {@link InstrumentationInstaller#CAPTURE_PARAMS}
 * static field, which the runtime advice bodies read on every invocation.
 *
 * <p>AC mapping: AC-04-1, AC-04-3 (kill switch / G-13).
 */
class InstrumentationInstallerCaptureParamsTest {

    private boolean previousCaptureParams;
    private boolean previousDebug;

    @BeforeEach
    void snapshotFlags() {
        previousCaptureParams = InstrumentationInstaller.CAPTURE_PARAMS;
        previousDebug = InstrumentationInstaller.DEBUG;
    }

    @AfterEach
    void restoreFlags() {
        InstrumentationInstaller.CAPTURE_PARAMS = previousCaptureParams;
        InstrumentationInstaller.DEBUG = previousDebug;
    }

    /**
     * UT-INST-01: captureParams=true → static field flips on. install() returns
     * cleanly even when no AgentBuilder side-effect is observable in this JVM.
     */
    @Test
    void captureParamsTrueRegistersTwoNewAdvices() {
        InstrumentationInstaller.CAPTURE_PARAMS = false;
        Instrumentation instrumentation = Mockito.mock(Instrumentation.class);

        InstrumentationInstaller.install(
                instrumentation,
                stubConfig(true),
                Mockito.mock(SpanQueue.class),
                new MaskingEngine(List.of(), new ObjectMapper()),
                new AgentLogger(false));

        assertTrue(InstrumentationInstaller.CAPTURE_PARAMS,
                "captureParams=true must propagate to the runtime static field");
    }

    /**
     * UT-INST-02: captureParams=false → static field flips off. Advice classes
     * never get woven into target bytecode (verified separately by the matcher
     * tests + design grep).
     */
    @Test
    void captureParamsFalseSkipsTwoAdvices() {
        InstrumentationInstaller.CAPTURE_PARAMS = true;
        Instrumentation instrumentation = Mockito.mock(Instrumentation.class);

        InstrumentationInstaller.install(
                instrumentation,
                stubConfig(false),
                Mockito.mock(SpanQueue.class),
                new MaskingEngine(List.of(), new ObjectMapper()),
                new AgentLogger(false));

        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS,
                "captureParams=false kill switch must propagate to the runtime static field");
    }

    /**
     * UT-INST-03: re-installing with a different captureParams flips the static
     * field — supports a defensive hot-reload-style scenario.
     */
    @Test
    void captureParamsStaticFieldUpdated() {
        Instrumentation instrumentation = Mockito.mock(Instrumentation.class);

        InstrumentationInstaller.install(
                instrumentation, stubConfig(true), Mockito.mock(SpanQueue.class),
                new MaskingEngine(List.of(), new ObjectMapper()), new AgentLogger(false));
        assertTrue(InstrumentationInstaller.CAPTURE_PARAMS);

        InstrumentationInstaller.install(
                instrumentation, stubConfig(false), Mockito.mock(SpanQueue.class),
                new MaskingEngine(List.of(), new ObjectMapper()), new AgentLogger(false));
        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS,
                "second install with captureParams=false must flip the static field off");
    }

    /**
     * Build a minimal valid AgentConfig for the test — enabled, with a recognised
     * server URL and service name so install() doesn't early-return.
     */
    private static AgentConfig stubConfig(boolean captureParams) {
        return new AgentConfig(
                true,                                 // enabled
                null,                                 // disabledReason
                "http://localhost:8765",              // serverUrl
                "test-svc",                           // serviceName
                AgentConfig.DEFAULT_SAMPLING_RATE,    // samplingRate
                AgentConfig.DEFAULT_BATCH_MAX_SIZE,   // batchMaxSize
                AgentConfig.DEFAULT_BATCH_FLUSH_INTERVAL_MS,
                AgentConfig.DEFAULT_QUEUE_CAPACITY,
                AgentConfig.DEFAULT_PAYLOAD_MAX_BYTES,
                false,                                // debug
                false,                                // captureResultSet
                captureParams,
                List.of(),                            // [Phase R18] excludePackages — default 제외 없음
                false);                               // [Phase R20] R20/AC-01-1 requireEntryRoot — default 꺼짐
    }
}
