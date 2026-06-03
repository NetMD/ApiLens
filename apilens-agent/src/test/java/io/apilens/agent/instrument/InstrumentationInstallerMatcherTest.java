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

import io.apilens.agent.instrument.matcher.SpringMatchers;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UT-15 ~ UT-17: regression guard for the extraction of the repository advice
 * matcher into {@link SpringMatchers#repositoryBusinessMethods()}.
 *
 * <p>The matcher used to live inline in {@code InstrumentationInstaller.install()}
 * (lines 91-96 of the old code), with no test coverage — small typos like
 * "set" → "Set" would silently re-instrument SimpleJpaRepository setters and
 * regress the noise-traces fix. The extraction allows a UT to lock the exact
 * predicate set.
 *
 * <p>The behavioural equivalence with the old inlined matcher is asserted on a
 * canonical set of method names: business methods are matched, framework
 * accessors are skipped.
 */
class InstrumentationInstallerMatcherTest {

    /** UT-15: business methods — match. */
    @Test
    void repositoryMatcherAcceptsBusinessMethods() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.repositoryBusinessMethods();

        for (String name : new String[]{"save", "findById", "delete", "count", "existsById", "queryByCustom"}) {
            Method method = Probe.class.getDeclaredMethod(name);
            assertTrue(m.matches(asDesc(method)),
                    "business method '" + name + "()' must be instrumented");
        }
    }

    /** UT-16: setter / getter / Object methods — reject. */
    @Test
    void repositoryMatcherRejectsAccessorsAndObjectMethods() throws Exception {
        ElementMatcher<MethodDescription> m = SpringMatchers.repositoryBusinessMethods();

        // setX / getX excluded
        for (String name : new String[]{
                "setProjectionFactory", "setEscapeCharacter", "setRepositoryInformation",
                "getEntityManager", "getDomainClass"
        }) {
            Method method = Probe.class.getDeclaredMethod(name);
            assertFalse(m.matches(asDesc(method)),
                    "framework accessor '" + name + "()' must NOT be instrumented");
        }
        // Object methods excluded (declaredBy Object)
        assertFalse(m.matches(asDesc(Object.class.getDeclaredMethod("toString"))));
        assertFalse(m.matches(asDesc(Object.class.getDeclaredMethod("hashCode"))));
    }

    /**
     * UT-17: behavioural equivalence — every method name in a representative
     * fixture set must yield the SAME match decision under {@link SpringMatchers#repositoryBusinessMethods()}
     * as the legacy inlined predicate (re-encoded here for the assertion).
     */
    @Test
    void extractedMatcherIsBehaviourallyEquivalentToLegacyInlineMatcher() throws Exception {
        ElementMatcher<MethodDescription> extracted = SpringMatchers.repositoryBusinessMethods();

        for (Method method : Probe.class.getDeclaredMethods()) {
            boolean expected = legacyPredicate(method);
            boolean actual = extracted.matches(asDesc(method));
            assertEquals(expected, actual,
                    "method '" + method.getName() + "': legacy and extracted matchers must agree");
        }

        // Object method spot-check — the legacy predicate also excludes Object methods
        Method objToString = Object.class.getDeclaredMethod("toString");
        assertEquals(legacyPredicate(objToString), extracted.matches(asDesc(objToString)));
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static MethodDescription asDesc(Method m) {
        return new MethodDescription.ForLoadedMethod(m);
    }

    /**
     * Direct re-encoding of the predicate that used to live inlined in
     * {@code InstrumentationInstaller.install()} pre-Phase E2:
     * <pre>
     * isMethod & isPublic & !isStatic & !isDeclaredBy(Object) & !nameStartsWith("set") & !nameStartsWith("get")
     * </pre>
     * — applied via reflection so the test doesn't depend on ByteBuddy mechanics.
     */
    private static boolean legacyPredicate(Method m) {
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) return false;
        if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) return false;
        if (m.getDeclaringClass() == Object.class) return false;
        if (m.getName().startsWith("set")) return false;
        if (m.getName().startsWith("get")) return false;
        return true;
    }

    /**
     * Probe class — every method here represents a method shape that real
     * Spring repositories expose at runtime. Names are intentionally chosen
     * to mirror SimpleJpaRepository's surface area.
     */
    @SuppressWarnings("unused")
    public static class Probe {
        // business
        public void save() {}
        public void findById() {}
        public void delete() {}
        public void count() {}
        public void existsById() {}
        public void queryByCustom() {}

        // framework setters (must be excluded — these were the noise source)
        public void setProjectionFactory() {}
        public void setEscapeCharacter() {}
        public void setRepositoryInformation() {}

        // JavaBean getters
        public void getEntityManager() {}
        public void getDomainClass() {}
    }
}
