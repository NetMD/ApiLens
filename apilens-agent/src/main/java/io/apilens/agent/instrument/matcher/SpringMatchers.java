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
package io.apilens.agent.instrument.matcher;

import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * String-based matchers so the agent doesn't pull Spring as a compile-time dep.
 * (Agent must be lightweight and version-agnostic.)
 */
public final class SpringMatchers {

    public static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    public static final String CONTROLLER = "org.springframework.stereotype.Controller";
    public static final String SERVICE = "org.springframework.stereotype.Service";
    public static final String REPOSITORY = "org.springframework.stereotype.Repository";

    public static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";
    public static final String CRUD_REPOSITORY = "org.springframework.data.repository.CrudRepository";

    public static final String PREPARED_STATEMENT = "java.sql.PreparedStatement";
    public static final String CONNECTION = "java.sql.Connection";

    /**
     * MyBatis runtime proxy backing every user-defined {@code @Mapper interface}.
     * Used as the single instrumentation point for mapper-layer spans — VAMS-style
     * Controller → Service → Mapper → mapper.xml layered architecture.
     */
    public static final String MYBATIS_MAPPER_PROXY = "org.apache.ibatis.binding.MapperProxy";

    private SpringMatchers() {
    }

    /** Matches a class annotated with {@code @RestController} or {@code @Controller}. */
    public static ElementMatcher.Junction<TypeDescription> annotatedWithController() {
        return annotatedWithName(REST_CONTROLLER).or(annotatedWithName(CONTROLLER));
    }

    public static ElementMatcher.Junction<TypeDescription> annotatedWithService() {
        return annotatedWithName(SERVICE);
    }

    /** Matches {@code @Repository} or any subtype of Spring Data's {@code CrudRepository}. */
    public static ElementMatcher.Junction<TypeDescription> springRepository() {
        return annotatedWithName(REPOSITORY)
                .or(ElementMatchers.hasSuperType(named(CRUD_REPOSITORY)))
                .or(ElementMatchers.hasSuperType(named(JPA_REPOSITORY)));
    }

    public static ElementMatcher.Junction<TypeDescription> implementsPreparedStatement() {
        return ElementMatchers.<TypeDescription>not(ElementMatchers.isInterface())
                .and(ElementMatchers.hasSuperType(named(PREPARED_STATEMENT)));
    }

    public static ElementMatcher.Junction<TypeDescription> implementsConnection() {
        return ElementMatchers.<TypeDescription>not(ElementMatchers.isInterface())
                .and(ElementMatchers.hasSuperType(named(CONNECTION)));
    }

    /** Methods declared on PreparedStatement that we instrument for SQL execution. */
    public static ElementMatcher.Junction<MethodDescription> preparedStatementExecuteMethods() {
        return ElementMatchers.named("execute")
                .or(ElementMatchers.named("executeQuery"))
                .or(ElementMatchers.named("executeUpdate"))
                .or(ElementMatchers.named("executeLargeUpdate"));
    }

    /**
     * PreparedStatement standard setters — exactly the 12 names pinned by user
     * prompt D-01 (Phase E3 비협상): setString / setInt / setLong / setDouble /
     * setFloat / setBoolean / setBigDecimal / setDate / setTime / setTimestamp /
     * setBytes / setNull.
     *
     * <p>Matched by name + first-arg int (the JDBC parameterIndex). The type
     * matcher {@link #implementsPreparedStatement()} already restricts to
     * classes that implement {@code java.sql.PreparedStatement}, so raw
     * {@code Statement} is naturally excluded by type narrowing.
     *
     * <p>Pool wrappers that <em>do</em> implement {@code PreparedStatement}
     * (HikariProxyPreparedStatement, Tomcat JDBC PooledPreparedStatement, DBCP2
     * DelegatingPreparedStatement) are intentionally included — they delegate
     * each setter to the underlying driver statement, which is the layer where
     * parameter values flow, so capturing at the wrapper layer is correct.
     *
     * <p><b>Regression guard — DO NOT add the following without re-opening
     * Phase E3:</b>
     * <ul>
     *   <li>setObject / setArray / setBlob / setClob / setRef etc. — user prompt
     *       D-01 pins exactly 12 setters; broader coverage is opted out by design.</li>
     *   <li>Driver-specific setters (setOracleObject, setPGobject, etc.) —
     *       user prompt D-04 "표준 JDBC API 전부 cover, 비표준 driver 확장 미지원"
     *       forbids extension.</li>
     *   <li>{@code nameStartsWith("set")} bare prefix — would re-introduce the
     *       noise traces that {@link #repositoryBusinessMethods()} explicitly removed
     *       and break NFR-05 (matcher false-positive 차단).</li>
     * </ul>
     *
     * <p>AC mapping: AC-02-1 / AC-02-3 / AC-02-4 (planner §2 US-02).
     */
    public static ElementMatcher.Junction<MethodDescription> preparedStatementSetParamMethods() {
        // Phase E3 fix² (2026-05-14 VAMS dogfooding R14) — whitelist → blacklist 전환.
        // 직전 12-setter + setObject whitelist 가 VAMS 운영망에서 여전히 PAYLOAD IN 0 hit.
        // MyBatis / HikariCP / driver wrapping layer 가 호출하는 setter 가 다층적이고
        // wrapper 별로 다른 setter 가 사용될 수 있어 whitelist 한계. D-04 "표준 JDBC API 전부
        // cover" 정신은 PreparedStatement 표준 인터페이스의 setXxx(int parameterIndex, ...)
        // 패턴을 모두 cover 하는 것 — 그 패턴 자체 매칭으로 전환.
        //
        // 매칭 조건: public + non-static + name 'set' 시작 + 첫 인자 int.
        // 명시 제외: Statement 의 cursor/timeout/fetch 설정 메서드 8 종.
        // driver-specific setter (setOracleObject 등) 까지 잡혀도 advice 본문이
        // try-catch silent drop 이라 host throw 0 유지 (D-05).
        return ElementMatchers.<MethodDescription>isMethod()
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()))
                .and(ElementMatchers.nameStartsWith("set"))
                .and(ElementMatchers.takesArgument(0, int.class))
                .and(ElementMatchers.not(ElementMatchers.named("setFetchDirection")))
                .and(ElementMatchers.not(ElementMatchers.named("setFetchSize")))
                .and(ElementMatchers.not(ElementMatchers.named("setMaxRows")))
                .and(ElementMatchers.not(ElementMatchers.named("setMaxFieldSize")))
                .and(ElementMatchers.not(ElementMatchers.named("setQueryTimeout")))
                .and(ElementMatchers.not(ElementMatchers.named("setEscapeProcessing")))
                .and(ElementMatchers.not(ElementMatchers.named("setCursorName")))
                .and(ElementMatchers.not(ElementMatchers.named("setPoolable")));
    }

    /**
     * {@code PreparedStatement.addBatch()} (no-arg). Paired with
     * {@link io.apilens.agent.instrument.advice.PreparedStatementAddBatchAdvice}
     * to mark a batch boundary in {@link io.apilens.agent.instrument.jdbc.JdbcParamCache}.
     *
     * <p>{@code Statement.addBatch(String)} (1-arg) is deliberately excluded —
     * the {@code takesArguments(0)} guard plus the
     * {@link #implementsPreparedStatement()} type narrowing means only the
     * no-arg overload on a PreparedStatement subtype matches.
     *
     * <p>AC mapping: AC-02-2 (planner §2 US-02).
     */
    public static ElementMatcher.Junction<MethodDescription> preparedStatementAddBatchMethod() {
        return ElementMatchers.<MethodDescription>named("addBatch")
                .and(ElementMatchers.takesArguments(0))
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()));
    }

    /**
     * Matches MyBatis {@code MapperProxy} — wraps any {@code @Mapper interface}
     * registered with the SqlSessionFactory.
     */
    public static ElementMatcher.Junction<TypeDescription> mybatisMapperProxy() {
        return ElementMatchers.named(MYBATIS_MAPPER_PROXY);
    }

    /**
     * The single {@code InvocationHandler#invoke(Object, Method, Object[])}
     * method declared on {@code MapperProxy}. {@code MyBatisMapperAdvice}
     * targets this to record one span per mapper invocation.
     */
    public static ElementMatcher.Junction<MethodDescription> mybatisMapperProxyInvoke() {
        return ElementMatchers.named("invoke")
                .and(ElementMatchers.takesArguments(3))
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()));
    }

    /**
     * {@code PreparedStatement.getResultSet()} — no-arg accessor used by callers
     * that follow the {@code execute() + getResultSet()} pattern (MyBatis, raw JDBC).
     * Paired with {@code JdbcGetResultSetAdvice} so the captured wrapper produced
     * during {@code execute()} interception is returned in place of the driver's
     * {@code ResultSet}.
     */
    public static ElementMatcher.Junction<MethodDescription> preparedStatementGetResultSetMethod() {
        return ElementMatchers.named("getResultSet")
                .and(ElementMatchers.takesArguments(0))
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()));
    }

    /**
     * Methods on {@code Connection} that prepare a SQL statement. We only need the
     * overloads that actually take a SQL string as the first argument — drivers
     * also expose {@code prepareStatement(String, int)}, {@code prepareStatement(String, int[])},
     * etc., and historically pool proxies (HikariCP) override several of these. The
     * old {@code takesArguments(1)} matcher missed everything but the canonical
     * 1-arg overload, leaving non-canonical overloads un-cached and producing DB
     * spans without a {@code db.statement} attribute.
     */
    public static ElementMatcher.Junction<MethodDescription> connectionPrepareMethods() {
        return ElementMatchers.named("prepareStatement")
                .and(ElementMatchers.takesArgument(0, String.class))
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()));
    }

    /**
     * Public, non-static, non-abstract business methods on a Spring repository
     * (extracted from {@code InstrumentationInstaller} so we can cover the
     * setter/getter exclusions from a unit test without booting an instrumented
     * JVM).
     *
     * <p>Excludes:
     * <ul>
     *   <li>{@link Object} methods (toString/equals/hashCode)</li>
     *   <li>JavaBean style accessors — Spring framework setters like {@code setEntityManager}
     *       fire during proxy initialisation and produce noise traces</li>
     * </ul>
     */
    public static ElementMatcher.Junction<MethodDescription> repositoryBusinessMethods() {
        return ElementMatchers.<MethodDescription>isMethod()
                .and(ElementMatchers.isPublic())
                .and(ElementMatchers.not(ElementMatchers.isStatic()))
                .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("set")))
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("get")));
    }

    /**
     * Skip framework / agent / JDK classes during instrumentation scanning.
     *
     * <p>Phase F2 fix² · US-05: Spring Boot autoconfigure / Spring web servlet handler
     * internals 2 prefix 추가. 이는 사용자가 존재하지 않는 리소스에 접근했을 때
     * (예: {@code /users/99999} → 404) BasicErrorController / DispatcherServlet 핸들러
     * 체인이 사용자 코드 노드 그래프에 framework noise 로 등장하는 회귀를 차단한다.
     *
     * <p>회귀 가드 (절대 추가 금지):
     * <ul>
     *   <li>{@code org.springframework.*} (전체) — {@code SimpleJpaRepository} =
     *       {@code org.springframework.data.*} 의 RepositoryAdvice 추적 가치 보존 의무</li>
     *   <li>{@code org.springframework.data.*} — 위와 동일</li>
     *   <li>{@code org.springframework.web.bind.*} — {@code RequestMappingHandlerAdapter}
     *       가 사용자 controller 를 호스팅하므로 visit 필요 (SpringMatchersTest:53-54 회귀 가드)</li>
     * </ul>
     */
    public static ElementMatcher.Junction<TypeDescription> ignoredTypes() {
        return ElementMatchers.<TypeDescription>nameStartsWith("io.apilens.")
                .or(ElementMatchers.nameStartsWith("net.bytebuddy."))
                .or(ElementMatchers.nameStartsWith("com.fasterxml.jackson."))
                .or(ElementMatchers.nameStartsWith("org.springframework.boot.loader."))
                .or(ElementMatchers.nameStartsWith("org.springframework.boot.autoconfigure.")) // ← 신규 (US-05)
                .or(ElementMatchers.nameStartsWith("org.springframework.web.servlet.handler.")) // ← 신규 (US-05)
                .or(ElementMatchers.nameStartsWith("sun."))
                .or(ElementMatchers.nameStartsWith("jdk."))
                .or(ElementMatchers.nameStartsWith("java."))
                .or(ElementMatchers.nameStartsWith("javax."))
                .or(ElementMatchers.nameStartsWith("jakarta."));
    }

    private static ElementMatcher.Junction<NamedElement> named(String name) {
        return ElementMatchers.named(name);
    }

    private static ElementMatcher.Junction<TypeDescription> annotatedWithName(String name) {
        return ElementMatchers.isAnnotatedWith(named(name));
    }
}
