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
package io.apilens.server.retention;

import io.apilens.server.db.SqlitePragmas;
import io.apilens.server.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retention cleanup: deletes traces older than the resolved retention window,
 * in small batches, with explicit child-first deletes.
 *
 * <p>// [Phase R12] AC-A1-2/AC-A1-3/AC-A1-5 — D-04 비협상 verbatim: "기존 22GB DB 리셋은
 * // 사용자 수행 완료 (2026-06-10). 파이프라인이 운영 DB 파일을 추가로 삭제하지 말 것".
 * // 사용자 명시 비협상 결정. 본 서비스의 공간 회수는 전부 행 단위 DELETE + PRAGMA —
 * // 운영 DB **파일** 삭제/이동 경로 0. CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
 *
 * <p>// [Phase R12] A1 재해석 차단 (Design §0-2 비협상): cleanup 은 payloads → spans → traces
 * // **명시적 3단 DELETE**. V1 의 ON DELETE CASCADE 의존 코드 0 — PRAGMA foreign_keys 는
 * // OFF 유지 확정 (Design §2-A2-FK) 이므로 CASCADE 는 계속 장식이다.
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    /**
     * 배치당 trace 수 (Design §2-A1): SQLite 파라미터 바인딩 한계(구버전 999) 안전 마진 +
     * 실측 비율(traces:spans:payloads ≈ 1:2:3.1) 기준 배치당 ≈ 3,000행 — 배치 트랜잭션
     * 수십 ms 수준으로 장시간 writer 락 금지(NFR-04) 충족.
     *
     * <p>// [Phase R13] AC-D1-2 / E1 — 값 변경 없이 측정 조건 명문 (Design §7.3 / §8 D-E1):
     * // 배치 500 유지. 250 으로 낮추면 배치당 tx 는 짧아지나 배치 수가 2배가 되어 전체 cleanup
     * // 시간이 오히려 늘 수 있다. 성능 합격 기준은 단일 수치 단정이 아니라 cold(첫 기동 직후, page
     * // cache 비움) / warm(반복 실행) + DB 규모(1만/10만/100만 trace) 를 동반해 측정한다
     * // (R12 교훈: "cleanup <500ms" 단정이 cold 0.65~0.81s 에서 모호했음). 250 채택은 실측 후 backlog.
     */
    static final int RETENTION_DELETE_BATCH_SIZE = 500;

    /**
     * [Phase R20] R20/AC-08-1 — 배치 사이 양보 창(ms). 배치 tx 커밋으로 write lock 이 풀린 사이에
     * 다른 writer(ingest)/reader 가 끼어들 짬을 <b>보장</b>한다 — {@code Thread.yield()} 는 스케줄러
     * 힌트일 뿐이라 채택하지 않았다. cleanup 은 04:00 백그라운드 작업이라 배치당 +50ms 는 무해하고,
     * purge(수동)도 같은 경로라 두 호출 모두에 효과가 미친다. R17 ingest 청크 커밋과 동형 사상.
     */
    static final long BATCH_YIELD_MS = 50L;

    static final long DAY_MS = 86_400_000L;

    /**
     * ① 야간 정리 1회가 회수하는 free page 수 상한 — <b>페이지 개수</b> 단위.
     *
     * <p>// [Phase R22] R22/AC-01-1/R22/AC-01-3 — R22/AC-01-3 verbatim: "예산 기본값은
     * // {@code RETENTION_VACUUM_BUDGET_PAGES} <b>상수 한 곳</b>에 있고, 그 자리에 근거 주석이 달린다.
     * // 근거로 <b>실측 행</b>을 지목한다 (계산으로 채운 행이 아니다)". 사용자 명시 결정(OQ-1).
     * // CLAUDE.md '절대 변경하지 말아야 할 결정 사항 §2' (SQLite + Flyway) 인용 — 스키마 변경 0.
     *
     * <p>★★ <b>이 값은 "자동커밋" 과 한 쌍이다. 따로 고를 수 없다.</b> ★★
     * 회수 루프는 회수 PRAGMA 를 {@code TransactionTemplate} 으로 <b>묶지 않고</b>
     * 문장마다 자동커밋으로 돈다. 근거는 아래 <b>실측 2행</b>(운영 DB 사본 직접 측정 — 계산 값 아님):
     * <pre>
     *   예산 5,000 · 자동커밋 (채택)  → 회수 19.6 MB · WAL +56.1 MB · 루프 268 ms · 뒤이은 체크포인트 130 ms
     *   예산 5,000 · 트랜잭션 1개 묶음 → 회수 19.6 MB · WAL +22.3 MB · 루프 192 ms · 뒤이은 체크포인트 132 ms
     * </pre>
     * 묶음이 WAL·속도 둘 다 낫는데도 자동커밋을 고른 이유: 04:00 창에는 <b>ingest writer 가 살아 있다.</b>
     * 묶음은 192 ms 동안 쓰기 잠금을 통째로 잡고, 자동커밋은 5,000번 잡았다 놓는다. 이 서비스는
     * {@link #BATCH_YIELD_MS} 로 "배치 사이에 다른 writer 가 끼어들 짬을 보장" 하는 쪽을 이미 골랐고,
     * <b>잠금 보유 시간을 WAL 용량보다 우선</b>해 왔다. 같은 방향이다.
     *
     * <p>★ <b>예산 ≤ 5,000 에서만 자동커밋이 정당하다.</b> 실측상 자동커밋의 페이지당 WAL 은 예산과 함께
     * 커진다 (9.29 → 10.96 → 16.70 → 19.57 KB/page). <b>10,000 이상으로 올리려면 트랜잭션 묶음으로 함께
     * 바꿔야 한다</b> — 예산만 올리고 방식을 그대로 두면 WAL 이 초선형으로 뛴다(171 MB → 400.9 MB).
     * 20,000 이상으로 올리려면 (1) 트랜잭션 묶음으로 바꾸고 (2) {@link #RECLAIM_WAL_BYTES_PER_PAGE} 를
     * 다시 재야 한다 — 그 지점에서 이 상수의 마진이 0.97배로 사라진다.
     *
     * <p><b>이 예산이 못 하는 것</b> (막았다고 쓰지 않는다):
     * <ol>
     *   <li><b>전량 회수가 아니다.</b> 밀려 있는 잔량은 이 루프로 안 없어진다 — 수집기를 멈추고
     *       1회 수동 회수해야 한다 ({@code docs/api.md} 「밀린 빈 공간을 한 번에 돌려받기」 절).</li>
     *   <li><b>틀리는 방향</b>: 예산을 넘는 free page 는 <b>남는 쪽</b>으로 틀린다 (데이터가 지워지는
     *       쪽이 아니다). 안전한 방향이다.</li>
     *   <li><b>돌려준 공간은 영구가 아니다.</b> 트래픽이 늘면 파일이 다시 그만큼 자란다.</li>
     *   <li>"예산을 얼마까지 키워도 되는가" 는 아래 관측 로그 3점이 쌓인 뒤 판정한다.
     *       // [Phase R23] R23/AC-08-6 — ★그 판정을 했다: <b>현행 5,000 유지</b>.
     *       // 근거는 관측이다 — 2026-08-13 배포 이후 야간 실행 <b>7회</b>분의 {@code cleanup start} 로그에서
     *       // 삭제 전 빈 페이지 수가 <b>4회는 0</b> 이었다. 예산이 모자라서 남는 상태가 아니라 회수할 것
     *       // 자체가 없는 밤이 절반이라, 예산을 올려도 얻을 것이 없고 위 WAL 마진만 줄어든다.
     *       // 다시 판정할 조건: 이 로그의 삭제 전 빈 페이지 수가 <b>예산에 붙어 있는 밤이 이어질 때</b>.
     *       // 그때는 {@link #RECLAIM_WAL_BYTES_PER_PAGE} 를 다시 재고 트랜잭션 묶음 전환도 함께 본다.</li>
     * </ol>
     */
    static final int RETENTION_VACUUM_BUDGET_PAGES = 5_000;

    /**
     * ① 디스크 가드가 쓰는 페이지당 WAL 증가분 상계 — 20 KiB/page.
     *
     * <p>// [Phase R22] R22/AC-01-5 verbatim: "새 가드의 크기 계산식은 <b>선형식이 아니다</b>.
     * // 페이지당 WAL 이 예산과 함께 커지는 것이 실측됐으므로, 선형식으로 세우면 가드가 뚫린다."
     * // 사용자 명시 결정(A-2). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★ <b>이 값은 "한 점을 재고 곱한" 값이 아니다.</b> 3,000페이지에서 잰 9.30 KB/page 를 예산에
     * 곱하면 20,000페이지 예측이 182 MB 인데 실측은 400.9 MB 였다 (<b>2.2배 과소</b> — 가드가 뚫린다).
     * 대신 <b>실측 곡선의 최댓값 19.57 KB/page 를 20 으로 올린 상계</b>를 쓴다:
     * <pre>
     *   예산 2,000  → 실측 WAL 19.0 MB  · 가드 요구 39.1 MB  (2.06배 여유)
     *   예산 5,000  → 실측 WAL 56.1 MB  · 가드 요구 97.7 MB  (1.74배 여유)  ← 채택 예산
     *   예산 10,000 → 실측 WAL 171.0 MB · 가드 요구 195.3 MB (1.14배 여유)
     *   예산 20,000 → 실측 WAL 400.9 MB · 가드 요구 390.6 MB (0.97배 — ★여기서 마진이 사라진다)
     * </pre>
     *
     * <p>⚠️ <b>한계</b>: 이 상수는 실측 범위(≤20,000페이지)에서만 검증됐고, 20,000 에서는 이미 부족하다.
     * 또 이 실측은 <b>동시 적재·동시 조회가 없는 상태</b>다 — 04:00 창에는 ingest writer 가 살아 있어
     * WAL 은 더 커지는 방향이다. 채택 예산 5,000 의 1.74배 여유가 그 몫을 흡수하는 자리다.
     * <b>틀리는 방향</b>: 상수가 실제보다 크면 가드가 과하게 막는 쪽(회수를 건너뛰고 로그만) — 안전하다.
     */
    static final long RECLAIM_WAL_BYTES_PER_PAGE = 20L * 1024L;

    /**
     * ③ 스윕 1회에 후보로 삼는 고아 span 개수 상한.
     *
     * <p>// [Phase R23] R23/AC-08-8 — 이 블록의 유예 단위 표기 3곳을 <b>스윕 실행 횟수 기준</b>으로
     * // 통일했다. 사유: R23 이 스윕을 자체 스케줄로 떼어내 정리 실행 횟수와 스윕 실행 횟수가 갈렸다.
     * // 같은 파일에서 한 낱말이 두 뜻으로 쓰이는 것을 막으려고 한 편집에서 다 고쳤다.
     *
     * <p>// [Phase R22] R22/AC-03-10 verbatim: "후보 개수 상한({@code ORPHAN_CANDIDATE_CAP})과 초과 시
     * // 동작이 정해져 있다. 초과분을 그 밤에 버려도 고아는 안 사라지므로 다음 밤에 다시 후보가 된다 —
     * // 잃는 것이 없다". 사용자 명시 결정(D-2). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p><b>왜 500 인가</b> — 새 근거를 발명하지 않고 {@link #RETENTION_DELETE_BATCH_SIZE} 의 기존 근거를
     * 그대로 따랐다: SQLite 파라미터 바인딩 한계(구버전 999)의 안전 마진. 스윕의 DELETE 두 문장이 각각
     * 최대 500개 자리표시자를 만드는데 같은 마진 안이다. 행 크기로도 상한이 된다 — span_id 는 W3C 규격
     * 16자리 16진수라 500 × (16 + 구분자 1) ≈ <b>8.5 KB</b>. {@code settings} 한 행이 스윕 1회에 통째로 다시
     * 쓰이므로, 이 상한이 "이상 상황 한 번이 영구적으로 큰 행을 남기는 것" 을 막는다.
     *
     * <p><b>이 상한이 못 하는 것</b> (막았다고 쓰지 않는다):
     * <ol>
     *   <li>스윕 1회에 최대 500개만 후보가 된다. 고아가 5,000개인 밤이면 다 없애는 데 열흘 넘게 걸린다.</li>
     *   <li><b>틀리는 방향</b>: 못 잡은 고아는 <b>남는 쪽</b>이다 (지워지는 쪽이 아니다). 안전하다.</li>
     *   <li>후보 탐색이 상한에 걸리면 어젯밤 후보가 오늘 목록에서 빠질 수 있고, 그러면 그 span 은 삭제가
     *       한 주기 더 밀린다. 역시 남는 쪽이다.</li>
     * </ol>
     */
    static final int ORPHAN_CANDIDATE_CAP = 500;

    /**
     * ③ [전체 삭제] 즉시 스윕의 회전 안전 상한 (= 최대 {@code 20 × 500} = 10,000 span).
     *
     * <p>// [Phase R22] R22/AC-03-8 — [전체 삭제]는 2밤차를 기다리지 않고 즉시 지운다. 그 루프가
     * // 무한히 돌지 않도록 회전 수에 상한을 둔다. 넘으면 경고 후 종료 — 다음 purge/야간이 이어받는다.
     */
    static final int ORPHAN_PURGE_MAX_ROUNDS = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SettingsService settingsService;
    private final int batchSize;
    /**
     * ③ 후보 목록 단일 진입점. <b>생성자 본문에서 파생</b>한다 — bean 주입으로 만들면 이 생성자가
     * 5-인자가 되어 server 테스트 5곳의 호출부가 전부 깨진다 (진입점 시그니처 불변 봉인).
     */
    private final OrphanCandidateStore candidates;

    /**
     * ③ [Phase R23] R23/AC-05-6 — 야간 스윕이 <b>마지막으로 끝난 시각</b>(epoch millis, 인메모리).
     * 다음 스윕이 {@code now - 이 값} 을 {@code sinceLastNightlySweepMs} 로그 필드로 남겨,
     * 누군가 {@code orphan-sweep-cron} 을 줄여 <b>유예가 깎이는 것을 보이게</b> 만든다.
     *
     * <p>★ <b>삭제 판정에는 쓰지 않는다</b>(I-09). 후보 행의 {@code updated_at} 을 <b>읽지도 않는다</b> —
     * "판정에 안 쓴다" 보다 강한 상태다. DB 를 안 쓰는 이유가 하나 더 있다: [전체 삭제] 의 즉시 스윕이
     * 후보 목록을 비우며 {@code updated_at} 을 갱신하는데, 여기서 재려는 것은 <b>야간 스윕 간격</b>이라
     * 인메모리 쪽이 뜻이 더 정확하다.
     *
     * <p><b>한계</b>: 서버를 재시작하면 값이 없어 <b>첫 밤은 {@code unknown}</b> 이다.
     * 0 = "아직 없음" 이고 센티넬 숫자(-1 등)를 만들지 않는다 — 로그 필드라 문자열로 충분하고,
     * 센티넬을 만들면 표면 전염·상수화·표시 분기가 따라온다.
     */
    private final AtomicLong lastNightlySweepEndedAtMs = new AtomicLong(0L);

    @Autowired
    public RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                   SettingsService settingsService) {
        this(jdbc, txManager, settingsService, RETENTION_DELETE_BATCH_SIZE);
    }

    /** 테스트 전용 — 배치 루프 종료 검증을 위해 배치 크기 주입 (Design §7.1 배치 종료 행). */
    RetentionCleanupService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            SettingsService settingsService, int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.settingsService = settingsService;
        this.batchSize = batchSize;
        // [Phase R22] R22/AC-03-5 — ★생성자에서 jdbc 를 호출하지 않는다. optimize 경계 테스트가
        //   mock JdbcTemplate 을 넘기므로, 여기서 질의하면 그 테스트가 stub 부재로 깨진다.
        this.candidates = new OrphanCandidateStore(jdbc);
    }

    /** Cleanup 실행 결과 (관측/테스트용). */
    public record CleanupResult(int batches, int deletedTraces) {
    }

    public CleanupResult cleanup() {
        return cleanup(System.currentTimeMillis());
    }

    /**
     * 컷오프 = nowMs − resolveRetentionDays()×1일. 삭제 조건은 {@code received_at < cutoff}
     * (엄격 미만 — 경계값 cutoff 행은 보존, Design §7.1 확정).
     *
     * <p>배치 = 트랜잭션 경계: 배치 사이 트랜잭션을 닫아 ingest writer 에 양보 (락 분산).
     * 같은 트랜잭션 + SQLite 단일 writer 특성으로 3단 사이 집합 불변 — 고아 0 보장 (NFR-02).
     */
    CleanupResult cleanup(long nowMs) {
        // [Phase R22] R22/AC-01-6 관측 로그 ①/3 — 삭제 **전** freelist_count.
        //   ★이 값이 "주기 최솟값"(하룻밤 사이에 낮 적재가 재사용하고 남은 바닥) 이고, 다음 라운드가
        //   예산을 얼마까지 키워도 되는지 정하는 **유일한 입력**이다. 빠지면 영원히 모른다.
        logFreelistBeforeDelete();

        // [Phase R12] AC-A1-6 — D-05 resolve 경유 (settings DB 값 > yml fallback)
        long cutoffMs = nowMs - settingsService.resolveRetentionDays() * DAY_MS;

        // 배치 3단 DELETE 루프 (cleanup/purgeAll 공통) — cutoff 지정 = 만료분만 삭제.
        CleanupResult result = deleteInBatches(OptionalLong.of(cutoffMs));

        finalizeMaintenance(nowMs);

        log.info("retention cleanup finished: deletedTraces={} batches={} cutoffMs={}",
                result.deletedTraces(), result.batches(), cutoffMs);
        return result;
    }

    /**
     * Purge ALL traces/spans/payloads (manual "clear everything" action).
     *
     * <p>// [수동 정리] D-04 비협상 verbatim: "운영 DB **파일** 삭제/이동/재생성 절대 금지.
     * // 행 단위 DELETE + PRAGMA만으로 공간 회수". 사용자 명시 비협상 결정.
     * // cleanup 의 배치/트랜잭션/3단 DELETE 패턴을 그대로 재사용 — cutoff 없는 전체 삭제만 차이.
     * // CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' + RetentionCleanupService 클래스 주석 인용.
     */
    public CleanupResult purgeAll() {
        long now = System.currentTimeMillis();

        // cutoff 미지정(empty) = 전체 trace 가 삭제 대상.
        CleanupResult result = deleteInBatches(OptionalLong.empty());

        // [Phase R22] R22/AC-03-8 — ★finalizeMaintenance **앞**에 놓는다. 스윕이 만든 free page 를
        //   같은 실행의 ① 회수가 함께 돌려주기 때문이다. 예외는 자체 try-catch 가 흡수하므로
        //   아래 finalizeMaintenance 는 어떤 경우에도 실행된다.
        purgeOrphanSpansImmediately();

        finalizeMaintenance(now);

        log.info("manual purge-all finished: deletedTraces={} batches={}",
                result.deletedTraces(), result.batches());
        return result;
    }

    /**
     * 전체 VACUUM 으로 파일 조각을 회수한다 (삭제 없이 행 재구성). 신규 public 메서드 (Design §4.1).
     *
     * <p>// [Phase K] AC-07-1/AC-07-3/AC-07-4/AC-07-5 — R14-D06 사용자 명시 비협상 결정:
     * // 온라인 전체 VACUUM(수동 버튼). VACUUM 은 같은 경로/inode 의 행 재구성이라 파일 삭제 금지(D-04)와
     * // 충돌하지 않는다(NFR-05). 사용자 명시 비협상 결정. CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
     *
     * <p>// ★GT-5 비협상★: VACUUM 은 TransactionTemplate 안에서 실행 금지(SQLite: cannot VACUUM from
     * // within a transaction). cleanup/purge 의 배치 tx 패턴을 재사용하지 않고 jdbc.execute("VACUUM") 직접.
     *
     * @return busy — true 면 부분 실패(SQLITE_BUSY/FULL) 또는 디스크 부족 거부, false 면 정상 회수.
     */
    public boolean optimizeDatabase() {
        // 1. 디스크 여유 가드 — VACUUM 임시파일이 원본 크기만큼 추가 점유(2배)하므로 실행 전 거부 (BL-10, AC-07-4).
        if (!hasEnoughDiskForVacuum()) {
            log.warn("optimize skipped — insufficient disk (need >= DB size for VACUUM temp file)");
            return true; // busy 취급(거부) — FE 디스크 부족 토스트 (Design §4.5)
        }
        // 2. ★GT-5★ VACUUM 은 트랜잭션 밖에서 (TransactionTemplate 미사용).
        try {
            jdbc.execute("VACUUM");  // 전체 행 재구성 (같은 inode — NFR-05)
            checkpointWal();         // 기존 private 메서드 재사용 (wal_checkpoint TRUNCATE)
            return false;            // 정상
        } catch (Exception e) {
            // BL-11: SQLITE_BUSY / SQLITE_FULL 비전파 — 호스트로 던지지 않고 busy 상태에 반영 (AC-07-3/07-5).
            log.warn("VACUUM failed (busy or full) — host unaffected, state reflected", e);
            return true; // busy=true
        }
    }

    /**
     * VACUUM 실행 전 디스크 여유 가드 (FR-C3 — ★52GB 사고 직결★, BL-10).
     *
     * <p>// [Phase K] AC-07-4 — VACUUM 임시파일은 원본 DB 와 같은 디렉토리에 원본 크기만큼 생성되어
     * // 일시적으로 디스크를 약 2배 점유한다. 가용 디스크가 DB 크기보다 작으면 거부(SQLITE_FULL 2차 사고 차단).
     * // 임계는 비율 상수 아님 — 런타임 dbFile.length() 와 getUsableSpace() 동적 비교(>=, Design §4.2/§5).
     */
    private boolean hasEnoughDiskForVacuum() {
        File dbFile = resolveDbFile();
        if (dbFile == null) {
            // 경로 해석 실패(in-memory DB 등) — 가드를 통과시키되 VACUUM 실패는 catch 가 흡수.
            log.warn("optimize disk-guard skipped — DB file path unresolved (in-memory or driver limitation)");
            return true;
        }
        return hasEnoughDisk(dbFile.getUsableSpace(), dbFile.length());
    }

    /**
     * 디스크 여유 비교 핵심 (경계값 단위 테스트 진입점, [S-66] 임계 분기 봉인).
     *
     * <p>// [Phase K] AC-07-4 — VACUUM 임시파일이 원본 크기만큼 추가 점유(2배)하므로
     * // 가용 >= DB크기 일 때만 허용한다. 경계: 가용 == DB크기 → 허용(>=). 가용 < DB크기 → 거부 (Design §8.1 (C)).
     *
     * @param usableSpace DB 파일 디렉토리의 가용 디스크 byte
     * @param dbSize      현재 DB 파일 byte
     * @return 허용이면 true, 거부면 false
     */
    static boolean hasEnoughDisk(long usableSpace, long dbSize) {
        return usableSpace >= dbSize;
    }

    /**
     * 운영 DB 파일 경로 해석 — SQLite {@code PRAGMA database_list} 의 main DB file 경로(하드코딩 회피).
     *
     * <p>// [Phase K] AC-07-4 — datasource URL 의 apilens.db 경로를 드라이버에 직접 질의해 해석한다
     * // (런타임 해석 — Design §4.2). file 컬럼이 빈 문자열(in-memory) 이거나 매핑 불가면 null.
     */
    private File resolveDbFile() {
        try {
            // PRAGMA database_list → (seq, name, file). name='main' 행의 file 이 DB 파일 절대경로.
            List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA database_list");
            for (Map<String, Object> row : rows) {
                Object name = row.get("name");
                Object file = row.get("file");
                if ("main".equals(String.valueOf(name)) && file != null) {
                    String path = String.valueOf(file);
                    if (!path.isBlank()) {
                        return new File(path);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DB file path resolution failed — disk guard will be skipped", e);
        }
        return null;
    }

    /**
     * Batch loop shared by cleanup (cutoff present) and purgeAll (cutoff absent).
     * Each batch is its own transaction (writer 락 분산) + explicit 3-step child-first DELETE.
     *
     * <p>[Phase R20] R20/AC-08-1 — 2회차 배치부터 SELECT 직전에 {@link #BATCH_YIELD_MS} 만큼 정지
     * (배치 사이에만 — 첫 배치·종료 직전 빈 SELECT 앞엔 없음). 삭제 순서(payloads→spans→traces)·
     * SQL 문자열·배치 tx 경계 diff 0(AC-08-3). W-10 전건 준수(AC-08-2): setQueryTimeout 미사용 ·
     * 시간 상한 미도입(목적은 양보이지 상한이 아님) · org.sqlite.* main import 0 유지.
     *
     * @param cutoffMs present → {@code received_at < cutoff} 만 삭제. empty → 전체 삭제.
     */
    private CleanupResult deleteInBatches(OptionalLong cutoffMs) {
        int batches = 0;
        int deletedTraces = 0;
        while (true) {
            if (batches > 0) {
                sleepQuietly(BATCH_YIELD_MS);   // 배치 사이 양보 창 — 다른 writer/reader 가 끼어들 짬.
            }
            // cutoff 유무로 대상 선정만 분기 — cutoff 없으면 전체에서 오래된 순으로 배치.
            List<String> traceIds = cutoffMs.isPresent()
                    ? jdbc.queryForList(
                            """
                                    SELECT trace_id FROM traces
                                    WHERE received_at < ?
                                    ORDER BY received_at ASC
                                    LIMIT ?
                                    """,
                            String.class, cutoffMs.getAsLong(), batchSize)
                    : jdbc.queryForList(
                            """
                                    SELECT trace_id FROM traces
                                    ORDER BY received_at ASC
                                    LIMIT ?
                                    """,
                            String.class, batchSize);
            if (traceIds.isEmpty()) {
                break; // AC-A1-5: 대상 소진 = 종료 조건 (purgeAll 도 동일)
            }
            String in = String.join(",", Collections.nCopies(traceIds.size(), "?"));
            Object[] params = traceIds.toArray();
            tx.executeWithoutResult(status -> {
                // [Phase R12] AC-A1-3 — 명시적 3단 DELETE (CASCADE 의존 금지 — FK OFF, 비협상).
                // 1단: 자식의 자식(payloads) 먼저 → 2단: spans → 3단: traces.
                jdbc.update("DELETE FROM payloads WHERE span_id IN (SELECT span_id FROM spans WHERE trace_id IN ("
                        + in + "))", params);
                jdbc.update("DELETE FROM spans WHERE trace_id IN (" + in + ")", params);
                jdbc.update("DELETE FROM traces WHERE trace_id IN (" + in + ")", params);
            });
            batches++;
            deletedTraces += traceIds.size();
        }
        return new CleanupResult(batches, deletedTraces);
    }

    /**
     * [Phase R20] R20/AC-08-1 — 양보 sleep. {@code InterruptedException} 시 interrupt 플래그 복원 후
     * 즉시 진행한다(삭제 정확성 우선 — 배치 루프를 중단하지 않는다).
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Post-delete maintenance shared by cleanup and purgeAll:
     * stamp last_cleanup_at, reclaim free pages, truncate WAL, refresh stats.
     *
     * <p>// [Phase R13] AC-D1-2 — 디스크 회수의 구조적 한계 2가지 (D-04 파일 삭제 금지 전제):
     * // [Phase R22] R22/AC-01-10 — ★오진단 정정: 이전 서술 "incremental_vacuum 는 tail-only" 는 **틀렸다**.
     * // [Phase R22] ★오진단 정정: "free page 가 파일 끝에 모일 때만 회수한다" 도 **틀린 설명**이었다.
     * // (i) incremental_vacuum 은 비어 있는 페이지를 **전량 회수할 수 있다.** 회수가 안 보였던 진짜 이유는
     * //     드라이버가 이 PRAGMA 를 호출당 한 페이지만 진행시켰기 때문이다. 그래서 이제 예산만큼 반복해서
     * //     부른다 (reclaimFreePages). 남는 한계는 "한 번에 예산만큼만 회수한다" 이고, 밀린 잔량은
     * //     수집기를 멈추고 1회 수동 회수해야 없어진다.
     * //     (중간 단편화까지 재배치하는 full VACUUM 은 파일 재생성이라 D-04 로 금지 — 그대로다.)
     * // (ii) wal_checkpoint(TRUNCATE) 는 reader 경합(SQLITE_BUSY)에 취약 — 수동 purge 는 운영자가
     * //      화면을 보는 중(reader 활성) 실행될 확률이 높아 busy 로 부분 실패할 수 있다.
     * //      그 경우 다음 cleanup(nightly/수동)에서 자연 재시도된다 (재시도 코드 불요). ★이 서술은 그대로 참이다.
     */
    private void finalizeMaintenance(long nowMs) {
        // AC-A1-7: 정상 종료 시 항상 갱신 (삭제 0건 포함) — "마지막 실행 시각" 의미 (T-10).
        // [Phase R22] R22/AC-04-1 verbatim: "retention_meta 갱신이 **upsert** 다. SettingsService 의
        //   ON CONFLICT ... DO UPDATE 모양을 따라간다 (새 관용구를 만들지 않는다). retention_meta.id 가
        //   INTEGER PRIMARY KEY 라 ON CONFLICT(id) 가 성립한다."
        //   ★행이 사라진 운영 사고가 실재했다 — UPDATE 는 행이 없으면 0행 갱신으로 조용히 성공한다.
        //   ★이 문장은 **일부러 try-catch 로 감싸지 않는다**: 지금 UPDATE 도 실패하면 전파되고 그것이
        //     현재 계약이다. 여기를 삼키면 DB 쓰기 실패가 조용히 사라져, ④가 겪은 무음 실패를 새로 심는다.
        jdbc.update("""
                        INSERT INTO retention_meta (id, last_cleanup_at) VALUES (1, ?)
                        ON CONFLICT(id) DO UPDATE SET last_cleanup_at = excluded.last_cleanup_at
                        """, nowMs);

        // BL-03: incremental_vacuum — full VACUUM 경로 아님 (D-04: 파일 삭제/재생성 0).
        // [Phase R22] R22/AC-01-1/R22/AC-01-8 — 한 줄 호출이 **예산 제한 루프**가 된다. 순서상 자리는
        //   그대로다(위 (i) 정정 참조: 한계는 tail-only 가 아니라 **한 번에 예산만큼**이다).
        //   시각 기록(위 upsert) **바로 뒤** — 회수가 터져도 그 밤의 기록은 이미 남았다.
        reclaimFreePages();
        // [수동 정리] WAL truncate 미호출 시 -wal 파일이 안 줄어드는 문제 (디스크 회수 보강).
        // 순서: incremental_vacuum → wal_checkpoint(TRUNCATE) → ANALYZE. (순서 불변 봉인 — Design §2.D.1)
        // TRUNCATE 모드는 checkpoint 후 -wal 파일을 0 바이트로 잘라 디스크를 즉시 회수한다.
        checkpointWal();
        // AC-A4-3: cleanup 후 ANALYZE — 인덱스 통계 갱신.
        jdbc.execute("ANALYZE");
    }

    // ══ [Phase R22] ① 예산 제한 free page 회수 (신규 패턴 — 앵커 밀도 2배) ══════════════════════
    //
    //  신규 패턴 #3 "예산 제한 PRAGMA 루프". 이 패턴이 처음이라 되풀이 차단을 위해 아래 3가지를
    //  코드 옆에 남긴다. 다음 조사자가 여기부터 읽는다.
    //
    //   (ㄱ) **예산 차감이 호출 횟수가 아니라 freelist_count 차이다.** 지금 드라이버는 1호출 = 1페이지지만,
    //        그 1:1 은 드라이버 버전에 딸린 성질이라 다음 갱신에서 조용히 깨질 수 있다. 차이로 세면
    //        1호출 = N페이지가 되어도 예산이 지켜진다 (R22/AC-01-2).
    //   (ㄴ) **after >= free 즉시 종료가 무한 루프를 막는다.** auto_vacuum 이 INCREMENTAL 이 아니면
    //        incremental_vacuum 은 no-op 이라 freelist_count 가 안 줄어든다. 이 가드가 없으면 그 환경에서
    //        루프가 예산만큼 헛돈다. ★단위 테스트 DB 가 정확히 그 상태다(Flyway 만 돌아 auto_vacuum=NONE).
    //   (ㄷ) **회수 루프는 페이지 도메인만 다룬다.** 바이트 변환은 디스크 가드 한 곳에서만 일어난다.
    //
    //  ★회수 PRAGMA 를 부르는 곳은 이 메서드 한 곳뿐이다 (단일 위임 진입점 — G-05 로 검증).

    /**
     * ① 예산 제한 free page 회수. <b>예외는 이 메서드 밖으로 나가지 않는다.</b>
     *
     * <p>// [Phase R22] R22/AC-01-1/R22/AC-01-2/R22/AC-01-8 — R22/AC-01-8 verbatim: "회수 루프는
     * // {@code finalizeMaintenance} 안에서 <b>정리 시각 기록보다 뒤</b>에 놓이고, <b>자체 try-catch</b> 로
     * // 감싸인다. 여기서 나온 예외는 밖으로 나가지 않는다." 사용자 명시 결정(OQ-1).
     * // CLAUDE.md '아키텍처 핵심 원칙' (호스트 앱 영향 0 · 실패 시 silent drop) 과 같은 결의 방어.
     *
     * <p>잡은 뒤 그냥 돌아오므로 {@link #checkpointWal()}·{@code ANALYZE} 는 그대로 진행된다.
     * 수동 경로({@code MaintenanceController}, try-catch 없음)에도 500 이 나가지 않는다.
     */
    private void reclaimFreePages() {
        try {
            long free = readFreelistCount();
            // R22/AC-01-6 관측 로그 ②/3 — 삭제 **후** freelist_count + 예산. 이번 밤에 회수 가능했던 양.
            log.info("reclaim start: freelistPages={} budgetPages={}", free, RETENTION_VACUUM_BUDGET_PAGES);
            if (free <= 0) {
                return;                                  // 회수할 것이 없음 — 정상 종료.
            }
            if (!hasEnoughDiskForReclaimResolved()) {
                return;                                  // 가드 거부 (자체 warn 로그). 정리 자체는 성공으로 끝난다.
            }

            long budget = Math.min(free, RETENTION_VACUUM_BUDGET_PAGES);
            long reclaimed = 0;
            while (reclaimed < budget) {
                // 자동커밋 1문장 — TransactionTemplate 으로 묶지 않는다 (예산 상수 javadoc 의 "한 쌍" 근거).
                jdbc.execute("PRAGMA incremental_vacuum");
                long after = readFreelistCount();
                if (after >= free) {
                    break;                               // ★종료 조건 = freelist 재확인. 진행이 없으면 즉시 종료.
                }
                reclaimed += (free - after);             // ★예산은 "실제 줄어든 페이지" 로만 차감한다.
                free = after;
            }
            // R22/AC-01-6 관측 로그 ③/3 — 종료 후 freelist_count + 이번에 회수한 페이지 수(실제 회수량).
            log.info("reclaim done: reclaimedPages={} freelistPages={}", reclaimed, free);
        } catch (Exception e) {
            // 회수는 실패했지만 **정리 자체는 성공**했음이 드러나게 적는다 (시각은 이미 기록됐다).
            log.warn("free page reclaim failed — cleanup itself succeeded (timestamp already stamped)", e);
        }
    }

    /**
     * ① 관측 로그 ①/3 — 삭제 <b>전</b> {@code freelist_count} (주기 최솟값).
     *
     * <p>// [Phase R22] R22/AC-01-6 verbatim: "★①이 빠지면 예산을 얼마까지 키워도 되는지 다음 라운드가
     * // 영원히 모른다." 자체 try-catch — 관측 실패가 정리를 막지 않는다.
     *
     * <p>★ {@code purgeAll()} 에는 넣지 않는다 — purge 는 주기 작업이 아니라 "주기 최솟값" 의미가 성립하지
     * 않는다. 수동 [보관 기간 즉시 적용] 은 {@code cleanup()} 경유라 함께 찍히는데, 무해한 추가 데이터다.
     */
    private void logFreelistBeforeDelete() {
        try {
            // [Phase R23] R23/AC-16-1/R23/AC-16-2 — 앞머리 `cleanup start: freelistPagesBeforeDelete=` 는
            //   **고정**이고 필드는 뒤에만 덧붙인다(회수 예산 판정이 이 앞머리로 이뤄진다).
            //   ★단위: pageCount 는 **개수**, pageSize 는 **바이트/페이지** — **곱해야 바이트**다.
            // [Phase R23] R23/AC-16-3 — ★비교 경계: 이 시계열의 시작점은 **2026-08-13 전체 삭제 이후
            //   재축적분**이다. 다음 라운드(v0.7.0)의 근거 값(SQL 원문 중복 등)은 **그 이전 DB 에서 잰
            //   것**이라 **직접 빼면 안 된다.** 그리고 이 줄은 수동 [보관 기간 즉시 적용] 경로에서도
            //   찍히므로(바로 위 주석), 시계열을 읽을 때는 스레드 이름 `[scheduling-1]` 로 걸러
            //   야간 실행분만 남긴다.
            log.info("cleanup start: freelistPagesBeforeDelete={} pageCount={} pageSize={}",
                    readFreelistCount(), SqlitePragmas.pageCount(jdbc), SqlitePragmas.pageSize(jdbc));
        } catch (Exception e) {
            // [Phase R23] R23/AC-16-5 — 값이 셋으로 늘어도 이 자체 try-catch 가 그대로 남는다:
            //   관측 실패가 그날 정리를 막지 않는다.
            log.warn("freelist observation before delete failed — cleanup continues", e);
        }
    }

    /**
     * {@code PRAGMA freelist_count} — 회수 가능한 빈 페이지 수. 매핑 불가 시 0 (루프 미진입 = 안전한 방향).
     *
     * <p>// [Phase R23] R23/AC-07-3 — 본문만 {@link SqlitePragmas} 위임으로 바뀌었다.
     * // 이름·가시성·호출부는 그대로다(소비처 diff 0).
     */
    private long readFreelistCount() {
        return SqlitePragmas.freelistCount(jdbc);
    }

    /**
     * ① 회수 루프 실행 전 디스크 여유 가드 (런타임 경로).
     *
     * <p>// [Phase R22] R22/AC-01-4 verbatim: "야간 경로에 예산 크기에 맞는 <b>새 디스크 가드</b>가 있다.
     * // 기존 {@code hasEnoughDisk(long, long)} 과 {@code hasEnoughDiskForVacuum()} 은 <b>시그니처·본문
     * // 무변경</b>이고, {@code optimizeDatabase()} 전용으로 남는다." 사용자 명시 결정(A-2).
     *
     * <p>기존 가드를 왜 못 쓰는가: 그쪽은 <b>전체 VACUUM 용</b>이라 원본 크기만큼의 여유를 요구한다.
     * 19.6 MB 를 회수하려고 DB 전체 크기만큼의 여유를 요구하면 <b>과하게 막는다</b>.
     *
     * <p>{@code resolveDbFile()} 이 null 이면(in-memory 등) {@link #hasEnoughDiskForVacuum()} 과 같은
     * 관례로 <b>통과</b>시킨다 — 새 관례를 만들지 않는다.
     */
    private boolean hasEnoughDiskForReclaimResolved() {
        File dbFile = resolveDbFile();
        if (dbFile == null) {
            log.warn("reclaim disk-guard skipped — DB file path unresolved (in-memory or driver limitation)");
            return true;
        }
        long usable = dbFile.getUsableSpace();
        if (!hasEnoughDiskForReclaim(usable, RETENTION_VACUUM_BUDGET_PAGES)) {
            // 필요했던 여유 · 실제 여유를 함께 남긴다 (가드가 왜 거부했는지 로그만 보고 알 수 있게).
            log.warn("free page reclaim skipped — insufficient disk. needBytes={} usableBytes={} budgetPages={}",
                    RETENTION_VACUUM_BUDGET_PAGES * RECLAIM_WAL_BYTES_PER_PAGE, usable,
                    RETENTION_VACUUM_BUDGET_PAGES);
            return false;
        }
        return true;
    }

    /**
     * ① 디스크 여유 비교 핵심 (경계값 단위 테스트 진입점).
     *
     * <p>// [Phase R22] R22/AC-01-4/R22/AC-01-5 — 루프 중에는 DB 파일이 안 줄고 WAL 만 자란다
     * // (실측: page_count 297,724 → 277,699 인데 파일 크기 변동 0 B). 최대 점유 시점 = 루프 끝 =
     * // 기존 DB 크기 + WAL. DB 파일은 이미 그 자리를 차지하고 있으므로, <b>새로 필요한 여유는 WAL 증가분뿐</b>이다.
     *
     * <p>경계: 가용 == 필요 → <b>허용</b>({@code >=}). 기존 {@link #hasEnoughDisk} 와 같은 경계 규약을 따른다.
     *
     * @param usableSpace DB 파일 디렉토리의 가용 디스크 byte
     * @param budgetPages 이번 회수의 예산(페이지 수)
     * @return 허용이면 true, 거부면 false
     */
    static boolean hasEnoughDiskForReclaim(long usableSpace, long budgetPages) {
        return usableSpace >= budgetPages * RECLAIM_WAL_BYTES_PER_PAGE;
    }

    /**
     * [Phase R13] AC-D1-3 — wal_checkpoint(TRUNCATE) 결과 로깅 (재시도 안 함 — 한계 (ii)).
     *
     * <p>// PRAGMA wal_checkpoint(TRUNCATE) 는 단일 row 3컬럼 (busy, log, checkpointed) 을 반환한다.
     * // busy=1 이면 reader 경합으로 부분 실패 — 다음 cleanup 에서 자연 재시도되므로 로깅만 한다.
     * // queryForMap 매핑이 드라이버 사정으로 불가하면 execute fallback (busy 관측 생략, 가용성 우선).
     */
    private void checkpointWal() {
        try {
            java.util.Map<String, Object> ck = jdbc.queryForMap("PRAGMA wal_checkpoint(TRUNCATE)");
            Object busy = ck.get("busy"); // SQLite: 첫 컬럼명 "busy"
            if (busy instanceof Number n && n.intValue() != 0) {
                log.info("wal_checkpoint(TRUNCATE) busy — reader 경합, 부분 회수 (다음 cleanup 에서 재시도). result={}",
                        ck);
            }
        } catch (Exception e) {
            // queryForMap 매핑 실패(드라이버 차이) 또는 checkpoint 자체 실패 — execute fallback.
            // 디스크 회수 한계를 가용성보다 우선하지 않는다 (cleanup 종료를 막지 않음).
            log.warn("wal_checkpoint(TRUNCATE) result mapping unavailable — falling back to execute", e);
            try {
                jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Exception e2) {
                log.warn("wal_checkpoint(TRUNCATE) failed — continuing (디스크 회수 한계, 가용성 우선)", e2);
            }
        }
    }

    // ══ [Phase R22] ③ 고아 span 2밤차 스윕 (신규 패턴 — 앵커 밀도 2배) ═════════════════════════
    //
    //  신규 패턴 #1 "다밤(多夜) 상태 기계". 상태가 코드가 아니라 **DB 한 행**(settings 의 내부 키)에
    //  들어 있고, 스윕 1회에 한 칸씩만 움직인다([Phase R23] R23/AC-08-8 — 유예 단위를 스윕 실행
    //  횟수 기준으로 한정. R23 이 스윕을 자체 스케줄로 떼어내 정리 실행 횟수와 갈렸다).
    //  다음 조사자가 상태 전이를 코드에서 못 읽고 헤매지 않도록
    //  전이표를 여기 남긴다.
    //
    //    없음        → 1밤차 후보 : 쓰기 3단 candidates.write(todayOrphans \ toDelete)
    //    1밤차 후보  → 삭제       : 쓰기 1·2단 (yesterday ∩ todayOrphans)
    //    1밤차 후보  → 소멸(자가치유): 오늘 고아가 아니면 교집합에서 빠지고 새 목록에도 안 실린다
    //    삭제 실패   → 재후보     : 다음 밤 탐색이 다시 잡는다 (안전한 방향)
    //
    //  ★ nextCandidates = todayOrphans \ toDelete 인 이유: 오늘 지운 것을 내일 후보로 들고 가면
    //    이미 없는 span_id 를 계속 나른다. **오늘 처음 본 고아만** 내일의 후보다.
    //
    //  신규 패턴 #2 "호출 출처에 따라 다르게 도는 공유 메서드" 는 **일부러 만들지 않았다.**
    //    (ㄲ) cleanup(long nowMs) 에 출처 플래그를 넘겨 분기하는 안은 **기각**됐다:
    //      (1) "실수로 true 를 넘기는" 경로가 열리고 (2) 공유 메서드가 출처별로 다르게 도는 첫 사례를 만들며
    //      (3) cleanup(long) 시그니처가 바뀌어 server 테스트 5곳의 호출부가 흔들린다.
    //    대신 (ㄱ) 호출처 분리를 채택했다.
    //  ★ [Phase R23] R23/AC-05-1 — 강제 수단이 바뀌었다. R22 때는 "호출처가 RetentionCleanupJob 단
    //    하나인 것" 이 강제 수단이었는데, R23 이 그 호출을 빼고 스윕에 자체 스케줄을 줬다(정리 주기를
    //    줄여도 고아 유예가 안 깎이게). 지금 강제하는 것은 **G-07 grep + 행위 테스트
    //    OrphanSweepTest.keepsOrphansUntouchedWhenTheManualCleanupRunsTwice 한 쌍**이고,
    //    그중 **정본은 행위 테스트**다 — 파일 단위 검색만으로는 이 봉인을 못 지킨다.
    //  ★ 그래도 **패턴 #2 금지는 그대로다**: 다음 라운드가 이 메서드를 cleanup() 안으로 옮기거나
    //    출처 플래그 분기를 되살리지 말 것. 분리한 스케줄을 다시 합치는 것도 같은 금지에 든다.

    /**
     * ③ 야간 전용 고아 span 스윕 — <b>이틀에 걸쳐 확인하고 지운다.</b>
     *
     * <p>// [Phase R22] R22/AC-03-2/R22/AC-03-3/R22/AC-03-7/R22/AC-03-11 — R22/AC-03-7 verbatim:
     * // "수동 <b>[지난 데이터 정리 / 보관 기간 즉시 적용]</b> 경로는 후보 상태를 <b>읽지도 쓰지도 않는다.</b>
     * // ⇒ 이 버튼을 연달아 두 번 눌러도 고아 삭제는 0 이다."
     * // <b>사용자 명시 비협상 결정</b>(U-1). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     * // ★ [Phase R23] R23/AC-05-1 — 그 문장의 <b>"밤" 은 이제 "스윕 스케줄 실행 1회"</b> 로 한정된다.
     * // 정리와 스윕이 별개 스케줄 키로 갈렸으므로 정리 실행 횟수와 더 이상 같지 않다.
     *
     * <p>★ [Phase R23] R23/AC-05-1/R23/AC-08-1/R23/AC-08-2 — <b>이 메서드는 자기 스케줄로 돈다</b>
     * (키 {@code apilens.retention.orphan-sweep-cron}, 기본 04:00 — 정리와 같은 시각이지만 키가 별개다).
     * 수동 경로가 후보 상태에 닿지 않는다는 결론을 지키는 <b>강제 수단은 다음 한 쌍</b>이다:
     * <ol>
     *   <li>G-07 grep — {@code MaintenanceController} 안에 {@code sweepOrphan}/{@code OrphanCandidate}
     *       /{@code orphanCandidates} <b>0 hit</b>. 사정권은 <b>cleanup·purge·optimize·status 경로</b>다
     *       (그 컨트롤러가 여는 표면 전부). <b>비협상</b>.</li>
     *   <li>행위 테스트 {@code OrphanSweepTest.keepsOrphansUntouchedWhenTheManualCleanupRunsTwice}
     *       — <b>이쪽이 정본</b>이다. <b>파일 단위 검색만으로는 이 봉인을 못 지킨다</b> — 다른 파일을 한 번
     *       거쳐 부르면 grep 은 그대로 통과한다.</li>
     * </ol>
     *
     * <p>★ [Phase R23] R23/AC-05-3 — <b>정리와 스윕의 실행 순서는 결과를 바꾸지 않는다.</b>
     * 정리는 {@code SELECT trace_id FROM traces} 로만 대상을 고르고 payloads → spans → traces 를 한
     * 트랜잭션에서 지운다 ⇒ <b>고아 집합을 새로 만들지도 없애지도 못한다.</b> 그래서 어느 쪽이 먼저
     * 돌든 그 밤의 고아 집합은 같다. 달라지는 것은 <b>비용뿐</b>이다 — 스윕이 먼저 돌면
     * {@code detectOrphanSpans} 가 훑는 {@code spans} 행이 대략 2배가 된다(보관 기간 하루치가 아직
     * 안 걷힌 상태). {@code traces.trace_id} 가 PRIMARY KEY 라 안쪽은 인덱스 조회다.
     * ⚠️ <b>실행 순서에 기대는 문장을 남기지 않는다</b> — 두 스케줄의 순서는 미정이다
     * (스케줄 스레드가 1개라 동시 실행은 구조로 배제된다 — {@link RetentionCleanupJob} 클래스 javadoc).
     *
     * <p>★ [Phase R23] R23/AC-05-5 — <b>이 메서드는 자기 {@code try-catch} 하나로 격리된다.</b>
     * {@link RetentionCleanupJob} 이 감싸 주던 두 번째 그물은 R23 에서 호출과 함께 사라졌다.
     * (같은 사실이 본문 {@code catch} 블록 옆에도 적혀 있다 — 넓은 서술은 여기, 정확한 한정은 그 자리다.)
     *
     * <p>★ [Phase R23] R23/AC-05-4 — <b>적용 범위 한계</b>: 유예를 <b>시간 기반으로 만든 것이 아니다.</b>
     * 유예는 여전히 <b>"스윕 스케줄 실행 1회"</b> 이고, 누군가 {@code orphan-sweep-cron} 자체를 줄이면
     * <b>유예는 똑같이 깎인다.</b> 달라진 것은 셋뿐이다 — ① 정리 주기를 줄이는 것만으로는 안 깎인다
     * ② 키 이름이 자기가 무엇을 지배하는지 말한다 ③ 아래 {@code sinceLastNightlySweepMs} 로그가
     * 깎임을 보이게 만든다. <b>"이제 안전하다" 로 읽지 말 것.</b>
     *
     * <p>★ <b>이 스윕이 고치지 못하는 것</b>: 스윕은 <b>증상을 치우는 것이지 원인을 고치는 것이 아니다.</b>
     * // [Phase R23] R23/AC-08-4 — ★현행화: 고아를 만드는 <b>가장 큰 원인</b>(요약 저장의 쓰기 승격 실패)은
     * // <b>R23 에서 처리했다</b> — 요약 경로의 읽기 선행 트랜잭션을 걷어내 그 실패 경로 자체를 없앴다
     * // ({@code IngestService.persistTrace}). 남는 것은 <b>다른 이유로 실패하는 요약</b>이 만드는 고아뿐이고
     * // 그쪽은 여전히 이 스윕 몫이다. <b>"고아 span 은 0 이다" 를 단정으로 쓰지 않는다.</b>
     */
    // [Phase R23] R23/AC-05-1 — 자체 스케줄. 기본값은 정리와 같은 04:00 이지만 **키가 별개**다.
    @Scheduled(cron = "${apilens.retention.orphan-sweep-cron:0 0 4 * * *}")
    public void sweepOrphanSpansNightly() {
        try {
            // ── 트랜잭션 밖 (읽기) ──────────────────────────────────────────────
            List<String> todayOrphans = detectOrphanSpans(ORPHAN_CANDIDATE_CAP);
            List<String> yesterday = candidates.read();

            Set<String> todaySet = new LinkedHashSet<>(todayOrphans);
            // ★교집합 — 어젯밤 후보였고 오늘도 고아인 것만 지운다. 저장해 둔 후보를 그대로 지우지 않는다.
            List<String> toDelete = yesterday.stream().filter(todaySet::contains).distinct().toList();
            Set<String> deleteSet = new LinkedHashSet<>(toDelete);
            // ★오늘 처음 발견된 고아만 내일의 후보다 (오늘 지운 것은 안 나른다).
            List<String> nextCandidates = todayOrphans.stream().filter(id -> !deleteSet.contains(id)).toList();

            // ── 트랜잭션 1개 (쓰기) ─────────────────────────────────────────────
            // R22/AC-03-11: 스윕과 후보 기록은 **한 트랜잭션**이다. 따로 두면 삭제만 커밋되거나
            //   후보만 갱신되는 어긋남이 생긴다.
            int[] deleted = new int[2];
            tx.executeWithoutResult(status -> {
                int[] counts = deleteOrphanSpans(toDelete);
                deleted[0] = counts[0];
                deleted[1] = counts[1];
                candidates.write(nextCandidates);
            });

            if (todayOrphans.size() >= ORPHAN_CANDIDATE_CAP) {
                log.warn("orphan candidate cap reached: cap={} — the rest become candidates again on the next night",
                        ORPHAN_CANDIDATE_CAP);
            }
            // ★로그에 span_id 를 나열하지 않는다 — **개수만** 남긴다 (식별자 자체가 운영 정보다).
            // [Phase R23] R23/AC-05-6 — ★경과 시간은 **로그로만** 남긴다. 유예가 깎이는 것이 보이게
            //   하려는 장치이고, **삭제 판정에는 쓰지 않는다**(후보 행의 updated_at 은 읽지도 않는다).
            //   값이 없는 첫 실행은 `unknown` — 센티넬 숫자(-1 등)를 만들지 않는다(표면 전염 방지).
            long sweepEndedAt = System.currentTimeMillis();
            long previousEnd = lastNightlySweepEndedAtMs.get();
            String sinceLastNightlySweepMs = previousEnd == 0L
                    ? "unknown"
                    : String.valueOf(sweepEndedAt - previousEnd);
            log.info("orphan sweep finished: deletedSpans={} deletedPayloads={} candidatesRecorded={} sinceLastNightlySweepMs={}",
                    deleted[1], deleted[0], nextCandidates.size(), sinceLastNightlySweepMs);
            lastNightlySweepEndedAtMs.set(sweepEndedAt);
        } catch (Exception e) {
            // 후보 값이 깨져 있든, 탐색이 실패하든, 삭제가 실패하든 밖으로 안 나간다.
            // ★ [Phase R23] R23/AC-05-5 — 이 try-catch 는 이제 **유일한 코드 그물**이다.
            //   R22 때는 RetentionCleanupJob 의 두 번째 try-catch 가 2겹째였는데, R23 이 그 호출을 빼면서
            //   함께 사라졌다. **"RetentionCleanupJob 이 감싸 주니 중복" 이라고 판단해 지우지 말 것.**
            log.error("orphan span sweep failed — will retry at next schedule", e);
        }
    }

    /**
     * ③ [전체 삭제] 직후의 즉시 스윕 — <b>2밤차 유예 없음</b>.
     *
     * <p>// [Phase R22] R22/AC-03-8 verbatim: "<b>[전체 삭제]({@code purgeAll})는 2밤차를 기다리지 않고
     * // 즉시 지운다.</b> 그리고 후보 목록도 <b>비운다</b> (전부 지운 뒤 이미 없는 span_id 를 후보로 들고
     * // 다음 밤을 시작하지 않게 한다)." 사용자 명시 결정(D-2).
     *
     * <p>왜 필요한가: {@code purgeAll()} 의 배치 루프는 {@code SELECT trace_id FROM traces} 로 대상을
     * 고르므로 <b>traces 행이 없는 고아 span 은 절대 못 잡는다.</b> 그러면 "전체 삭제" 를 눌러도 고아가
     * 남아 <b>그 조작의 이름과 계약이 거짓</b>이 된다.
     *
     * <p>수동·의도적·되돌릴 수 없는 조작이라 유예를 걸지 않는다 — 2밤차 유예는 야간 정리에만 건다.
     */
    private void purgeOrphanSpansImmediately() {
        try {
            int rounds = 0;
            int totalSpans = 0;
            int totalPayloads = 0;
            while (rounds < ORPHAN_PURGE_MAX_ROUNDS) {
                List<String> orphans = detectOrphanSpans(ORPHAN_CANDIDATE_CAP);
                if (orphans.isEmpty()) {
                    break;
                }
                if (rounds > 0) {
                    sleepQuietly(BATCH_YIELD_MS);   // 회전 사이 양보 — 배치 루프와 같은 관례.
                }
                int[] counts = new int[2];
                tx.executeWithoutResult(status -> {
                    int[] c = deleteOrphanSpans(orphans);
                    counts[0] = c[0];
                    counts[1] = c[1];
                });
                totalPayloads += counts[0];
                totalSpans += counts[1];
                rounds++;
            }
            if (rounds >= ORPHAN_PURGE_MAX_ROUNDS) {
                log.warn("orphan purge stopped at the round cap: rounds={} — the next purge or nightly sweep continues",
                        ORPHAN_PURGE_MAX_ROUNDS);
            }
            // 이미 없는 span_id 를 후보로 들고 다음 밤을 시작하지 않게 후보 목록을 비운다.
            tx.executeWithoutResult(status -> candidates.write(List.of()));
            log.info("orphan purge finished: deletedSpans={} deletedPayloads={} rounds={}",
                    totalSpans, totalPayloads, rounds);
        } catch (Exception e) {
            log.error("orphan span purge failed — purge itself succeeded, nightly sweep will pick it up", e);
        }
    }

    /**
     * ③ 고아 span <b>탐색</b> — {@code traces} 에 행이 없는 {@code spans} 를 최대 {@code limit} 건 찾는다.
     *
     * <p>// [Phase R22] R22/AC-03-1 verbatim: "고아 판정 기준은 <b>기준 A</b> — {@code spans} 중
     * // {@code trace_id} 가 {@code traces} 에 없는 행. 이 뜻은 {@code RetentionCleanupServiceTest} 의
     * // {@code SELECT COUNT(*) FROM spans WHERE trace_id NOT IN (SELECT trace_id FROM traces)} 단언식과
     * // 같다. <b>새 정의를 만들지 않는다.</b>"
     *
     * <p>{@code NOT IN} 대신 {@code NOT EXISTS} 를 쓴 이유는 <b>성능 하나뿐</b>이다 — 상관 서브쿼리가
     * {@code traces} PK 인덱스를 직격한다. {@code spans.trace_id} 는 {@code NOT NULL} 이고
     * {@code traces.trace_id} 는 PK 라 <b>NULL 함정이 없어</b> 두 형태의 결과가 같다.
     *
     * <p>★ 이 문장은 <b>탐색</b>이다. 자식부터 지우는 <b>삭제</b> 순서와 낱말을 구분해 쓴다 (R22/AC-03-4).
     *
     * <p>★ <b>{@code spans} 전수 훑기는 "집계는 {@code traces} 기점" 규칙의 구조적 예외다.</b>
     * 그 규칙은 <b>화면에 보여 줄 집계</b>의 규칙이다(창·서비스·정렬이 전부 {@code traces} 에 있어 거기서
     * 시작해야 인덱스가 듣는다). 그런데 여기서 찾는 것은 <b>{@code traces} 에 행이 없는 span</b> 이라,
     * {@code traces} 를 기점으로 잡으면 <b>찾을 수 있는 집합이 공집합</b>이다 — 규칙 위반이 아니라
     * <b>규칙이 다루는 대상이 아닌 것</b>이다. 이 예외의 사정권은 <b>이 메서드 한 곳</b>이고,
     * 화면 집계 쿼리는 전부 {@code traces} 기점 그대로다. 다음 검토가 "traces 기점으로 바꾸자" 를
     * 넣지 않도록 여기 적어 둔다 (그 수정은 성립 불가능하다).
     */
    private List<String> detectOrphanSpans(int limit) {
        return jdbc.queryForList(
                """
                        SELECT s.span_id FROM spans s
                         WHERE NOT EXISTS (SELECT 1 FROM traces t WHERE t.trace_id = s.trace_id)
                         LIMIT ?
                        """,
                String.class, limit);
    }

    /**
     * ③ 고아 span <b>삭제</b> — 반드시 {@code payloads} → {@code spans} 순서.
     *
     * <p>// [Phase R22] R22/AC-03-4 verbatim: "<b>삭제 순서는 {@code payloads} → {@code spans}</b> 다.
     * // (비협상) 기존 payload DELETE 가 {@code spans} 를 경유해 대상을 찾으므로, {@code spans} 를 먼저
     * // 지우면 그 payload 를 찾을 길이 사라져 <b>영구히</b> 남는다."
     * // <b>사용자 명시 비협상 결정</b>. CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★ <b>두 DELETE 문 모두에 고아 술어를 다시 넣는다.</b> 탐색과 쓰기 트랜잭션 사이(수 밀리초)에
     * 요약이 도착해 <b>되살아난 span 을 죽이는 창</b>을 닫는다. 술어를 두 문장에 다 넣었으므로 되살아난
     * span 은 어느 문장에도 안 걸린다. 두 문장이 <b>같은 트랜잭션</b> 안이고 SQLite 는 단일 writer 라,
     * 첫 쓰기 문장부터 쓰기 잠금을 잡아 그 사이 다른 writer 가 커밋할 수 없다 — 술어 판정이 어긋나지 않는다.
     * <b>id 목록만으로 지우지 말 것.</b>
     *
     * <p>★ <b>바인딩만 쓴다</b> — 자리표시자만 문자열로 만들고 값은 전부 파라미터로 넘긴다
     * (기존 배치 DELETE 관례 그대로). 문자열로 이어 붙이는 경로 0.
     *
     * @return {@code [삭제된 payload 수, 삭제된 span 수]}
     */
    private int[] deleteOrphanSpans(List<String> spanIds) {
        if (spanIds.isEmpty()) {
            return new int[]{0, 0};
        }
        String in = String.join(",", Collections.nCopies(spanIds.size(), "?"));
        Object[] params = spanIds.toArray();

        // 1단 — payloads. 기존 3단 DELETE 의 "spans 를 경유해 대상을 찾는" 모양을 그대로 따른다.
        int payloads = jdbc.update(
                "DELETE FROM payloads WHERE span_id IN ("
                        + "SELECT s.span_id FROM spans s WHERE s.span_id IN (" + in + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM traces t WHERE t.trace_id = s.trace_id))",
                params);
        // 2단 — spans. 같은 고아 술어를 다시 확인한다.
        int spans = jdbc.update(
                "DELETE FROM spans WHERE span_id IN (" + in + ")"
                        + " AND NOT EXISTS (SELECT 1 FROM traces t WHERE t.trace_id = spans.trace_id)",
                params);
        return new int[]{payloads, spans};
    }
}
