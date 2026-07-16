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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies masking rules to captured payloads. Used both by the agent (optional client-side
 * pre-mask) and the server (mandatory store-time mask).
 *
 * <p>Behaviour:
 * <ol>
 *   <li>If {@code contentType} indicates JSON, body is parsed and walked. Object keys matching
 *       FIELD_NAME rules cause the entire value (string/number/object/array) to be masked.
 *       Other string values pass through REGEX rules.</li>
 *   <li>Otherwise (or if JSON parse fails), only REGEX rules are applied to the raw body.</li>
 * </ol>
 *
 * <p>Disabled rules are skipped at construction time. The engine is immutable and thread-safe.
 *
 * <p>// [Phase R18] AC-06-1 — 공유 ReDoS 실행 deadline: {@link #mask(String, String)} 1회 호출당
 * // 누적(cumulative) 예산으로 catastrophic backtracking 을 상한한다. 절대 deadline 은 mask() 호출마다
 * // 로컬로 계산되므로 엔진에 per-call mutable 상태가 없다("immutable and thread-safe" 보존).
 * // 비협상 봉인: 2-arg {@link #MaskingEngine(List, ObjectMapper)} 생성자 byte-identical 유지 —
 * // deadline 은 파생 오버로드(3-arg public / 4-arg package-private 테스트)로만 주입(EXT-004 §5 우회).
 * // CLAUDE.md '아키텍처 핵심 원칙'(공유 엔진 결과 일관성) 인용.
 */
public final class MaskingEngine {

    private static final String FULL_MASK = "***";
    private static final String SUBTREE_MASK = "***";

    /** [Phase R18] AC-06-1 — mask() 1회 누적 예산 기본값(ms). 운영자 조정은 3-arg 오버로드. Design §4.4. */
    public static final long DEFAULT_MASK_DEADLINE_MILLIS = 1000;

    private final List<CompiledRule> fieldNameRules;
    private final List<CompiledRule> regexRules;
    private final ObjectMapper mapper;
    // [Phase R18] deadline 예산(nanos) + 시간소스 — final 이라 엔진 immutable/thread-safe 보존.
    private final long deadlineBudgetNanos;
    private final LongSupplier nanoSource;

    /**
     * 봉인된 2-arg 생성자 — byte-identical 유지(소비처 무수정). 기본 deadline({@value #DEFAULT_MASK_DEADLINE_MILLIS}ms)
     * 을 쓰는 3-arg 로 위임한다.
     */
    public MaskingEngine(List<MaskingRule> rules, ObjectMapper mapper) {
        this(rules, mapper, DEFAULT_MASK_DEADLINE_MILLIS);
    }

    /**
     * [Phase R18] deadline 조정 오버로드 — 운영자가 mask() 누적 예산(ms)을 상/하향할 때.
     *
     * @param deadlineMillis mask() 1회 누적 예산(ms). {@code <= 0} 이면 misconfig fail-fast.
     * @throws IllegalArgumentException {@code deadlineMillis <= 0} 일 때
     */
    public MaskingEngine(List<MaskingRule> rules, ObjectMapper mapper, long deadlineMillis) {
        this(rules, mapper, deadlineMillis, System::nanoTime);
    }

    /**
     * [Phase R18][EXT-006] 시간소스 주입 오버로드 — package-private 테스트 전용(결정적 deadline 경계 검증).
     * production 은 항상 위 3-arg({@code System::nanoTime})만 사용한다.
     */
    MaskingEngine(List<MaskingRule> rules, ObjectMapper mapper, long deadlineMillis, LongSupplier nanoSource) {
        Objects.requireNonNull(rules, "rules");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.nanoSource = Objects.requireNonNull(nanoSource, "nanoSource");
        if (deadlineMillis <= 0) {
            throw new IllegalArgumentException("deadlineMillis must be > 0");
        }
        this.deadlineBudgetNanos = deadlineMillis * 1_000_000L; // [S-120] 단위 변환은 한 곳(ms→nano)에서만.
        List<CompiledRule> fields = new ArrayList<>();
        List<CompiledRule> regexes = new ArrayList<>();
        for (MaskingRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            Pattern pattern = Pattern.compile(rule.pattern());
            CompiledRule compiled = new CompiledRule(rule, pattern);
            switch (rule.ruleType()) {
                case FIELD_NAME -> fields.add(compiled);
                case REGEX -> regexes.add(compiled);
            }
        }
        this.fieldNameRules = List.copyOf(fields);
        this.regexRules = List.copyOf(regexes);
    }

    /**
     * Apply rules to {@code body}. Returns {@code null} for {@code null} input.
     *
     * @param body        raw payload body (may be JSON, plain text, or null)
     * @param contentType MIME type hint (e.g. {@code application/json}); may be null
     * @return masked body
     */
    public String mask(String body, String contentType) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        // [Phase R18] AC-06-1 — cumulative 절대 deadline 1개를 이 호출 전체(walk/regex)에 관통시킨다.
        //   deadline 초과 시 DeadlineCharSequence.charAt 이 RegexTimeoutException 을 던지고, 예외는
        //   mask() 밖으로 완전 unwind 돼 호출부가 경로별로 흡수한다(부분결과 PII escape 0 — NFR-04).
        long deadlineNanos = nanoSource.getAsLong() + deadlineBudgetNanos;
        if (looksLikeJson(contentType)) {
            try {
                JsonNode root = mapper.readTree(body);
                JsonNode masked = walk(root, deadlineNanos);
                return mapper.writeValueAsString(masked);
            } catch (JsonProcessingException e) {
                // declared JSON but unparseable — fall through to regex-only
            }
        }
        return applyRegexRules(body, deadlineNanos);
    }

    private static boolean looksLikeJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("json");
    }

    private JsonNode walk(JsonNode node, long deadlineNanos) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                CompiledRule fieldMatch = matchFieldName(key, deadlineNanos);
                if (fieldMatch != null) {
                    out.set(key, maskValue(value, fieldMatch.rule().strategy()));
                } else {
                    out.set(key, walk(value, deadlineNanos));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(element -> out.add(walk(element, deadlineNanos)));
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(applyRegexRules(node.asText(), deadlineNanos));
        }
        return node;
    }

    private CompiledRule matchFieldName(String key, long deadlineNanos) {
        for (CompiledRule cr : fieldNameRules) {
            // [Phase R18] 필드명 매칭 입력도 deadline wrapper 로 감싼다(field-name 룰의 ReDoS 방어).
            if (cr.pattern().matcher(new DeadlineCharSequence(key, deadlineNanos, nanoSource)).matches()) {
                return cr;
            }
        }
        return null;
    }

    private JsonNode maskValue(JsonNode value, MaskingStrategy strategy) {
        if (value.isTextual()) {
            return TextNode.valueOf(applyStrategy(value.asText(), strategy));
        }
        if (value.isNumber() || value.isBoolean()) {
            return TextNode.valueOf(applyStrategy(value.asText(), strategy));
        }
        if (value.isNull()) {
            return value;
        }
        // object or array — replace whole subtree to avoid leaking nested data via the matched key
        return TextNode.valueOf(strategy == MaskingStrategy.LENGTH_ONLY
                ? "[len=?]"
                : SUBTREE_MASK);
    }

    private String applyRegexRules(String value, long deadlineNanos) {
        if (value == null || regexRules.isEmpty()) {
            return value;
        }
        String result = value;
        for (CompiledRule cr : regexRules) {
            MaskingStrategy strategy = cr.rule().strategy();
            // [Phase R18] 매칭 입력을 deadline wrapper 로 감싼다 — backtracking 폭주 시 charAt 에서 탈출.
            //   toString/charAt/subSequence 를 delegate 로 위임하므로 정상 입력의 마스킹 출력은 byte-identical.
            result = cr.pattern().matcher(new DeadlineCharSequence(result, deadlineNanos, nanoSource))
                    .replaceAll(match -> Matcher.quoteReplacement(applyStrategy(match.group(), strategy)));
        }
        return result;
    }

    static String applyStrategy(String value, MaskingStrategy strategy) {
        if (value == null) {
            return null;
        }
        return switch (strategy) {
            case FULL -> FULL_MASK;
            case PARTIAL -> partial(value);
            case HASH -> hash(value);
            case LENGTH_ONLY -> "[len=" + value.length() + "]";
        };
    }

    private static String partial(String value) {
        int n = value.length();
        if (n == 0) {
            return value;
        }
        int keep = Math.max(1, n / 4);
        if (n <= keep) {
            return "*".repeat(n);
        }
        return value.substring(0, keep) + "*".repeat(n - keep);
    }

    private static String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return "[h:" + hex.substring(0, 8) + "]";
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JRE — if it disappears, mask conservatively
            return FULL_MASK;
        }
    }

    private record CompiledRule(MaskingRule rule, Pattern pattern) {
    }
}
