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
package io.apilens.server.query.dto;

/**
 * Single payload returned by {@code GET /v1/traces/{traceId}/spans/{spanId}/payloads}.
 *
 * <p>{@code direction} is lowercase ({@code "in"|"out"}) to match the on-disk
 * representation in {@code payloads.direction}. Body is already masked at ingest
 * time — never re-mask here.
 *
 * @param direction   "in" or "out"
 * @param contentType MIME type, may be null
 * @param body        masked body, may be null when not captured
 * @param sizeBytes   original size before truncation
 * @param truncated   {@code true} when body was cut to fit max-capture limit
 */
public record PayloadDto(
        String direction,
        String contentType,
        String body,
        long sizeBytes,
        boolean truncated
) {
}
