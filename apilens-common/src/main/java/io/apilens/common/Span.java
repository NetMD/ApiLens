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

import java.util.List;
import java.util.Map;

/**
 * Single instrumented operation. OpenTelemetry-compatible structure: parent-child
 * tree formed via {@code parentSpanId} (root span has {@code null}).
 *
 * <p>{@code attributes} carries free-form metadata (HTTP method/URL, SQL, exception
 * class/message, JDBC param count, etc.). Persisted as JSON text in {@code spans.attributes_json}.
 *
 * @param spanId         span identifier, unique within trace
 * @param traceId        parent trace id (W3C trace context format)
 * @param parentSpanId   parent span id, {@code null} for root
 * @param serviceName    logical service name; in v0.1 single-service so all spans share it,
 *                       kept per-span for v0.3 MSA propagation
 * @param operationName  human-readable label (e.g. {@code GET /api/orders/{id}})
 * @param spanKind       kind classification (SERVER/CLIENT/INTERNAL/DB/UI_EVENT)
 * @param startTime      epoch millis at start
 * @param endTime        epoch millis at end
 * @param status         OK or ERROR
 * @param attributes     free-form attributes; {@code null} when none captured
 * @param payloads       captured request/response bodies; empty list when none
 */
public record Span(
        String spanId,
        String traceId,
        String parentSpanId,
        String serviceName,
        String operationName,
        SpanKind spanKind,
        long startTime,
        long endTime,
        SpanStatus status,
        Map<String, Object> attributes,
        List<Payload> payloads
) {
}
