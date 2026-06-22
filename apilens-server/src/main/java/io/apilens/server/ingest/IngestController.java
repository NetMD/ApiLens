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
package io.apilens.server.ingest;

import io.apilens.common.IngestRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Span ingest endpoint. Agent posts batches here.
 */
@RestController
public class IngestController {

    private final IngestService service;
    // [Phase R15] AC-A2-1 — 수신 일시정지 상태 주입(controller 레이어 분기). 사용자 명시 비협상 결정(D02).
    // CLAUDE.md '아키텍처 핵심 원칙' (Agent 무변경 — server 가 503 으로 수신 차단) 인용.
    private final IngestPauseState pauseState;

    // [봉인#1 NFR-04] IngestService 시그니처 불변 — pause 체크는 controller 레이어에서만.
    // R13 287a7e7 회귀 진원지(IngestService 생성자 변경이 통합테스트 컴파일 깨짐). 2-인자 추가만.
    public IngestController(IngestService service, IngestPauseState pauseState) {
        this.service = service;
        this.pauseState = pauseState;
    }

    // [Phase R15] AC-A2-1/AC-A2-3 — 일시정지면 503+Retry-After 로 즉시 응답, service.ingest() 미호출
    //   (validate/mask/truncate/DB write 전부 skip). 사용자 명시 비협상 결정(D02).
    //   CLAUDE.md '아키텍처 핵심 원칙' (Agent 무변경 — server 만 503 으로 수신 멈춤) 인용.
    // [봉인#3] 503 = ResponseEntity 직접 반환(throw 아님 — @ExceptionHandler 400 매핑 회피).
    @PostMapping("/v1/spans")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest request) {
        if (pauseState.isPaused()) {
            return ResponseEntity.status(503)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "서버가 유지보수 중이라 잠시 수신을 멈췄습니다."));
        }
        IngestResponse response = service.ingest(request);
        // 202 — body { accepted, traces } 형식 불변(GT-3). @ResponseStatus(ACCEPTED) 제거 후 ResponseEntity 통일.
        return ResponseEntity.accepted().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
