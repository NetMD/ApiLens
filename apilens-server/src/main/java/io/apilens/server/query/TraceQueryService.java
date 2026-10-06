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
import io.apilens.server.query.dto.PayloadDto;
import io.apilens.server.query.dto.PayloadListResponse;
import io.apilens.server.query.dto.ServiceListResponse;
import io.apilens.server.query.dto.SpanDto;
import io.apilens.server.query.dto.TraceDetailResponse;
import io.apilens.server.query.dto.TraceListResponse;
import io.apilens.server.query.dto.TracePointsResponse;
import io.apilens.server.query.dto.TracePointsResponse.TraceBucket;
import io.apilens.server.query.dto.TracePointsResponse.TracePoint;
import io.apilens.server.query.dto.TraceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Read-side service. Wraps {@link TraceQueryRepository} with pagination,
 * limit normalisation, and 404 propagation.
 */
@Service
public class TraceQueryService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    // ══ [Phase R28] 대표 점 조회 — 칸 크기 · 창 상한 · 동시 실행 상한 (신규 패턴 — 앵커 밀도 2배) ══════════
    //
    //  [Phase R28] AC-28-01-5/AC-28-01-7/AC-28-01-8 — 사용자 명시 결정(UD-2 서버가 칸마다 대표 점 ·
    //  확정 ③ 오류 안전 상한). CLAUDE.md '아키텍처 핵심 원칙'(작은 도구 — 무거운 읽기가 수신을 밀어내지 않게) 인용.
    //  값은 전부 이 블록 한 곳에만 있다(매직넘버 금지 — 설계 부록 E G-R28-12). 근거 = 설계 §0.4 UQ-R28-01~07.
    //   · 칸은 **서버가 창 길이로 고른다** — 화면은 칸 크기를 안 보내므로 0 · 음수 · 과다 칸 입력 표면이 없다.
    //   · 칸 수 ≤ 목표 + 여유 ÷ 가장 작은 칸 + 경계에 걸친 칸 하나 · 점 수 ≤ 칸 상한 + max(오류 상한, 칸 상한).
    //     둘 다 **구조 상한**이라 따로 자르는 코드가 없다(따로 자르면 「오류 전부」 문구가 거짓이 된다 — DD-28-09).
    //   · 창 여유는 화면의 표시 지연(대시보드 차트가 창 끝을 늦추는 값 · 화면 time.ts)보다 커야 한다 — 화면이 그
    //     값을 올리면 여기도 함께 올린다. 아니면 Live 를 켠 7일 창이 400 이 되고, 1시간 · 24시간 창은 칸 단이
    //     소리 없이 다음 단으로 바뀐다(설계 §10 R-06 · 가드 G-R28-18 이 두 상수를 나란히 본다).
    static final long[] POINTS_BUCKET_LADDER_MS = {1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L};
    static final int POINTS_BUCKETS_TARGET = 720;
    static final long POINTS_WINDOW_SLACK_MS = 10_000L;
    static final long POINTS_MAX_WINDOW_MS = 7L * 86_400_000L + POINTS_WINDOW_SLACK_MS;
    static final int POINTS_BUCKETS_MAX = POINTS_BUCKETS_TARGET + (int) (POINTS_WINDOW_SLACK_MS / POINTS_BUCKET_LADDER_MS[0]) + 1;
    static final int POINTS_ERRORS_MAX = 500;
    static final int POINTS_MAX = POINTS_BUCKETS_MAX + Math.max(POINTS_ERRORS_MAX, POINTS_BUCKETS_MAX);
    static final int POINTS_MAX_CONCURRENT = 1;
    static final long POINTS_PERMIT_WAIT_MS = 2_000L;
    static final long POINTS_SLOW_LOG_MS = 1_000L;

    private static final Logger log = LoggerFactory.getLogger(TraceQueryService.class);

    /** 점이 정상 먼저 · 오류 나중(빨간 점이 위에) · 그 안에서 시작 시각 · trace id 오름차순(설계 §2.1). */
    private static final Comparator<TracePoint> POINT_ORDER = Comparator
            .comparing((TracePoint p) -> p.status() == SpanStatus.ERROR || p.hasError())
            .thenComparingLong(TracePoint::startTime)
            .thenComparing(TracePoint::traceId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final TraceQueryRepository repo;

    /**
     * [Phase R28] AC-28-01-8 — 서버 전체에서 동시에 도는 대표 점 질의 수의 상한(공정 세마포어).
     *
     * <p>자리 = 이 서비스의 <b>인라인 필드</b>다. 생성자를 늘리지 않는다 — 계측기 통합 시험이 이 서비스를
     * 저장소 하나로 직접 만든다(진입점 시그니처 불변). 필터 · 인터셉터 · Aspect 로 내지 않는다(조회 경로 안).
     * 계측 분석 게이트와 <b>같이 쓰지 않는다</b> — 같이 쓰면 계측 분석 화면이 폴링마다 409 를 받는다.
     *
     * <p><b>이 상한이 못 하는 것</b>(막았다고 쓰지 않는다):
     * <ol>
     *   <li>도는 질의를 <b>끊지 못한다</b> — 이 드라이버는 질의 시간 제한을 실행 시간 제한으로 안 쓴다
     *       (계측 분석 게이트 javadoc 의 R19 실측). 아주 느린 7일 창 질의 하나가 허가를 오래 쥐면 다른 탭이
     *       503 을 받는다(틀리는 방향 = 「서버가 바빠」 · 수신 안전 쪽).</li>
     *   <li>화면 밖 호출자(키 미설정 설치)도 같은 허가를 쓴다.</li>
     *   <li>화면이 옛 요청을 끊어도 서버 질의는 끝까지 돈다 — 검색어 끊어 치기 · 범위 연달아 바꾸기는 자기
     *       요청끼리 허가를 다툰다(30일 규모 7일 창에서만 · 받아들임 DD-28-36).</li>
     *   <li><b>허가는 연결 수만 묶고 쓰기 잠금은 못 묶는다</b> — 도는 대표 점 질의를 체크포인트(TRUNCATE)가
     *       기다리는 동안 수신 쓰기가 막힌다(설계 R-11 · 배포 전 판정 GATE-Q1 ⓐ).</li>
     *   <li>연결 넷이 다 쓰이는 겹침(대표 점 1 + 뺏긴 분석 2 + 04:00 정리 또는 수동 정리 1)을 막지 못한다(R-14).</li>
     *   <li>근본 해소 = 대표 점 전용 인덱스(스키마 라운드 · 백로그 · 다시 볼 조건 = 아래 느린 줄).</li>
     * </ol>
     */
    private final Semaphore pointsPermits = new Semaphore(POINTS_MAX_CONCURRENT, true);

    public TraceQueryService(TraceQueryRepository repo) {
        this.repo = repo;
    }

    /** {@code q} 미사용 호출 형태 유지용 — q=null 위임 (R12 FR-C2 신설 파라미터). */
    public TraceListResponse listTraces(
            String service,
            Long since,
            Long until,
            SpanStatus status,
            Integer requestedLimit,
            String cursorParam
    ) {
        return listTraces(service, since, until, status, null, requestedLimit, cursorParam);
    }

    /**
     * // [Phase R12] AC-C2-1 — FR-C2: q = root_operation 풀 FQCN 부분 일치 검색
     * // (LIKE escape 는 repository 단일 책임 — FE 이스케이프 금지, 이중 처리 방지).
     */
    public TraceListResponse listTraces(
            String service,
            Long since,
            Long until,
            SpanStatus status,
            String q,
            Integer requestedLimit,
            String cursorParam
    ) {
        int limit = normaliseLimit(requestedLimit);
        CursorCodec.Cursor cursor = (cursorParam == null || cursorParam.isEmpty())
                ? null
                : CursorCodec.decode(cursorParam);

        // fetch limit+1 so we know whether more pages exist
        List<TraceSummary> rows = repo.findTraces(service, since, until, status, q, limit + 1, cursor);

        String nextCursor = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            TraceSummary last = rows.get(rows.size() - 1);
            nextCursor = CursorCodec.encode(last.startTime(), last.traceId());
        }
        return new TraceListResponse(rows, nextCursor);
    }

    public TraceDetailResponse getTrace(String traceId) {
        TraceSummary summary = repo.findTraceSummary(traceId)
                .orElseThrow(() -> new TraceNotFoundException(traceId));
        List<SpanDto> spans = repo.findSpans(traceId);
        // 편의 필드 — 스팬 스캐너 client(UI)가 root를 찾기 위해 다시 순회하지 않게.
        // null parentSpanId를 가진 스팬이 여러 개면 가장 먼저 발견되는 것을 우선 (start_time ASC 정렬됨).
        String rootSpanId = spans.stream()
                .filter(s -> s.parentSpanId() == null)
                .map(SpanDto::spanId)
                .findFirst()
                .orElse(null);
        return new TraceDetailResponse(summary, rootSpanId, spans);
    }

    public PayloadListResponse getPayloads(String traceId, String spanId) {
        // 404 when the (traceId, spanId) pair does not exist; empty payloads list is a valid 200
        if (!repo.spanExists(traceId, spanId)) {
            throw new SpanNotFoundException(traceId, spanId);
        }
        List<PayloadDto> payloads = repo.findPayloads(spanId);
        return new PayloadListResponse(payloads);
    }

    /**
     * [Phase H] AC-06-3 — D-03 / Q-03. 사용자 명시 비협상 결정.
     * CLAUDE.md '아키텍처 핵심 원칙' 인용.
     *
     * <p>server-side 단일 출처: now 는 호출 시점 1회 계산 → 모든 row 동일 now 기준
     * healthStatus 분기. client drift 0.
     */
    public ServiceListResponse listServices() {
        long now = System.currentTimeMillis();
        return new ServiceListResponse(repo.findServicesWithHealth(now));
    }

    /**
     * [Phase R28] AC-28-01-1/AC-28-01-5/AC-28-01-8 — representative points of one window (production entry).
     * The window is validated before the permit is taken; the permit wait and the clock are the defaults.
     */
    public TracePointsResponse listTracePoints(String service, Long since, Long until, SpanStatus status, String q) {
        return listTracePoints(service, since, until, status, q, POINTS_PERMIT_WAIT_MS, System::nanoTime);
    }

    /**
     * Test-only overload — the permit wait and the clock are injected (constructor unchanged).
     *
     * <p>순서가 계약이다: ① 검증(허가 <b>전</b> — 잘못된 요청은 허가를 안 쓴다) ② 허가 못 얻음 = 던짐(반납할
     * 허가가 없으므로 finally 밖) ③ 얻은 <b>바로 다음 문장이 try</b>(획득과 try 사이 문장 0) ④ 허가는 질의가
     * 실제로 끝난 뒤 finally 에서만 푼다(정상 · 예외 모두) ⑤ 응답 조립은 허가 밖.
     */
    TracePointsResponse listTracePoints(String service, Long since, Long until, SpanStatus status, String q,
                                        long permitWaitMs, LongSupplier nanoClock) {
        long bucketMs = validateAndPickBucket(service, since, until);
        List<TraceQueryRepository.PointRow> rows;                  // 선언은 획득 앞에 둔다(획득과 try 사이 문장 0)
        long elapsedMs;
        if (!tryAcquire(permitWaitMs)) {
            // 바쁨 줄의 값은 **설정 상한**이다(잰 시간이 아니다 — 인터럽트로 일찍 끝나도 같은 값).
            log.info("trace points busy: waitLimitMs={}", permitWaitMs);
            throw new PointsBusyException();
        }
        try {
            long started = nanoClock.getAsLong();
            rows = repo.findTracePoints(service, since, until, status, q, bucketMs, POINTS_ERRORS_MAX);
            elapsedMs = (nanoClock.getAsLong() - started) / 1_000_000L;
        } finally {
            pointsPermits.release();
        }
        TracePointsResponse response = assemble(rows, bucketMs);
        if (elapsedMs >= POINTS_SLOW_LOG_MS) {
            // [Phase R28] NFR-28-15 — `trace points slow:` 줄의 단위 · 비교 경계(설계서 · 이 주석 · 백로그 부기 세 자리에
            //   같은 문장): 「elapsedMs = 밀리초(정수 · 내림) · 허가를 **얻은 뒤** 저장소 질의 한 번의 시간(허가 기다림
            //   0~2,000 ms 는 안 듦) · windowMs = 요청 창 길이(Live ON 의 표시 지연 8초 포함 그대로) · bucketMs = 서버가
            //   고른 칸(밀리초) · totalCount = 창 안 trace 건수. 줄은 elapsedMs ≥ 1,000(이상)일 때만 · INFO. 다시 볼
            //   조건의 「하루」 = 로그 줄 날짜(서버 지역 시각) 한 날.」
            //   다시 볼 조건(UQ-R28-10): 이 줄이 하루 1줄 이상인 날이 3일 연속 → 전용 인덱스(스키마 라운드) 검토.
            log.info("trace points slow: elapsedMs={} windowMs={} bucketMs={} totalCount={}",
                    elapsedMs, until - since, bucketMs, response.totalCount());
        }
        return response;
    }

    /**
     * [Phase R28] AC-28-01-5 — 창 · 서비스 검증 후 칸 크기를 고른다. 거절 문장은 영어 고정 문장이고
     * <b>요청 값을 문장에 넣지 않는다</b>. 순서: ① 창 끝 둘 ② since ≥ 0 그리고 since &lt; until(창 길이 계산 전에
     * long 양 끝 같은 값을 여기서 끊는다) ③ 창 상한 ④ 서비스.
     */
    static long validateAndPickBucket(String service, Long since, Long until) {
        if (since == null || until == null) {
            throw new IllegalArgumentException("since and until are required");
        }
        if (since < 0 || since >= until) {
            throw new IllegalArgumentException("since must be >= 0 and less than until");
        }
        if (until - since > POINTS_MAX_WINDOW_MS) {
            throw new IllegalArgumentException("window must be at most 7 days");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service is required");
        }
        return bucketMsFor(until - since);
    }

    /**
     * 사다리에서 「(창 − 여유)를 칸으로 나눈 올림 ≤ 목표」인 가장 작은 칸. 창 검증 뒤에만 부른다.
     * 마지막 줄에는 닿지 않는다 — 가장 큰 칸으로도 목표를 넘는 창은 창 상한이 먼저 거절한다.
     */
    static long bucketMsFor(long windowMs) {
        long core = Math.max(1L, windowMs - POINTS_WINDOW_SLACK_MS);
        for (long b : POINTS_BUCKET_LADDER_MS) {
            if (Math.ceilDiv(core, b) <= POINTS_BUCKETS_TARGET) {
                return b;
            }
        }
        return POINTS_BUCKET_LADDER_MS[POINTS_BUCKET_LADDER_MS.length - 1];
    }

    /**
     * Bucket rows → buckets + totals (totals are the bucket sums — the same snapshot by construction), point rows
     * → points in contract order. Runs outside the permit.
     */
    private static TracePointsResponse assemble(List<TraceQueryRepository.PointRow> rows, long bucketMs) {
        List<TraceBucket> buckets = new ArrayList<>();
        List<TracePoint> points = new ArrayList<>();
        long totalCount = 0L;
        long errorCount = 0L;
        for (TraceQueryRepository.PointRow row : rows) {
            if (row.bucketRow()) {
                buckets.add(new TraceBucket(row.bucketIndex() * bucketMs, row.count(), row.errorCount()));
                totalCount += row.count();
                errorCount += row.errorCount();
            } else {
                points.add(new TracePoint(row.traceId(), row.rootOperation(), row.startTime(), row.durationMs(),
                        row.status(), row.hasError()));
            }
        }
        buckets.sort(Comparator.comparingLong(TraceBucket::start));
        points.sort(POINT_ORDER);
        // 「줄여 보냈다」 판정 = SQL 의 넘침 갈래 판정과 같은 수 · 같은 순간(칸 오류 건수의 합).
        boolean errorsReduced = errorCount > POINTS_ERRORS_MAX;
        return new TracePointsResponse(bucketMs, totalCount, errorCount, errorsReduced,
                List.copyOf(points), List.copyOf(buckets));
    }

    private boolean tryAcquire(long waitMs) {
        try {
            return pointsPermits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 인터럽트 표시는 되살리고 바쁨으로 처리한다(허가 없음).
            return false;
        }
    }

    /** 시험용 읽기 — 지금 허가를 쥔 질의 수. 음수면 얻지 못한 허가를 반납한 것(누수 반대 방향 · C07). */
    int pointsQueriesInFlight() {
        return POINTS_MAX_CONCURRENT - pointsPermits.availablePermits();
    }

    /** 시험용 읽기 — 허가 앞에서 기다리는 호출 수(겹침 전제를 먼저 단언하는 손잡이 · C01 · C04). */
    int pointsQueriesWaiting() {
        return pointsPermits.getQueueLength();
    }

    /**
     * [Phase R28] AC-28-01-8 — the points permit was not obtained within the wait — mapped to HTTP 503 by the
     * controller. 게이트의 계약과 같은 자리에 둔다(별도 예외 파일을 만들지 않는다 — 계측 분석 게이트 전례).
     */
    public static class PointsBusyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public PointsBusyException() {
            super("trace points query is busy — retry shortly");
        }
    }

    private static int normaliseLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
