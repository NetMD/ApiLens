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

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks on {@link SpringMatchers} — we can't easily fake Spring annotations
 * without bringing Spring as a dep, so the meaningful coverage is the {@link
 * SpringMatchers#ignoredTypes()} matcher (string-based, dep-free).
 */
class SpringMatchersTest {

    @Test
    void ignoredTypesSkipsAgentItself() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        assertTrue(matcher.matches(typeNamed("io.apilens.agent.AgentMain")),
                "agent's own classes must be skipped — risk of self-instrumenting recursion");
        assertTrue(matcher.matches(typeNamed("io.apilens.common.Span")));
    }

    @Test
    void ignoredTypesSkipsBytebuddyAndJackson() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        assertTrue(matcher.matches(typeNamed("net.bytebuddy.agent.builder.AgentBuilder")));
        assertTrue(matcher.matches(typeNamed("com.fasterxml.jackson.databind.ObjectMapper")));
    }

    @Test
    void ignoredTypesSkipsJdkAndServletInternals() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        assertTrue(matcher.matches(typeNamed("java.lang.String")));
        assertTrue(matcher.matches(typeNamed("javax.servlet.Filter")));
        assertTrue(matcher.matches(typeNamed("jakarta.servlet.Filter")));
        assertTrue(matcher.matches(typeNamed("sun.misc.Unsafe")));
        assertTrue(matcher.matches(typeNamed("jdk.internal.reflect.Reflection")));
    }

    @Test
    void ignoredTypesDoesNotSkipUserCode() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        assertFalse(matcher.matches(typeNamed("com.example.checkout.OrderController")));
        assertFalse(matcher.matches(typeNamed("org.springframework.web.bind.RequestMappingHandlerAdapter")),
                "Spring framework classes themselves must be visited (they host user controllers)");
    }

    // ─── UT-10 ~ UT-14: MethodDescription-based matcher tests ───────────────

    /**
     * UT-10: connectionPrepareMethods() must match {@code prepareStatement(String)}.
     * Uses a real Connection-like fixture class so we get a genuine MethodDescription.
     */
    @Test
    void connectionPrepareMethodsMatchesSingleStringOverload() throws Exception {
        ElementMatcher<MethodDescription> matcher = SpringMatchers.connectionPrepareMethods();

        Method m = ConnectionFixture.class.getDeclaredMethod("prepareStatement", String.class);
        assertTrue(matcher.matches(asDescription(m)),
                "prepareStatement(String) must match — this is the canonical overload");
    }

    /**
     * UT-11: connectionPrepareMethods() must ALSO match prepareStatement(String, int)
     * because the matcher is takesArgument(0, String.class) — not takesArguments(1).
     * (Old matcher missed this; HikariCP / drivers use the multi-arg overloads in
     * production.)
     */
    @Test
    void connectionPrepareMethodsMatchesMultiArgOverloads() throws Exception {
        ElementMatcher<MethodDescription> matcher = SpringMatchers.connectionPrepareMethods();

        Method m1 = ConnectionFixture.class.getDeclaredMethod("prepareStatement", String.class, int.class);
        Method m2 = ConnectionFixture.class.getDeclaredMethod("prepareStatement", String.class, int[].class);
        Method m3 = ConnectionFixture.class.getDeclaredMethod("prepareStatement", String.class, String[].class);

        assertTrue(matcher.matches(asDescription(m1)), "prepareStatement(String, int) must match");
        assertTrue(matcher.matches(asDescription(m2)), "prepareStatement(String, int[]) must match");
        assertTrue(matcher.matches(asDescription(m3)), "prepareStatement(String, String[]) must match");
    }

    /**
     * UT-12: connectionPrepareMethods() must NOT match overloads whose first argument
     * is not a String (some drivers expose helper overloads).
     */
    @Test
    void connectionPrepareMethodsRejectsNonStringFirstArg() throws Exception {
        ElementMatcher<MethodDescription> matcher = SpringMatchers.connectionPrepareMethods();

        Method m = ConnectionFixture.class.getDeclaredMethod("prepareStatement", Object.class);
        assertFalse(matcher.matches(asDescription(m)),
                "prepareStatement(Object) must NOT match — only String first-arg overloads are SQL");
    }

    /**
     * UT-13: repositoryBusinessMethods() must match user-defined business methods
     * and EXCLUDE setX/getX accessors. This is the regression guard for the original
     * bug (SimpleJpaRepository.setEscapeCharacter creating noise traces).
     */
    @Test
    void repositoryBusinessMethodsExcludesSettersAndGetters() throws Exception {
        ElementMatcher<MethodDescription> matcher = SpringMatchers.repositoryBusinessMethods();

        Method save = RepositoryFixture.class.getDeclaredMethod("save", Object.class);
        Method findById = RepositoryFixture.class.getDeclaredMethod("findById", Long.class);
        Method setProjectionFactory = RepositoryFixture.class.getDeclaredMethod("setProjectionFactory", Object.class);
        Method setEscapeCharacter = RepositoryFixture.class.getDeclaredMethod("setEscapeCharacter", char.class);
        Method getEntityManager = RepositoryFixture.class.getDeclaredMethod("getEntityManager");

        assertTrue(matcher.matches(asDescription(save)), "save(...) is a business method");
        // findById starts with "find" not "get" — should match (Spring Data finder)
        assertTrue(matcher.matches(asDescription(findById)), "findById is a business method");
        assertFalse(matcher.matches(asDescription(setProjectionFactory)),
                "setProjectionFactory is a Spring framework setter — must be excluded");
        assertFalse(matcher.matches(asDescription(setEscapeCharacter)),
                "setEscapeCharacter is a Spring framework setter — must be excluded");
        assertFalse(matcher.matches(asDescription(getEntityManager)),
                "getEntityManager is a JavaBean getter — must be excluded");
    }

    /**
     * UT-14: repositoryBusinessMethods() must exclude {@link Object} methods
     * (toString/equals/hashCode) — they're declared by Object and must be filtered.
     */
    @Test
    void repositoryBusinessMethodsExcludesObjectMethods() throws Exception {
        ElementMatcher<MethodDescription> matcher = SpringMatchers.repositoryBusinessMethods();

        // toString/equals/hashCode are declared on Object — but RepositoryFixture inherits them.
        // We must derive the description for the overridden / inherited form. Easiest path:
        // pull it from Object directly — it must NOT match.
        MethodDescription toStringFromObject = asDescription(Object.class.getDeclaredMethod("toString"));
        MethodDescription hashCodeFromObject = asDescription(Object.class.getDeclaredMethod("hashCode"));
        MethodDescription equalsFromObject = asDescription(Object.class.getDeclaredMethod("equals", Object.class));

        assertFalse(matcher.matches(toStringFromObject),
                "Object.toString must be excluded");
        assertFalse(matcher.matches(hashCodeFromObject),
                "Object.hashCode must be excluded");
        assertFalse(matcher.matches(equalsFromObject),
                "Object.equals must be excluded");
    }

    // ─── UT-15 ~ UT-16: Phase F2 fix² · US-05 framework ignore prefix 추가 ───────

    /**
     * UT-15: ignoredTypes() must skip Spring Boot autoconfigure classes
     * (e.g. BasicErrorController) — framework noise blocking (US-05).
     */
    @Test
    void ignoredTypesSkipsSpringBootAutoconfigure() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        assertTrue(matcher.matches(typeNamed("org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController")),
                "BasicErrorController is a Spring Boot autoconfigure framework class — must be skipped to keep node graph user-code only");
        assertTrue(matcher.matches(typeNamed("org.springframework.boot.autoconfigure.SpringBootApplication")));
    }

    /**
     * UT-16: ignoredTypes() must skip Spring web.servlet.handler internals
     * (e.g. HandlerExceptionResolver impls) — but NOT skip Spring Data
     * (SimpleJpaRepository) and NOT skip org.springframework.web.bind.* (user-hosting framework classes).
     */
    @Test
    void ignoredTypesSkipsServletHandlerInternalsButKeepsDataAndBind() {
        ElementMatcher<TypeDescription> matcher = SpringMatchers.ignoredTypes();

        // skip
        assertTrue(matcher.matches(typeNamed("org.springframework.web.servlet.handler.SimpleUrlHandlerMapping")));
        assertTrue(matcher.matches(typeNamed("org.springframework.web.servlet.handler.HandlerExceptionResolverComposite")));
        assertTrue(matcher.matches(typeNamed("org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration")),
                "DispatcherServletAutoConfiguration is a Spring Boot autoconfigure framework class — must be skipped");

        // do NOT skip (regression guards)
        assertFalse(matcher.matches(typeNamed("org.springframework.data.jpa.repository.support.SimpleJpaRepository")),
                "Spring Data SimpleJpaRepository must NOT be skipped — RepositoryAdvice depends on it (F2 fix² AC-05-3)");
        assertFalse(matcher.matches(typeNamed("org.springframework.web.bind.RequestMappingHandlerAdapter")),
                "web.bind.* must NOT be skipped — pre-existing regression guard (line 53-54)");
        assertFalse(matcher.matches(typeNamed("org.springframework.web.bind.method.annotation.RequestMappingHandlerAdapter")),
                "web.bind.method.annotation.* must NOT be skipped — sub-prefix collision check vs new web.servlet.handler.* prefix");
        assertFalse(matcher.matches(typeNamed("com.example.UserController")),
                "user app code must NEVER be skipped");
    }

    // ─── Phase E3 — preparedStatementSetParamMethods / preparedStatementAddBatchMethod ───
    //
    // 사용자 비협상 D-01 (12종 setter 정확 명시) / D-02 (raw Statement 명시 제외) /
    // D-04 (비표준 driver 확장 미지원) 의 회귀 가드. SpringMatchersTest 확장.

    /** UT-MATCH-01 ~ UT-MATCH-02: 12 표준 setter 가 매치된다. */
    @Test
    void setParamMatchesAll12Setters() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        String[] all12 = {"setString", "setInt", "setLong", "setDouble", "setFloat",
                "setBoolean", "setBigDecimal", "setDate", "setTime", "setTimestamp",
                "setBytes", "setNull"};
        for (String name : all12) {
            Method method = PreparedStatementFixture.class.getDeclaredMethod(name, int.class, Object.class);
            assertTrue(m.matches(asDescription(method)),
                    "12 표준 setter 중 '" + name + "' 가 매치되지 않음 (D-01 회귀)");
        }
    }

    /**
     * UT-MATCH-03: setObject 매치 단언 (Phase E3 fix¹ — VAMS dogfooding R13).
     *
     * <p>MyBatis UnknownTypeHandler 등이 setLong/setInt 대신
     * setObject(i, value[, jdbcType[, scaleOrLength]]) 호출하는 케이스가 광범위
     * → 12 setter whitelist 만으로는 PAYLOAD IN 본문 0 hit 회귀. setObject 도
     * D-04 표준 JDBC API 의 일부이므로 default 매처에 포함하는 것이 정합.
     */
    @Test
    void setParamMatchesSetObject() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("setObject", int.class, Object.class);
        assertTrue(m.matches(asDescription(method)),
                "setObject(int, Object) 는 표준 JDBC API — 매치 단언 (Phase E3 fix¹)");
    }

    /**
     * UT-MATCH-04 ~ 05b: Phase E3 fix² (2026-05-14 VAMS dogfooding R14) — whitelist
     * → blacklist 전환. 모든 setXxx(int parameterIndex, ...) 패턴이 매처에 포함되어야
     * 한다 (D-04 "표준 JDBC API 전부 cover" 정신). driver-specific setter
     * (setOracleObject 등) 까지 잡혀도 advice 본문이 try-catch silent drop 이라
     * host throw 0 유지 (D-05).
     */
    @Test
    void setParamMatchesAllPreparedStatementSetters() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        for (String name : new String[]{
                "setObject", "setArray", "setBlob", "setClob", "setRef", "setURL",
                "setRowId", "setNClob", "setNCharacterStream", "setOracleObject"}) {
            Method method = PreparedStatementFixture.class.getDeclaredMethod(name, int.class, Object.class);
            assertTrue(m.matches(asDescription(method)),
                    name + " 은 blacklist 매처에서 매치되어야 함 (D-04 표준 API 전부 cover)");
        }
    }

    /**
     * UT-MATCH-05 (회귀 가드): Statement / PreparedStatement 의 cursor/timeout/fetch
     * 설정 메서드 8종은 명시 제외되어 매치 0건이어야 한다.
     */
    @Test
    void setParamRejectsStatementConfigSetters() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        for (String name : new String[]{
                "setFetchDirection", "setFetchSize", "setMaxRows", "setMaxFieldSize",
                "setQueryTimeout", "setEscapeProcessing", "setPoolable"}) {
            Method method = PreparedStatementFixture.class.getDeclaredMethod(name, int.class);
            assertFalse(m.matches(asDescription(method)),
                    name + " 은 Statement 설정 메서드 — 명시 제외로 매치 0건 단언");
        }
        // setCursorName(String) — 첫 인자 String 이라 자연 제외 + 명시 제외 동시 적용
        Method setCursorName = PreparedStatementFixture.class.getDeclaredMethod("setCursorName", String.class);
        assertFalse(m.matches(asDescription(setCursorName)),
                "setCursorName(String) 은 첫 인자가 int 가 아님 + 명시 제외 — 매치 0건 단언");
    }

    /** UT-MATCH-06: 첫 인자가 int 가 아닌 가짜 setter (예: 0-arg) 미매치. */
    @Test
    void setParamRejectsZeroArgs() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("setStringNoArgs");
        assertFalse(m.matches(asDescription(method)),
                "0-arg setString fixture 는 takesArgument(0, int.class) 가드로 매치 0");
    }

    /** UT-MATCH-07: static setter 미매치. */
    @Test
    void setParamRejectsStaticSetter() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("staticSetString", int.class, Object.class);
        assertFalse(m.matches(asDescription(method)),
                "static setter 는 not(isStatic) 가드로 매치 0");
    }

    /** UT-MATCH-08: addBatch() (no-arg) 매치. */
    @Test
    void addBatchMatchesNoArg() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementAddBatchMethod();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("addBatch");
        assertTrue(m.matches(asDescription(method)),
                "addBatch() no-arg 는 PreparedStatement.addBatch 시그니처와 일치");
    }

    /** UT-MATCH-09: Statement.addBatch(String) (1-arg) 매치 0건 — sub-prefix 충돌 가드. */
    @Test
    void addBatchRejectsStringArg() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementAddBatchMethod();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("addBatch", String.class);
        assertFalse(m.matches(asDescription(method)),
                "Statement.addBatch(String) (1-arg) 는 takesArguments(0) 가드로 매치 0");
    }

    /** UT-MATCH-10: getString 같은 getter 류는 12종 외 — 매치 0건. */
    @Test
    void setParamRejectsGetters() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.preparedStatementSetParamMethods();

        Method method = PreparedStatementFixture.class.getDeclaredMethod("getString", int.class);
        assertFalse(m.matches(asDescription(method)),
                "getString 은 12 setter whitelist 외 — 매치 0건");
    }

    /**
     * UT-MATCH-11 (NFR-05 회귀 가드): {@code preparedStatementSetParamMethods()}
     * 정적 메서드 본문 영역에 {@code nameStartsWith("set")} / {@code nameStartsWith("java.sql.")}
     * 같은 절대 금지 prefix 단독 매치가 들어가면 즉시 회귀.
     *
     * <p>전체 파일 grep 은 부적합 — {@code repositoryBusinessMethods()} 가 setter/getter
     * 제외 목적으로 {@code not(nameStartsWith("set"))} / {@code not(nameStartsWith("get"))}
     * 패턴을 의도적으로 사용하기 때문. 본 단언은 method 본문 영역만 슬라이스해서 검사한다.
     */
    @Test
    void setParamMatcherSourceHasBlacklistGuards() throws Exception {
        String src = readSource("apilens-agent/src/main/java/io/apilens/agent/instrument/matcher/SpringMatchers.java");
        String setterBody = sliceMethodBody(src, "preparedStatementSetParamMethods");
        String addBatchBody = sliceMethodBody(src, "preparedStatementAddBatchMethod");

        // Phase E3 fix² blacklist 패턴 가드 — 8종 명시 제외가 모두 동봉되어야 한다.
        // nameStartsWith("set") + takesArgument(0, int.class) 조합은 의도된 사용이며,
        // false-positive 위험은 명시 제외 8종 모두로 차단된다.
        for (String required : new String[]{
                "setFetchDirection", "setFetchSize", "setMaxRows", "setMaxFieldSize",
                "setQueryTimeout", "setEscapeProcessing", "setCursorName", "setPoolable"}) {
            assertTrue(setterBody.contains("named(\"" + required + "\")"),
                    "blacklist 8종 명시 제외 누락: " + required + " — NFR-05 회귀 위험. body=" + setterBody);
        }
        // 절대 금지: 와일드카드 prefix — addBatch 매처에는 nameStartsWith 자체 부적합.
        for (String body : new String[]{setterBody, addBatchBody}) {
            assertFalse(body.contains("nameStartsWith(\"java.sql.\""),
                    "절대 금지: nameStartsWith(\"java.sql.\") — NFR-05 회귀: " + body);
            assertFalse(body.contains("nameStartsWith(\"get\""),
                    "절대 금지: nameStartsWith(\"get\") — setter 매처에 getter prefix: " + body);
        }
        // addBatch 매처에는 nameStartsWith("set") 도 부적합 (정확 이름 매처)
        assertFalse(addBatchBody.contains("nameStartsWith(\"set\""),
                "절대 금지: addBatch 매처에 nameStartsWith(\"set\") — 잘못된 매처: " + addBatchBody);
    }

    /**
     * 단순한 슬라이스 helper — {@code methodName} 으로 시작하는 본문 블록을 균형 brace 카운트로
     * 추출한다. javadoc / 메서드 시그니처 영역 위 절대 금지 패턴은 별도 단언으로 검사.
     */
    private static String sliceMethodBody(String src, String methodName) {
        int sig = src.indexOf(methodName + "()");
        if (sig < 0) return "";
        int braceOpen = src.indexOf('{', sig);
        if (braceOpen < 0) return "";
        int depth = 1;
        int i = braceOpen + 1;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        return src.substring(braceOpen, Math.min(i, src.length()));
    }

    /** Synthesise a TypeDescription with a specific name — enough for nameStartsWith matchers. */
    private static TypeDescription typeNamed(String name) {
        return new TypeDescription.Latent(name, 0, TypeDescription.Generic.OBJECT);
    }

    /** Build a {@link MethodDescription} from a reflective {@link Method}. */
    private static MethodDescription asDescription(Method m) {
        return new MethodDescription.ForLoadedMethod(m);
    }

    /** Stand-in for {@code java.sql.Connection} — declares the prepareStatement overloads. */
    @SuppressWarnings("unused")
    static class ConnectionFixture {
        public Object prepareStatement(String sql) { return null; }
        public Object prepareStatement(String sql, int autoGenKeys) { return null; }
        public Object prepareStatement(String sql, int[] columnIndexes) { return null; }
        public Object prepareStatement(String sql, String[] columnNames) { return null; }
        // negative case: first arg is not a String
        public Object prepareStatement(Object sql) { return null; }
    }

    /** Stand-in for a Spring repository implementation with Spring framework setters. */
    @SuppressWarnings("unused")
    static class RepositoryFixture {
        // user business methods
        public Object save(Object entity) { return entity; }
        public Object findById(Long id) { return null; }

        // Spring framework setters that historically produced noise traces
        public void setProjectionFactory(Object factory) {}
        public void setEscapeCharacter(char c) {}

        // JavaBean getter
        public Object getEntityManager() { return null; }
    }

    /**
     * Stand-in for {@code java.sql.PreparedStatement} / {@code Statement} —
     * declares the 12 표준 setter + 비표준 setter / addBatch 오버로드 / getter
     * fixture methods used by Phase E3 matcher tests.
     *
     * <p>모든 setter 의 두 번째 인자는 {@code Object} — D-01 매처가
     * {@code takesArgument(0, int.class)} 만 검사하므로 두 번째 인자 타입은
     * 매치 판정과 무관. 진짜 setString(int, String) 등은 fixture 가 아니라
     * runtime 의 PreparedStatement implementer (예: Hikari proxy) 에서 매치.
     */
    @SuppressWarnings("unused")
    static class PreparedStatementFixture {
        // ─── 12 표준 setter (D-01) ────────────────────────────────────────
        public void setString(int idx, Object v) {}
        public void setInt(int idx, Object v) {}
        public void setLong(int idx, Object v) {}
        public void setDouble(int idx, Object v) {}
        public void setFloat(int idx, Object v) {}
        public void setBoolean(int idx, Object v) {}
        public void setBigDecimal(int idx, Object v) {}
        public void setDate(int idx, Object v) {}
        public void setTime(int idx, Object v) {}
        public void setTimestamp(int idx, Object v) {}
        public void setBytes(int idx, Object v) {}
        public void setNull(int idx, Object v) {}

        // ─── 12종 외 표준 setter (D-01 영역 밖) — 매치 0건 단언 ───────────
        public void setObject(int idx, Object v) {}
        public void setArray(int idx, Object v) {}
        public void setBlob(int idx, Object v) {}
        public void setClob(int idx, Object v) {}
        public void setRef(int idx, Object v) {}
        public void setURL(int idx, Object v) {}
        public void setRowId(int idx, Object v) {}
        public void setNClob(int idx, Object v) {}
        public void setNCharacterStream(int idx, Object v) {}

        // ─── 비표준 driver-specific setter (D-04 영역 밖) ────────────────
        public void setOracleObject(int idx, Object v) {}

        // ─── Statement 설정 메서드 (UT-MATCH-05 명시 제외 가드용) ────────
        // 실 JDBC API 시그니처와 일부 차이 있을 수 있으나, 매처 명시 제외 검증
        // 목적의 fixture 이므로 int / String 첫 인자만으로 충분.
        public void setFetchDirection(int dir) {}
        public void setFetchSize(int rows) {}
        public void setMaxRows(int max) {}
        public void setMaxFieldSize(int max) {}
        public void setQueryTimeout(int seconds) {}
        public void setEscapeProcessing(int enable) {}
        public void setPoolable(int poolable) {}
        public void setCursorName(String name) {}

        // ─── 가짜 0-arg / static / getter (가드 단언용) ──────────────────
        public void setStringNoArgs() {}
        public static void staticSetString(int idx, Object v) {}
        public Object getString(int idx) { return null; }

        // ─── addBatch 오버로드 ────────────────────────────────────────────
        public void addBatch() {}
        public void addBatch(String sql) {}
    }

    /**
     * Read the source file from disk so the matcher source can be statically
     * grep'd for forbidden patterns (NFR-05 회귀 가드).
     *
     * <p>Working directory may differ depending on how Gradle launches tests
     * (root vs. submodule). Try a few sensible candidates and fall back to
     * empty string so the assert phrase still fires meaningfully.
     */
    private static String readSource(String relativePath) {
        java.nio.file.Path[] candidates = new java.nio.file.Path[]{
                java.nio.file.Paths.get(relativePath),
                java.nio.file.Paths.get("..", relativePath),
                java.nio.file.Paths.get(relativePath.replaceFirst("^apilens-agent/", "")),
        };
        for (java.nio.file.Path candidate : candidates) {
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate);
                } catch (java.io.IOException e) {
                    // try next candidate
                }
            }
        }
        return "";
    }
}
