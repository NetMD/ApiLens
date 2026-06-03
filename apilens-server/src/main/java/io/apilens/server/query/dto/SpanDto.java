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

import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;

import java.util.Map;

/**
 * Single span returned by {@code GET /v1/traces/{traceId}}. The flat list (no
 * tree) is intentional — React Flow consumes flat nodes + edges, so server-side
 * tree assembly would just be re-flattened in the client.
 *
 * <p>{@code attributes} is the parsed {@code attributes_json}. Parse failure
 * returns {@code Map.of("_raw", originalString)} for defensive forward-compat.
 *
 * @param spanId        span identifier
 * @param parentSpanId  parent span id, {@code null} for root
 * @param serviceName   service that produced this span
 * @param operationName human-readable label
 * @param spanKind      SERVER/CLIENT/INTERNAL/DB/UI_EVENT
 * @param startTime     epoch millis at start
 * @param endTime       epoch millis at end
 * @param status        OK or ERROR
 * @param attributes    parsed JSON attributes; empty map when none stored
 */
public record SpanDto(
        String spanId,
        String parentSpanId,
        String serviceName,
        String operationName,
        SpanKind spanKind,
        long startTime,
        long endTime,
        SpanStatus status,
        Map<String, Object> attributes
) {
}
