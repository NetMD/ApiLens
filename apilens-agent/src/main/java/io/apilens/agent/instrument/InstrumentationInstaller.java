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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.apilens.agent.config.AgentConfig;
import io.apilens.agent.instrument.advice.ControllerAdvice;
import io.apilens.agent.instrument.advice.JdbcAdvice;
import io.apilens.agent.instrument.advice.JdbcConnectionAdvice;
import io.apilens.agent.instrument.advice.JdbcGetResultSetAdvice;
import io.apilens.agent.instrument.advice.MyBatisMapperAdvice;
import io.apilens.agent.instrument.advice.PreparedStatementAddBatchAdvice;
import io.apilens.agent.instrument.advice.PreparedStatementParamAdvice;
import io.apilens.agent.instrument.advice.RepositoryAdvice;
import io.apilens.agent.instrument.advice.ServiceAdvice;
import io.apilens.agent.instrument.matcher.SpringMatchers;
import io.apilens.agent.transport.SpanQueue;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.MaskingEngine;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

/**
 * Wires ByteBuddy's {@link AgentBuilder} to four {@code @Advice} classes —
 * controller, service, repository, JDBC — and stores their shared dependencies
 * (queue, masking engine, config) as static fields so the inlined advice
 * bytecode can reference them from the target's classloader.
 *
 * <p>{@link AgentBuilder} runs on every class load and any matching class is
 * transformed before its first use. Already-loaded classes are picked up via
 * {@link AgentBuilder.RedefinitionStrategy#RETRANSFORMATION}.
 */
public final class InstrumentationInstaller {

    // ─── Static state visible to advice (advice methods are inlined into target classes) ───
    public static volatile SpanQueue QUEUE;
    public static volatile MaskingEngine MASKING;
    public static volatile ObjectMapper MAPPER;
    public static volatile AgentLogger LOGGER;
    public static volatile String SERVICE_NAME;
    public static volatile int PAYLOAD_MAX_BYTES;
    public static volatile boolean DEBUG;
    /** opt-in JDBC ResultSet wrapping. v0.1.1: caller-side risk (driver-specific unwrap) — default off. */
    public static volatile boolean CAPTURE_RESULT_SET;
    /**
     * Phase E3 — JDBC parameter capture kill switch. 사용자 비협상 D-03 직접 인용:
     * default {@code true}. When the operator flips
     * {@code -Dapilens.jdbc.capture-params=false} both
     * {@link io.apilens.agent.instrument.advice.PreparedStatementParamAdvice} and
     * {@link io.apilens.agent.instrument.advice.PreparedStatementAddBatchAdvice}
     * matchers are not registered at all — the advice classes are never woven
     * into target bytecode, so the runtime cost is zero.
     */
    public static volatile boolean CAPTURE_PARAMS;

    private InstrumentationInstaller() {
    }

    public static void install(Instrumentation instrumentation,
                               AgentConfig config,
                               SpanQueue queue,
                               MaskingEngine masking,
                               AgentLogger logger) {
        QUEUE = queue;
        MASKING = masking;
        // 사용자 앱 DTO에 java.time.LocalDateTime/Instant 등이 있어도 직렬화 가능하게 JavaTimeModule 등록.
        // timestamps(epoch) 대신 ISO-8601 문자열로 — 사람이 trace UI에서 직접 읽기 위함.
        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // [R11] AC-F-R11-05 (D-P0-01 비협상 — verbatim 인용)
        //   본질: agent AdviceSupport.serializeReturn → Jackson → FileSystemResource.getOutputStream()
        //         → mp4 0바이트 truncate 차단 (Layer 3 — ResourceMixIn 등록).
        //         Spring 의 Resource 인터페이스 5 위험 getter (getOutputStream / getInputStream /
        //         getFile / getURI / getURL) 를 Jackson traverse 에서 영구 제외.
        //         Spring 미존재 앱은 ClassNotFoundException 으로 silent skip (US-05 정합).
        //   회귀 가드 grep: 정방향 = `Class.forName("org.springframework.core.io.Resource"` (1 hit) +
        //                    `addMixIn` (1 hit) / 반대방향 = `ClassNotFoundException` catch 누락 0 hit
        //   CLAUDE.md 인용: "아키텍처 핵심 원칙 — Agent 자체 장애가 호스트 앱에 영향 0"
        try {
            Class<?> resourceCls = Class.forName(
                    "org.springframework.core.io.Resource", false,
                    InstrumentationInstaller.class.getClassLoader());
            MAPPER.addMixIn(resourceCls, ResourceMixIn.class);
        } catch (ClassNotFoundException ignore) {
            // Spring 없는 일반 자바 앱 — MixIn 미등록 silent skip
        }
        LOGGER = logger;
        SERVICE_NAME = config.serviceName();
        PAYLOAD_MAX_BYTES = config.payloadMaxBytes();
        DEBUG = config.debug();
        CAPTURE_RESULT_SET = config.captureResultSet();
        // Phase E3 — 사용자 비협상 D-03: default=true (config 가 같은 default 보장).
        // 이 static 필드는 advice 본문의 second-line defensive guard 가 참조.
        CAPTURE_PARAMS = config.captureParams();

        try {
            AgentBuilder builder = new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE);

            // debug=true 일 때만 transform 시도/실패를 stderr에 출력 (production 노이즈 회피)
            if (config.debug()) {
                builder = builder.with(new TransformDebugListener());
            }

            builder = builder
                    // [Phase R18] AC-01-1/AC-01-2 — 운영자 opt-in 계측 exclude 필터 합성. 사용자 명시
                    //   비협상 결정(NFR-05: default 빈 목록 → userExcludedTypes(List.of())=none() →
                    //   ignoredTypes().or(none())=ignoredTypes() byte-identical). weaving 시점 결정,
                    //   런타임 비용 0 — static 필드 불요(advice 런타임 미참조). CLAUDE.md 'Agent 는 가볍게' 인용.
                    .ignore(SpringMatchers.ignoredTypes()
                            .or(SpringMatchers.userExcludedTypes(config.excludePackages())))
                    // 1) Controllers — root SERVER span
                    .type(SpringMatchers.annotatedWithController())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(ControllerAdvice.class).on(ElementMatchers.isMethod()
                                    .and(ElementMatchers.isPublic())
                                    .and(ElementMatchers.not(ElementMatchers.isStatic())))))
                    // 2) Services — INTERNAL spans
                    .type(SpringMatchers.annotatedWithService())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(ServiceAdvice.class).on(ElementMatchers.isMethod()
                                    .and(ElementMatchers.isPublic())
                                    .and(ElementMatchers.not(ElementMatchers.isStatic())))))
                    // 3) Repositories — INTERNAL spans (covers @Repository + JpaRepository / CrudRepository).
                    // SimpleJpaRepository의 setter (setProjectionFactory, setEscapeCharacter 등)는
                    // startup proxy 초기화 시 호출되어 별도 noise trace를 만들기 때문에 제외.
                    // user repository에 보통 setter/getter 없으므로 부작용도 적음.
                    // 매처 표현식은 SpringMatchers.repositoryBusinessMethods()로 추출 (UT 가능).
                    .type(SpringMatchers.springRepository())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(RepositoryAdvice.class).on(SpringMatchers.repositoryBusinessMethods())))
                    // 4a) Connection.prepareStatement — stash SQL into JdbcSqlCache
                    .type(SpringMatchers.implementsConnection())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(JdbcConnectionAdvice.class).on(SpringMatchers.connectionPrepareMethods())))
                    // 4b) PreparedStatement.execute* — DB span (leaf)
                    .type(SpringMatchers.implementsPreparedStatement())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(JdbcAdvice.class).on(SpringMatchers.preparedStatementExecuteMethods())))
                    // 4c) PreparedStatement.getResultSet — return CapturedResultSet wrapper stashed by 4b
                    //     when the operator opts into apilens.jdbc.capture-result-set. Covers MyBatis /
                    //     raw-JDBC pattern of execute() (boolean) + getResultSet() (ResultSet).
                    .type(SpringMatchers.implementsPreparedStatement())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(JdbcGetResultSetAdvice.class).on(SpringMatchers.preparedStatementGetResultSetMethod())))
                    // 5) MyBatis MapperProxy.invoke — one span per @Mapper interface call.
                    //    Spring matchers can't reach user @Mapper interfaces (no concrete impl),
                    //    so we intercept the proxy's invoke instead. Covers the VAMS-style
                    //    Controller → Service → Mapper → mapper.xml layered architecture.
                    .type(SpringMatchers.mybatisMapperProxy())
                    .transform((b, t, cl, m, pd) -> b.visit(
                            Advice.to(MyBatisMapperAdvice.class).on(SpringMatchers.mybatisMapperProxyInvoke())));

            // 4d/4e) Phase E3 — JDBC parameter capture (사용자 비협상 D-01/D-03).
            //   Opt-in 토글이 켜졌을 때(default true) 만 매처 등록. captureParams=false 면
            //   advice 클래스 자체가 weaving 되지 않아 advice 진입 0건 + 런타임 비용 0.
            //   이 형태가 "표준 API 호환성은 default 충족" 원칙을 코드 레벨에서 단언한다.
            if (config.captureParams()) {
                builder = builder
                        .type(SpringMatchers.implementsPreparedStatement())
                        .transform((b, t, cl, m, pd) -> b.visit(
                                Advice.to(PreparedStatementParamAdvice.class)
                                        .on(SpringMatchers.preparedStatementSetParamMethods())))
                        .type(SpringMatchers.implementsPreparedStatement())
                        .transform((b, t, cl, m, pd) -> b.visit(
                                Advice.to(PreparedStatementAddBatchAdvice.class)
                                        .on(SpringMatchers.preparedStatementAddBatchMethod())));
            }

            builder.installOn(instrumentation);
            logger.info("ApiLens instrumentation installed (controller/service/repository/jdbc)"
                    + (config.debug() ? " [DEBUG listener active]" : ""));
        } catch (Throwable t) {
            // host app must keep running even if instrumentation fails entirely
            logger.error("instrumentation install failed; agent transport-only mode", t);
        }
    }

    /**
     * Diagnostic listener — only attached when {@code apilens.debug=true}. Prints
     * one line per successfully transformed type and one line per transform error.
     * Matches & ignores are NOT logged (volume).
     */
    private static final class TransformDebugListener implements AgentBuilder.Listener {

        private static final String PREFIX = "[ApiLens][TRANSFORM] ";

        @Override
        public void onDiscovery(String typeName, ClassLoader cl, JavaModule m, boolean loaded) {
            // skip — too noisy
        }

        @Override
        public void onTransformation(TypeDescription type, ClassLoader cl, JavaModule m,
                                      boolean loaded, DynamicType dt) {
            System.err.println(PREFIX + "transformed " + type.getName()
                    + " (cl=" + classLoaderTag(cl) + ", loaded=" + loaded + ")");
        }

        @Override
        public void onIgnored(TypeDescription type, ClassLoader cl, JavaModule m, boolean loaded) {
            // 모든 무시 클래스를 찍으면 너무 많음 — 우리가 관심 있는 패턴만
            String n = type.getName();
            if (n.startsWith("com.example.sampleapp.")
                    || n.contains("Repository")
                    || n.contains("Controller")
                    || n.contains("Service")
                    || n.contains("PreparedStatement")
                    || n.contains("Connection")) {
                System.err.println(PREFIX + "IGNORED " + n + " (cl=" + classLoaderTag(cl) + ")");
            }
        }

        @Override
        public void onError(String typeName, ClassLoader cl, JavaModule m, boolean loaded, Throwable t) {
            System.err.println(PREFIX + "ERROR " + typeName
                    + " (cl=" + classLoaderTag(cl) + "): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        @Override
        public void onComplete(String typeName, ClassLoader cl, JavaModule m, boolean loaded) {
            // skip
        }

        private static String classLoaderTag(ClassLoader cl) {
            if (cl == null) {
                return "boot";
            }
            return cl.getClass().getSimpleName();
        }
    }
}
