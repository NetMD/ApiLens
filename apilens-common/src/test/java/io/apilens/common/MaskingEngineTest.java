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
package io.apilens.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskingEngineTest {

    private static final String JSON = "application/json";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nullAndEmptyBodyPassThrough() {
        MaskingEngine engine = new MaskingEngine(List.of(), mapper);

        assertNull(engine.mask(null, JSON));
        assertEquals("", engine.mask("", JSON));
    }

    @Test
    void disabledRulesAreIgnored() {
        MaskingRule disabled = new MaskingRule(
                "password", MaskingRuleType.FIELD_NAME, "password", MaskingStrategy.FULL, false);
        MaskingEngine engine = new MaskingEngine(List.of(disabled), mapper);

        String body = "{\"password\":\"secret\"}";
        assertEquals(body, engine.mask(body, JSON));
    }

    @Test
    void fieldNameFullStrategyReplacesValue() throws Exception {
        MaskingRule rule = new MaskingRule(
                "password", MaskingRuleType.FIELD_NAME, "password|passwd|pwd", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"password\":\"secret\",\"name\":\"alice\"}", JSON));

        assertEquals("***", out.get("password").asText());
        assertEquals("alice", out.get("name").asText());
    }

    @Test
    void fieldNameMatchIsAnchored() throws Exception {
        // pattern "password" must NOT match "password_hint"
        MaskingRule rule = new MaskingRule(
                "password", MaskingRuleType.FIELD_NAME, "password", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"password\":\"x\",\"password_hint\":\"y\"}", JSON));

        assertEquals("***", out.get("password").asText());
        assertEquals("y", out.get("password_hint").asText());
    }

    @Test
    void fieldNameMaskAppliesToNestedObjectsAsSubtreeReplacement() throws Exception {
        MaskingRule rule = new MaskingRule(
                "credentials", MaskingRuleType.FIELD_NAME, "credentials", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"credentials\":{\"user\":\"a\",\"pwd\":\"b\"}}", JSON));

        assertEquals("***", out.get("credentials").asText());
    }

    @Test
    void fieldNameMaskAppliesToPrimitiveNumbers() throws Exception {
        MaskingRule rule = new MaskingRule(
                "ssn_number", MaskingRuleType.FIELD_NAME, "ssn_number", MaskingStrategy.LENGTH_ONLY, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"ssn_number\":1234567890}", JSON));

        assertEquals("[len=10]", out.get("ssn_number").asText());
    }

    @Test
    void regexPartialMasksMiddleOfMatch() throws Exception {
        MaskingRule rrn = new MaskingRule(
                "주민번호", MaskingRuleType.REGEX, "\\d{6}-?\\d{7}", MaskingStrategy.PARTIAL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rrn), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"note\":\"고객 123456-1234567 입력함\"}", JSON));

        String masked = out.get("note").asText();
        // 14-char match, keep first 14/4=3 chars, mask 11
        assertTrue(masked.contains("123***********"), "got: " + masked);
        assertTrue(masked.startsWith("고객 "));
    }

    @Test
    void regexHashStrategyProducesShortDeterministicHash() throws Exception {
        MaskingRule rule = new MaskingRule(
                "email", MaskingRuleType.REGEX, "[\\w.]+@[\\w.]+", MaskingStrategy.HASH, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        JsonNode first = mapper.readTree(engine.mask(
                "{\"contact\":\"alice@example.com\"}", JSON));
        JsonNode second = mapper.readTree(engine.mask(
                "{\"contact\":\"alice@example.com\"}", JSON));

        String hashed = first.get("contact").asText();
        assertTrue(hashed.startsWith("[h:") && hashed.endsWith("]"),
                "expected [h:xxxxxxxx], got: " + hashed);
        assertEquals(hashed.length(), 12); // [h: + 8 hex + ]
        assertEquals(hashed, second.get("contact").asText()); // deterministic
    }

    @Test
    void regexAppliesAcrossNestedStringsAndArrays() throws Exception {
        MaskingRule card = new MaskingRule(
                "카드번호", MaskingRuleType.REGEX, "\\d{4}-\\d{4}-\\d{4}-\\d{4}", MaskingStrategy.PARTIAL, true);
        MaskingEngine engine = new MaskingEngine(List.of(card), mapper);

        JsonNode out = mapper.readTree(engine.mask(
                "{\"items\":[\"카드 1234-5678-9012-3456\",\"기타\"]}", JSON));

        // 19 chars, keep 19/4=4, mask 15
        String first = out.get("items").get(0).asText();
        assertTrue(first.contains("1234***************"), "got: " + first);
        assertEquals("기타", out.get("items").get(1).asText());
    }

    @Test
    void nonJsonContentTypeAppliesRegexRulesOnly() {
        MaskingRule rrn = new MaskingRule(
                "주민번호", MaskingRuleType.REGEX, "\\d{6}-?\\d{7}", MaskingStrategy.FULL, true);
        // Field-name rule should be ignored because there's no JSON to walk
        MaskingRule pwd = new MaskingRule(
                "password", MaskingRuleType.FIELD_NAME, "password", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rrn, pwd), mapper);

        String result = engine.mask("password=hunter2 ssn=900101-1234567", "text/plain");

        assertEquals("password=hunter2 ssn=***", result);
    }

    @Test
    void invalidJsonFallsBackToRegexOnly() {
        MaskingRule rule = new MaskingRule(
                "주민번호", MaskingRuleType.REGEX, "\\d{6}-\\d{7}", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        // Malformed JSON declared as JSON
        String result = engine.mask("not-json 900101-1234567 still-not-json", JSON);

        assertEquals("not-json *** still-not-json", result);
    }

    @Test
    void regexReplacementWithSpecialCharsDoesNotBreak() {
        // Hash output never contains $ or \, but be defensive against future strategy outputs
        MaskingRule rule = new MaskingRule(
                "x", MaskingRuleType.REGEX, "VALUE", MaskingStrategy.FULL, true);
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper);

        // FULL mask is "***"; verify no replacement-string interpretation surprises
        assertEquals("a *** b", engine.mask("a VALUE b", "text/plain"));
    }

    // ─── [Phase R18] AC-06-1 — ReDoS 실행 deadline (EXT-006 결정적 시간소스 주입) ─────

    /** default deadline 상수 고정(1000ms). 매직넘버 회귀 가드. */
    @Test
    void defaultMaskDeadlineMillisIsOneSecond() {
        assertEquals(1000L, MaskingEngine.DEFAULT_MASK_DEADLINE_MILLIS);
    }

    /** 3-arg 오버로드는 deadlineMillis <= 0 을 misconfig fail-fast(IllegalArgumentException) 한다. */
    @Test
    void threeArgConstructorRejectsNonPositiveDeadline() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaskingEngine(List.of(), mapper, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new MaskingEngine(List.of(), mapper, -1L));
    }

    /**
     * deadline 초과 → mask() 가 {@link RegexTimeoutException} 을 전파(엔진 내부 degrade 안 함).
     * 시간소스 주입으로 결정적: 첫 호출(mask 진입, deadline 계산)=0, 이후(charAt 체크)=예산 초과값.
     */
    @Test
    void maskThrowsRegexTimeoutWhenDeadlineExceeded() {
        MaskingRule rule = new MaskingRule(
                "a", MaskingRuleType.REGEX, "a+", MaskingStrategy.FULL, true);
        LongSupplier jumpsPastDeadline = new LongSupplier() {
            private boolean first = true;

            @Override
            public long getAsLong() {
                if (first) {
                    first = false;
                    return 0L;                 // mask 진입: deadlineNanos = 0 + 1s
                }
                return 2_000_000_000L;         // charAt 체크: 2s > 1s → 초과
            }
        };
        // 4-arg package-private 테스트 생성자(같은 패키지 io.apilens.common 이라 접근 가능).
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper, 1000L, jumpsPastDeadline);

        String longBody = "a".repeat(2000); // charAt 1024 회 초과 → deadline 체크 발동
        assertThrows(RegexTimeoutException.class, () -> engine.mask(longBody, "text/plain"));
    }

    /** deadline 미도달 정상 입력 → throw 0 + 마스킹 출력 정상(시간소스가 항상 예산 이하). */
    @Test
    void maskDoesNotThrowAndMasksNormallyWithinDeadline() {
        MaskingRule rule = new MaskingRule(
                "a", MaskingRuleType.REGEX, "a+", MaskingStrategy.FULL, true);
        LongSupplier alwaysWithinDeadline = () -> 0L; // deadlineNanos=1s, 체크 nano=0 → 미초과
        MaskingEngine engine = new MaskingEngine(List.of(rule), mapper, 1000L, alwaysWithinDeadline);

        assertEquals("***", engine.mask("a".repeat(2000), "text/plain"),
                "deadline 미도달 → 정상 마스킹(전체 a+ 런 → FULL '***')");
    }
}
