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

/**
 * The time window a result was computed over, plus when it was computed.
 *
 * <p>// [Phase R19] AC-02-6 — 정량 수치는 표본·조회 시각·구간을 동봉한다(비협상 S-10).
 * // 화면이 "언제 잰 값인지 / 어느 구간인지" 를 항상 적을 수 있게 두 응답이 공통으로 담는다.
 *
 * <p>두 응답이 같은 모양을 쓰므로 한 곳에만 선언한다(정의가 갈라지면 두 화면의 구간 표기가 어긋난다).
 *
 * @param fromMs      구간 시작 (epoch millis, 포함)
 * @param toMs        구간 끝 (epoch millis, 미포함)
 * @param queriedAtMs 이 결과를 만든 서버 시각 (epoch millis)
 */
public record Window(long fromMs, long toMs, long queriedAtMs) {
}
