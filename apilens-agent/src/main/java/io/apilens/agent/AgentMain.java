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
package io.apilens.agent;

import io.apilens.agent.config.AgentConfig;
import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.agent.transport.HttpTransport;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.agent.transport.SpanSender;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.MaskingEngine;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JVM agent entry point ({@code Premain-Class}). Wired up by the manifest of
 * the shadow jar — see {@code apilens-agent/build.gradle.kts}.
 *
 * <p>Phase D scope: bring up the transport pipeline and emit a single hello
 * span so the operator can verify the agent → server channel before any
 * ByteBuddy instrumentation is added in Phase E.
 *
 * <p>Hard rule: this method must never propagate an exception. Any failure
 * here would crash the host application — exactly the opposite of what an
 * observability tool is supposed to do.
 */
public final class AgentMain {

    public static final String AGENT_VERSION = "0.1.0";

    private AgentMain() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            // 주의: bootstrap classloader 등록(appendToBootstrapClassLoaderSearch)을
            // 시도하지 말 것. JDK 9+ 모듈 시스템에서 bootstrap loader는 java.base만
            // 보이고 java.net.http (HttpTransport가 사용) 같은 별도 module을 못 봐서
            // NoClassDefFoundError로 agent 자체가 죽음. 일반 Spring Boot 환경에서는
            // -javaagent로 system classloader에 agent jar가 올라가고 app classloader가
            // 그 자식이므로 advice helper 클래스 가시성은 자연스레 확보됨.
            init(instrumentation);
        } catch (Throwable t) {
            // Last line of defence — agent disabled, host app continues unaffected.
            System.err.println("[ApiLens] agent failed to initialise, will not run: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void init(Instrumentation instrumentation) {
        // Logger is created with a tentative debug flag derived directly from the
        // raw system property so that AgentConfig parsing diagnostics are visible
        // even before the full config is built.
        boolean debugBootstrap = "true".equalsIgnoreCase(System.getProperty(AgentConfig.PROP_DEBUG));
        AgentLogger logger = new AgentLogger(debugBootstrap);

        AgentConfig config = AgentConfig.fromSystemProperties(logger);
        if (!config.enabled()) {
            logger.info("agent disabled: " + config.disabledReason());
            return;
        }

        SpanQueue queue = new SpanQueue(config.queueCapacity());
        HttpTransport transport = new HttpTransport(config.serverUrl(), logger);
        SpanSender sender = new SpanSender(
                queue, transport, logger, config.batchMaxSize(), config.batchFlushIntervalMs());

        Thread senderThread = new Thread(sender, "apilens-sender");
        senderThread.setDaemon(true);
        senderThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sender.shutdown();
            try {
                senderThread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "apilens-shutdown"));

        // Hello span: the verifiable signal that the pipeline is alive end-to-end.
        if (!queue.offer(buildHelloSpan(config))) {
            logger.warn("hello span dropped (queue full at startup, capacity=" + config.queueCapacity() + ")");
        }

        // v0.1 masking 정책: server-side 적용 (CLAUDE.md). agent는 빈 룰셋으로 통과.
        // client-side masking 토글은 v0.2.
        MaskingEngine masking = new MaskingEngine(List.of(), new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

        // ByteBuddy advice 설치 — controller/service/repository/jdbc 자동 인스트루먼트
        if (instrumentation != null) {
            InstrumentationInstaller.install(instrumentation, config, queue, masking, logger);
        } else {
            // dynamic attach가 아닌 -javaagent로만 동작 가정 — null이면 transport-only 모드
            logger.warn("Instrumentation is null; running in transport-only mode (no advice installed)");
        }

        logger.info("ApiLens agent started: service=" + config.serviceName()
                + ", server=" + config.serverUrl()
                + ", samplingRate=" + config.samplingRate()
                + ", batchMaxSize=" + config.batchMaxSize());
    }

    static Span buildHelloSpan(AgentConfig config) {
        long now = System.currentTimeMillis();
        String traceId = "agent-startup-" + shortId();
        String spanId = shortId();
        return new Span(
                spanId,
                traceId,
                null,
                config.serviceName(),
                "agent.startup",
                SpanKind.INTERNAL,
                now,
                now,
                SpanStatus.OK,
                Map.of(
                        "apilens.agent.version", AGENT_VERSION,
                        "apilens.agent.pid", String.valueOf(ProcessHandle.current().pid())
                ),
                List.of()
        );
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
