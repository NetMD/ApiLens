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

import org.springframework.http.HttpStatus;
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
    @DeleteMapping("/{serviceName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String serviceName) {
        service.delete(serviceName);
    }
}
