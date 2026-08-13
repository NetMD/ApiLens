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
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final RetentionCleanupService service;

    public RetentionCleanupJob(RetentionCleanupService service) {
        this.service = service;
    }

    /**
     * [Phase R22] R22/AC-03-7 verbatim: <b>"밤" = 야간 스케줄 실행 1회</b>다. 고아 span 스윕의
     * <b>호출처는 이 메서드 단 하나</b>이고, 수동 버튼 경로({@code MaintenanceController})에는 이 호출이
     * 없다 — 그래서 수동 [보관 기간 즉시 적용] 을 연달아 두 번 눌러도 고아 삭제는 0 이다.
     * <b>사용자 명시 비협상 결정</b>(U-1). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
     *
     * <p>★ {@code service.cleanup()} 호출은 <b>그대로 둔다.</b> 이 호출 대상을 다른 이름으로 바꾸면
     * {@code RetentionCleanupServiceTest} 의 익명 하위 클래스 재정의({@code cleanup()} override)가
     * <b>여전히 통과하면서 아무것도 검증하지 않는 상태</b>가 된다.
     *
     * <p>★ try-catch 를 <b>두 개로 나눈</b> 이유: 하나로 묶으면 {@code cleanup()} 실패가 스윕을 통째로
     * 건너뛰게 만든다. 스윕은 교집합 기반이라 cleanup 실패 상태에서 돌아도 <b>안전한 방향으로만</b> 실패한다
     * (어젯밤 후보와 오늘 고아가 둘 다여야 지운다).
     */
    @Scheduled(cron = "${apilens.retention.cleanup-cron:0 0 4 * * *}")
    public void runScheduled() {
        try {
            service.cleanup();
        } catch (Exception e) {
            // AC-A1-8: 예외 격리 — throw 전파 0. 다음 cron 주기가 자연 재시도.
            log.error("retention cleanup failed — will retry at next schedule", e);
        }
        try {
            // [Phase R22] R22/AC-03-2/R22/AC-03-3 — cleanup() 이 **반환한 뒤** 돈다.
            //   ⇒ 시각 기록·회수·체크포인트·ANALYZE 는 이미 전부 끝난 상태다(예외 격리가 구조로 보장된다).
            service.sweepOrphanSpansNightly();
        } catch (Exception e) {
            // 2겹째 그물 — 스윕 자신도 본문 전체를 try-catch 로 감싼다.
            log.error("orphan span sweep failed — will retry at next schedule", e);
        }
    }
}
