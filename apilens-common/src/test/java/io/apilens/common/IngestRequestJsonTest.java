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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that ingest DTOs round-trip cleanly through Jackson without custom
 * configuration — the wire contract between agent and server.
 */
class IngestRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsBatchWithRootAndChildSpans() throws Exception {
        Payload requestBody = new Payload(
                PayloadDirection.IN,
                "application/json",
                "{\"orderId\":42}",
                14L,
                false
        );
        Span root = new Span(
                "span-root",
                "trace-1",
                null,
                "checkout",
                "POST /api/orders",
                SpanKind.SERVER,
                1_700_000_000_000L,
                1_700_000_000_120L,
                SpanStatus.OK,
                Map.of("http.method", "POST", "http.status_code", 200),
                List.of(requestBody)
        );
        Span child = new Span(
                "span-db",
                "trace-1",
                "span-root",
                "checkout",
                "INSERT orders",
                SpanKind.DB,
                1_700_000_000_010L,
                1_700_000_000_080L,
                SpanStatus.OK,
                Map.of("db.statement", "INSERT INTO orders ..."),
                List.of()
        );
        IngestRequest request = new IngestRequest(List.of(root, child));

        String json = mapper.writeValueAsString(request);
        IngestRequest restored = mapper.readValue(json, IngestRequest.class);

        assertEquals(request, restored);
        assertEquals(2, restored.spans().size());
        assertNull(restored.spans().get(0).parentSpanId());
        assertEquals("span-root", restored.spans().get(1).parentSpanId());
        assertEquals(SpanKind.DB, restored.spans().get(1).spanKind());
    }

    @Test
    void traceSummaryOmitsSpansWhenNull() throws Exception {
        Trace summary = new Trace(
                "trace-1",
                "POST /api/orders",
                "checkout",
                1_700_000_000_000L,
                120L,
                SpanStatus.OK,
                2,
                1,
                false,
                1_700_000_000_500L,
                null
        );

        String json = mapper.writeValueAsString(summary);
        Trace restored = mapper.readValue(json, Trace.class);

        assertEquals(summary, restored);
        assertNull(restored.spans());
        assertNotNull(restored.traceId());
    }
}
