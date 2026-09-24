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

    /**
     * [Phase R26] R26/AC-R26-29 — 설치 마법사가 한 번에 등록할 수 있는 서비스 개수 상한.
     * 사용자 명시 결정(UA-11). 이 화면은 <b>사람이 손으로 채우는 자리</b>라 50 이면 넉넉하고,
     * 이 입구는 <b>인증 면제 경로</b>라({@code AuthWhitelist} 가 {@code /v1/setup/} 을 전부 면제) 상한이
     * 없으면 한 번의 요청으로 임의 개수의 행을 만들 수 있다.
     * // [2026-09-24] SEC-R26-03 ⓐ — 종전 문면 「무인증 입구가 아닌데도」는 사실과 반대였다(동작 변경 0).
     * //   ⓑ(서비스 이름 길이 상한)는 선택 사항으로 남겨 둔다 — 이름 형식 검사(영문·숫자·-·_)는 이미 있다.
     */
    static final int SETUP_SERVICES_MAX = 50;

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
        // [Phase R26] R26/AC-R26-29 — 서버 주소에 **호스트가 있어야** 한다. 접두만 보면 "http://" 하나가
        //   그대로 통과해 설정 화면에 못 쓰는 값이 저장된다. ★기존 통과 갈래는 안 좁힌다 —
        //   빈 값·null 은 위 skip 경로 그대로다. 주소 만들기가 실패하면 호스트 없음으로 본다.
        if (url != null && !url.isBlank() && hostOf(url) == null) {
            throw new IllegalArgumentException("serverUrl must include a host (예: http://192.168.0.10:8765)");
        }
        // [Phase R26] R26/AC-R26-29 — 서비스 배열 상한(사용자 명시 결정 UA-11). 문면에 **몇 개까지 되는지**를
        //   적는다 — 숫자가 없으면 운영자가 몇 개를 줄여야 하는지 모른다. 거부 방향만 넓힌다.
        if (req.services() != null && req.services().size() > SETUP_SERVICES_MAX) {
            throw new IllegalArgumentException(
                    "too many services — at most " + SETUP_SERVICES_MAX + " are allowed");
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

    /**
     * [Phase R26] R26/AC-R26-29 — 주소에서 호스트를 꺼낸다. 못 꺼내면 {@code null}.
     *
     * <p>형식이 아예 주소가 아니거나({@code URISyntaxException}) 호스트가 비면 <b>호스트 없음</b>으로 본다 —
     * 예외를 밖으로 던지지 않는 이유는 여기서 나는 오류의 뜻이 "주소가 아니다" 하나뿐이기 때문이다.
     */
    private static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return (host == null || host.isBlank()) ? null : host;
        } catch (java.net.URISyntaxException e) {
            return null;
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
