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
package io.apilens.agent.instrument;

import io.apilens.agent.instrument.capture.PayloadTruncator;
import io.apilens.agent.instrument.context.TraceContext;
import io.apilens.agent.instrument.jdbc.CapturedResultSet;
import io.apilens.common.Payload;
import io.apilens.common.PayloadDirection;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers shared by every {@code @Advice} class. Advice methods inline this
 * type's static calls into the target class's bytecode; the actual class
 * resolves at the target's classloader at runtime (agent jar is on the system
 * loader, so it's visible from app classloaders).
 *
 * <p>Every method is wrapped in {@code try/catch(Throwable)} — agent failure
 * must never escape into the host application.
 */
public final class AdviceSupport {

    public static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * [Phase R20] R20/AC-07-2 — {@code exception.stacktrace} 문자 수 상한(OQ-7 architect 확정값:
     * 4,096자 + 후미 절단 + {@code "... (truncated)"} 접미). 상한 존재 자체가 봉인(W-3 — 용량 절감
     * 라운드에서 유일하게 부피를 늘리는 항목이라 실용 최소). 원인 지점은 항상 문자열 앞부분
     * (최상단 프레임 + 첫 Caused by)에 있으므로 앞 보존이 운영 가치 순서와 일치.
     */
    public static final int STACKTRACE_MAX_CHARS = 4096;

    /** [Phase R20] R20/AC-07-2 — 절단 접미 문자열 (상한 초과분에만 부착). */
    static final String STACKTRACE_TRUNCATED_SUFFIX = "... (truncated)";

    /**
     * Re-entrancy guard for JDBC. JDBC drivers (HikariCP proxy → driver → underlying)
     * commonly stack 3 wrappers on the same {@code execute*} call; without this guard
     * we'd record one DB span per wrapper layer.
     *
     * <p>Convention: outer (first enter on this thread) sets to TRUE, all inner
     * enters return {@link TraceContext.Frame#SKIPPED}. Only the outer exit removes
     * the ThreadLocal entry — "set 한 측이 remove" 원칙.
     */
    private static final ThreadLocal<Boolean> IN_DB_SPAN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private AdviceSupport() {
    }

    /** {@code @OnMethodEnter} helper — pushes a new frame. Returns null on any error. */
    public static TraceContext.Frame enter(String operationName, String spanKind) {
        try {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][ADVICE] enter " + spanKind + " " + operationName);
            }
            // [Phase R20] (Q-1) — R20/AC-01-2 Q-U1 verbatim: 옵션 ON && root 후보(스택 깊이 0) && kind 가
            // 진입점(SERVER)이 아니면 trace 생성 억제. 기본값 반드시 꺼짐(Q-D3 — 사용자 명시 비협상 결정).
            // v0.7.0 전방 호환(Q-U2): traceparent 를 수신한 span(원격 부모 있음)은 진입점 취급 —
            // 이 조건식은 depth/kind 만 보므로 그때 "‖ 원격 부모 있음" 축을 여기 한 곳에 더하면 된다.
            if (InstrumentationInstaller.REQUIRE_ENTRY_ROOT
                    && !"SERVER".equals(spanKind)
                    && TraceContext.depth() == 0) {
                return TraceContext.Frame.SKIPPED;
            }
            // [Phase R20] (Q-2) 게이트 exclude — R20/AC-06-1: 리플렉션 실제 이름(FQN) 정확 일치만.
            // "com.foo.Bar" exclude 가 "com.foo.BarMapper#x" 에 오매칭되지 않는다(prefix·부분 문자열 금지 —
            // 패키지 단위 빼기는 weaving exclude 의 소관, W-9 역할 분리). 빈 Set 이면 비용 ≈ 0.
            Set<String> gateExcluded = InstrumentationInstaller.GATE_EXCLUDED_NAMES;
            if (!gateExcluded.isEmpty()) {
                int hash = operationName.indexOf('#');
                if (hash > 0 && gateExcluded.contains(operationName.substring(0, hash))) {
                    return TraceContext.Frame.SKIPPED;
                }
            }
            return TraceContext.push(operationName, spanKind);
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][ADVICE] enter FAILED " + spanKind + " " + operationName
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /**
     * JDBC-specific enter helper. Returns {@link TraceContext.Frame#SKIPPED} for
     * the inner wrappers (re-entrancy), or pushes a real frame for the outer-most
     * call.
     *
     * <p>Caller (advice) must always pair this with {@link #markDbSpanExited(boolean)}
     * in a {@code finally}, with {@code outer = true} for non-SKIPPED frames.
     */
    public static TraceContext.Frame enterDbSpan(String typeName, String operationName) {
        // [Phase R20] (Q-1) — R20/AC-01-4 (W-4, 사용자 명시 비협상 결정): 이 판정은 IN_DB_SPAN 의
        // 어떤 읽기/쓰기보다 앞에 있어야 한다. set(아래) 이후에 두면 억제된 root 가 ThreadLocal 을
        // true 로 남겨 그 스레드의 이후 JDBC span 이 전부 SKIP 되는 영구 누수가 된다.
        // kind 는 "DB" 고정이라 SERVER 비교 불요. boolean·depth 읽기만이라 throw 표면 없음(불변식 5).
        if (InstrumentationInstaller.REQUIRE_ENTRY_ROOT && TraceContext.depth() == 0) {
            return TraceContext.Frame.SKIPPED;
        }
        Boolean inFlight = IN_DB_SPAN.get();
        if (Boolean.TRUE.equals(inFlight)) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] enter inner SKIP " + typeName);
            }
            return TraceContext.Frame.SKIPPED;
        }
        IN_DB_SPAN.set(Boolean.TRUE);
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][JDBC] enter outer " + typeName);
        }
        try {
            return TraceContext.push(operationName, "DB");
        } catch (Throwable t) {
            // push 실패 시 ThreadLocal을 즉시 정리해야 다음 호출이 정상 진행
            IN_DB_SPAN.remove();
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] enter outer FAILED " + typeName
                        + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /** Outer exit helper — must be called from {@code finally} when {@code outer == true}. */
    public static void markDbSpanExited(boolean outer) {
        if (outer) {
            IN_DB_SPAN.remove();
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] exit outer cleared TL");
            }
        }
    }

    /** Diagnostic helper invoked from JdbcConnectionAdvice; only logs in debug. */
    public static void logPrepareReturn(Object returned, String sql) {
        if (!InstrumentationInstaller.DEBUG) {
            return;
        }
        String cls = returned == null ? "null" : returned.getClass().getName();
        String s;
        if (sql == null) {
            s = "null";
        } else if (sql.length() > 80) {
            s = sql.substring(0, 80) + "…";
        } else {
            s = sql;
        }
        System.err.println("[ApiLens][JDBC] prepareStatement returned cls=" + cls + " sql=" + s);
    }

    /**
     * {@code @OnMethodExit} helper — pops the frame, builds a {@link Span},
     * and enqueues it. {@code attributes} and payloads are nullable.
     */
    public static void exit(TraceContext.Frame frame,
                            Throwable thrown,
                            Map<String, Object> attributes,
                            List<Payload> payloads) {
        try {
            if (frame == null) {
                return;
            }
            // Re-entrancy guard sentinel — inner JDBC wrapper. NEVER pop the live
            // stack (would orphan the outer frame) and NEVER enqueue a span.
            if (frame == TraceContext.Frame.SKIPPED) {
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][JDBC] exit inner NO_OP");
                }
                return;
            }
            TraceContext.Frame popped = TraceContext.pop();
            if (popped == null) {
                return;
            }
            // popped should == frame in well-behaved cases; if not, prefer popped (live stack)
            TraceContext.Frame f = popped;

            long now = System.currentTimeMillis();
            // ERROR 판정: thrown != null 또는 attributes의 http.status_code >= 400.
            // 운영자가 보는 빨간 점이 진짜 빨간 점이어야 한다 — 4xx/5xx 응답도 의미상 ERROR.
            SpanStatus status = SpanStatus.OK;
            if (thrown != null) {
                status = SpanStatus.ERROR;
            } else if (attributes != null) {
                Object code = attributes.get("http.status_code");
                if (code instanceof Number n && n.intValue() >= 400) {
                    status = SpanStatus.ERROR;
                }
            }
            Map<String, Object> attrs = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
            if (thrown != null) {
                attrs.put("exception.type", thrown.getClass().getSimpleName());
                if (thrown.getMessage() != null) {
                    attrs.put("exception.message", thrown.getMessage());
                }
                // [Phase R20] R20/AC-07-1 — exception.stacktrace 추가(OTel semconv 표준명, 기존 두 키
                //   불변). thrown == null 이면 키 자체를 넣지 않는다(기존 두 키 전례). 에러 span 에만
                //   발생 — 핫패스 아님. 상한·절단은 STACKTRACE_MAX_CHARS(4,096자 + 후미 절단) 참조.
                String stacktrace = buildStackTrace(thrown);
                if (stacktrace != null) {
                    attrs.put("exception.stacktrace", stacktrace);
                }
            }

            String serviceName = InstrumentationInstaller.SERVICE_NAME;
            Span span = new Span(
                    f.spanId,
                    f.traceId,
                    f.parentSpanId,
                    serviceName,
                    f.operationName,
                    parseKind(f.spanKind),
                    f.startMillis,
                    now,
                    status,
                    attrs.isEmpty() ? null : attrs,
                    payloads == null ? List.of() : payloads
            );

            if (InstrumentationInstaller.QUEUE != null) {
                boolean queued = InstrumentationInstaller.QUEUE.offer(span);
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][ADVICE] exit " + f.spanKind + " " + f.operationName
                            + " durMs=" + (now - f.startMillis) + " queued=" + queued);
                }
            }
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][ADVICE] exit FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * [Phase R20] R20/AC-07-1/AC-07-2 — 예외 전체 스택 문자열 생성 + 상한 절단.
     * {@code printStackTrace(PrintWriter(StringWriter))} 가 원인 사슬({@code Caused by:})을 자동
     * 포함한다. 어떤 실패에도 throw 하지 않고 null 을 반환한다(호스트 throw 0 — 불변식 5:
     * 이 helper 가 실패해도 exit 는 stacktrace 키만 생략하고 span enqueue 를 계속한다).
     */
    private static String buildStackTrace(Throwable thrown) {
        try {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            return truncateStackTrace(sw.toString(), STACKTRACE_MAX_CHARS);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * [Phase R20] R20/AC-07-2 — 문자 수 상한 절단(경계값 단위 테스트 진입점, [S-66] 임계 분기 봉인).
     * 경계: 길이 == max → 무절단(≤ 비교). 길이 > max → 앞 max 자 + {@code "... (truncated)"}.
     * 프레임 수가 아니라 문자 수 상한 — 원인 사슬 경계 계산 불요 + ASCII 위주라 UTF-8 경계 문제 회피.
     */
    static String truncateStackTrace(String raw, int maxChars) {
        if (raw == null || raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, maxChars) + STACKTRACE_TRUNCATED_SUFFIX;
    }

    /**
     * Opt-in helper for {@link io.apilens.agent.instrument.advice.JdbcAdvice} — try to capture
     * a {@link ResultSet} into an in-memory snapshot and return a {@link Proxy}-based wrapper
     * the caller can use in place of the original. Side effects:
     *
     * <ul>
     *   <li>{@code attributes} gets {@code db.rows_read} (and {@code db.rows_truncated} if cut off).</li>
     *   <li>{@code payloadsOut} (must be a mutable list — typically {@code new ArrayList<>(1)})
     *       gets one masked + truncated {@code OUT} payload containing the JSON-serialised rows.</li>
     * </ul>
     *
     * <p>Returns {@code null} on any failure — caller leaves {@code returned} untouched, which
     * means the host app gets back the (possibly partially-iterated) original {@code ResultSet}.
     * This is the opt-in risk the operator accepts by enabling
     * {@code apilens.jdbc.capture-result-set}.
     */
    public static ResultSet tryCaptureResultSet(ResultSet underlying,
                                                Map<String, Object> attributes,
                                                List<Payload> payloadsOut) {
        if (underlying == null) {
            return null;
        }
        int maxBytes = InstrumentationInstaller.PAYLOAD_MAX_BYTES > 0
                ? InstrumentationInstaller.PAYLOAD_MAX_BYTES : 65_536;
        // 행 수 상한 — payload 크기로 1차 제한하지만 무한 SELECT가 들어왔을 때를 위해 행 수도 가드.
        int maxRows = 100;
        try {
            CapturedResultSet.Result captured = CapturedResultSet.capture(underlying, maxRows, maxBytes);
            String json = capturedRowsToJson(captured);
            if (json != null) {
                payloadsOut.add(maskedPayload(PayloadDirection.OUT, CONTENT_TYPE_JSON, json));
            }
            attributes.put("db.rows_read", captured.rowCount);
            if (captured.truncated) {
                attributes.put("db.rows_truncated", Boolean.TRUE);
            }
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] capture rows=" + captured.rowCount
                        + " truncated=" + captured.truncated);
            }
            return captured.wrapper;
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] capture FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /**
     * Serialise captured rows to JSON of shape {@code {columns:[...], rows:[[...], ...]}}.
     * Null on any failure so the advice falls through to a {@code null} payload.
     */
    private static String capturedRowsToJson(CapturedResultSet.Result captured) {
        if (InstrumentationInstaller.MAPPER == null) {
            return null;
        }
        try {
            int colCount = captured.metaData.columnCount();
            List<String> columns = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                columns.add(captured.metaData.label(i));
            }
            // LinkedHashMap → JSON object 순서 보존 (columns 먼저)
            Map<String, Object> shape = new LinkedHashMap<>(2);
            shape.put("columns", columns);
            shape.put("rows", captured.rows);
            return InstrumentationInstaller.MAPPER.writeValueAsString(shape);
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][JDBC] capture serialize FAILED: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    /** Build a payload list with optional in/out bodies; each is masked + truncated. */
    public static List<Payload> payloadsOf(String inBody, String outBody) {
        List<Payload> out = new ArrayList<>(2);
        if (inBody != null) {
            out.add(maskedPayload(PayloadDirection.IN, CONTENT_TYPE_JSON, inBody));
        }
        if (outBody != null) {
            out.add(maskedPayload(PayloadDirection.OUT, CONTENT_TYPE_JSON, outBody));
        }
        return out;
    }

    /** Apply masking + truncation to a body before it ever reaches SpanQueue. */
    public static Payload maskedPayload(PayloadDirection direction, String contentType, String rawBody) {
        try {
            String masked = rawBody;
            if (InstrumentationInstaller.MASKING != null) {
                masked = InstrumentationInstaller.MASKING.mask(rawBody, contentType);
            }
            int max = InstrumentationInstaller.PAYLOAD_MAX_BYTES > 0
                    ? InstrumentationInstaller.PAYLOAD_MAX_BYTES : 65_536;
            PayloadTruncator.Result truncated = PayloadTruncator.truncate(masked, max);
            return new Payload(direction, contentType, truncated.body(),
                    truncated.sizeBytes(), truncated.truncated());
        } catch (Throwable t) {
            // even capture should not blow up — produce an empty placeholder
            return new Payload(direction, contentType, null, 0, false);
        }
    }

    /**
     * If {@code v} is a Spring {@code ResponseEntity} / {@code HttpEntity}, return its
     * {@code body}; otherwise return {@code v}. Done via reflection so the agent
     * doesn't pull Spring as a compile-time dependency.
     *
     * <p>[R11] AC-F-R11-04 (D-P0-01 비협상 — verbatim 인용)
     *   본질: agent AdviceSupport.serializeReturn → Jackson → FileSystemResource.getOutputStream()
     *         → mp4 0바이트 truncate 차단 (Layer 2 — body 영역 위험 타입 사전 skip)
     *   회귀 가드 grep: 정방향 = `isUnsafeToSerialize(body)` (1 hit) / 반대방향 = body 추출 후
     *                    raw Object 반환 분기에서 위험 타입 차단 누락 0 hit
     *   CLAUDE.md 인용: "Agent 자체 장애가 호스트 앱에 영향 0 — 모든 agent 코드는
     *                    try-catch 로 감싸고 실패 시 silent drop"
     */
    private static Object unwrapResponseEntity(Object v) {
        String cn = v.getClass().getName();
        if ("org.springframework.http.ResponseEntity".equals(cn)
                || "org.springframework.http.HttpEntity".equals(cn)) {
            try {
                Object body = v.getClass().getMethod("getBody").invoke(v);
                // [Phase R11] F-R11-02 / AC-02-1 — Layer 2 body 영역 위험 타입 사전 skip (D-P0-01 비협상).
                // Layer 1 helper 재사용 (단일 출처) — body 가 ResourceRegion / FileSystemResource 면
                // Jackson 에 넘기지 않고 placeholder string 으로 치환. Layer 1 실패 시도 호스트 앱 보호.
                if (isUnsafeToSerialize(body)) {
                    return "{\"_apilens\":\"streaming-body-skipped\",\"type\":\""
                            + body.getClass().getName() + "\"}";
                }
                return body;
            } catch (Throwable ignore) {
                // fall through: serialise the whole thing
            }
        }
        return v;
    }

    /**
     * [R11] AC-F-R11-01 (D-P0-01 비협상 — verbatim 인용)
     *   본질: agent AdviceSupport.serializeReturn → Jackson → FileSystemResource.getOutputStream()
     *         → mp4 0바이트 truncate 차단 (Layer 1 — 위험 타입 사전 판별 helper)
     *   회귀 가드 grep: 정방향 = `isUnsafeToSerialize(...)` (≥ 4 hit) /
     *                    반대방향 = `MAPPER.writeValueAsString(.*Resource` 0 hit /
     *                    `import org.springframework` 0 hit (agent 는 Spring 의존성 직접 import 금지)
     *   CLAUDE.md 인용: "Agent 자체 장애가 호스트 앱에 영향 0 — 모든 agent 코드는
     *                    try-catch 로 감싸고 실패 시 silent drop"
     *
     * <p>Jackson 에 넘기면 부수효과 (특히 file truncate) 를 일으킬 수 있는 타입인지 판별.
     * - WritableResource.getOutputStream() → Files.newOutputStream(path) → CREATE+TRUNCATE_EXISTING
     *   (FileSystemResource, PathResource 등이 해당)
     * - Stream/Reader/Writer 류는 직렬화도 불가능하고 consume 시 side effect 발생
     *
     * <p>agent 는 Spring 의존성 없음 — class 이름 문자열 기반 매칭으로 처리.
     * helper 자체 throw 0 (boolean 반환, reflection 없음) — ByteBuddy advice silent fail 회피.
     */
    private static boolean isUnsafeToSerialize(Object v) {
        if (v == null) return false;
        Class<?> c = v.getClass();
        // class 이름 기반 매칭 (agent 는 Spring 의존성 없음)
        String n = c.getName();
        if (n.startsWith("org.springframework.core.io.")) return true;             // Resource 군 전체
        if (n.equals("org.springframework.http.converter.ResourceRegion")) return true;
        if (n.startsWith("org.springframework.web.multipart.")) return true;       // MultipartFile
        if (n.contains("StreamingResponseBody")) return true;
        if (v instanceof java.io.InputStream
                || v instanceof java.io.OutputStream
                || v instanceof java.io.Reader
                || v instanceof java.io.Writer
                || v instanceof java.nio.channels.Channel
                || v instanceof java.nio.file.Path
                || v instanceof java.io.File) {
            return true;
        }
        return false;
    }

    /** Best-effort serialise of method arguments to a JSON object. Drops servlet-y args. */
    public static String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeArgs: " + (args == null ? "args=null" : "argsLen=0"));
            }
            return null;
        }
        try {
            Map<String, Object> shape = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) {
                    shape.put("arg" + i, null);
                    continue;
                }
                String cn = a.getClass().getName();
                // Skip servlet types — they are not serialisable and aren't user payload
                if (cn.contains("HttpServletRequest") || cn.contains("HttpServletResponse")
                        || cn.contains("HttpServletRequestWrapper")) {
                    continue;
                }
                // [Phase R11] AC-01-5 — Layer 1 인자 영역 위험 타입 사전 skip (D-P0-01 비협상).
                // controller 메서드 인자에 MultipartFile / InputStream / FileSystemResource 가 들어와도
                // 호스트 앱 파일 0바이트 truncate 차단. helper 단일 출처 (isUnsafeToSerialize) 재사용.
                if (isUnsafeToSerialize(a)) {
                    shape.put("arg" + i, "[skipped:" + cn + "]");
                    continue;
                }
                shape.put("arg" + i, a);
            }
            if (shape.isEmpty()) {
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][RAW] serializeArgs: all args dropped (servlet types?)");
                }
                return null;
            }
            String json = InstrumentationInstaller.MAPPER.writeValueAsString(shape);
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeArgs: ok, " + json.length() + "ch");
            }
            return json;
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeArgs: THROW "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    public static String serializeReturn(Object returnValue) {
        if (returnValue == null) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeReturn: returnValue=null");
            }
            return null;
        }
        try {
            // Spring ResponseEntity / HttpEntity 류는 body만 추출 — agent는 Spring 의존성 없음,
            // 클래스명 + reflection 으로 처리. 운영자가 실제 응답 JSON을 보길 기대하지 headers
            // (pragma, contentLanguage, acceptLanguage…) 노이즈를 원하지 않는다.
            Object toSerialize = unwrapResponseEntity(returnValue);
            if (toSerialize == null) {
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][RAW] serializeReturn: ResponseEntity body=null");
                }
                return null;
            }
            // [Phase R11] AC-01-4 — Layer 1 위험 타입 사전 skip (D-P0-01 비협상).
            // FileSystemResource.getOutputStream() → 호스트 앱 파일 0바이트 truncate 차단.
            // CLAUDE.md '아키텍처 핵심 원칙' (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
            if (isUnsafeToSerialize(toSerialize)) {
                if (InstrumentationInstaller.DEBUG) {
                    System.err.println("[ApiLens][RAW] serializeReturn: skip unsafe type "
                            + toSerialize.getClass().getName());
                }
                return "{\"_apilens\":\"streaming-body-skipped\",\"type\":\""
                        + toSerialize.getClass().getName() + "\"}";
            }
            String json = InstrumentationInstaller.MAPPER.writeValueAsString(toSerialize);
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeReturn: ok, " + json.length() + "ch");
            }
            return json;
        } catch (Throwable t) {
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][RAW] serializeReturn: THROW "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            return null;
        }
    }

    private static SpanKind parseKind(String kind) {
        try {
            return SpanKind.valueOf(kind);
        } catch (Throwable t) {
            return SpanKind.INTERNAL;
        }
    }
}
