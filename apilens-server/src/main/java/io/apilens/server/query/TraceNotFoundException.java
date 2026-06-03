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

/**
 * Thrown when a requested trace does not exist. Mapped to HTTP 404.
 */
public class TraceNotFoundException extends RuntimeException {

    private final String traceId;

    public TraceNotFoundException(String traceId) {
        super("trace not found: " + traceId);
        this.traceId = traceId;
    }

    public String getTraceId() {
        return traceId;
    }
}
