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
package io.apilens.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.server.masking.MaskingConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-side factories for the two JSON mappers the server runs with after the Boot 4.1 upgrade.
 *
 * <p>[Phase R30] R30/AC-R30-10/R30/AC-R30-11 — 서버는 두 판의 Jackson 을 함께 쓴다(설계 §4.1 · §4.2 · DT-R30-09):
 * <ul>
 *   <li>{@link #forMasking()} — 공유 마스킹 엔진(apilens-common)이 받는 Jackson 2 mapper. 운영 빈과 <b>같은 공장</b>
 *       ({@link MaskingConfig} 의 정적 공장)을 불러 옵션이 구조로 같다(다른 자리에서 새로 만들지 않는다).</li>
 *   <li>{@link #forServices()} — 401 본문 · 적재 · 조회 세 소비처가 받는 Jackson 3 {@code JsonMapper}. Jackson 3 기본값
 *       그대로다(설계 §4.2 문면 — Boot 자동 구성 빈과 같다고 단언하지 않는다). 설계 DD-R30-07 의 처리({@code use-jackson2-defaults})가 발동하면 이 한 줄만
 *       {@code JsonMapper.builderWithJackson2Defaults()} 로 바꾼다.</li>
 * </ul>
 * 사용자 명시 비협상 결정(「길 ⓐ 전면 이행」). CLAUDE.md '아키텍처 핵심 원칙'(마스킹은 공유 엔진 — 결과 일관성 필수) 인용.
 */
public final class TestMappers {

    private TestMappers() {
    }

    /** Jackson 2 mapper for the shared masking engine — same factory as the production bean. */
    public static ObjectMapper forMasking() {
        return MaskingConfig.newMaskingObjectMapper();
    }

    /** Jackson 3 mapper for the three server-side JSON consumers — plain Jackson 3 defaults. */
    public static JsonMapper forServices() {
        return JsonMapper.builder().build();
    }
}
