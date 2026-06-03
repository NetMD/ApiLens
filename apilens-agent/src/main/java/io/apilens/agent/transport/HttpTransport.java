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
package io.apilens.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.apilens.agent.util.AgentLogger;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Sends span batches to {@code POST {server}/v1/spans} using JDK's built-in
 * {@link HttpClient}. No external HTTP libraries — keeping the agent's
 * dependency footprint to apilens-common + ByteBuddy (relocated) only.
 *
 * <p>Retry policy (v0.1, intentionally simple):
 * <ul>
 *   <li>2xx → success, batch consumed</li>
 *   <li>4xx → log + drop (retry won't help with malformed payload)</li>
 *   <li>5xx or IOException → 1 retry after 1 second; if still failing, silent drop</li>
 * </ul>
 *
 * <p>Connection-state logging is <em>edge-triggered</em>: when the collector is
 * unreachable (e.g. the server hasn't been started yet → {@code ConnectException}),
 * the loss is logged <strong>once</strong> on the up→down transition and then
 * suppressed until the collector responds again, at which point a single recovery
 * line is logged. This keeps the host application's stderr from being flooded with
 * one error per flush cycle while ApiLens server is down.
 *
 * <p>Exponential backoff and persistent retry queues are out of scope for v0.1.
 */
public final class HttpTransport {

    static final long RETRY_DELAY_MS = 1_000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI ingestUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AgentLogger logger;

    /**
     * Collector reachability, used to log connection errors on state transitions
     * only (see class javadoc). Starts {@code true} (optimistic) so the first
     * failure against a down server still logs exactly once. Confined to the
     * single {@link SpanSender} thread that drives {@link #send(List)}, so a plain
     * field is sufficient — no synchronization required.
     */
    private boolean serverReachable = true;

    public HttpTransport(String serverUrl, AgentLogger logger) {
        this(URI.create(serverUrl + "/v1/spans"), defaultClient(), logger);
    }

    /**
     * Test seam: inject a configured HttpClient (e.g. one pointed at an embedded server).
     *
     * <p>Jackson's {@code ObjectMapper} is intentionally not a parameter — keeping it
     * inside this class so external callers don't depend on Jackson types, which the
     * shadow build relocates into {@code io.apilens.agent.shaded.jackson.*}.
     */
    HttpTransport(URI ingestUri, HttpClient client, AgentLogger logger) {
        this.ingestUri = ingestUri;
        this.client = client;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.logger = logger;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * Attempt to deliver the batch. Returns {@code true} when at least one HTTP
     * exchange completed with a 2xx status; otherwise the batch is dropped after
     * the retry budget is exhausted.
     */
    public boolean send(List<Span> batch) {
        if (batch == null || batch.isEmpty()) {
            return true;
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new IngestRequest(batch));
        } catch (Exception e) {
            // serialization failure is non-recoverable — never retry
            logger.error("failed to serialize span batch (size=" + batch.size() + ")", e);
            return false;
        }

        Outcome first = attempt(body);
        switch (first) {
            case SUCCESS -> {
                return true;
            }
            case DROPPED -> {
                return false;
            }
            case RETRYABLE -> {
                sleepQuietly(RETRY_DELAY_MS);
                return attempt(body) == Outcome.SUCCESS;
            }
        }
        return false;
    }

    private Outcome attempt(byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(ingestUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // A reply of any status means the TCP connection succeeded → the
            // collector is reachable; log recovery once if we were previously down.
            markReachable();
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                logger.debug("ingest ok: status=" + status + " bytes=" + body.length);
                return Outcome.SUCCESS;
            }
            if (status >= 400 && status < 500) {
                logger.warn("ingest rejected (4xx, will not retry): status=" + status
                        + " body=" + truncate(response.body()));
                return Outcome.DROPPED;
            }
            logger.warn("ingest server error: status=" + status);
            return Outcome.RETRYABLE;
        } catch (IOException e) {
            // Collector unreachable (e.g. server not started → ConnectException).
            // Log the transition once, then stay silent until it recovers.
            markUnreachable(e);
            return Outcome.RETRYABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.DROPPED;
        }
    }

    /**
     * Marks the collector reachable. On the down→up transition, logs a single
     * recovery line; while already up this is a no-op.
     */
    private void markReachable() {
        if (!serverReachable) {
            serverReachable = true;
            logger.info("ingest connection restored: " + ingestUri);
        }
    }

    /**
     * Marks the collector unreachable. On the up→down transition, logs a single
     * error line; while already down the repeat is demoted to debug so a stopped
     * server does not flood the host's stderr.
     */
    private void markUnreachable(IOException e) {
        if (serverReachable) {
            serverReachable = false;
            logger.error("ingest connection lost — collector unreachable at " + ingestUri
                    + "; suppressing repeat errors until it recovers", e);
        } else {
            logger.debug("ingest still unreachable: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum Outcome {
        /** 2xx — batch consumed. */
        SUCCESS,
        /** 4xx or unrecoverable client error — drop and do not retry. */
        DROPPED,
        /** 5xx or transient IO — try once more before dropping. */
        RETRYABLE
    }
}
