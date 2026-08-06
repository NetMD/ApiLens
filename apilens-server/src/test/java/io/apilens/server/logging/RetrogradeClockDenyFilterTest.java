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
package io.apilens.server.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [Phase R20] R20/AC-09-2 — Retrograde DENY 필터 (B-20).
 *
 * <p>R20/AC-09-2 verbatim (비협상): "HikariPool 'Retrograde clock change' 를 <b>message 조건 DENY</b>
 * 로만 거른다. <b>hikari 로거 레벨 상향 금지</b>(불변식 11 — 같은 로거의 형제 신호 'Thread starvation'
 * 보존)." — 형제 신호가 NEUTRAL 로 흐르는 것이 이 필터의 존재 이유 절반이다.
 *
 * <p>format 인자는 렌더 전 템플릿 — HikariCP 5.1.0 실측(HouseKeeper 상수풀):
 * {@code "{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool."}
 */
class RetrogradeClockDenyFilterTest {

    private static final String RETROGRADE_FORMAT =
            "{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool.";
    private static final String STARVATION_FORMAT =
            "{} - Thread starvation or clock leap detected (housekeeper delta={}).";

    private final RetrogradeClockDenyFilter filter = new RetrogradeClockDenyFilter();
    private final LoggerContext context = new LoggerContext();

    private Logger logger(String name) {
        return context.getLogger(name);
    }

    /** hikari 로거 + Retrograde format → DENY (잡음 차단이 이 필터의 정방향 — returns 동사로 명명). */
    @Test
    void returnsDenyForRetrogradeLineFromHikariLogger() {
        FilterReply reply = filter.decide(null, logger("com.zaxxer.hikari.pool.HikariPool"),
                Level.WARN, RETROGRADE_FORMAT, new Object[]{"pool", "45s"}, null);

        assertEquals(FilterReply.DENY, reply);
    }

    /** hikari 로거 + 'Thread starvation' 형제 신호 → NEUTRAL (레벨 판정으로 그대로 — 불변식 11 핵심). */
    @Test
    void keepsThreadStarvationSiblingNeutral() {
        FilterReply reply = filter.decide(null, logger("com.zaxxer.hikari.pool.HikariPool"),
                Level.WARN, STARVATION_FORMAT, new Object[]{"pool", "3m"}, null);

        assertEquals(FilterReply.NEUTRAL, reply, "형제 신호 보존 — message 조건 DENY 는 Retrograde 만");
    }

    /** 타 로거 + 같은 문구 → NEUTRAL (hikari 로거 한정 — 타 로거 오차단 0). */
    @Test
    void keepsOtherLoggersNeutralEvenWithSamePhrase() {
        FilterReply reply = filter.decide(null, logger("io.apilens.server.SomeService"),
                Level.WARN, RETROGRADE_FORMAT, null, null);

        assertEquals(FilterReply.NEUTRAL, reply);
    }

    /** format == null 방어 — NEUTRAL (NPE 0). */
    @Test
    void keepsNullFormatNeutral() {
        FilterReply reply = filter.decide(null, logger("com.zaxxer.hikari.pool.HikariPool"),
                Level.WARN, null, null, null);

        assertEquals(FilterReply.NEUTRAL, reply);
    }
}
