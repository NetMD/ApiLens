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

/**
 * Trace summary plus optional span tree.
 *
 * <p>List view (dashboard scatter plot) returns {@code spans = null} for cheap
 * payloads; detail view ({@code GET /v1/traces/{id}}) populates the full tree.
 *
 * <p>Summary fields ({@code spanCount}, {@code durationMs}, {@code hasError},
 * {@code serviceCount}) are computed at server ingest time, not sent by agent.
 *
 * @param traceId       W3C trace id
 * @param rootOperation operation name of the root span (typically {@code GET /...})
 * @param serviceName   service that produced the root span
 * @param startTime     epoch millis at trace start
 * @param durationMs    end-to-end latency in millis
 * @param status        OK or ERROR (ERROR if any span errored)
 * @param spanCount     total spans in this trace
 * @param serviceCount  distinct services touched (1 in v0.1)
 * @param hasError      convenience flag, equivalent to {@code status == ERROR}
 * @param receivedAt    epoch millis at server ingest, retention base
 * @param spans         full span tree; {@code null} in list responses
 */
public record Trace(
        String traceId,
        String rootOperation,
        String serviceName,
        long startTime,
        long durationMs,
        SpanStatus status,
        int spanCount,
        int serviceCount,
        boolean hasError,
        long receivedAt,
        List<Span> spans
) {
}
