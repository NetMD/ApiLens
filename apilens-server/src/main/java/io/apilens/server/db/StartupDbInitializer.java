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
package io.apilens.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot SQLite maintenance at startup: converts an existing DB to
 * {@code auto_vacuum=INCREMENTAL} (idempotent) and refreshes planner statistics.
 *
 * <p>// [Phase R12] AC-A2-2/AC-A4-3 — D-04 비협상 ("기존 22GB DB 리셋은 사용자 수행 완료.
 * // 파이프라인이 운영 DB 파일을 추가로 삭제하지 말 것"). 사용자 명시 비협상 결정.
 * // CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용.
 * // 본 컴포넌트는 운영 DB **파일** 을 삭제/이동/재생성하지 않는다 — auto_vacuum 전환·VACUUM·ANALYZE 만 수행.
 * // SQLite 의 VACUUM 은 행 내 재구성 명령으로 파일 삭제가 아님 (Design §2-A2 결정 2).
 *
 * <p>실행 시점: ApplicationRunner 는 컨텍스트 초기화(= Flyway 마이그레이션 완료) 후 실행 —
 * V3 적용 후 통계가 잡히도록 자연 보장.
 *
 * <p>VACUUM 을 V3 마이그레이션에 넣지 않는 이유: SQLite 의 VACUUM 은 트랜잭션 내 실행 불가 —
 * Flyway 가 마이그레이션을 트랜잭션으로 감싸므로 충돌. 본 Runner 가 정위치 (Design §2-A2).
 */
@Component
public class StartupDbInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDbInitializer.class);

    /** PRAGMA auto_vacuum 값 2 = INCREMENTAL — 멱등 가드 마커 (별도 플래그 저장 불요). */
    static final int AUTO_VACUUM_INCREMENTAL = 2;

    private final JdbcTemplate jdbc;

    public StartupDbInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /**
     * Returns whether the one-time VACUUM was executed (test observability).
     *
     * <p>// [Phase R12] AC-A2-2: 멱등 가드 — PRAGMA auto_vacuum 현재값이 이미 INCREMENTAL(2) 이면
     * // 전환·VACUUM 을 skip. auto_vacuum 값 자체가 적용 완료 마커.
     * <p>// E-08 (기획 권고 채택 확정): 전환 실패 시 warn 로그 + 기동 진행 — URL 의
     * // auto_vacuum=INCREMENTAL 이 신규 페이지부터 점진 효과 + cleanup 의 incremental_vacuum 이 fallback.
     */
    boolean initialize() {
        boolean vacuumExecuted = false;
        try {
            Integer autoVacuum = jdbc.queryForObject("PRAGMA auto_vacuum", Integer.class);
            if (autoVacuum == null || autoVacuum != AUTO_VACUUM_INCREMENTAL) {
                log.info("converting SQLite auto_vacuum to INCREMENTAL (current={}) — one-time VACUUM follows",
                        autoVacuum);
                // ⚠️ PRAGMA 설정과 VACUUM 은 **반드시 동일 connection** 에서 실행한다 (실측 —
                // DbPragmaTest V-06 봉인): auto_vacuum 은 connection 수준 pending 설정이라
                // 다른 connection 의 VACUUM 은 기존 값(NONE)으로 재구성해 전환이 silent 실패한다.
                // (sqlite-jdbc 는 auto_vacuum 을 URL pragma 로 지원하지 않음 — SQLiteConfig.Pragma 부재.)
                jdbc.execute((java.sql.Connection con) -> {
                    try (java.sql.Statement st = con.createStatement()) {
                        st.execute("PRAGMA auto_vacuum=INCREMENTAL");
                        // auto_vacuum 전환은 VACUUM 으로 페이지 재구성이 일어나야 기존 DB 에 반영된다.
                        st.execute("VACUUM");
                    }
                    return null;
                });
                vacuumExecuted = true;
                log.info("SQLite auto_vacuum conversion completed");
            } else {
                log.debug("SQLite auto_vacuum already INCREMENTAL — skipping one-time VACUUM (idempotent guard)");
            }
        } catch (Exception e) {
            // E-08: 실패해도 기동 진행 (서버 가용성 우선)
            log.warn("SQLite auto_vacuum conversion failed — continuing startup (incremental cleanup will compensate)", e);
        }
        try {
            // AC-A4-3: ANALYZE 는 매 기동 무조건 — V3 인덱스 통계 신선도 유지.
            // [Phase R13] AC-D1-2 규모 동반 정정: 소규모 DB 에서는 수십 ms 수준이나,
            // 대용량에서는 비용이 크다 (실측 3.7GB DB 에서 약 30.7s 기동 지연). 매 기동 무조건 실행은
            // 통계 신선도 우선 결정 — analysis_limit / PRAGMA optimize 로 비용을 제어하는 방안은
            // 동작 변경(통계 신선도 trade-off)이라 본 라운드 server-only 범위 밖 (backlog, Design §8 D-E2).
            jdbc.execute("ANALYZE");
        } catch (Exception e) {
            log.warn("SQLite ANALYZE failed — continuing startup", e);
        }
        return vacuumExecuted;
    }
}
