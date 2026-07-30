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
 * Request body of {@code POST /v1/instrument/analysis}.
 *
 * <p>// [Phase R19] AC-02-1 — 시간 구간은 1 / 6 / 24 세 개만 받는다(화이트리스트 검증).
 * // 범위 검사가 아니라 열거 검사인 이유: 임의 값이 들어와 구간이 무제한으로 커지는 경로를 원천 차단한다.
 * // "전체" 선택지는 만들지 않는다 — 비용 상한이 이 열거 하나로 걸린다(사용자 명시 비협상 결정 D-9 계열).
 *
 * @param serviceName 분석 대상 서비스 이름 (1~255자)
 * @param windowHours 시간 구간(시간 단위) — 1 / 6 / 24 중 하나
 */
public record AnalysisRequest(String serviceName, int windowHours) {
}
