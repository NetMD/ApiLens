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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for retention cleanup.
 *
 * <p>// [Phase R12] AC-A1-1/AC-A1-8 — cron 기본 매일 04:00 (yml `apilens.retention.cleanup-cron`
 * // 으로 변경 가능 — settings(DB) 비노출, Design §2-A1). 예외 격리: cleanup 실패가 서버·ingest 에
 * // 영향 0 — 다음 주기 자연 재시도 (E-06). 스케줄 스레드는 Spring 기본 1개 — 중첩 실행 없음.
 *
 * <p>// [Phase R23] R23/AC-05-1 — 고아 span 스윕은 이 클래스가 아니라
 * // {@code RetentionCleanupService.sweepOrphanSpansNightly()} 자신의 {@code @Scheduled}
 * // (키 `apilens.retention.orphan-sweep-cron`, 기본값도 04:00)로 돈다.
 * // ★스케줄 스레드가 1개라 두 스케줄은 <b>절대 겹쳐 돌지 않는다</b> — 실행 순서는 미정이지만
 * // 동시 실행이 구조로 배제되므로 새 잠금 경합은 생기지 않는다. 순서가 결과를 바꾸지 않는 이유는
 * // 스윕 메서드 javadoc 의 「순서 무관」 문단에 있다.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final RetentionCleanupService service;

    public RetentionCleanupJob(RetentionCleanupService service) {
        this.service = service;
    }

    /**
     * [Phase R23] R23/AC-05-1/R23/AC-05-2 — ★고아 span 스윕이 <b>여기서 빠졌다.</b> 스윕은 이제 자기
     * 스케줄 키({@code apilens.retention.orphan-sweep-cron})로 돌고, 이 메서드는 정리만 부른다
     * (두 번 도는 것 방지). 기본 시각은 정리와 같은 04:00 이지만 <b>키가 별개</b>라, 정리 주기를 줄이는
     * 것만으로는 고아 유예가 깎이지 않는다.
     *
     * <p>[Phase R22] R22/AC-03-7 은 유예의 단위를 <b>야간 정리 실행 횟수</b>로 적었는데, R23 뒤로 그 단위는
     * <b>스윕 스케줄 실행 횟수</b>로 한정된다(정리 실행 횟수와 더 이상 같지 않다).
     * 수동 [보관 기간 즉시 적용] 을 연달아 두 번 눌러도 <b>고아 삭제가 0 인 것은 그대로 참</b>이다
     * (U-1 — <b>사용자 명시 비협상 결정</b>). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★ 그 결론을 지키는 <b>강제 수단</b>은 이제 다음 <b>한 쌍</b>이다 — 호출처가 하나라는 사실이
     * 아니다(호출이 없어졌다).
     * <ol>
     *   <li>G-07 grep: {@code MaintenanceController} 안에 {@code sweepOrphan}/{@code OrphanCandidate}
     *       /{@code orphanCandidates} <b>0 hit</b> (cleanup·purge·optimize·status 경로에서 후보 상태로
     *       가는 코드가 아예 없다는 뜻) — <b>비협상</b>.</li>
     *   <li>행위 테스트 {@code OrphanSweepTest.keepsOrphansUntouchedWhenTheManualCleanupRunsTwice}
     *       — <b>이쪽이 정본</b>이다. 파일 단위 검색만으로는 이 봉인을 못 지킨다(다른 파일을 거쳐
     *       부르면 grep 은 통과한다).</li>
     * </ol>
     *
     * <p>★ {@code service.cleanup()} 호출은 <b>그대로 둔다.</b> 이 호출 대상을 다른 이름으로 바꾸면
     * {@code RetentionCleanupServiceTest} 의 익명 하위 클래스 재정의({@code cleanup()} override)가
     * <b>여전히 통과하면서 아무것도 검증하지 않는 상태</b>가 된다.
     *
     * <p>★ [Phase R23] try-catch 는 이제 <b>하나</b>다. 스윕은 자기 스케줄로 돌고 <b>자기 안쪽
     * try-catch 하나로</b> 격리된다 — 그래서 {@code RetentionCleanupService.sweepOrphanSpansNightly()}
     * 안의 그 try-catch 를 "Job 이 감싸 주니 중복" 이라고 판단해 <b>지우지 말 것</b>. 그것이 유일한 그물이다.
     */
    @Scheduled(cron = "${apilens.retention.cleanup-cron:0 0 4 * * *}")
    public void runScheduled() {
        try {
            service.cleanup();
        } catch (Exception e) {
            // AC-A1-8: 예외 격리 — throw 전파 0. 다음 cron 주기가 자연 재시도.
            log.error("retention cleanup failed — will retry at next schedule", e);
        }
    }
}
