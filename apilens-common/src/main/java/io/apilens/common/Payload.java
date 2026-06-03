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

/**
 * Captured request/response body attached to a span.
 *
 * <p>Wire format: agent sends raw {@code body}; server applies masking before
 * persisting (per architecture: 마스킹은 server-side 적용).
 *
 * @param direction   IN (received) or OUT (sent)
 * @param contentType MIME type, e.g. {@code application/json}; {@code null} if unknown
 * @param body        body text, possibly masked or truncated; {@code null} if not captured
 * @param sizeBytes   original size before truncation
 * @param truncated   {@code true} when body was cut to fit max-capture limit
 */
public record Payload(
        PayloadDirection direction,
        String contentType,
        String body,
        long sizeBytes,
        boolean truncated
) {
}
