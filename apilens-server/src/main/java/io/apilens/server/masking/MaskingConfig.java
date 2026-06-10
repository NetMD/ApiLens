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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the hot-reloadable {@link MaskingEngineHolder} bean.
 *
 * <p>// [Phase R12] AC-B2-3 — v0.1 의 "Rule edits via API (v0.2) will require either a
 * // refresh endpoint or replacing the bean" 예약 문구 **이행 완료**: startup 1회 고정
 * // MaskingEngine 빈 → MaskingEngineHolder (AtomicReference 인스턴스 교체) 로 대체.
 * // 룰 mutation(POST/PATCH/DELETE) 성공 직후 MaskingRuleService 가 reload() 호출 —
 * // 이후 ingest 분부터 반영 (BL-06). apilens-common MaskingEngine 코드 diff 0 (NFR-03).
 */
@Configuration
public class MaskingConfig {

    @Bean
    public MaskingEngineHolder maskingEngineHolder(MaskingRuleRepository repository, ObjectMapper mapper) {
        MaskingEngineHolder holder = new MaskingEngineHolder(repository, mapper);
        holder.reload(); // startup 1회 — 저장 룰 반영 (v0.1 빈 로딩과 동일 시점 보장)
        return holder;
    }
}
