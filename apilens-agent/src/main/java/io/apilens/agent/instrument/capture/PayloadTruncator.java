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
package io.apilens.agent.instrument.capture;

import java.nio.charset.StandardCharsets;

/**
 * Truncates payload bodies to fit {@code apilens.payload.max-bytes}.
 *
 * <p>Returns a {@link Result} carrying the (possibly truncated) body, the
 * original byte length, and a {@code truncated} flag matching what
 * {@code payloads.truncated} expects.
 *
 * <p>Truncation cuts on a UTF-8 boundary so we never split a multi-byte char.
 */
public final class PayloadTruncator {

    private PayloadTruncator() {
    }

    public static Result truncate(String body, int maxBytes) {
        if (body == null) {
            return new Result(null, 0, false);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long originalSize = bytes.length;
        if (bytes.length <= maxBytes) {
            return new Result(body, originalSize, false);
        }
        // Walk back to a UTF-8 char boundary so we don't slice a multi-byte sequence
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new Result(new String(bytes, 0, cut, StandardCharsets.UTF_8), originalSize, true);
    }

    public record Result(String body, long sizeBytes, boolean truncated) {
    }
}
