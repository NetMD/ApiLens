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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Span;
import io.apilens.common.SpanKind;
import io.apilens.common.SpanStatus;
import io.apilens.server.ingest.IngestService;
import io.apilens.server.masking.MaskingEngineHolder;
import io.apilens.server.masking.MaskingRuleRepository;
import io.apilens.server.query.dto.ServiceInfo;
import io.apilens.server.query.dto.TraceSummary;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase R12] T-A3 — services 24h 윈도우 카운트 경계 + findTraces {@code q} LIKE escape
 * (Design §7.2). TraceQueryServicesHealthTest 의 "윈도우 경계 자체의 검증은 본 클래스 전담"
 * 위임 이행.
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용):
 * <ul>
 *   <li>D-03: "필터는 status + operation 검색 — duration 필터는 작업 외" → 본 테스트도
 *       duration 필터 케이스를 추가하지 않는다</li>
 *   <li>AC-A3-1: traceCount = 최근 24h (start_time 기준) — "전수 COUNT 패턴 부재"</li>
 *   <li>W-C2 (Design §8.4): LIKE 는 파라미터 바인딩 + escapeLike — 검색어 리터럴 매칭만</li>
 * </ul>
 *
 * <p>경계 (Design §7.1): {@code start_time >= now−24h} — now−24h−1 제외 / now−24h 포함 / now 포함.
 * LIKE escape 4입력: {@code 100%} / {@code a_b} / {@code C:\path} / {@code find%_\}.
 */
class TraceQueryRepositoryTest {

    private static final long DAY_MS = 86_400_000L;

    @TempDir
    Path tempDir;
    private IngestService ingestService;
    private TraceQueryRepository repository;

    @BeforeEach
    void setup() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("apilens-repo-test.db").toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        ObjectMapper mapper = new ObjectMapper();
        MaskingEngineHolder holder = new MaskingEngineHolder(new MaskingRuleRepository(jdbc), mapper);
        holder.reload();
        this.ingestService = new IngestService(jdbc, holder, mapper);
        this.repository = new TraceQueryRepository(jdbc, mapper);
    }

    // ─── AC-A3-1: 24h 윈도우 경계 (now−24h−1 / now−24h / now) ──────────────

    @Test
    void countsTracesInsideTwentyFourHourWindowWithInclusiveBoundary() {
        long now = System.currentTimeMillis();
        ingestTrace("t-out", "win-svc", "GET /a", now - DAY_MS - 1); // 윈도우 밖 (경계 −1ms)
        ingestTrace("t-edge", "win-svc", "GET /b", now - DAY_MS);    // 경계값 — 포함 (>= 확정)
        ingestTrace("t-in", "win-svc", "GET /c", now);               // 포함

        List<ServiceInfo> services = repository.findServicesWithHealth(now);

        assertEquals(1, services.size());
        assertEquals("win-svc", services.get(0).name());
        assertEquals(2L, services.get(0).traceCount(),
                "경계 포함 2건 (now−24h, now) — now−24h−1 은 제외돼야 한다");
    }

    @Test
    void excludesAncientTracesProvingNoFullTableCount() {
        // "전수 COUNT 패턴 부재" 동작 검증 — 24h 밖 (30일 전) trace 가 카운트에 미포함
        long now = System.currentTimeMillis();
        ingestTrace("t-ancient", "old-svc", "GET /old", now - 30 * DAY_MS);
        ingestTrace("t-fresh", "old-svc", "GET /new", now - 1_000L);

        List<ServiceInfo> services = repository.findServicesWithHealth(now);

        assertEquals(1L, services.get(0).traceCount(),
                "30일 전 trace 는 누적 전수와 달리 미포함이어야 한다 (AC-A3-1)");
    }

    // ─── FR-C2: q = root_operation 풀 FQCN 부분 일치 ────────────────────────

    @Test
    void findsTracesByFullFqcnSubstring() {
        long now = System.currentTimeMillis();
        ingestTrace("t-fq1", "svc", "com.acme.OrderService.create", now - 3_000L);
        ingestTrace("t-fq2", "svc", "com.acme.UserService.login", now - 2_000L);

        // BL-09: 검색 기준은 풀 FQCN 원본 컬럼 (shortenOperation 은 FE 표시 전용)
        List<TraceSummary> hits = findByQuery("acme.OrderService");
        assertEquals(1, hits.size());
        assertEquals("t-fq1", hits.get(0).traceId());

        assertEquals(2, findByQuery("com.acme").size(), "공통 prefix 는 둘 다 hit");
        assertEquals(0, findByQuery("PaymentService").size());
    }

    @Test
    void matchesLikeMetaCharactersLiterallyOnly() {
        // E-07 — 4입력 (Design §7.1): 각각 리터럴 매칭만 hit, 와일드카드 비발동
        long now = System.currentTimeMillis();
        ingestTrace("t-pct", "svc", "discount 100% promo", now - 5_000L);
        ingestTrace("t-pct-decoy", "svc", "discount 100x promo", now - 4_900L); // '%' 와일드카드면 오염 hit
        ingestTrace("t-us", "svc", "a_b", now - 4_000L);
        ingestTrace("t-us-decoy", "svc", "axb", now - 3_900L);                  // '_' 와일드카드면 오염 hit
        ingestTrace("t-bs", "svc", "C:\\path", now - 3_000L);
        ingestTrace("t-mix", "svc", "find%_\\ op", now - 2_000L);

        List<TraceSummary> pct = findByQuery("100%");
        assertEquals(1, pct.size(), "'100%' 는 리터럴만 hit — 와일드카드 비발동");
        assertEquals("t-pct", pct.get(0).traceId());

        List<TraceSummary> us = findByQuery("a_b");
        assertEquals(1, us.size(), "'a_b' 의 '_' 는 리터럴 — 'axb' 미포함");
        assertEquals("t-us", us.get(0).traceId());

        List<TraceSummary> bs = findByQuery("C:\\path");
        assertEquals(1, bs.size(), "백슬래시 포함 검색어 리터럴 매칭");
        assertEquals("t-bs", bs.get(0).traceId());

        List<TraceSummary> mix = findByQuery("find%_\\");
        assertEquals(1, mix.size(), "혼합 메타문자 검색어 리터럴 매칭");
        assertEquals("t-mix", mix.get(0).traceId());
    }

    @Test
    void escapesBackslashBeforePercentAndUnderscore() {
        // escapeLike 단일 거주지 (Design §3.1.7) — 순서 의무: 백슬래시 최우선
        assertEquals("\\\\", TraceQueryRepository.escapeLike("\\"));
        assertEquals("\\%", TraceQueryRepository.escapeLike("%"));
        assertEquals("\\_", TraceQueryRepository.escapeLike("_"));
        // 입력 \% → \\ + \% (백슬래시를 먼저 치환하지 않으면 4-backslash 오염 결과)
        assertEquals("\\\\\\%", TraceQueryRepository.escapeLike("\\%"));
        assertEquals("plain", TraceQueryRepository.escapeLike("plain"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private List<TraceSummary> findByQuery(String q) {
        return repository.findTraces(null, null, null, null, q, 100, null);
    }

    private void ingestTrace(String traceId, String serviceName, String rootOperation, long startTime) {
        Span root = new Span(
                traceId + "-root", traceId, null,
                serviceName, rootOperation, SpanKind.SERVER,
                startTime, startTime + 50, SpanStatus.OK,
                null, List.of()
        );
        ingestService.ingest(new IngestRequest(List.of(root)));
    }
}
