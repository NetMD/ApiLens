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
package io.apilens.server.instrument;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Lets only one instrumentation analysis run at a time (both endpoints share this gate).
 *
 * <p>필터나 인터셉터로 만들지 않았다 — 제한 대상이 신규 endpoint 둘뿐인데 필터로 만들면 모든
 * 요청을 가로지르고, 한 번 그런 층이 생기면 다음 라운드에 다른 책임이 얹힌다. 가로지르는 층
 * 신설 0건 기준선을 지킨다.
 *
 * <p>// [Phase R19] AC-08-5/NFR-10 — 동시 실행 1건 + 점유 상한. 사용자 명시 비협상 결정.
 * // CLAUDE.md '아키텍처 핵심 원칙'(운영자가 결재 없이 직접 깔고 싶은 작은 도구 — 무거운 작업이
 * // 겹쳐 수집을 밀어내지 않게) 인용. 시간 소스 주입은 {@code IngestPauseState} 와 동형.
 *
 * <p><b>실행 시간 상한을 자바 수준에서 거는 이유</b>: 이 프로젝트의 SQLite 드라이버는
 * {@code Statement.setQueryTimeout} 을 <b>쿼리 실행 시간 제한으로 적용하지 않는다</b>
 * (R19 dev 실행 게이트 GT-18 실측 — sqlite-jdbc 3.47.1.0 은 그 값을 busy(잠금 대기) timeout 으로만
 * 쓴다. 2초로 걸어 둔 쿼리가 51초 동안 완주했다). 설계가 둔 4번 방어선(15초)을 그대로 두면
 * <b>실체가 없는 방어선</b>이 되므로, 같은 상한을 <b>작업 스레드 + 데드라인 대기</b>로 강제한다.
 * 상수 이름({@link #STATEMENT_TIMEOUT_SEC})은 설계 원문을 유지하되 강제 지점만 바꾼 것이다.
 *
 * <p>데드라인을 넘기면 {@link DeadlineExceededException} 이 나가고 컨트롤러가 <b>504</b> 로 옮긴다.
 * 500(그 밖의 서버 오류)과 코드를 나누는 것이 중요하다 — 화면은 상태 코드로만 문구를 고르므로
 * 둘이 같은 코드면 운영자가 "구간을 좁혀 다시 시도해 주세요" 를 영영 못 본다.
 *
 * <p>⚠️ <b>데드라인을 넘겨도 점유를 즉시 풀지 않는다.</b> 버려진 작업이 아직 DB 를 붙들고 있는데
 * 게이트만 풀면 무거운 읽기 둘이 동시에 도는 상태가 된다(동시 실행 1건 규약 위반). 점유는 그 작업이
 * 끝나는 순간 풀리고, 끝내 안 풀리면 점유 상한이 뺏는다.
 *
 * <p><b>점유 상한이 있는 이유</b>: 위 데드라인은 <b>호출자</b>를 풀어 줄 뿐 쿼리 자체를 끊지는
 * 못한다. 그래서 상한을 넘겨 점유된 게이트는 다음 요청이 강제로 뺏는다 — 그러지 않으면 한 번
 * 오래 걸린 요청 뒤로 모든 요청이 영구히 409 가 되어 기능이 죽는다.
 * {@code IngestPauseState} 의 자가 재개 패턴과 같은 성격이다(발명 아님).
 *
 * <p>상태는 서버 메모리에만 둔다. 재시작 시 초기화가 정상이다.
 */
@Component
public class InstrumentAnalysisGate {

    private static final Logger log = LoggerFactory.getLogger(InstrumentAnalysisGate.class);

    /**
     * 점유 상한 — 상한을 넘긴 점유는 다음 요청이 강제로 뺏는다(매직넘버 금지: static final 봉인).
     * 경계는 {@code >} 비교 — 정확히 상한값에서는 아직 점유가 유효하다.
     */
    static final long OCCUPANCY_CAP_MS = 60_000L;

    /**
     * 실행 시간 상한(초). 실측 최댓값(24시간 창 재귀 계산 약 2.4초)에 재측정 편차를 곱한 뒤
     * 안전 계수를 얹은 값 — 평균이 아니라 <b>꼬리</b> 기준이다. 화면 타임아웃(25초)보다 짧아
     * 서버가 먼저 끊고 504 를 돌려준다.
     */
    static final int STATEMENT_TIMEOUT_SEC = 15;

    /**
     * 점유 표식. 값이 아니라 <b>인스턴스 동일성</b>으로 소유자를 구분한다 — 상한 초과로 점유를
     * 뺏긴 원 점유자가 뒤늦게 끝나면서 남의 점유를 풀어 버리는 사고를 막는다.
     *
     * @param startedAtMs 점유 시작 시각(epoch millis)
     */
    private record Occupancy(long startedAtMs) {
    }

    private final AtomicReference<Occupancy> occupancy = new AtomicReference<>(null);
    private final LongSupplier nowMs;
    private final long deadlineMs;

    /**
     * 무거운 읽기를 실어 나르는 작업 스레드. 게이트가 동시 실행을 1건으로 묶으므로 상시 스레드는
     * 사실상 1개이고, 데드라인을 넘겨 버려진 작업이 아직 도는 동안에만 잠깐 하나가 더 생긴다.
     * 데몬 스레드라 종료를 막지 않는다.
     */
    private final ExecutorService worker;

    /** Production 생성자 — 시간 소스 = System.currentTimeMillis (Spring 이 이 생성자로 bean 생성). */
    public InstrumentAnalysisGate() {
        this(System::currentTimeMillis, STATEMENT_TIMEOUT_SEC * 1000L);
    }

    /**
     * 테스트 전용 — 시간 소스 주입. production 호출 0(운영 동작 불변).
     * {@code IngestPauseState(LongSupplier)} 오버로드와 동형 — 경계값을 {@code Thread.sleep} 0 으로 단언한다.
     */
    InstrumentAnalysisGate(LongSupplier nowMs) {
        this(nowMs, STATEMENT_TIMEOUT_SEC * 1000L);
    }

    /** 테스트 전용 — 데드라인까지 주입해 504 경로를 짧게(대기 없이) 재현한다. */
    InstrumentAnalysisGate(LongSupplier nowMs, long deadlineMs) {
        this.nowMs = nowMs;
        this.deadlineMs = deadlineMs;
        AtomicLong seq = new AtomicLong();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "apilens-instrument-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // [Phase R20] R20/AC-11-3 — newCachedThreadPool → newFixedThreadPool(2) (R-2, 사용자 확정
        //   Q-U7③). 위 필드 javadoc("상시 1개 + 버려진 작업 1개")과 상한 2 가 정확 정합 — cached 는
        //   이론상 무한 생성이라 상한을 코드로 못박는다.
        this.worker = Executors.newFixedThreadPool(2, factory);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Run the given work exclusively and within the deadline.
     *
     * <p>무거운 집계는 <b>오직</b> 이 메서드를 통해서만 실행된다(단일 위임 진입점).
     * 점유 해제는 작업 자신의 {@code finally} 라 예외·데드라인 초과·클라이언트 중단 어느 경로에서도
     * 반드시 풀린다 — 해제가 누락되면 다음 요청이 영구 409 가 된다.
     *
     * @param work 실행할 작업
     * @param <T>  결과 타입
     * @return 작업 결과
     * @throws BusyException             이미 다른 분석이 도는 중일 때 (→ 409)
     * @throws DeadlineExceededException 실행 시간 상한을 넘겼을 때 (→ 504)
     */
    public <T> T runExclusive(Supplier<T> work) {
        Occupancy mine = acquire();
        Future<T> running = worker.submit(() -> {
            try {
                return work.get();
            } finally {
                // 내가 잡은 표식일 때만 해제한다(상한 초과로 뺏긴 뒤라면 남의 점유를 건드리지 않는다).
                occupancy.compareAndSet(mine, null);
            }
        });
        try {
            return running.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // ⚠️ 여기서 점유를 풀지 않는다 — 버려진 작업이 아직 DB 를 붙들고 있다.
            //    쿼리 자체를 끊을 방법이 이 드라이버에는 없으므로(GT-18) 호출자만 풀어 준다.
            log.warn("instrument analysis exceeded the {} ms deadline; the query keeps running until it finishes",
                    deadlineMs);
            throw new DeadlineExceededException();
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running an instrumentation analysis", e);
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("instrumentation analysis failed", cause);
    }

    private Occupancy acquire() {
        long now = nowMs.getAsLong();
        Occupancy current = occupancy.get();
        Occupancy mine = new Occupancy(now);

        if (current == null) {
            if (occupancy.compareAndSet(null, mine)) {
                return mine;
            }
            throw new BusyException();
        }

        long heldMs = now - current.startedAtMs();
        if (heldMs > OCCUPANCY_CAP_MS && occupancy.compareAndSet(current, mine)) {
            log.warn("instrument analysis gate held for {} ms (cap {} ms), forcing takeover",
                    heldMs, OCCUPANCY_CAP_MS);
            return mine;
        }
        throw new BusyException();
    }

    /**
     * Signals that another analysis is already running — mapped to HTTP 409 by the controller.
     *
     * <p>게이트의 계약과 같은 자리에 둔다(별도 예외 파일을 만들지 않는다).
     */
    public static class BusyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BusyException() {
            super("another instrumentation analysis is already running");
        }
    }

    /**
     * Signals that the analysis outran its deadline — mapped to HTTP 504 by the controller.
     *
     * <p>500(그 밖의 서버 오류)과 <b>반드시 다른 코드</b>여야 한다. 화면은 상태 코드로만 문구를
     * 고르므로, 둘을 같은 코드로 묶으면 "구간을 좁혀 다시 시도해 주세요" 가 화면에서 사라진다.
     */
    public static class DeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public DeadlineExceededException() {
            super("instrumentation analysis exceeded its time budget");
        }
    }
}
