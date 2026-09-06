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

import io.apilens.server.instrument.dto.AnalysisResponse;
import io.apilens.server.instrument.dto.SimulationResponse;
import io.apilens.server.instrument.dto.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the three-axis ranking and the exclusion simulation. Read-only.
 *
 * <p>// [Phase R19] AC-02 / AC-03 / AC-04 / AC-05 계열 — 계측 분석 본체. 사용자 명시 비협상 결정
 * // (S-1 traces 기점 / S-3 절감·부작용 동봉 / S-4 3분류 / S-5 클래스 단위 워커 판별).
 * // CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p><b>무거운 집계는 오직 {@link InstrumentAnalysisGate#runExclusive} 를 통해서만 실행된다</b>
 * (단일 위임 진입점). 이 클래스 안에서 {@code repository.*} 호출이 {@code runExclusive} 람다 밖에
 * 있는 자리는 0곳이다.
 *
 * <p><b>단위 도메인</b>: 비율은 언제나 0.0~1.0 실수다. 100 을 곱한 값을 만들지 않는다.
 * 시간은 요청만 시간(hour) 단위이고 그 밖은 전부 밀리초다 — 변환은 {@link #analyze} 의 창 계산
 * 한 곳에서만 한다.
 */
@Service
public class InstrumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentAnalysisService.class);

    /**
     * 허용 시간 구간(시간 단위) — 범위 검사가 아니라 <b>열거 검사</b>다. 임의 값이 들어와 구간이
     * 무제한으로 커지는 경로를 원천 차단한다. "전체" 선택지는 만들지 않는다.
     *
     * <p>보존 구간 비율에 따라 쿼리 형태를 바꾸는 분기도 만들지 않는다 — 만들면 {@code spans} 기점
     * 경로가 코드에 실재하게 되고 다음 라운드가 그 경로를 확장한다. 대신 선택지 자체를 24시간에서 끊는다.
     */
    static final Set<Integer> ALLOWED_WINDOW_HOURS = Set.of(1, 6, 24);

    /** 시간(hour) → 밀리초 변환 상수. 이 도메인 변환이 일어나는 자리는 {@link #analyze} 한 곳뿐이다. */
    static final long MILLIS_PER_HOUR = 3_600_000L;

    /** GROUP BY 결과 행 상한. 정상 앱은 수십~수백 — 2000 은 비정상 폭주 차단선이다. */
    static final int ANALYZE_GROUP_CAP = 2000;

    /** 축별 상위 N. 세 축 합집합 최대 150행 + 고정 합계 행 1 = 화면이 감당 가능한 크기. */
    // [Phase R25] AC-25-06-1/AC-25-06-2/AC-25-06-3 — 값 50 은 그대로 둔다. 다시 볼 조건만 적는다.
    // 다시 볼 조건(두 어휘로 적는다 — 로그에는 truncated 라는 낱말이 없다):
    //   응답 truncated=true 이거나 로그의 totalClasses > returned 가 한 번이라도 찍히면 재판정.
    //   관측: 2026-08-28 최대 41 · 2026-09-05 최대 35 (창 1/6/24h). 값 50 유지.
    static final int ANALYZE_TOP_N = 50;

    /** 재귀 깊이 상한. 실측 최대 트리 깊이의 4배 여유. */
    static final int MAX_CLIMB_DEPTH = 20;

    /**
     * 백그라운드 워커 판별 임계 — 클래스의 span 중 시작점 비율이 이 값 <b>이상</b>이면 워커로 본다.
     *
     * <p>실측에서 워커는 100%/100%/100%, 비워커는 0.2%/3.6% 로 두 무리 사이가 아주 넓다.
     * <b>표본 하한은 두지 않는다</b> — 하한 N 을 정할 실측 근거가 없고, 근거 없는 숫자를 코드에
     * 박느니 판단을 사람에게 남기는 편이 정직하다(화면이 span 수를 항상 함께 보여준다).
     * 계산 단위는 <b>클래스 단위만</b>이다 — 패키지 단위 평균을 계산하는 코드 경로가 존재하지 않는다.
     */
    static final double BACKGROUND_WORKER_ROOT_RATIO = 0.80d;

    /** 클래스 이름이 없는 span 들이 묶이는 고정 합계 행의 키(빈 문자열). 순위 경쟁 대상이 아니다. */
    private static final String TOTAL_ROW_CLASS_NAME = "";

    private final InstrumentAnalysisRepository repository;
    private final InstrumentAnalysisGate gate;

    public InstrumentAnalysisService(InstrumentAnalysisRepository repository, InstrumentAnalysisGate gate) {
        this.repository = repository;
        this.gate = gate;
    }

    /**
     * Rank the classes of one service over a bounded window.
     *
     * @param serviceName 대상 서비스
     * @param windowHours 1 / 6 / 24 (호출 전에 검증됨)
     * @return 순위 응답
     */
    public AnalysisResponse analyze(String serviceName, int windowHours) {
        // ⚠️ 밀리초 변환은 여기 1곳에서만 한다.
        long queriedAtMs = System.currentTimeMillis();
        long toMs = queriedAtMs;
        long fromMs = toMs - windowHours * MILLIS_PER_HOUR;
        Window window = new Window(fromMs, toMs, queriedAtMs);

        return gate.runExclusive(() -> {
            long startedNanos = System.nanoTime();

            InstrumentAnalysisRepository.SummaryRow summaryRow =
                    repository.aggregateSummary(serviceName, fromMs, toMs);
            List<InstrumentAnalysisRepository.SpanRow> spanRows =
                    repository.aggregateSpansByClass(serviceName, fromMs, toMs, ANALYZE_GROUP_CAP);
            List<InstrumentAnalysisRepository.PayloadRow> payloadRows =
                    repository.aggregatePayloadsByClass(serviceName, fromMs, toMs, ANALYZE_GROUP_CAP);
            // [Phase R25] AC-25-05-1/AC-25-05-5 — 요약 질의와 **같은 창**으로 따로 한 문장. 요약 질의에
            //   합치면 trace 단위 묶음에 payload 가 결합돼 span_cnt 가 부풀어 오른다(Q1a javadoc).
            long uniquePayloadBytes = repository.aggregateUniquePayloadBytes(serviceName, fromMs, toMs);

            Map<String, Merged> merged = merge(spanRows, payloadRows);

            // ★ 랭크는 "합집합을 뽑기 전 전체 집합" 기준이다 — 이 순서가 뒤집혀 합집합을 먼저 뽑고
            //   랭크를 매기면 "받은 목록 안 순위" 가 되어 화면이 뜻하는 값과 달라진다.
            assignRanks(merged.values());

            LinkedHashSet<String> selected = selectTopUnion(merged.values());

            List<AnalysisResponse.ClassStat> items = buildItems(merged, selected);

            AnalysisResponse.Summary summary = new AnalysisResponse.Summary(
                    summaryRow.totalSpans(),
                    summaryRow.totalTraces(),
                    ratio(summaryRow.totalSpans(), summaryRow.totalTraces()),
                    ratio(summaryRow.singleSpanTraces(), summaryRow.totalTraces()),
                    uniquePayloadBytes
            );

            int totalClasses = merged.size();
            boolean truncated = totalClasses > items.size();

            log.info("instrument analysis done: service={} windowHours={} totalClasses={} returned={} elapsedMs={}",
                    serviceName, windowHours, totalClasses, items.size(),
                    (System.nanoTime() - startedNanos) / 1_000_000L);

            return new AnalysisResponse(window, summary, totalClasses, truncated, items);
        });
    }

    /**
     * Estimate what happens when the given classes are excluded.
     *
     * <p>절감({@code savings})과 부작용({@code impact})은 <b>서로 다른 계산식</b>이고 결과는 한 응답에
     * 함께 담긴다. 절감만 담긴 응답을 만드는 경로가 없다.
     *
     * @param serviceName 대상 서비스
     * @param fromMs      순위 응답이 준 구간 시작 그대로
     * @param toMs        순위 응답이 준 구간 끝 그대로
     * @param targets     빼 볼 클래스 이름 목록(호출 전에 검증됨)
     * @return 시뮬레이션 응답
     */
    public SimulationResponse simulate(String serviceName, long fromMs, long toMs, List<String> targets) {
        long queriedAtMs = System.currentTimeMillis();
        Window window = new Window(fromMs, toMs, queriedAtMs);
        List<String> safeTargets = targets == null ? List.of() : List.copyOf(targets);

        return gate.runExclusive(() -> {
            long startedNanos = System.nanoTime();

            // 축 1 — 절감(직접 귀속 감산). 자식 span 을 합산하지 않는다.
            InstrumentAnalysisRepository.SavingsRow savingsRow =
                    repository.directSavings(serviceName, fromMs, toMs, safeTargets);

            // 축 2 — 부작용(트리 재계산). 위 감산식과 변수를 공유하지 않는다.
            InstrumentAnalysisRepository.OrphanRow orphanRow =
                    repository.simulateOrphans(serviceName, fromMs, toMs, safeTargets, MAX_CLIMB_DEPTH);

            SimulationResponse.Savings savings = new SimulationResponse.Savings(
                    savingsRow.spanDelta(), savingsRow.payloadCountDelta(), savingsRow.payloadBytesDelta());

            SimulationResponse.Impact impact = new SimulationResponse.Impact(
                    orphanRow.remainingSpans(),
                    orphanRow.resultTraces(),
                    ratio(orphanRow.remainingSpans(), orphanRow.resultTraces()),
                    ratio(orphanRow.singleSpanTraces(), orphanRow.resultTraces())
            );

            log.info("instrument simulation done: service={} targets={} remainingSpans={} resultTraces={} elapsedMs={}",
                    serviceName, safeTargets.size(), orphanRow.remainingSpans(), orphanRow.resultTraces(),
                    (System.nanoTime() - startedNanos) / 1_000_000L);

            return new SimulationResponse(window, savings, impact, orphanRow.cappedCount() > 0);
        });
    }

    // ─── 내부 조립 ───────────────────────────────────────────────────────────

    /** 클래스 하나의 세 축 값을 모으는 가변 홀더(조립 중에만 산다). */
    private static final class Merged {
        private final String className;
        private long spanCount;
        private long rootCount;
        private long payloadCount;
        private long payloadBytes;
        private Integer spanRank;
        private Integer payloadCountRank;
        private Integer payloadBytesRank;

        private Merged(String className) {
            this.className = className;
        }
    }

    /**
     * className 을 키로 span 축과 payload 축을 합친다. 한쪽에만 있으면 없는 축은 0이다
     * (두 쿼리의 상한이 서로 다른 기준으로 잘리므로 실제로 생길 수 있다).
     */
    private static Map<String, Merged> merge(List<InstrumentAnalysisRepository.SpanRow> spanRows,
                                             List<InstrumentAnalysisRepository.PayloadRow> payloadRows) {
        Map<String, Merged> merged = new LinkedHashMap<>();
        for (InstrumentAnalysisRepository.SpanRow row : spanRows) {
            Merged m = merged.computeIfAbsent(nullSafe(row.className()), Merged::new);
            m.spanCount = row.spanCount();
            m.rootCount = row.rootCount();
        }
        for (InstrumentAnalysisRepository.PayloadRow row : payloadRows) {
            Merged m = merged.computeIfAbsent(nullSafe(row.className()), Merged::new);
            m.payloadCount = row.payloadCount();
            m.payloadBytes = row.payloadBytes();
        }
        return merged;
    }

    /**
     * 세 축 각각 내림차순으로 정렬해 1부터 순위를 매긴다 — <b>전체 집합 기준</b>.
     * 동점은 클래스 이름 오름차순으로 갈라 결과가 실행마다 흔들리지 않게 한다.
     */
    private static void assignRanks(Iterable<Merged> all) {
        List<Merged> list = new ArrayList<>();
        all.forEach(list::add);

        rank(list, Comparator.comparingLong((Merged m) -> m.spanCount).reversed(),
                (m, r) -> m.spanRank = r);
        rank(list, Comparator.comparingLong((Merged m) -> m.payloadCount).reversed(),
                (m, r) -> m.payloadCountRank = r);
        rank(list, Comparator.comparingLong((Merged m) -> m.payloadBytes).reversed(),
                (m, r) -> m.payloadBytesRank = r);
    }

    private static void rank(List<Merged> list, Comparator<Merged> byValue, RankSetter setter) {
        List<Merged> sorted = new ArrayList<>(list);
        sorted.sort(byValue.thenComparing(m -> m.className));
        int rank = 1;
        for (Merged m : sorted) {
            setter.set(m, rank++);
        }
    }

    @FunctionalInterface
    private interface RankSetter {
        void set(Merged merged, int rank);
    }

    /**
     * 세 축 각각 상위 N 의 합집합. 고정 합계 행은 순위와 무관하게 언제나 포함한다
     * (화면이 "나머지 전부" 를 항상 볼 수 있어야 절감 숫자의 분모가 정직해진다).
     */
    private static LinkedHashSet<String> selectTopUnion(Iterable<Merged> all) {
        List<Merged> list = new ArrayList<>();
        all.forEach(list::add);

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Merged m : list) {
            if (TOTAL_ROW_CLASS_NAME.equals(m.className)) {
                selected.add(m.className);
            }
        }
        addTopN(selected, list, Comparator.comparingInt((Merged m) -> m.spanRank));
        addTopN(selected, list, Comparator.comparingInt((Merged m) -> m.payloadCountRank));
        addTopN(selected, list, Comparator.comparingInt((Merged m) -> m.payloadBytesRank));
        return selected;
    }

    private static void addTopN(LinkedHashSet<String> selected, List<Merged> list, Comparator<Merged> byRank) {
        List<Merged> sorted = new ArrayList<>(list);
        sorted.sort(byRank);
        int limit = Math.min(ANALYZE_TOP_N, sorted.size());
        for (int i = 0; i < limit; i++) {
            selected.add(sorted.get(i).className);
        }
    }

    /**
     * 응답 항목 조립. 순서는 <b>고정 합계 행 먼저, 그다음 span 순위 오름차순</b>이고
     * 화면은 이 순서를 그대로 쓴다.
     */
    private static List<AnalysisResponse.ClassStat> buildItems(Map<String, Merged> merged,
                                                              LinkedHashSet<String> selected) {
        List<Merged> chosen = new ArrayList<>();
        for (String className : selected) {
            Merged m = merged.get(className);
            if (m != null) {
                chosen.add(m);
            }
        }
        chosen.sort(Comparator
                .comparing((Merged m) -> !TOTAL_ROW_CLASS_NAME.equals(m.className))
                .thenComparingInt(m -> m.spanRank));

        List<AnalysisResponse.ClassStat> items = new ArrayList<>(chosen.size());
        for (Merged m : chosen) {
            boolean totalRow = TOTAL_ROW_CLASS_NAME.equals(m.className);
            ExcludeClassifier.Verdict verdict = ExcludeClassifier.classify(m.className);
            double rootRatio = ratio(m.rootCount, m.spanCount);
            items.add(new AnalysisResponse.ClassStat(
                    m.className,
                    m.spanCount,
                    m.payloadCount,
                    m.payloadBytes,
                    // 고정 합계 행은 순위 경쟁 대상이 아니라 rank 3종이 전부 null 이다.
                    totalRow ? null : m.spanRank,
                    totalRow ? null : m.payloadCountRank,
                    totalRow ? null : m.payloadBytesRank,
                    rootRatio,
                    rootRatio >= BACKGROUND_WORKER_ROOT_RATIO,
                    verdict.status(),
                    verdict.reasonCode(),
                    verdict.target()
            ));
        }
        return items;
    }

    /** 분모가 0이면 0.0. 비율은 0.0~1.0 실수 도메인에서만 만든다. */
    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0d : (double) numerator / (double) denominator;
    }

    private static String nullSafe(String className) {
        return className == null ? TOTAL_ROW_CLASS_NAME : className;
    }
}
