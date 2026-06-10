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

import org.springframework.http.HttpStatus;
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

    @GetMapping("/v1/settings")
    public SettingsResponse getSettings() {
        return service.getSettings();
    }

    /**
     * // [Phase R12] AC-B1-3 — "retention.days 1~3650, 범위 외 400 (원자 거부)" (비협상 — PM 확정).
     * // 갱신 후 GET 과 동일 형태 응답 (Design §5.2).
     */
    @PutMapping("/v1/settings")
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
