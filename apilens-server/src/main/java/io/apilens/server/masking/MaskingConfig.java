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
package io.apilens.server.masking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the hot-reloadable {@link MaskingEngineHolder} bean and the masking-only Jackson 2
 * {@code maskingObjectMapper} bean it consumes.
 *
 * <p>// [Phase R30] R30/AC-R30-10/R30/AC-R30-11 — 공유 마스킹 엔진(apilens-common)은 Jackson 2 를 공개 계약으로 품는다.
 * // Boot 4.1 은 Jackson 3 만 자동 구성하므로 엔진이 받을 Jackson 2 mapper 를 <b>이름 있는 빈 하나</b>로 직접 만든다
 * // (설계 DD-R30-05 · {@code @Primary} 0 — 받는 두 자리가 {@code @Qualifier} 로 이름을 고른다). 옵션은 agent 세 자리
 * // (AgentMain · HttpTransport · InstrumentationInstaller)와 같다 — 정적 공장 하나로 시험 helper 도 같은 옵션을 쓴다.
 * // 사용자 명시 비협상 결정(「길 ⓐ 전면 이행」 · UD-R30-9). CLAUDE.md '아키텍처 핵심 원칙'(마스킹은 apilens-common 의
 * // 공유 엔진 — 결과 일관성 필수) 인용.
 *
 * <p>// [Phase R12] AC-B2-3 — v0.1 의 "Rule edits via API (v0.2) will require either a
 * // refresh endpoint or replacing the bean" 예약 문구 **이행 완료**: startup 1회 고정
 * // MaskingEngine 빈 → MaskingEngineHolder (AtomicReference 인스턴스 교체) 로 대체.
 * // 룰 mutation(POST/PATCH/DELETE) 성공 직후 MaskingRuleService 가 reload() 호출 —
 * // 이후 ingest 분부터 반영 (BL-06). apilens-common MaskingEngine 코드 diff 0 (NFR-03).
 */
@Configuration
public class MaskingConfig {

    /** 마스킹 전용 Jackson 2 빈 이름 — 받는 자리의 {@code @Qualifier} 와 같은 글자. */
    public static final String MASKING_OBJECT_MAPPER = "maskingObjectMapper";

    /**
     * Builds the masking-only Jackson 2 mapper with the same options as the agent.
     *
     * <p>단일 진입 — 빈과 시험 helper 가 이 공장만 부른다(다른 자리에서 Jackson 2 mapper 를 새로 만들지 않는다).
     */
    public static ObjectMapper newMaskingObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean(MASKING_OBJECT_MAPPER)
    public ObjectMapper maskingObjectMapper() {
        return newMaskingObjectMapper();
    }

    @Bean
    public MaskingEngineHolder maskingEngineHolder(MaskingRuleRepository repository,
                                                   @Qualifier(MASKING_OBJECT_MAPPER) ObjectMapper mapper) {
        MaskingEngineHolder holder = new MaskingEngineHolder(repository, mapper);
        holder.reload(); // startup 1회 — 저장 룰 반영 (v0.1 빈 로딩과 동일 시점 보장)
        return holder;
    }
}
