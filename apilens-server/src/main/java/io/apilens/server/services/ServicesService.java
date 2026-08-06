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
package io.apilens.server.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the services-side wizard endpoints (DELETE only — GET is
 * served by {@code TraceQueryController#listServices} to preserve UI compat).
 *
 * <p>[Phase H] AC-06-5 — D-05 (services row 만 제거, traces/spans/payloads 보존).
 * 사용자 명시 비협상 결정. CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>R12 회귀 가드 — [Phase R21] R21/AC-02-11 (R-U4) 갱신: services +
 * service_instrument_configs 두 테이블 touch. traces / spans / payloads 는 여전히
 * 미접근(D-05 불변). cascade 자체 미존재 (FK 없음).
 */
@Service
public class ServicesService {

    private final JdbcTemplate jdbc;

    public ServicesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Delete the {@code services} row by name. Q-02 정합: 멱등 — 존재하지 않는
     * service 도 affectedRows 검사 없이 204 으로 처리 (호출자가 NO_CONTENT 응답).
     *
     * <p>D-05 비협상: traces / spans / payloads 보존. 운영자가 명시적으로
     * 삭제한 service 가 다음 trace 도착 시 자동 재등록 (D-02 경로 B).
     */
    @Transactional
    public void delete(String serviceName) {
        jdbc.update("DELETE FROM services WHERE service_name = ?", serviceName);
        // [Phase R21] R21/AC-02-11 (R-U4) — 서비스 삭제 시 저장된 원격 계측 설정 지시도 함께 철회한다(같은 트랜잭션).
        //   D-05(traces/spans/payloads 보존)는 불변 — 접촉 테이블은 services + service_instrument_configs 뿐.
        //   agent 에 이미 적용된 값은 철회 의미론(전달 중단·비복귀)대로 되돌아가지 않는다.
        jdbc.update("DELETE FROM service_instrument_configs WHERE service_name = ?", serviceName);
    }
}
