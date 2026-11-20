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

import io.apilens.server.web.ApiMediaTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Services-side wizard endpoints. Only DELETE lives here — the GET listing
 * stays in {@code TraceQueryController} to preserve UI compatibility and avoid
 * splitting the read path.
 *
 * <p>[Phase H] AC-06-5 — D-05. 사용자 명시 비협상 결정 (services row 만 제거).
 * CLAUDE.md '아키텍처 핵심 원칙' 인용.
 */
@RestController
@RequestMapping("/v1/services")
public class ServicesController {

    private final ServicesService service;

    public ServicesController(ServicesService service) {
        this.service = service;
    }

    /**
     * Q-02 정합: 멱등 DELETE. 존재하지 않는 service 이름도 204.
     */
    // [Phase R33] R33/AC-R33-01-1/R33/AC-R33-01-2/R33/AC-R33-02-1 — 이 파일의 메서드 매핑마다 응답 형식(JSON 두 값)을 같은 한 줄에 선언해 처리 전 406 을 낸다 · 본문 받는 매핑은 받는 형식(JSON 두 값)도. 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406). 설계 본편 DD-R33-01 · 02 · 03 · 부록 A §A1.3 인용.
    @DeleteMapping(value = "/{serviceName}", produces = {MediaType.APPLICATION_JSON_VALUE, ApiMediaTypes.JSON_SUFFIX_VALUE})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String serviceName) {
        service.delete(serviceName);
    }
}
