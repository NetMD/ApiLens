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
 * Span batch sent by agent to {@code POST /v1/spans}.
 *
 * <p>Wrapper kept (instead of bare {@code List<Span>}) so future fields like
 * agent version or capture flags can be added without breaking the wire format.
 *
 * @param spans batch of spans; can span multiple traces if agent buffered them
 */
public record IngestRequest(
        List<Span> spans
) {
}
