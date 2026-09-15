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
 * Denies noisy library WARN lines by message condition only — the source logger levels
 * themselves are untouched.
 *
 * <p>// [Phase R26] R26/AC-R26-32 — 이 클래스는 <b>메시지 조건 거부의 단일 거주지</b>이고 <b>갈래가 둘</b>이다:
 * // ① HikariCP 의 시계 역행 경고 ② micrometer 의 음수 기록 경고. 클래스 이름은 ①에서 왔지만
 * // <b>이름을 안 바꾼다</b> — 바꾸면 {@code logback-spring.xml} 의 등록 줄과 시험이 함께 움직여
 * // 회귀 표면만 넓어진다. 갈래를 더할 때는 <b>서로 다른 로거 접두 + 서로 다른 메시지 조건</b>으로
 * // 분기를 나란히 둔다(한 조건식에 합치면 어느 갈래가 잡았는지 못 가린다).
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
        if (format == null) {
            return FilterReply.NEUTRAL;
        }
        // 갈래 ① hikari 시계 역행 — 로거 한정 + 메시지 조건. 타 로거 오차단 0.
        if (logger.getName().startsWith("com.zaxxer.hikari")
                && format.contains("Retrograde clock change detected")) {
            return FilterReply.DENY;
        }
        // 갈래 ② [Phase R26] R26/AC-R26-32 — micrometer 의 음수 기록 경고. 사용자 명시 결정(UA-10).
        //   ★hikari 가드 **밖의 별도 분기**다(같은 조건식에 합치지 않는다). 로거 레벨 상향은 금지 —
        //   같은 로거의 다른 경고까지 함께 사라진다.
        //   ground truth (dev 진입 게이트 실측 · micrometer 1.14.2 AbstractTimer 상수풀):
        //   경고 본문 = "'amount' should not be negative but was: <N>" 뒤에 공용 후위 문장이 붙는다.
        //   micrometer 는 렌더한 문자열 하나를 그대로 넘기므로({} 파라미터가 없다) format 인자 contains 가 유효하다.
        //   로거 이름은 io.micrometer.core.instrument.AbstractTimer — 아래 접두 판정에 부합한다.
        if (logger.getName().startsWith("io.micrometer")
                && format.contains("'amount' should not be negative")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;   // 'Thread starvation' 등 형제 신호는 레벨 판정으로 그대로 흐른다
    }
}
