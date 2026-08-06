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
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Denies HikariCP's noisy "Retrograde clock change detected" WARN lines by message
 * condition only — the hikari logger level itself is untouched.
 *
 * <p>[Phase R20] R20/AC-09-2 — <b>DENY 는 message 조건으로만(불변식 11, 사용자 명시 비협상 결정)</b>:
 * hikari 로거 <b>레벨 상향 금지</b> — 같은 로거의 형제 신호 'Thread starvation' 은 NEUTRAL 로
 * 기존 레벨 판정에 그대로 흐른다(보존이 구조로 보장). janino 의존 추가 없이 message 조건을
 * 구현하는 유일한 가벼운 길 = 커스텀 TurboFilter 1클래스.
 *
 * <p>ground truth (dev 진입 게이트 실측, HikariCP 5.1.0 {@code HikariPool$HouseKeeper} 상수풀):
 * format 템플릿 = {@code "{} - Retrograde clock change detected (housekeeper delta={}),
 * soft-evicting connections from pool."} — "Retrograde clock change detected" 가 렌더 전
 * format 문자열에 <b>리터럴로 포함</b>된다(poolName·delta 만 {@code {}} 파라미터). 그래서
 * TurboFilter 의 format 인자 {@code contains} 판정이 유효하다. HouseKeeper 의 로거 이름은
 * {@code com.zaxxer.hikari.pool.HikariPool} — 아래 prefix 판정에 부합.
 */
public class RetrogradeClockDenyFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format != null
                && logger.getName().startsWith("com.zaxxer.hikari")     // hikari 로거 한정 — 타 로거 오차단 0
                && format.contains("Retrograde clock change detected")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;   // 'Thread starvation' 등 형제 신호는 레벨 판정으로 그대로 흐른다
    }
}
