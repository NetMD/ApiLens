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
 * Body of {@code GET /v1/traces/{traceId}/spans/{spanId}/payloads}. Empty list
 * is a valid 200 response when the span has no captured payloads (not 404).
 *
 * @param payloads payloads attached to the span, in {@code payload_id} order
 */
public record PayloadListResponse(
        List<PayloadDto> payloads
) {
}
