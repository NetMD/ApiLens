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
package io.apilens.server.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * URL-safe base64 codec for trace pagination cursors.
 *
 * <p>Cursor encodes the last seen {@code (start_time, trace_id)} so that the next
 * page query can resume from a stable position. Offset-based pagination is
 * unsuitable here because new traces arrive continuously and would shift offsets.
 *
 * <p>Wire format: {@code base64url("{startTime}:{traceId}")} (no padding).
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(long startTime, String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            throw new IllegalArgumentException("traceId must not be empty");
        }
        String raw = startTime + ":" + traceId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new InvalidCursorException("cursor is empty");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor is not valid base64");
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        int sep = raw.indexOf(':');
        if (sep <= 0 || sep == raw.length() - 1) {
            throw new InvalidCursorException("cursor missing separator");
        }
        long startTime;
        try {
            startTime = Long.parseLong(raw.substring(0, sep));
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("cursor has invalid startTime");
        }
        String traceId = raw.substring(sep + 1);
        return new Cursor(startTime, traceId);
    }

    public record Cursor(long startTime, String traceId) {
    }

    public static class InvalidCursorException extends IllegalArgumentException {
        public InvalidCursorException(String message) {
            super(message);
        }
    }
}
