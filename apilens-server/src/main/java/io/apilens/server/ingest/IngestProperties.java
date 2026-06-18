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
package io.apilens.server.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ingest configuration from {@code application.yml} ({@code apilens.ingest.*}).
 *
 * <p>// [Phase R13] AC-A2-1 — 한도 키명 변경 max-batch-size-bytes → max-payload-bytes
 * // (정책이 batch 총합 거부가 아니라 개별 payload body 한도임을 키명에 정확히 반영). 사용자 명시 결정.
 * // CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' · 'v0.1 범위' 인용.
 *
 * <p>// 기본 1MB(1048576 byte): agent payload 한도(64KB)의 16배 여유 — 정상 흐름에서는 agent 가
 * // 먼저 64KB 로 자르므로 server 가드는 idle(안전망). agent 우회/오작동/직접 POST/대형 payload
 * // (관측 9.2MB) 같은 폭증을 server 저장 직전에 차단하는 용도다 (Design §2.A.2 / D-A2).
 *
 * <p>// @ConfigurationPropertiesScan(ApiLensApplication.java:27)이 이미 적용되어 record 만
 * // 추가하면 bean 자동 등록 — 별도 @EnableConfigurationProperties 코드 0 (RetentionProperties 동형).
 *
 * @param maxPayloadBytes 개별 payload body 의 최대 저장 byte. 초과 시 server 가 한도까지 잘라 저장
 *                        + truncated=true (mask 적용 후 측정·절단, UTF-8 경계 보존). 기본 1MB.
 */
@ConfigurationProperties(prefix = "apilens.ingest")
public record IngestProperties(
        @DefaultValue("1048576") long maxPayloadBytes
) {
}
