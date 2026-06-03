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
package io.apilens.server.setup;

import io.apilens.server.setup.dto.ServiceRegistration;
import io.apilens.server.setup.dto.SetupCompleteRequest;
import io.apilens.server.setup.dto.SetupCompleteResponse;
import io.apilens.server.setup.dto.SetupStateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Business logic for the setup wizard endpoints.
 *
 * <p>[Phase H] AC-06-1 / AC-06-2 — D-01 / D-04 / NFR-04 (멱등). 사용자 명시 비협상 결정.
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>Q-01 정합: services null/[]/omit 모두 빈 배열로 정규화 → 200.
 */
@Service
public class SetupService {

    // 영문/숫자/하이픈/언더스코어 — wizard UI 와 AgentOptionBuilder 와 동일 규약
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final SetupRepository repo;

    public SetupService(SetupRepository repo) {
        this.repo = repo;
    }

    /**
     * Defensive: setup_state row 미존재 (V2 INSERT 가 보장하므로 정상 케이스 0) 시
     * 미완료 fallback. FirstRunGuard 가 children 통과 시킴.
     */
    public SetupStateResponse getState() {
        return repo.findState().orElse(new SetupStateResponse(false, null, null));
    }

    /**
     * Complete setup. Idempotent — 재호출 시 completed_at / server_url 갱신.
     * services 가 null/[] 이면 setup_state 만 갱신.
     *
     * <p>D-04 (skip 허용) 정합: serverUrl 이 빈 문자열/null 이면 그 자체로 정상 (skip 분기).
     * 빈 문자열은 NULL 로 정규화해 setup_state.server_url 에 저장 — DB 표현 통일.
     */
    @Transactional
    public SetupCompleteResponse complete(SetupCompleteRequest request) {
        validate(request);
        long now = System.currentTimeMillis();

        // D-04 정합: skip 경로 ("" / null) 는 NULL 로 정규화해 저장. 이후 정상 완료 시 정상 갱신.
        String normalizedUrl = normalizeServerUrl(request.serverUrl());
        repo.updateSetupState(now, normalizedUrl);

        // Q-01 정합: services null/[] 둘 다 빈 배열로 정규화
        List<ServiceRegistration> regs = request.services() == null
                ? List.of()
                : request.services();
        for (ServiceRegistration reg : regs) {
            repo.insertWizardService(reg.name(), now);
        }

        return new SetupCompleteResponse(true, now);
    }

    /**
     * D-04 (skip 허용) + Q-01 정합:
     * <ul>
     *   <li>serverUrl 이 빈 문자열/null → 통과 (skip 경로 — setup_state.server_url 은 NULL 로 저장)</li>
     *   <li>serverUrl 이 있으면 http(s):// 형식 검증</li>
     *   <li>services 는 null/[]/omit 동등 (Q-01) — 정상 분기. 각 name 만 정규식 검증</li>
     * </ul>
     */
    private static void validate(SetupCompleteRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String url = req.serverUrl();
        // D-04: 빈 문자열 / null 은 skip 경로 — 형식 검증 우회
        if (url != null && !url.isBlank()
                && !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("serverUrl must start with http:// or https://");
        }
        if (req.services() != null) {
            for (ServiceRegistration r : req.services()) {
                if (r == null || r.name() == null || r.name().isBlank()) {
                    throw new IllegalArgumentException("service name is required");
                }
                if (!SERVICE_NAME_PATTERN.matcher(r.name()).matches()) {
                    throw new IllegalArgumentException("service name format invalid");
                }
            }
        }
    }

    /** 빈 문자열 / null → NULL 저장. 그 외엔 trim 없이 원본 보존 (사용자 입력 그대로). */
    private static String normalizeServerUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url;
    }
}
