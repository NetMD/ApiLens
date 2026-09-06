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
package io.apilens.server.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.IngestRequest;
import io.apilens.common.Payload;
import io.apilens.common.RegexTimeoutException;
import io.apilens.common.Span;
import io.apilens.server.masking.MaskingEngineHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Persists ingested spans + payloads, then derives and upserts the trace summary.
 *
 * <p>The trace summary is always recomputed from the {@code spans} table (not from
 * the current batch), so a trace whose spans arrive across multiple batches keeps a
 * correct summary. See {@code upsertTraceSummary}.
 *
 * <p>[Phase R17] FR-01 — 거대 trace 적재는 span 을 {@code SPAN_CHUNK_SIZE} 단위 청크로 나눠
 * 청크마다 짧은 프로그래매틱 트랜잭션으로 커밋한다(write lock 보유 시간 단축). 청크 중간이
 * SQLITE_BUSY 로 실패하면 앞 청크만 남는 부분 적재를 허용하고(모니터링 도구 — 통째 유실보다 나음),
 * 호스트에는 예외를 던지지 않는다(host-throw-0). 요약은 청크 루프 밖에서 1회만 재집계한다.
 *
 * <p>[Phase R19] 서비스 등록 경로(클래스 javadoc 보완 — 그동안 누락돼 있던 문단):
 * 적재의 마지막 단계에서 {@code upsertServiceRegistration} 이 {@code services} 테이블을
 * trace 단위로 UPSERT 한다(자동 등록 = D-02 경로 B). 이 호출은 청크 트랜잭션 <b>밖</b>의
 * auto-commit 문맥에 있고 {@code catch(Throwable)} 로 감싸여 있어, 실패해도 이미 커밋된
 * span/payload/traces 에 영향이 없고 호스트로 예외가 새지 않는다. R19 부터는 같은 문에서
 * agent 가 시작할 때 보고한 버전({@code services.agent_version})도 함께 갱신한다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final JdbcTemplate jdbc;
    // [Phase R12] AC-B2-3 — MaskingEngine 직접 주입 → MaskingEngineHolder 전환 (핫 리로드).
    // 매 mask 호출 시점에 current() 로 최신 엔진을 읽는다 — 룰 변경은 이후 ingest 분부터 반영 (BL-06).
    private final MaskingEngineHolder maskingHolder;
    private final ObjectMapper mapper;
    // [Phase R13] AC-A2-1 — payload 저장 직전 크기 가드 한도(개별 payload body byte).
    // @ConfigurationPropertiesScan(ApiLensApplication.java:27)으로 bean 자동 등록 → 생성자 주입.
    private final IngestProperties ingestProperties;

    // [Phase R17] FR-01 — 청크 단위 프로그래매틱 트랜잭션. 생성자 시그니처 불변(V-02):
    //   이미 주입된 jdbc 의 DataSource 로 트랜잭션 관리자를 본문에서 구성한다(새 인자 0).
    private final TransactionTemplate chunkTx;

    // [Phase R17] FR-03 — SQLITE_BUSY 발생/유실 카운터(인메모리, host-throw-0). 생성자 인자 아님.
    //   재시작 시 0 복귀는 정상(스키마 무변경 — DB 저장 안 함). 기준선은 로그 파일 누적으로 비교.
    //   encountered = SQLITE_BUSY 예외를 catch 한 횟수(경합 이벤트), dropped = 유실된 청크 수(청크 ≈ 500 span).
    private final AtomicLong sqliteBusyEncountered = new AtomicLong();
    private final AtomicLong sqliteBusyDropped = new AtomicLong();

    // [Phase R23] R23/AC-06-1 — 요약(traces 한 행)을 저장하지 못한 **흐름**의 누적 개수. 인메모리이고
    //   생성자 인자가 아니다(V-02 4-인자 봉인 유지). 단위가 위 두 카운터와 다르다 —
    //   encountered = 경합 횟수 · dropped = 유실된 청크 수(청크 ≈ 500 span) · deferred = 흐름 수.
    //   재시작 시 0 복귀는 정상(스키마 무변경 — DB 저장 안 함).
    private final AtomicLong traceSummaryDeferred = new AtomicLong();

    // [Phase R23] R23/AC-01-1 — 요약 실패 WARN 에 싣는 근본 예외 메시지의 길이 상한(문자).
    //   이 문자열은 **외부(드라이버)가 정한 값**이라 통제 대상이다. 상한이 없으면 회전 로그 한 줄을
    //   지배해 유실률 기준선 대조가 끊긴다. 개행 제거와 한 쌍이다(가짜 로그 줄 삽입 차단).
    private static final int ROOT_MESSAGE_MAX_CHARS = 512;

    // [Phase R17] FR-01 — 청크 크기 상수(EXT-003 매직넘버 상수화). OQ-C 확정값(span 개수).
    private static final int SPAN_CHUNK_SIZE = 500;

    // [Phase R18] AC-02-1/NFR-04 — ReDoS deadline 초과 시 degrade 본문(상수 전체마스킹).
    //   사용자 명시 비협상 결정: 부분결과·원문 저장 금지(PII) → body 전체를 고정 상수로 대체 후 비throw.
    //   CLAUDE.md '아키텍처 핵심 원칙'(마스킹은 공유 엔진, 결과 일관성) 인용.
    private static final String REDOS_DEGRADE_BODY = "***";

    // [Phase R19] AC-01-4/AC-01-5 — agent 시작 알림(hello) span 을 알아보는 두 문자열.
    //   agent 와 server 사이의 암묵 계약이며 원본 좌표는 다음과 같다:
    //     - operationName : apilens-agent/src/main/java/io/apilens/agent/AgentMain.java:141
    //     - attribute key : apilens-agent/src/main/java/io/apilens/agent/AgentMain.java:147
    //   ⚠️ apilens-common 에 공유 상수를 만들지 않는다 — common 을 건드리면 agent shadow jar 내용이
    //   바뀌어 "agent 소스·산출물 무변경" 증명이 흐려진다(S-2 비협상). 공유 상수화는 agent 를 여는
    //   라운드로 이연. CLAUDE.md 'Build 설정 lessons §1'(shadow jar relocate 함정) 인용.
    private static final String AGENT_HELLO_OPERATION = "agent.startup";
    private static final String AGENT_VERSION_ATTRIBUTE = "apilens.agent.version";

    // [Phase R24] R24/FR-05 — 끊겼다 이어진 구간을 로그 한 줄로 남기는 임계.
    //   ★이 값 **미만**의 공백에서는 아무 줄도 안 찍는다. 그리고 이것은 **일이 벌어진 뒤에 남기는
    //   기록**이지 경보가 아니다 — 끊겨 있는 동안에는 어디에도 안 뜨고, 다시 수신이 들어와야 드러난다.
    //   ★TraceQueryRepository.computeHealthStatus 의 5분·30분 경계와 무관한 전용 값이다.
    //   그 두 값은 사용자 명시 비협상 결정이라 참조도 재사용도 하지 않는다 — 그래서 그 두 상수의
    //   이름을 여기에 적지도 않는다(회귀 가드가 이름으로 세는 자리라 인용 자체가 거짓 hit 가 된다).
    private static final long RESUME_GAP_THRESHOLD_MS = 600_000L; // 10 minutes

    // [Phase R24] R24/FR-05 — 사람이 읽는 시각 표기. ISO_LOCAL_DATE_TIME / LocalDateTime.toString() 은
    //   초가 0 이면 ":00" 을 생략해 자릿수가 들쭉날쭉해진다(JDK 규격). 그래서 형식을 고정한다.
    private static final DateTimeFormatter RESUME_TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // [Phase R24] R24/FR-05 — 괄호 표기용 나눗셈 상수(밀리초 → 일). 사람이 자릿수를 가늠하는 용도이고
    //   계산에 쓰는 수치가 아니다(계산에 쓰는 것은 gapMs 하나뿐 — logIngestResumedIfGap javadoc 참조).
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    // ══ [Phase R25] 본문 내용주소 저장 + SQL 원문 인터닝 (V6·V7) ═══════════════════════════════
    //
    // [Phase R25] AC-25-02-5 — 예약 속성 키. 사용자 명시 비협상 결정(UD-2 + CXP-3 세 규칙).
    //   ⚠️ 이 키가 놓이는 attributes 맵은 **인증 없는 입구가 채우는 그릇과 같다**
    //   (AuthWhitelist 가 POST /v1/spans 를 통과시키고 validate() 는 속성 키를 거르지 않는다).
    //   그래서 세 규칙이 함께 든다: ⓐ apilens. 접두 ⓑ 원문 우선 ⓒ 밖에서 이 키를 이미 차지했으면
    //   그 span 은 인터닝하지 않고 원문 그대로 저장한다(새 도피 규약을 만들지 않는다).
    //   CLAUDE.md 'Span attribute 키 명세' 인용 — apilens. 이름 공간은 이미 쓰는 자리다
    //   (같은 파일의 AGENT_VERSION_ATTRIBUTE = "apilens.agent.version").
    //   ★이 두 상수는 <b>쓰기와 읽기가 공유하는 유일한 거주지</b>다. 읽는 쪽(TraceQueryRepository)이 자기
    //   문자열을 따로 들면 두 벌이 갈려 한쪽만 고치는 순간 SQL 이 조용히 사라진다 — 그래서 public 이다.
    public static final String STMT_REF_ATTRIBUTE = "apilens.stmt.ref";

    // [Phase R25] AC-25-02-7 — 인터닝 대상은 이 한 키뿐이다. db.parameters 등 다른 키로 넓히지 않는다:
    //   다른 키는 값이 다양해 인터닝 이득이 없고, 넓히면 예약 키가 늘어 계약 표면이 커진다.
    public static final String DB_STATEMENT_ATTRIBUTE = "db.statement";

    // [Phase R25] AC-25-01-2 — IN (?, …) 목록 한 번에 묶는 최대 개수. SQLite 의 바인딩 변수 한도(구버전 999)
    //   안쪽 안전 마진이고, 같은 파일의 SPAN_CHUNK_SIZE·RetentionCleanupService 의 500 관례와 같은 값이다.
    private static final int HASH_IN_CHUNK_SIZE = 500;

    // [Phase R17] EXT-003 anchor — V-02 생성자 4-인자 불변(사용자 명시 비협상 결정).
    //   협력자(트랜잭션 관리자·카운터)는 생성자 인자가 아니라 내부 필드/본문으로 얻는다.
    //   R13 287a7e7 회귀 진원지(생성자 변경이 agent 통합테스트 컴파일 파손) 재발 차단.
    //   CLAUDE.md 'Build 설정 lessons §1'(shadow jar relocate 함정) 인용.
    public IngestService(JdbcTemplate jdbc, MaskingEngineHolder maskingHolder, ObjectMapper mapper,
                         IngestProperties ingestProperties) {
        this.jdbc = jdbc;
        this.maskingHolder = maskingHolder;
        this.mapper = mapper;
        this.ingestProperties = ingestProperties;
        // [Phase R17] FR-01 — jdbc.getDataSource() 로 트랜잭션 관리자 구성(생성자 인자 미추가 = V-02 봉인).
        //   ingest 는 순수 JdbcTemplate(JPA 엔티티 미사용)이라 DataSourceTransactionManager 로 정합(GT-9).
        this.chunkTx = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    // [Phase R17] FR-01 — @Transactional 제거: 상위 통짜 트랜잭션 대신 청크별 프로그래매틱 트랜잭션.
    public IngestResponse ingest(IngestRequest request) {
        validate(request);
        long receivedAt = System.currentTimeMillis();

        // [Phase R24] R24/FR-05/R24/AC-02-6 — ★반드시 persistTrace 루프보다 **앞**이다.
        //   persistTrace 의 마지막 문장이 upsertServiceRegistration 이고, 그 UPSERT 의
        //   `last_seen_at = excluded.last_seen_at` 이 직전 값을 같은 문장에서 덮는다.
        //   순서가 역전되면 읽을 값이 이미 사라져 이 기능이 통째로 무력화된다.
        logIngestResumedIfGap(request, receivedAt);

        Map<String, List<Span>> byTrace = request.spans().stream()
                .collect(Collectors.groupingBy(Span::traceId));

        for (Map.Entry<String, List<Span>> entry : byTrace.entrySet()) {
            persistTrace(entry.getKey(), entry.getValue(), receivedAt);
        }

        return new IngestResponse(request.spans().size(), byTrace.size());
    }

    // [Phase R17] FR-01 — 청크 단위 프로그래매틱 트랜잭션. 한 trace 의 span 을 SPAN_CHUNK_SIZE 씩 나눠
    //   각 청크(span INSERT OR REPLACE + payload delete-then-insert)를 짧은 트랜잭션으로 커밋한다.
    //   청크 경계마다 write lock 이 풀려 UI 조회·다른 writer 가 끼어들 여지가 생긴다(거대 trace 완화 근본 레버).
    private void persistTrace(String traceId, List<Span> spans, long receivedAt) {
        boolean committedAny = false;
        int total = spans.size();
        for (int start = 0, idx = 0; start < total; start += SPAN_CHUNK_SIZE, idx++) {
            List<Span> chunk = spans.subList(start, Math.min(start + SPAN_CHUNK_SIZE, total));
            try {
                chunkTx.executeWithoutResult(status -> {
                    // [Phase R17] V-03/G-09 — insertSpans 의 INSERT OR REPLACE spans SQL 문자열·컬럼 diff 0.
                    insertSpans(chunk);           // A5 비협상: SQL·REPLACE 시맨틱 무변경, 호출 인자만 청크로.
                    deletePayloadsForChunk(chunk); // [Phase R17] OQ-A — payload 멱등 가드(재적재 중복 0).
                    // [Phase R17] V-03/G-09 — INSERT INTO payloads SQL 문자열 diff 0.
                    // [Phase R25] AC-25-08-5 — ★위 봉인을 이 라운드가 **재개방했다**. 지우지 않고 이력으로 남긴다:
                    //   payloads 에 body_hash 열이 늘어 INSERT 문의 컬럼 목록이 필연으로 바뀐다(V6).
                    //   바로 위 insertSpans 쪽 봉인은 **그대로 유효하다** — spans 의 SQL 문자열·컬럼은
                    //   한 글자도 안 바뀌고 메서드 본문에 사전 패스 한 줄이 늘 뿐이다.
                    insertPayloads(chunk);
                });
                committedAny = true;
            } catch (DataAccessException e) {
                // [Phase R17] FR-03/V-04 — 청크 write 실패는 host 로 던지지 않는다(host-throw-0).
                boolean busy = isSqliteBusy(e);
                if (busy) {
                    sqliteBusyEncountered.incrementAndGet();
                }
                // 이 청크 + 남은 청크를 유실로 집계(지연 상한 위해 break — 지속 경합 시 남은 청크도 어차피 실패).
                int remaining = (int) Math.ceil((double) (total - start) / SPAN_CHUNK_SIZE);
                sqliteBusyDropped.addAndGet(remaining);
                // [Phase R25] AC-25-04-1/AC-25-04-2/AC-25-04-3 — traceId 는 인증 없는 입구가 정한 값이라
                //   위생 처리한다(개행 제거 + 길이 상한). 앞머리 문구와 필드 이름은 안 바꾼다.
                log.warn("SQLITE_BUSY drop: traceId={} chunkIdx={} droppedChunks={} busy={} cause={} encounteredTotal={} droppedTotal={}",
                        sanitizeForLog(traceId), idx, remaining, busy, e.getClass().getSimpleName(),
                        sqliteBusyEncountered.get(), sqliteBusyDropped.get());
                break; // 요청 지연을 ~1 busy_timeout 으로 제한(남은 청크 시도 안 함).
            }
        }
        if (committedAny) {
            try {
                // [Phase R17] FR-01 — 요약은 청크 루프 밖 1회. upsertTraceSummary 는 spans 테이블을 재집계하므로
                //   부분 적재라도 커밋된 span 만 반영한다(GT-10, G-10 SQL diff 0). 순서 역전 시 O(N²)+lock 점유로 목적 붕괴.
                // [Phase R23] R23/AC-02-1 — ★트랜잭션 래핑을 걷어냈다(자동커밋 직접 호출).
                //   왜: 이 트랜잭션은 **읽기가 먼저**였다(집계 SELECT → 대표 span SELECT → INSERT OR REPLACE).
                //   WAL 은 첫 읽기에서 스냅샷을 잡으므로, 그 사이 다른 커넥션이 커밋하면 쓰기 승격이
                //   **대기 없이 즉시** 실패한다(스냅샷 충돌). 자동커밋이 되면 승격이라는 단계 자체가 없어져
                //   그 실패 경로가 사라진다. 대신 세 문장이 각각 다른 스냅샷을 보므로
                //   **집계 null 가드가 반드시 함께 있어야 한다**(upsertTraceSummary 안 — R23/AC-02-2).
                upsertTraceSummary(traceId, spans, receivedAt);
            } catch (DataAccessException e) {
                boolean busy = isSqliteBusy(e);
                if (busy) {
                    sqliteBusyEncountered.incrementAndGet();
                }
                // 요약 실패는 span 유실 아님(이미 커밋) → dropped 증가 안 함.
                // [Phase R22] R22/AC-05-1 — 이전 서술("다음 ingest 가 재집계로 자가치유")은 **오진단**이었다.
                //   자가치유는 **같은 trace_id 로 span 이 더 올 때만** 일어난다(upsertTraceSummary 가
                //   traceId 를 받는다). 마지막 청크에서 실패하면 다음이 안 오고 **영구 고아 span** 이 된다.
                // [Phase R23] R23/AC-08-4 — ★현행화: 고아를 만드는 **가장 큰 원인**(읽기가 먼저인 트랜잭션의
                //   쓰기 승격 실패)은 R23 에서 없앴다. 남는 것은 다른 이유로 실패하는 요약뿐이고, 그렇게
                //   생긴 고아는 야간 스윕(RetentionCleanupService.sweepOrphanSpansNightly)이 자체 스케줄
                //   (apilens.retention.orphan-sweep-cron)로 돌며 이틀에 걸쳐 확인해 지운다.
                //   "고아 span 은 0 이다" 를 단정으로 쓰지 않는다.
                traceSummaryDeferred.incrementAndGet();
                // [Phase R23] R23/AC-01-2/R23/AC-01-5 — ★앞머리 토큰 `trace summary deferred` 는 **고정**이다.
                //   이 문자열이 운영 로그 7일치를 대조하는 기준점이라 바꾸면 과거와의 대조가 끊긴다.
                //   (지금까지 이 사실은 코드 어디에도 없고 운영 기록에만 있었다 — R23 이 코드에 남긴다.)
                //   새 필드는 **뒤에만** 덧붙인다.
                // [Phase R23] R23/AC-01-3 — errorCode·sqlState 는 **이번 사안의 판정 근거가 아니다**
                //   (실측 errorCode=5 · SQLState=null 로 평범한 잠금 경합과 값이 같다). 다른 종류의
                //   실패(CONSTRAINT=19 · FULL=13)를 가를 때만 쓴다. 원인을 가르는 것은 rootMessage 다.
                Throwable root = rootCauseOf(e);
                SQLException sql = deepestSqlException(e);
                // [Phase R25] AC-25-04-1 — traceId 도 감싼다. 같은 줄의 rootMessage 는 R23 부터 이미 감싸져 있다.
                log.warn("trace summary deferred (self-heal only if more spans arrive for this trace): traceId={} cause={}"
                                + " rootCause={} rootMessage={} errorCode={} sqlState={} busy={} deferredTotal={}",
                        sanitizeForLog(traceId), e.getClass().getSimpleName(),
                        root.getClass().getName(), sanitizeForLog(root.getMessage()),
                        sql == null ? -1 : sql.getErrorCode(),
                        sql == null ? null : sql.getSQLState(),
                        busy, traceSummaryDeferred.get());
            }
        }
        // [Phase H] AC-06-3 — D-02 경로 B (자동 등록). 사용자 명시 비협상 결정.
        // CLAUDE.md '아키텍처 핵심 원칙' (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
        // R6 회귀 가드: try-catch(Throwable) 외곽 — 호스트 throw 0 비협상.
        // [Phase R17] V-04 — 이제 auto-commit 문맥(상위 @Transactional 제거)에서 실행. catch(Throwable) 그대로 유지.
        upsertServiceRegistration(spans, receivedAt);
    }

    // [Phase R17] OQ-A — 청크 멱등 가드. 청크의 span_id 별 기존 payload 제거 후 재삽입 → 재적재 시 중복 0.
    //   idx_payloads_span_id(GT-7)로 각 삭제가 인덱스 조회라 저렴. foreign_keys=OFF 라 cascade 부작용 없음.
    //   IN(...) 이 아니라 batchUpdate(WHERE span_id = ?)라 SQLite 변수 한도(999)에 무의존(OQ-C).
    private void deletePayloadsForChunk(List<Span> chunk) {
        List<Object[]> ids = chunk.stream().map(s -> new Object[]{ s.spanId() }).toList();
        jdbc.batchUpdate("DELETE FROM payloads WHERE span_id = ?", ids);
    }

    // [Phase R17] FR-03 — SQLITE_BUSY 감지. org.sqlite 타입 미사용(GT-1 runtimeOnly — import 하면 컴파일 파손).
    //   java.sql.SQLException.getErrorCode(): SQLITE_BUSY=5, SQLITE_LOCKED=6 (dev STEP 0 실측 확인 errorCode==5).
    //   코드가 0으로 오는 엣지에는 message 매칭으로 폴백 → 감지 무공백.
    private static boolean isSqliteBusy(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException se && (se.getErrorCode() == 5 || se.getErrorCode() == 6)) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                String u = m.toUpperCase(Locale.ROOT);
                if (u.contains("SQLITE_BUSY") || u.contains("DATABASE IS LOCKED") || u.contains("SQLITE_LOCKED")) {
                    return true;
                }
            }
        }
        return false;
    }

    // [Phase R20] R20/AC-10-1 — status 표면(/v1/maintenance/status) + 테스트 관측용 getter.
    //   package-private → public 승격(MaintenanceController 는 다른 패키지 — retention ↔ ingest).
    //   생성자 인자 아님(V-02 4-인자 봉인 무관). R17 확정 설계 불변: 카운터 이름 그대로 · 인메모리
    //   (DB 저장 금지) · 재시작 0 복귀 정상 · 기준선은 logs/apilens.log 누적 비교.
    public long sqliteBusyEncounteredCount() {
        return sqliteBusyEncountered.get();
    }

    public long sqliteBusyDroppedCount() {
        return sqliteBusyDropped.get();
    }

    /**
     * [Phase R23] R23/AC-06-1 — 요약을 저장하지 못한 누적 <b>흐름 수</b> (status 표면 + 테스트 관측용).
     * 위 두 게터와 같은 형태로 노출한다(생성자 인자 아님 — V-02 4-인자 봉인 무관).
     * 인메모리라 재시작 시 0 복귀가 정상이고, 기준선은 logs/apilens.log 누적 비교다.
     */
    public long traceSummaryDeferredCount() {
        return traceSummaryDeferred.get();
    }

    /**
     * [Phase R23] R23/AC-01-1 — cause 사슬의 <b>맨 끝</b> 예외. 감싼 클래스 이름
     * ({@code TransientDataAccessResourceException} 등)만으로는 원인을 못 가르기 때문에 필요하다.
     *
     * <p>이 메서드와 {@link #deepestSqlException} · {@link #sanitizeForLog} 은 <b>절대 던지지 않는다</b> —
     * 로그를 만드는 도중의 예외가 catch 블록을 빠져나가면 host-throw-0 이 깨진다(I-07).
     * 자기 참조 cause 로 무한 순회하지 않도록 {@code c != c.getCause()} 로 끊는다(isSqliteBusy 와 같은 관용구).
     */
    private static Throwable rootCauseOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * [Phase R23] R23/AC-01-4 — cause 사슬에서 <b>가장 깊은</b> {@link SQLException}. 없으면 null.
     * {@code org.sqlite.*} 를 import 하지 않는다 — JDK 표준 타입만으로 코드·상태값을 읽을 수 있고,
     * 드라이버는 runtimeOnly 라 main 에서 import 하면 컴파일이 깨진다(I-08).
     */
    private static SQLException deepestSqlException(Throwable t) {
        SQLException found = null;
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof SQLException se) {
                found = se;
            }
        }
        return found;
    }

    /**
     * [Phase R23] §5.2 — 외부가 정한 문자열을 로그 한 줄에 실을 때의 위생 처리.
     * ① 개행({@code \r} {@code \n})을 공백으로 바꾼다 — 가짜 로그 줄 삽입 차단(로그 인젝션).
     *   이 WARN 이 유실률 기준선의 앵커라 <b>한 줄 단위</b>가 깨지면 대조가 틀린다.
     * ② {@value #ROOT_MESSAGE_MAX_CHARS} 자를 넘으면 잘라 내고 잘렸음을 표시한다.
     */
    private static String sanitizeForLog(String raw) {
        if (raw == null) {
            return null;
        }
        String oneLine = raw.replace('\r', ' ').replace('\n', ' ');
        if (oneLine.length() <= ROOT_MESSAGE_MAX_CHARS) {
            return oneLine;
        }
        return oneLine.substring(0, ROOT_MESSAGE_MAX_CHARS) + "[truncated]";
    }

    /**
     * [Phase R24] R24/FR-05 — 수신이 한동안 없다가 다시 들어오면 그 구간을 로그 한 줄로 남긴다.
     *
     * <p>★<b>이것은 일이 벌어진 뒤에 남기는 기록이지 경보가 아니다.</b> 수집기 프로세스가 아예
     * 없던 구간은 <b>다시 켜진 뒤에야</b> 드러나고, 끊긴 동안에는 아무 데도 안 뜬다 —
     * 표면을 만들어 주는 주체가 그 프로세스이기 때문이다. 「끊기면 알려 준다」로 읽으면 거짓이다.
     *
     * <p>안 찍히는 것이 정상인 경우가 셋이다: ① 직전 수신 시각이 없다(첫 등록 · NULL)
     * ② 공백이 {@link #RESUME_GAP_THRESHOLD_MS} 미만이다 ③ 이 요청에 쓸 만한 서비스 이름이 없다.
     *
     * <p><b>단위</b>: {@code services.last_seen_at} 과 {@code gapMs} 는 둘 다 <b>epoch/경과 밀리초</b>다.
     * {@code lastSeenBefore} 는 <b>사람이 읽는 시각 표기</b>이고 원본은 밀리초다.
     * 괄호 안의 {@code (3.83d)} 는 <b>사람이 자릿수를 가늠하라고 덧붙인 것이지 계산에 쓰는 값이 아니다</b> —
     * 계산에 쓰는 수치는 {@code gapMs} 하나뿐이다. 10분 같은 짧은 공백은 {@code (0.01d)} 로 뜬다.
     *
     * <p>앞머리 토큰 {@code ingest resumed:} 는 <b>고정</b>이다 — 운영 로그 대조의 기준점이라
     * 바꾸면 과거와의 대조가 끊긴다. 새 필드는 <b>뒤에만</b> 덧붙인다
     * (같은 규율이 {@code trace summary deferred} 에 이미 걸려 있다).
     *
     * <p><b>로그 인젝션</b>: 마지막 {@code service=} 값은 agent 가 보내는 값이라 서버 통제 밖이다.
     * // [Phase R25] AC-25-04-1/AC-25-04-2 — ★<b>결정이 났다: 감싸는 쪽이다.</b> R24 까지 이 자리는
     * // "처방 선택이 아직 결정되지 않았다" 는 이유로 비어 있었고, 그래서 이 파일에 감싼 줄과 안 감싼 줄이
     * // 공존했다. R25 가 인증 없는 입구와 그 예외 경로의 로그 인자를 <b>전수</b>로 {@link #sanitizeForLog}
     * // 로 감쌌다 — 새 클래스도 공용 도구도 만들지 않고 <b>이미 같은 파일에 있는 것</b>을 쓴다.
     * // 앞머리 문구와 필드 이름은 안 바꾼다(과거 기록 대조의 기준점).
     *
     * <p>본문 전체가 {@code try-catch(Throwable)} 안이다 — <b>호스트로 예외가 새지 않는다</b> 는
     * 사용자 명시 비협상 결정이고, 이 자리는 {@code upsertServiceRegistration} 의 catch <b>밖</b>이라
     * 가드를 따로 둔다(같은 규율의 두 번째 자리). CLAUDE.md '아키텍처 핵심 원칙'
     * (Agent 자체 장애가 호스트 앱에 영향 0) 인용.
     */
    private void logIngestResumedIfGap(IngestRequest request, long receivedAt) {
        try {
            Set<String> names = request.spans().stream()
                    .map(Span::serviceName)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());
            if (names.isEmpty()) {
                // validate() 가 공백 serviceName 을 이미 거르므로 ingest() 경로로는 안 닿는다.
                // 그래도 둔다 — 이 메서드가 다른 자리에서 불릴 때의 방어다.
                return;
            }
            // 행마다 부를 처리기를 **타입을 적어** 지역 변수로 둔다 — jdbc.query 의 2인자 오버로드가
            // 셋(RowMapper/RowCallbackHandler/ResultSetExtractor)이라 무형 람다는 어느 것으로 갈지
            // 읽는 사람이 갈라야 한다. 여기서 원하는 것은 값을 모으지 않는 순회다.
            RowCallbackHandler onRow = rs -> {
                String name = rs.getString("service_name");
                if (!names.contains(name)) {
                    return;
                }
                // sqlite-jdbc 는 작은 정수를 Integer 로 돌려줄 수 있어 (Long) 캐스트가
                // ClassCastException 이 된다(TraceQueryRepository.findServicesWithHealth 가 이미 겪은 실측).
                if (rs.getObject("last_seen_at") == null) {
                    return; // 첫 등록 = 직전 값 없음 → 안 찍는 것이 정상
                }
                long lastSeenAt = rs.getLong("last_seen_at");
                if (!isResumeGap(lastSeenAt, receivedAt)) {
                    return;
                }
                long gapMs = receivedAt - lastSeenAt;
                log.info("ingest resumed: lastSeenBefore={} gapMs={} ({}d) service={}",
                        RESUME_TS_FORMAT.format(Instant.ofEpochMilli(lastSeenAt)
                                .atZone(ZoneId.systemDefault())),
                        gapMs,
                        String.format(Locale.ROOT, "%.2f", gapMs / MILLIS_PER_DAY),
                        // [Phase R25] AC-25-04-1 — ★줄 맨 끝이라 개행이 들면 뒤 줄이 통째로 위조된다.
                        sanitizeForLog(name));
            };
            // 바인딩 파라미터 0개 — 외부 입력이 SQL 에 닿지 않는다. services 는 서비스당 1행인
            // 작은 표라 무-WHERE 1문이 IN 목록(SQLite 변수 999 한도)보다 싸고 안전하다.
            jdbc.query("SELECT service_name, last_seen_at FROM services", onRow);
        } catch (Throwable t) {
            // 호스트 throw 0 / 수신 흐름 영향 0 — silent log + skip.
            // [Phase R25] AC-25-04-1 — 예외 메시지는 외부 값이 흘러드는 2차 채널이다(드라이버가 입력값을
            //   그대로 실어 준다). 클래스 이름은 서버 통제 안이라 대상이 아니다.
            log.warn("ingest resume gap check skipped due to {}: {}",
                    t.getClass().getSimpleName(), sanitizeForLog(t.getMessage()));
        }
    }

    /**
     * [Phase R24] R24/FR-05 — 끊김 판정. <b>순수 함수라 시간 소스를 주입할 필요가 없다</b>:
     * 비결정 입력({@code now})이 이미 {@code receivedAt} 파라미터로 들어온다(생성자 4-인자 봉인 유지).
     * 경계는 <b>이상</b>({@code >=}) 이다. 음수 공백(시계 역행·미래 값)은 판정 false —
     * 안 찍는 쪽이 안전한 방향이다.
     */
    static boolean isResumeGap(long lastSeenAt, long receivedAt) {
        long gapMs = receivedAt - lastSeenAt;
        return gapMs >= RESUME_GAP_THRESHOLD_MS;
    }

    /**
     * Auto-register the services seen in this trace (D-02 path B).
     *
     * <p>⚠️ 이 메서드는 <b>배치가 아니라 한 trace 의 span 목록</b>을 받는다 — {@code ingest()} 가
     * {@code groupingBy(Span::traceId)} 로 나눈 뒤 trace 마다 {@code persistTrace} 를 부르고,
     * 그 마지막 줄이 이 호출이다([Phase R19] 상류 표현 정정 C-5). agent 의 시작 알림(hello)은
     * 자기 trace 를 새로 만드는 span 1개짜리 독립 trace 이므로 한 호출에 들어오는 hello 는 보통
     * 0개 또는 1개다. 그래도 서비스 이름 키 매칭과 승자 규칙(startTime 최댓값)은 그대로 구현한다 —
     * batching·MSA 로 조건이 바뀌어도 서비스별 값이 뒤바뀌지 않게 하기 위해서다.
     *
     * <p>[Phase H] AC-06-3 — D-02 / R6 / R12. 사용자 명시 비협상 결정.
     * CLAUDE.md '아키텍처 핵심 원칙' (호스트 throw 0) 인용.
     *
     * <p>R6 비협상: 어떤 이유로 실패해도 host throw 0. trace 수신 흐름
     * (spans/payloads/traces INSERT) 은 이미 INSERT 된 상태, services UPSERT 만
     * 실패해도 전체 트랜잭션 rollback 0 — Spring 의 DataAccessException 은
     * RuntimeException 이므로 정상이라면 rollback 마킹하지만, 본 분기는
     * try-catch(Throwable) 외곽으로 잡아 silent log + skip 한다. 트랜잭션은
     * 이미 INSERT/UPDATE 가 완료된 상태로 정상 commit.
     *
     * <p>R12 회귀 가드: 단일 UPSERT 1회 per distinct service_name. spans 전체
     * SQL 재집계 패턴 추가 도입 0.
     *
     * <p>D-02 멱등성: ON CONFLICT(service_name) DO UPDATE SET last_seen_at =
     * excluded.last_seen_at → source 와 registered_at 은 처음 INSERT 시점 값 유지.
     * wizard 로 먼저 등록된 service (source='wizard') 의 trace 가 도착해도
     * source='wizard' 유지.
     *
     * <p>[Phase R19] AC-01-4/AC-01-5 — hello span 이 있으면 {@code agent_version} 도 같은 문에서
     * 갱신한다(서비스당 실행 문 수는 여전히 1). hello 가 없는 일반 trace 는 바인딩 값이 NULL 이라
     * {@code COALESCE} 가 기존 값을 지켜 "마지막 확인 값" 이 영속된다.
     */
    private void upsertServiceRegistration(List<Span> traceSpans, long receivedAt) {
        try {
            // [Phase R19] AC-01-4 — hello 에서 버전 추출. 반드시 이 try 블록 **안**에 둔다:
            //   밖에 두면 형 변환 오류 같은 예외가 ingest 핫패스로 새어 호스트 앱에 도달한다(NFR-05 위반).
            Map<String, String> agentVersions = extractAgentVersions(traceSpans);

            Set<String> distinctServices = traceSpans.stream()
                    .map(Span::serviceName)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toSet());
            for (String name : distinctServices) {
                // [Phase R19] GT-16 실행 게이트 통과 실측(sqlite-jdbc 3.47.1.0 / SQLite 3.47.1):
                //   DO UPDATE SET 절의 한정자 없는 agent_version 은 "기존 행"의 값을 가리킨다
                //   (버전 NULL 재적재 후에도 기존 값 유지 확인). 폴백안(services.agent_version 한정
                //   참조)도 문법상 유효하나 설계 원안을 유지한다.
                jdbc.update(
                        """
                                INSERT INTO services (service_name, registered_at, last_seen_at, source, agent_version)
                                VALUES (?, ?, ?, 'auto', ?)
                                ON CONFLICT(service_name) DO UPDATE SET
                                    last_seen_at  = excluded.last_seen_at,
                                    agent_version = COALESCE(excluded.agent_version, agent_version)
                                """,
                        name, receivedAt, receivedAt, agentVersions.get(name)
                );
            }
        } catch (Throwable t) {
            // D-02 비협상 + R6 회귀 차단: services UPSERT 실패는 silent log + skip.
            // 호스트 throw 0 / 트랜잭션 전체 rollback 0.
            // [Phase R25] AC-25-04-1 — 예외 메시지 2차 채널(위 resume gap 자리와 같은 처방).
            log.warn("services UPSERT skipped due to {}: {}",
                    t.getClass().getSimpleName(), sanitizeForLog(t.getMessage()));
        }
    }

    /**
     * Collect the agent version reported by hello spans, keyed by service name.
     *
     * <p>[Phase R19] AC-01-4 — 값은 자바 객체 상태({@code io.apilens.common.Span:51}
     * {@code Map<String,Object> attributes})에서 꺼낸다. 저장된 {@code attributes_json} 을 다시
     * 파싱하지 않는다 — agent 가 {@code Map.of(...)} 로 attribute 를 만들어 실행마다 키 순서가
     * 섞이므로 문자열 위치·순서에 기대는 구현은 금지다.
     *
     * <p>같은 서비스에 hello 가 2건 이상이면 {@code startTime} 이 가장 큰 것이 이긴다(BL-03).
     * attribute 부재 · 값 null · 공백 문자열은 그 hello 를 건너뛴다 → 기존 값이 그대로 유지된다.
     *
     * <p>⚠️ 알려진 한계 — 서로 다른 시작 알림은 각자 다른 수집 요청으로 들어오므로
     *    <b>요청 도착 순서가 보장되지 않는다.</b> 드물게 오래된 시작 알림이 늦게 도착해
     *    최신 버전 값을 옛 값으로 덮을 수 있다. 이것을 완전히 막으려면 시작 알림을 받은
     *    시각을 따로 저장해야 하는데, 그것은 컬럼을 하나 더 만드는 일이라 R19 가 범위에서
     *    뺐다(정체성 메타 1컬럼만 추가). <b>결함이 아니라 알려진 한계이고, 컬럼을 더해
     *    해결할 문제가 아니다.</b>
     */
    private static Map<String, String> extractAgentVersions(List<Span> traceSpans) {
        Map<String, String> versions = new HashMap<>();
        Map<String, Long> winnerStartTime = new HashMap<>();
        for (Span span : traceSpans) {
            if (!AGENT_HELLO_OPERATION.equals(span.operationName())) {
                continue;
            }
            String serviceName = span.serviceName();
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            Map<String, Object> attributes = span.attributes();
            if (attributes == null) {
                continue;
            }
            Object raw = attributes.get(AGENT_VERSION_ATTRIBUTE);
            if (raw == null) {
                continue;
            }
            String version = String.valueOf(raw).trim();
            if (version.isEmpty()) {
                continue;
            }
            Long best = winnerStartTime.get(serviceName);
            if (best == null || span.startTime() >= best) {
                versions.put(serviceName, version);
                winnerStartTime.put(serviceName, span.startTime());
            }
        }
        return versions;
    }

    /**
     * [Phase R12] AC-A5-1 — spans batchUpdate 1회. SQL 문자열·컬럼·INSERT OR REPLACE
     * 시맨틱은 v0.1 단건 버전과 동일 (G-12 — REPLACE 시맨틱 무변경).
     *
     * <p>// [Phase R25] AC-25-02-1/AC-25-02-3 — SQL 원문 인터닝의 <b>사전 패스</b> 한 줄이 앞에 붙는다.
     * // 아래 spans 쓰기 문장의 <b>SQL 문자열·컬럼은 한 글자도 안 바뀐다</b> —
     * // 참조를 전용 열이 아니라 {@code attributes_json} 안 예약 키에 두기로 한 결정(UD-2)의 결과다.
     * // ★이 설명에 그 문장의 첫 낱말들을 그대로 옮겨 적지 않는다 — 그것을 세는 가드(R25 봉인 SQL 축)가
     * // 자기를 설명하는 주석을 먼저 물어 "바뀌었다" 로 읽힌다. 가리킬 때는 축 이름으로만 쓴다.
     * // 사전 패스와 참조 쓰기는 <b>같은 청크 트랜잭션 안</b>이라 그 트랜잭션이 되감기면 원문 행과
     * // 참조가 <b>함께</b> 사라진다(유령 참조가 구조로 불가능 — 프로세스 캐시를 만들지 않는 이유다).
     */
    private void insertSpans(List<Span> spans) {
        persistStatements(spans);   // [Phase R25] AC-25-02-3 — 참조되는 행을 먼저, 참조를 나중에.
        List<Object[]> rows = spans.stream()
                .map(span -> new Object[]{
                        span.spanId(),
                        span.traceId(),
                        span.parentSpanId(),
                        span.serviceName(),
                        span.operationName(),
                        span.spanKind().name(),
                        span.startTime(),
                        span.endTime(),
                        span.status().name(),
                        serializeAttributes(span.attributes())
                })
                .toList();
        jdbc.batchUpdate(
                """
                        INSERT OR REPLACE INTO spans (
                            span_id, trace_id, parent_span_id, service_name, operation_name,
                            span_kind, start_time, end_time, status, attributes_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                rows
        );
    }

    /**
     * [Phase R12] AC-A5-1 — payloads 마스킹 적용 후 batchUpdate 1회.
     * 마스킹은 저장 전 1회 적용 구조 그대로 (BL-06 — 기존 payload 재마스킹 경로 없음).
     *
     * <p>// [Phase R25] AC-25-01-1/AC-25-01-2 — 본문을 행에 담지 않고 <b>가리키기만</b> 한다.
     * // {@code body} 자리에는 {@code null} 을 싣고 {@code body_hash} 를 한 칸 더 싣는다.
     * // 지문 대상은 <b>마스킹을 거치고 가드로 자른 뒤의 저장 바이트 그대로</b>다
     * // (mask → guard → hash 순서 — 사용자 명시 비협상 결정. 마스킹 전 원문은 어디에도 안 남는다).
     */
    private void insertPayloads(List<Span> spans) {
        List<Object[]> rows = new ArrayList<>();
        // [Phase R25] AC-25-01-4 — 이 청크의 **서로 다른 지문**만 모은다(같은 본문이 여러 번 와도 한 벌).
        //   LinkedHashMap 이라 삽입 순서가 유지돼 batch 순서가 실행마다 흔들리지 않는다.
        Map<String, BodyRef> bodies = new LinkedHashMap<>();
        for (Span span : spans) {
            if (span.payloads() == null) {
                continue;
            }
            for (Payload payload : span.payloads()) {
                // NFR-06 비협상: mask → guard 순서 (마스킹 회피 차단). mask 결과를 측정·절단한다.
                // [Phase R18] AC-02-1/NFR-04 — ReDoS deadline 초과(RegexTimeoutException)는 청크 tx 람다 안
                //   이 catch 로 흡수 → body 전체를 상수 "***" 로 degrade(부분결과·원문 저장 금지). 비throw 라
                //   람다가 정상 완료돼 청크는 commit(롤백 0). 사용자 명시 비협상 결정.
                String maskedBody;
                try {
                    maskedBody = maskingHolder.current().mask(payload.body(), payload.contentType());
                } catch (RegexTimeoutException e) {
                    maskedBody = REDOS_DEGRADE_BODY;
                    // [Phase R25] AC-25-04-1/D-R25-22 — ★이 줄 하나가 인자 **둘**을 싣는다(줄 7 · 인자 8).
                    //   spanId 는 계측기가, contentType 은 호스트 앱 응답 헤더가 정하는 값이라 둘 다 통제 밖이다.
                    log.warn("ReDoS deadline exceeded; payload degraded to full mask: spanId={} contentType={}",
                            sanitizeForLog(span.spanId()), sanitizeForLog(payload.contentType()));
                }
                // [Phase R13] AC-A1-1/AC-A1-2/AC-A1-5 — D-03 server-side truncate 가드.
                // 한도 초과 시 잘라 저장 + truncated=1. agent 가 정상 흐름에서 먼저 자르므로 보통 idle(안전망).
                PayloadGuard.Result guarded = PayloadGuard.guard(maskedBody, ingestProperties.maxPayloadBytes());
                // [Phase R25] AC-25-01-2/AC-25-01-3 — 지문은 **저장되는 값** 에서 뽑는다(guard 결과).
                //   본문이 없는 행(계측이 값을 못 잡은 빈 자리표)은 지문도 null 이고 본문 표에 아무것도
                //   안 만든다 — 정상 입력이지 위반이 아니다. 빈 문자열("")은 평범한 값이라 특별 취급하지 않는다.
                String storedBody = guarded.body();
                String bodyHash = storedBody == null ? null : sha256Hex(storedBody);
                if (bodyHash != null) {
                    bodies.putIfAbsent(bodyHash,
                            new BodyRef(storedBody, storedBody.getBytes(StandardCharsets.UTF_8).length));
                }
                rows.add(new Object[]{
                        span.spanId(),
                        payload.direction().name().toLowerCase(Locale.ROOT),
                        payload.contentType(),
                        // [Phase R25] AC-25-01-1 — 본문 자리는 언제나 null 이다. 실물은 payload_bodies 에 있고
                        //   읽는 쪽이 COALESCE(pb.body, p.body) 로 되돌린다(옛 행은 p.body 로 읽힌다).
                        null,
                        // size_bytes: server 가 절단했을 때만 mask 결과의 원본 byte 로 재계산해 덮어씀.
                        // 미발동 시 agent 가 보낸 sizeBytes 신뢰 — "자르기 전 원본 크기" 의미 보존 (AC-A1-5, D-A3).
                        guarded.truncated() ? guarded.sizeBytes() : payload.sizeBytes(),
                        // truncated: server 가 잘랐거나(신규) agent 가 이미 잘랐으면(기존) 1 — OR 보존 (AC-A1-5).
                        (guarded.truncated() || payload.truncated()) ? 1 : 0,
                        bodyHash
                });
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        // [Phase R25] AC-25-01-1 — ★본문 표를 **먼저** 쓴다(같은 트랜잭션 안). 참조가 먼저 생기는 코드는
        //   외래키가 꺼져 있어도 읽는 사람이 안전을 의심하게 만든다.
        persistPayloadBodies(bodies);
        jdbc.batchUpdate(
                """
                        INSERT INTO payloads (span_id, direction, content_type, body, size_bytes, truncated, body_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                rows
        );
    }

    /** [Phase R25] 한 청크 안에서만 사는 본문 값 묶음(지문 → 본문·바이트 수). 프로세스 캐시가 아니다. */
    private record BodyRef(String body, int bytes) {
    }

    /**
     * [Phase R25] AC-25-01-1/AC-25-01-4/AC-25-01-7 — 본문 표 쓰기. <b>이 청크에 새로 나온 지문만</b> 넣는다.
     *
     * <p>멱등이 <b>구조로</b> 성립한다: {@code body_hash} 가 PRIMARY KEY 이고 {@code INSERT OR IGNORE} 라
     * 같은 본문이 몇 번을 다시 와도 표에는 아무 일도 안 일어난다. 그래서 이 경로에 <b>새 DELETE 를 추가하지
     * 않는다</b> — 기존 {@code deletePayloadsForChunk} 의 delete-then-insert 봉인을 한 글자도 안 건드린다.
     *
     * <p>★<b>지문 충돌 대조의 한계</b>(막았다고 쓰지 않는다): 이미 있는 지문의 {@code body_bytes} 가 다르면
     * 경고만 남기고 <b>먼저 있던 본문을 지킨다</b>. 이 대조는 <b>크기가 다를 때만</b> 안다 — 크기가 같고
     * 내용만 다른 충돌은 못 잡는다. 틀리는 방향은 <b>안전한 쪽</b>(먼저 것 보존)이다.
     */
    private void persistPayloadBodies(Map<String, BodyRef> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<String> hashes = new ArrayList<>(bodies.keySet());
        List<Object[]> toInsert = new ArrayList<>();
        // 이미 있는 지문을 청크당 SELECT 로 한 번에 걷어낸다 — payload 마다 조회하지 않는다.
        Map<String, Long> existing = new LinkedHashMap<>();
        for (int start = 0; start < hashes.size(); start += HASH_IN_CHUNK_SIZE) {
            List<String> slice = hashes.subList(start, Math.min(start + HASH_IN_CHUNK_SIZE, hashes.size()));
            jdbc.query(
                    "SELECT body_hash, body_bytes FROM payload_bodies WHERE body_hash IN ("
                            + placeholders(slice.size()) + ")",
                    (RowCallbackHandler) rs -> existing.put(rs.getString("body_hash"), rs.getLong("body_bytes")),
                    slice.toArray());
        }
        for (Map.Entry<String, BodyRef> e : bodies.entrySet()) {
            Long storedBytes = existing.get(e.getKey());
            if (storedBytes != null) {
                if (storedBytes != e.getValue().bytes()) {
                    log.warn("payload body hash collision: hash={} storedBytes={} incomingBytes={}",
                            e.getKey(), storedBytes, e.getValue().bytes());
                }
                continue;   // 덮지 않는다 — 먼저 있던 본문을 지킨다.
            }
            toInsert.add(new Object[]{ e.getKey(), e.getValue().body(), e.getValue().bytes(), now });
        }
        if (toInsert.isEmpty()) {
            return;   // 넣을 것이 없으면 문장을 아예 안 돌린다(같은 본문이 다시 오면 아무 일도 안 일어난다).
        }
        jdbc.batchUpdate(
                """
                        INSERT OR IGNORE INTO payload_bodies (body_hash, body, body_bytes, first_seen_at)
                        VALUES (?, ?, ?, ?)
                        """,
                toInsert
        );
    }

    /**
     * [Phase R25] AC-25-02-1/AC-25-02-3 — SQL 원문 표 쓰기. 이 청크의 서로 다른 원문을 모아
     * <b>전부</b> {@code INSERT OR IGNORE} 한다 — 이미 있는 행은 SQLite 가 그냥 지나간다.
     *
     * <p>★[v0.7.0 첫 밤 정정 · 2026-09-06] 처음 구현은 {@link #persistPayloadBodies} 처럼 "있는 것을
     * {@code SELECT} 로 먼저 걷어내고 없는 것만 넣는" 모양이었다. 그 {@code SELECT} 가 <b>청크 트랜잭션의
     * 첫 문장</b>이 되면서 v0.6.3 에 없던 수신 유실이 났다(첫 야간 정리 4분 동안 108 청크):
     * <ul>
     *   <li>SQLite(WAL) 는 트랜잭션이 <b>읽기로 시작</b>하면 읽기 스냅샷을 잡고, 그 뒤 처음 쓰려 할 때
     *       그 사이 다른 writer 가 커밋했으면 {@code busy_timeout} 을 부르지 않고 <b>즉시</b>
     *       {@code SQLITE_BUSY} 를 돌려준다(스냅샷이 낡아 기다려도 소용없기 때문).</li>
     *   <li>v0.6.3 의 첫 문장은 {@code INSERT OR REPLACE INTO spans}(쓰기)라 잠금을 먼저 잡고 10초를
     *       기다렸다. 야간 정리 배치가 ≈290 ms 마다 커밋하는 동안 읽기-먼저 청크는 전부 즉시 실패했다.</li>
     * </ul>
     * 그래서 이 메서드는 <b>읽지 않는다</b>. 원문은 작고(수십 종 · 수 KB) 청크마다 다시 보내도 비용이 없다.
     * 본문 쪽({@link #persistPayloadBodies})의 {@code SELECT} 는 {@code insertSpans} 의 쓰기 <b>뒤</b>에 돌아
     * 트랜잭션이 이미 쓰기 잠금을 쥔 상태라 같은 문제가 없다 — 그 자리는 그대로 둔다.
     * ★청크 트랜잭션의 첫 문장은 언제나 쓰기여야 한다 — 이 앞에 {@code SELECT} 를 넣지 말 것
     * (시험 {@code SqlStatementInterningTest#chunkTransactionStartsWithAWriteStatement} 가 지킨다).
     *
     * <p>대조할 크기 값이 없으므로 충돌 경고도 없다 — <b>원문 자체가 열쇠</b>라 같은 지문이면 같은 원문이다.
     *
     * <p>★{@code apilens.stmt.ref} 를 <b>밖에서 이미 채워 보낸</b> span 은 여기서도 건너뛴다
     * (AC-25-02-5 ⓒ — 그 span 은 원문 그대로 저장된다).
     */
    private void persistStatements(List<Span> spans) {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Span span : spans) {
            String raw = internableStatementOf(span.attributes());
            if (raw != null) {
                statements.putIfAbsent(sha256Hex(raw), raw);
            }
        }
        if (statements.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Object[]> toInsert = new ArrayList<>(statements.size());
        for (Map.Entry<String, String> e : statements.entrySet()) {
            toInsert.add(new Object[]{ e.getKey(), e.getValue(), now });
        }
        // 읽기 없이 바로 쓴다 — 청크 트랜잭션의 첫 문장이 쓰기라야 busy_timeout 이 산다(위 javadoc).
        jdbc.batchUpdate(
                """
                        INSERT OR IGNORE INTO sql_statements (stmt_hash, statement, first_seen_at)
                        VALUES (?, ?, ?)
                        """,
                toInsert
        );
    }

    /**
     * [Phase R25] AC-25-02-5/AC-25-02-7 — 이 span 에서 인터닝할 SQL 원문. 대상이 아니면 {@code null}.
     *
     * <p>세 가지를 <b>한자리에서</b> 판정해 쓰기 경로와 사전 패스가 같은 답을 내게 한다:
     * <ol>
     *   <li>{@code db.statement} 가 <b>{@code String} 일 때만</b> 대상이다 — 밖에서 오는 JSON 은 어떤 타입이든 올 수 있다.</li>
     *   <li>예약 키를 <b>밖에서 이미 차지</b>했으면 손대지 않는다(원문 그대로 저장 — 새 도피 규약을 안 만든다).</li>
     *   <li>속성이 없거나 비었으면 대상이 아니다.</li>
     * </ol>
     */
    private static String internableStatementOf(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        if (attributes.containsKey(STMT_REF_ATTRIBUTE)) {
            return null;
        }
        Object raw = attributes.get(DB_STATEMENT_ATTRIBUTE);
        return raw instanceof String s ? s : null;
    }

    /**
     * [Phase R25] AC-25-01-2 — 지문 계산의 <b>단일 위임 진입점</b>. SHA-256 → 소문자 16진수 64자.
     *
     * <p>새 클래스도 새 의존성도 만들지 않는다 — JDK 표준({@code MessageDigest})만 쓰고, 같은 파일의
     * {@code sanitizeForLog} 전례를 따라 {@code private static} 헬퍼 하나로 둔다.
     * {@code SHA-256} 은 JDK 규격이 반드시 제공하는 알고리즘이라 아래 catch 는 도달하지 않는다.
     */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec but is missing", e);
        }
    }

    /** [Phase R25] {@code ?, ?, ...} — 개수만 문자열로 만든다(값은 언제나 바인딩). */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private void upsertTraceSummary(String traceId, List<Span> batchSpans, long receivedAt) {
        // 같은 trace의 spans가 여러 batch로 나눠 들어와도 traces 요약은 항상 전체 상태를
        // 반영하도록 spans 테이블을 다시 집계. (이번 batch도 이미 INSERT OR REPLACE 됨.)
        // sample-app 검증에서 SpanSender의 poll-arrival 즉시 drain 패턴이 매 advice exit마다
        // 별도 batch를 만들어 옛 정책(batch 단위 덮어쓰기)이 마지막 batch로 traces.span_count를
        // 1로 덮어쓰는 부작용 발견 — Phase E1 후속 fix.

        Map<String, Object> aggregate = jdbc.queryForMap(
                """
                        SELECT
                            MIN(start_time)                                            AS min_start,
                            MAX(end_time)                                              AS max_end,
                            COUNT(*)                                                   AS span_count,
                            COUNT(DISTINCT service_name)                               AS service_count,
                            SUM(CASE WHEN status = 'ERROR' THEN 1 ELSE 0 END)          AS error_count
                        FROM spans
                        WHERE trace_id = ?
                        """,
                traceId
        );

        Map<String, Object> rootInfo = jdbc.queryForMap(
                """
                        SELECT operation_name, service_name FROM spans
                        WHERE trace_id = ?
                        ORDER BY (CASE WHEN parent_span_id IS NULL THEN 0 ELSE 1 END) ASC,
                                 start_time ASC
                        LIMIT 1
                        """,
                traceId
        );

        // [Phase R23] R23/AC-02-2/R23/AC-02-3 — ★집계 null 가드. R23 이 트랜잭션을 걷어낸 뒤로
        //   위 두 SELECT 는 **서로 다른 스냅샷**을 볼 수 있다. 집계는 비었는데(MIN/MAX 가 NULL)
        //   대표 span 조회는 행을 보는 순서가 새로 열리고, 그때 아래 역참조가 NPE 를 던진다.
        //   NPE 는 호출부의 catch(DataAccessException) 을 **빠져나가 적재 500** 이 된다(I-07 위반).
        //   ★0 으로 채우지 않는다 — 시작 시각이 1970년인 가짜 흐름이 생긴다. 저장 자체를 건너뛴다.
        //   ★이 건너뛰기는 가드 누락이 아니라 **정상 경로**다: 예외를 안 던지고 요약 행도 안 만드는 것이 답이다.
        //   COUNT(*) 인 span_count·service_count 는 SQLite 에서 NULL 이 되지 않으므로 가드 대상이 아니다.
        //   자리 선택: **두 번째 SELECT 뒤**다. spans 가 진짜로 비어 있는 경우는 위 rootInfo 조회의
        //   EmptyResultDataAccessException 이 먼저 받으므로 **오늘 동작이 한 바이트도 안 바뀐다**.
        if (aggregate.get("min_start") == null || aggregate.get("max_end") == null) {
            traceSummaryDeferred.incrementAndGet();
            // 앞머리 토큰은 위 WARN 과 같다(R23/AC-01-2). 사유는 reason 필드로 갈린다.
            // [Phase R25] AC-25-04-1 — traceId 위생 처리(같은 앞머리 토큰의 다른 갈래 — 위 WARN 과 한 쌍).
            log.warn("trace summary deferred (self-heal only if more spans arrive for this trace): traceId={}"
                            + " reason=empty-aggregate deferredTotal={}",
                    sanitizeForLog(traceId), traceSummaryDeferred.get());
            return;
        }

        long startTime = ((Number) aggregate.get("min_start")).longValue();
        long endTime = ((Number) aggregate.get("max_end")).longValue();
        int spanCount = ((Number) aggregate.get("span_count")).intValue();
        int serviceCount = ((Number) aggregate.get("service_count")).intValue();
        long errorCount = aggregate.get("error_count") == null
                ? 0L
                : ((Number) aggregate.get("error_count")).longValue();
        boolean hasError = errorCount > 0;

        jdbc.update(
                """
                        INSERT OR REPLACE INTO traces (
                            trace_id, root_operation, service_name, start_time, duration_ms,
                            status, span_count, service_count, has_error, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                traceId,
                rootInfo.get("operation_name"),
                rootInfo.get("service_name"),
                startTime,
                endTime - startTime,
                hasError ? "ERROR" : "OK",
                spanCount,
                serviceCount,
                hasError ? 1 : 0,
                receivedAt
        );
    }

    /**
     * [Phase R25] AC-25-02-1/AC-25-02-2 — 속성 직렬화. <b>순수 함수로 남는다</b>(DB 접근 0) —
     * 표 쓰기는 {@link #persistStatements} 사전 패스가 이미 끝냈다.
     *
     * <p>★<b>원본 맵을 안 고친다.</b> 대상이면 <b>복사본</b>에서 {@code db.statement} 를 빼고
     * 예약 키에 지문을 넣는다. 이유가 둘이다 — 시험 픽스처가 {@code Map.of(...)}(불변)이고,
     * 같은 맵을 {@code extractAgentVersions} 도 읽는다.
     *
     * <p>조기 반환은 <b>그대로 앞에</b> 둔다 — 속성이 없거나 빈 span 에서 헛일이 돌지 않는다.
     */
    private String serializeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Map<String, Object> toStore = attributes;
        String internable = internableStatementOf(attributes);
        if (internable != null) {
            toStore = new LinkedHashMap<>(attributes);
            toStore.remove(DB_STATEMENT_ATTRIBUTE);
            toStore.put(STMT_REF_ATTRIBUTE, sha256Hex(internable));
        }
        try {
            return mapper.writeValueAsString(toStore);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize span attributes", e);
        }
    }

    private static void validate(IngestRequest request) {
        if (request == null || request.spans() == null || request.spans().isEmpty()) {
            throw new IllegalArgumentException("spans is required and must be non-empty");
        }
        for (Span s : request.spans()) {
            if (s.spanId() == null || s.spanId().isBlank()) {
                throw new IllegalArgumentException("each span must have spanId");
            }
            if (s.traceId() == null || s.traceId().isBlank()) {
                throw new IllegalArgumentException("each span must have traceId");
            }
            if (s.spanKind() == null) {
                throw new IllegalArgumentException("each span must have spanKind");
            }
            if (s.status() == null) {
                throw new IllegalArgumentException("each span must have status");
            }
            if (s.operationName() == null || s.operationName().isBlank()) {
                throw new IllegalArgumentException("each span must have operationName");
            }
            if (s.serviceName() == null || s.serviceName().isBlank()) {
                throw new IllegalArgumentException("each span must have serviceName");
            }
            if (s.endTime() < s.startTime()) {
                throw new IllegalArgumentException("span endTime must be >= startTime");
            }
        }
    }
}
