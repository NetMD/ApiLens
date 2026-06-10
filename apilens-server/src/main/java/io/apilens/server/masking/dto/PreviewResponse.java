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
package io.apilens.server.masking.dto;

/**
 * Response of {@code POST /v1/masking-rules/preview}.
 *
 * <p>{@code sample} 은 입력 원문 echo (UX §9 요구 ② 응답 동봉 채택 — 기본 샘플 모드의
 * Before/After 동시 표시 성립, Design §3.1.5).
 *
 * @param sample      마스킹 전 원문 (echo)
 * @param masked      공유 엔진(MaskingEngine) 적용 결과 — agent/server/프리뷰 3자 동일 엔진 (AC-B3-3)
 * @param contentType 적용된 content type
 */
public record PreviewResponse(
        String sample,
        String masked,
        String contentType
) {
}
