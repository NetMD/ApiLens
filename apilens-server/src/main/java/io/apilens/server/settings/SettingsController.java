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
package io.apilens.server.settings;

import io.apilens.server.web.ApiMediaTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET/PUT /v1/settings} — 설정 페이지 Retention 섹션이 소비.
 *
 * <p>에러 응답은 기존 flat 표준 {@code { "error": "<message>" }} 그대로
 * (Design §5.5 — 신규 중첩 표준 도입 금지). 인증 없음 — 신뢰 네트워크 전제
 * (v0.3 인증 phase 예약, NFR-07).
 */
@RestController
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    // [Phase R33] R33/AC-R33-01-1/R33/AC-R33-01-2/R33/AC-R33-02-1 — 이 파일의 메서드 매핑마다 응답 형식(JSON 두 값)을 같은 한 줄에 선언해 처리 전 406 을 낸다 · 본문 받는 매핑은 받는 형식(JSON 두 값)도. 사용자 명시 비협상 결정(UQ-R33-3 처리 전 406). 설계 본편 DD-R33-01 · 02 · 03 · 부록 A §A1.3 인용.
    @GetMapping(value = "/v1/settings", produces = {MediaType.APPLICATION_JSON_VALUE, ApiMediaTypes.JSON_SUFFIX_VALUE})
    public SettingsResponse getSettings() {
        return service.getSettings();
    }

    /**
     * // [Phase R12] AC-B1-3 — "retention.days 1~3650, 범위 외 400 (원자 거부)" (비협상 — PM 확정).
     * // 갱신 후 GET 과 동일 형태 응답 (Design §5.2).
     */
    @PutMapping(value = "/v1/settings", produces = {MediaType.APPLICATION_JSON_VALUE, ApiMediaTypes.JSON_SUFFIX_VALUE}, consumes = {MediaType.APPLICATION_JSON_VALUE, ApiMediaTypes.JSON_SUFFIX_VALUE})
    public SettingsResponse putSettings(@RequestBody Map<String, Object> body) {
        return service.put(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        // E-01: 400 본문에 허용 범위 포함 — message 문자열에 포함 (Design §5.5)
        return Map.of("error", e.getMessage());
    }
}
