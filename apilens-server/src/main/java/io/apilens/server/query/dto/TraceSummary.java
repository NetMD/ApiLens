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

import io.apilens.common.SpanStatus;

/**
 * Single row of {@code GET /v1/traces}. Subset of {@link io.apilens.common.Trace}
 * trimmed to what the dashboard scatter plot and trace list need.
 *
 * @param traceId       W3C trace id
 * @param rootOperation operation name of the root span
 * @param serviceName   service that produced the root span
 * @param startTime     epoch millis at trace start
 * @param durationMs    end-to-end latency
 * @param status        OK or ERROR
 * @param spanCount     total spans in this trace
 * @param hasError      convenience flag, equivalent to {@code status == ERROR}
 */
public record TraceSummary(
        String traceId,
        String rootOperation,
        String serviceName,
        long startTime,
        long durationMs,
        SpanStatus status,
        int spanCount,
        boolean hasError
) {
}
