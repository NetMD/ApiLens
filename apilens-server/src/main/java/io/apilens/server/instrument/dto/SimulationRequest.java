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
package io.apilens.server.instrument.dto;

import java.util.List;

/**
 * Request body of {@code POST /v1/instrument/simulation}.
 *
 * <p>// [Phase R19] AC-04-1 — {@code fromMs}/{@code toMs} 는 순위 응답의 {@code window} 값을
 * // <b>그대로 되돌려 보낸다</b>. 서버가 창을 다시 계산하면 그 사이 시간이 흘러 두 결과의 기준 구간이
 * // 어긋난다(같은 출발점 규약 위반). 그래서 창을 요청이 들고 온다.
 *
 * <p>// [Phase R19] AC-04-5 — {@code targets} 는 <b>언제나 클래스 이름 목록</b>이다. 패키지 단위
 * // 입력을 받지 않는다 — 서버에 패키지 단위 집계 경로 자체가 없다(화면의 "패키지 전체" 는 보이는 목록
 * // 안 클래스를 한 번에 체크하는 단축키이고, 서버로 가는 값은 여전히 클래스 이름 목록이다).
 *
 * @param serviceName 분석 대상 서비스 이름 (1~255자)
 * @param fromMs      구간 시작 (순위 응답의 {@code window.fromMs} 그대로)
 * @param toMs        구간 끝 (순위 응답의 {@code window.toMs} 그대로)
 * @param targets     빼 볼 클래스 이름 목록 (0~500개, 각 1~512자). 빈 목록도 정상 요청이다
 */
public record SimulationRequest(String serviceName, long fromMs, long toMs, List<String> targets) {
}
