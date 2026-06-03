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

import java.util.List;

/**
 * Body of {@code GET /v1/traces/{traceId}}.
 *
 * @param trace       summary metadata
 * @param rootSpanId  span id of the trace root (the unique span with {@code parent_span_id == null}),
 *                    or {@code null} if no such span is present in {@code spans}. Convenience field —
 *                    UI clients otherwise need to scan {@code spans} themselves to find it.
 * @param spans       flat span list ordered by start_time ASC; payloads loaded lazily via separate endpoint
 */
public record TraceDetailResponse(
        TraceSummary trace,
        String rootSpanId,
        List<SpanDto> spans
) {
}
