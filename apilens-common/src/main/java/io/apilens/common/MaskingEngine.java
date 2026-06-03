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
 */
public final class MaskingEngine {

    private static final String FULL_MASK = "***";
    private static final String SUBTREE_MASK = "***";

    private final List<CompiledRule> fieldNameRules;
    private final List<CompiledRule> regexRules;
    private final ObjectMapper mapper;

    public MaskingEngine(List<MaskingRule> rules, ObjectMapper mapper) {
        Objects.requireNonNull(rules, "rules");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
        if (looksLikeJson(contentType)) {
            try {
                JsonNode root = mapper.readTree(body);
                JsonNode masked = walk(root);
                return mapper.writeValueAsString(masked);
            } catch (JsonProcessingException e) {
                // declared JSON but unparseable — fall through to regex-only
            }
        }
        return applyRegexRules(body);
    }

    private static boolean looksLikeJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("json");
    }

    private JsonNode walk(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                CompiledRule fieldMatch = matchFieldName(key);
                if (fieldMatch != null) {
                    out.set(key, maskValue(value, fieldMatch.rule().strategy()));
                } else {
                    out.set(key, walk(value));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(element -> out.add(walk(element)));
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(applyRegexRules(node.asText()));
        }
        return node;
    }

    private CompiledRule matchFieldName(String key) {
        for (CompiledRule cr : fieldNameRules) {
            if (cr.pattern().matcher(key).matches()) {
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

    private String applyRegexRules(String value) {
        if (value == null || regexRules.isEmpty()) {
            return value;
        }
        String result = value;
        for (CompiledRule cr : regexRules) {
            MaskingStrategy strategy = cr.rule().strategy();
            result = cr.pattern().matcher(result)
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
