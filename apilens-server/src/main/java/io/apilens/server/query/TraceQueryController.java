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
package io.apilens.server.query;

import io.apilens.common.SpanStatus;
import io.apilens.server.query.dto.PayloadListResponse;
import io.apilens.server.query.dto.ServiceListResponse;
import io.apilens.server.query.dto.TraceDetailResponse;
import io.apilens.server.query.dto.TraceListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read endpoints consumed by the dashboard and trace detail view.
 *
 * <p>Error handling:
 * <ul>
 *   <li>{@link IllegalArgumentException} (incl. {@link CursorCodec.InvalidCursorException}) → 400</li>
 *   <li>{@link TraceNotFoundException}, {@link SpanNotFoundException} → 404 with stable body shape</li>
 * </ul>
 */
@RestController
public class TraceQueryController {

    private final TraceQueryService service;

    public TraceQueryController(TraceQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/traces")
    public TraceListResponse listTraces(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Long until,
            @RequestParam(required = false) SpanStatus status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        return this.service.listTraces(service, since, until, status, limit, cursor);
    }

    @GetMapping("/v1/traces/{traceId}")
    public TraceDetailResponse getTrace(@PathVariable String traceId) {
        return service.getTrace(traceId);
    }

    @GetMapping("/v1/traces/{traceId}/spans/{spanId}/payloads")
    public PayloadListResponse getPayloads(
            @PathVariable String traceId,
            @PathVariable String spanId
    ) {
        return service.getPayloads(traceId, spanId);
    }

    @GetMapping("/v1/services")
    public ServiceListResponse listServices() {
        return service.listServices();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(TraceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleTraceNotFound(TraceNotFoundException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "trace not found");
        body.put("traceId", e.getTraceId());
        return body;
    }

    @ExceptionHandler(SpanNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleSpanNotFound(SpanNotFoundException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "span not found");
        body.put("traceId", e.getTraceId());
        body.put("spanId", e.getSpanId());
        return body;
    }
}
